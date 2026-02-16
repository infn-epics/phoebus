/*******************************************************************************
 * Copyright (c) 2025 European Spallation Source ERIC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.phoebus.security.managers;

import org.phoebus.security.PhoebusSecurity;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Acquires TLS certificates from a remote HTTPS endpoint (typically the OIDC/Keycloak
 * server) and stores them in a local PKCS12 truststore file.
 * <p>
 * This replaces the insecure {@link DummyX509TrustManager} approach (which trusts
 * <b>everything</b>) with a proper "Trust On First Use" (TOFU) model:
 * <ol>
 *     <li>Connect to the OIDC server via TLS</li>
 *     <li>Capture the server's certificate chain</li>
 *     <li>Store it in a local truststore file</li>
 *     <li>Build an {@link SSLContext} backed by that truststore</li>
 * </ol>
 * The truststore is persisted so certificates survive restarts. It is refreshed
 * when the existing certificates cannot validate the server (e.g., after cert rotation).
 * </p>
 *
 * <h4>Usage</h4>
 * <pre>
 *   OidcTrustStoreManager mgr = OidcTrustStoreManager.getInstance();
 *   SSLContext ctx = mgr.getSSLContext();
 *   HttpClient client = HttpClient.newBuilder().sslContext(ctx).build();
 * </pre>
 */
public class OidcTrustStoreManager {

    private static final Logger LOGGER = Logger.getLogger(OidcTrustStoreManager.class.getName());

    private static final String TRUSTSTORE_FILENAME = "phoebus_oidc_truststore.p12";
    private static final char[] TRUSTSTORE_PASSWORD = "changeit".toCharArray();
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private static volatile OidcTrustStoreManager instance;

    private final File truststoreFile;
    private KeyStore truststore;
    private SSLContext sslContext;

    /** Last connection error message, or {@code null} if the last operation succeeded. */
    private volatile String lastConnectionError;

    /**
     * Get or create the singleton instance.
     * The truststore file is stored in the user's home directory under {@code .phoebus/}.
     *
     * @return The singleton {@link OidcTrustStoreManager}
     */
    public static OidcTrustStoreManager getInstance() {
        if (instance == null) {
            synchronized (OidcTrustStoreManager.class) {
                if (instance == null) {
                    instance = new OidcTrustStoreManager();
                }
            }
        }
        return instance;
    }

    private OidcTrustStoreManager() {
        File dir = new File(System.getProperty("user.home"), ".phoebus");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        truststoreFile = new File(dir, TRUSTSTORE_FILENAME);
        loadOrCreateTruststore();
    }

