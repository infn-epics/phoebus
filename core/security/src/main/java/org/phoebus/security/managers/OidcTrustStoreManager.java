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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
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
                LOGGER.log(Level.WARNING, "No certificates received from {0}:{1}",
                        new Object[]{host, port});
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
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to acquire certificates from " + httpsUrl, e);
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

    // ---- Private helpers ----

    /**
     * Connect to a host via TLS using a trust-all context (just to grab certs),
     * and return the server's certificate chain.
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

        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.startHandshake();
        }

        return captured[0];
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
            LOGGER.log(Level.INFO, "Built composite SSLContext (JVM defaults + OIDC truststore)");
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