    /**
     * Returns an {@link SSLContext} backed by the local truststore.
     * If the truststore is empty (no certificates imported yet), this attempts
     * to fetch certificates from the configured OIDC server first.
     *
     * @return An {@link SSLContext} that trusts the OIDC server's certificates
     * plus the default JVM trusted CAs.
     */
    public synchronized SSLContext getSSLContext() {
        if (sslContext == null) {
            // If truststore has no entries, try to acquire certs now
            try {
                if (truststore.size() == 0) {
                    String oidcUrl = buildOidcUrl();
                    if (oidcUrl != null) {
                        acquireCertificates(oidcUrl);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Truststore size check failed", e);
            }
            sslContext = buildSSLContext();
        }
        return sslContext;
    }

    /**
     * Fetch the TLS certificate chain from a given HTTPS URL and store it in
     * the local truststore. The {@link SSLContext} is rebuilt after import.
     *
     * @param httpsUrl The HTTPS endpoint to fetch certificates from
     *                 (e.g., {@code https://idp-test.app.infn.it})
     * @return {@code true} if certificates were successfully acquired and stored
     */
    public synchronized boolean acquireCertificates(String httpsUrl) {
        try {
            URI uri = URI.create(httpsUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 443;

            LOGGER.log(Level.INFO, "Acquiring TLS certificates from {0}:{1}",
                    new Object[]{host, port});

            X509Certificate[] chain = fetchCertificateChain(host, port);
            if (chain == null || chain.length == 0) {
                String msg = "No certificates received from " + host + ":" + port;
                LOGGER.log(Level.WARNING, msg);
                lastConnectionError = msg;
                return false;
            }

            // Import each certificate in the chain
            for (int i = 0; i < chain.length; i++) {
                String alias = host + "_" + i;
                truststore.setCertificateEntry(alias, chain[i]);
                LOGGER.log(Level.INFO, "Imported certificate [{0}]: subject={1}, issuer={2}, expires={3}",
                        new Object[]{alias, chain[i].getSubjectX500Principal(),
                                chain[i].getIssuerX500Principal(), chain[i].getNotAfter()});
            }

            // Persist to disk
            saveTruststore();

            // Rebuild SSLContext with new certificates
            sslContext = buildSSLContext();

            LOGGER.log(Level.INFO, "Successfully acquired and stored {0} certificate(s) from {1}",
                    new Object[]{chain.length, host});
            lastConnectionError = null;
            return true;

        } catch (Exception e) {
            String msg = "Failed to connect to IDP at " + httpsUrl + ": " + e.getMessage();
            LOGGER.log(Level.WARNING, msg, e);
            lastConnectionError = msg;
            return false;
        }
    }

    /**
     * Refresh certificates from the configured OIDC server.
     * Call this if connections start failing due to certificate rotation.
     *
     * @return {@code true} if refresh was successful
     */
    public boolean refreshCertificates() {
        String oidcUrl = buildOidcUrl();
        if (oidcUrl == null) {
            LOGGER.log(Level.WARNING, "Cannot refresh: OAuth2 URL not configured");
            return false;
        }
        return acquireCertificates(oidcUrl);
    }

    /**
     * Get the local truststore file path.
     *
     * @return The truststore {@link File}
     */
    public File getTruststoreFile() {
        return truststoreFile;
    }

    /**
     * Returns the error message from the last failed IDP connection attempt,
     * or {@code null} if the last attempt was successful.
     *
     * @return Error message, or {@code null}
     */
    public String getLastConnectionError() {
        return lastConnectionError;
    }

    // ---- Private helpers ----

    /**
     * Connect to a host via TLS using a trust-all context (just to grab certs),
     * and return the server's certificate chain.
     * <p>
     * If the JVM is configured to use an HTTPS proxy (via {@code https.proxyHost} /
     * {@code https.proxyPort} system properties, or the default {@link ProxySelector}),
     * the connection is tunnelled through the proxy using HTTP CONNECT.
     * </p>
     */
    private X509Certificate[] fetchCertificateChain(String host, int port) throws Exception {
        // Use a capturing trust manager to grab the certificate chain
        final X509Certificate[][] captured = new X509Certificate[1][];

        X509TrustManager capturingTm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                captured[0] = chain;
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext tempCtx = SSLContext.getInstance("TLS");
        tempCtx.init(null, new TrustManager[]{capturingTm}, null);
        SSLSocketFactory factory = tempCtx.getSocketFactory();

        // Detect proxy configuration
        Proxy proxy = resolveProxy(host, port);

        if (proxy != null && proxy.type() == Proxy.Type.HTTP) {
            // Tunnel through HTTP proxy using CONNECT
            InetSocketAddress proxyAddr = (InetSocketAddress) proxy.address();
            LOGGER.log(Level.INFO, "Using HTTPS proxy {0}:{1} to reach {2}:{3}",
                    new Object[]{proxyAddr.getHostString(), proxyAddr.getPort(), host, port});

            try (Socket tunnel = new Socket()) {
                tunnel.setSoTimeout(CONNECT_TIMEOUT_MS);
                tunnel.connect(proxyAddr, CONNECT_TIMEOUT_MS);

                // Send HTTP CONNECT request to the proxy
                String connectReq = "CONNECT " + host + ":" + port + " HTTP/1.1\r\n"
                        + "Host: " + host + ":" + port + "\r\n"
                        + "\r\n";
                OutputStream out = tunnel.getOutputStream();
                out.write(connectReq.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                // Read the proxy response status line
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(tunnel.getInputStream(), StandardCharsets.US_ASCII));
                String statusLine = reader.readLine();
                if (statusLine == null || !statusLine.contains("200")) {
                    throw new java.io.IOException(
                            "Proxy CONNECT failed: " + (statusLine != null ? statusLine : "(no response)"));
                }
                // Consume remaining response headers
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // skip headers
                }

                // Layer SSL on top of the proxy tunnel
                try (SSLSocket socket = (SSLSocket) factory.createSocket(tunnel, host, port, true)) {
                    socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                    socket.startHandshake();
                }
            }
        } else {
            // Direct connection (no proxy)
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.startHandshake();
            }
        }

        return captured[0];
    }

    /**
     * Resolves the proxy to use for a given HTTPS target.
     * Checks the system properties {@code https.proxyHost} / {@code https.proxyPort}
     * first, then falls back to the JVM's default {@link ProxySelector}.
     *
     * @return A {@link Proxy} of type HTTP, or {@code null} if no proxy is needed
     */
    private Proxy resolveProxy(String host, int port) {
        // 1. Check explicit system properties (most common way to set proxy)
        String proxyHost = System.getProperty("https.proxyHost");
        if (proxyHost != null && !proxyHost.isEmpty()) {
            int proxyPort = 8080;
            try {
                proxyPort = Integer.parseInt(System.getProperty("https.proxyPort", "8080"));
            } catch (NumberFormatException ignored) {
            }
            LOGGER.log(Level.FINE, "Resolved proxy from system properties: {0}:{1}",
                    new Object[]{proxyHost, proxyPort});
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        }

        // 2. Fall back to ProxySelector
        try {
            ProxySelector selector = ProxySelector.getDefault();
            if (selector != null) {
                URI targetUri = URI.create("https://" + host + ":" + port);
                List<Proxy> proxies = selector.select(targetUri);
                for (Proxy p : proxies) {
                    if (p.type() == Proxy.Type.HTTP) {
                        LOGGER.log(Level.FINE, "Resolved proxy from ProxySelector: {0}", p);
                        return p;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "ProxySelector lookup failed", e);
        }

        return null;
    }

    private void loadOrCreateTruststore() {
        truststore = null;
        try {
            truststore = KeyStore.getInstance("PKCS12");
            if (truststoreFile.exists()) {
                try (FileInputStream fis = new FileInputStream(truststoreFile)) {
                    truststore.load(fis, TRUSTSTORE_PASSWORD);
                    LOGGER.log(Level.INFO, "Loaded OIDC truststore with {0} entries from {1}",
                            new Object[]{truststore.size(), truststoreFile.getAbsolutePath()});
                }
            } else {
                // Initialize empty truststore
                truststore.load(null, TRUSTSTORE_PASSWORD);
                LOGGER.log(Level.INFO, "Initialized empty OIDC truststore");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load truststore, creating fresh one", e);
            try {
                truststore = KeyStore.getInstance("PKCS12");
                truststore.load(null, TRUSTSTORE_PASSWORD);
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Cannot create truststore", ex);
            }
        }
    }

    private void saveTruststore() {
        try (FileOutputStream fos = new FileOutputStream(truststoreFile)) {
            truststore.store(fos, TRUSTSTORE_PASSWORD);
            LOGGER.log(Level.FINE, "Saved OIDC truststore to {0}", truststoreFile.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save truststore to " + truststoreFile, e);
        }
    }

    /**
     * Build an SSLContext that trusts both:
     * <ul>
     *     <li>The JVM's default trusted CAs (cacerts)</li>
     *     <li>The certificates in our local OIDC truststore</li>
     * </ul>
     * The resulting context is also installed as the JVM-wide default via
     * {@link SSLContext#setDefault(SSLContext)} so that <b>all</b> JVM
     * components that rely on the default SSLContext (including the JavaFX
     * {@code WebEngine}'s internal {@code HTTP2Loader}) will trust the
     * OIDC server's certificates without any per-client configuration.
     */
    private SSLContext buildSSLContext() {
        try {
            // Load the default JVM trust manager
            TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            defaultTmf.init((KeyStore) null); // null = JVM default cacerts
            X509TrustManager defaultTm = findX509TrustManager(defaultTmf);

            // Load our custom trust manager
            TrustManagerFactory customTmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            customTmf.init(truststore);
            X509TrustManager customTm = findX509TrustManager(customTmf);

            // Composite trust manager: try custom first, then default
            X509TrustManager compositeTm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    defaultTm.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    try {
                        customTm.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        // Fall back to JVM default CAs
                        defaultTm.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    X509Certificate[] defaultIssuers = defaultTm.getAcceptedIssuers();
                    X509Certificate[] customIssuers = customTm.getAcceptedIssuers();
                    X509Certificate[] combined = new X509Certificate[defaultIssuers.length + customIssuers.length];
                    System.arraycopy(defaultIssuers, 0, combined, 0, defaultIssuers.length);
                    System.arraycopy(customIssuers, 0, combined, defaultIssuers.length, customIssuers.length);
                    return combined;
                }
            };

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{compositeTm}, null);

            // Install as JVM-wide default so that components we do not control
            // (JavaFX WebEngine HTTP2Loader, Tomcat WebSocket client, etc.)
            // will trust both default CAs and the OIDC server's certificates.
            SSLContext.setDefault(ctx);
            LOGGER.log(Level.INFO,
                    "Built composite SSLContext (JVM defaults + OIDC truststore) and set as JVM default");
            return ctx;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to build composite SSLContext, falling back to default", e);
            try {
                return SSLContext.getDefault();
            } catch (Exception ex) {
                throw new RuntimeException("Cannot obtain default SSLContext", ex);
            }
        }
    }

    private static X509TrustManager findX509TrustManager(TrustManagerFactory tmf) {
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new RuntimeException("No X509TrustManager found");
    }

    /**
     * Build the OIDC base URL from PhoebusSecurity preferences.
     * Returns null if not configured.
     */
    private String buildOidcUrl() {
        String authUrl = PhoebusSecurity.oauth2_auth_url;
        String realm = PhoebusSecurity.oauth2_realm;
        if (authUrl == null || authUrl.isEmpty() || realm == null || realm.isEmpty()) {
            return null;
        }
        // Return the base URL (scheme + host + port) for certificate acquisition
        return authUrl;
    }
}
