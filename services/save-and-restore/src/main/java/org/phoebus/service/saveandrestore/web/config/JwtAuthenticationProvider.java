/*
 * Copyright (C) 2025 European Spallation Source ERIC.
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.phoebus.service.saveandrestore.web.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Custom authentication provider that verifies JWT tokens issued by an OIDC server.
 * <p>
 * Only instantiated when the application property <code>oauth2.enabled</code> is set to <code>true</code>.
 * The provider fetches the RSA public key from the OIDC server's
 * <code>.well-known/openid-configuration</code> endpoint and uses it to verify JWT signatures.
 * </p>
 */
@Component
@ConditionalOnProperty(name = "oauth2.enabled", havingValue = "true")
public class JwtAuthenticationProvider implements AuthenticationProvider {

    private static final Logger logger = Logger.getLogger(JwtAuthenticationProvider.class.getName());

    @Value("${oauth2.issueUri}")
    private String issuerUri;

    @Value("${oauth2.claimsName:name}")
    private String claimsName;

    private RestTemplate restTemplate;

    /**
     * Build a RestTemplate that trusts the JVM truststore (if set via
     * {@code javax.net.ssl.trustStore}) <b>and</b> the JRE default cacerts so
     * that connections to both the internal Elasticsearch cluster and the
     * external OIDC provider succeed.
     * <p>
     * If no custom truststore is configured, or if anything goes wrong building
     * the composite trust manager, we fall back to a plain {@link RestTemplate}
     * and log a warning.
     */
    @PostConstruct
    private void initRestTemplate() {
        String tsPath = System.getProperty("javax.net.ssl.trustStore");
        String tsPass = System.getProperty("javax.net.ssl.trustStorePassword", "changeit");

        if (tsPath != null) {
            try {
                // Load the custom truststore (e.g. Elasticsearch cert)
                KeyStore customTs = KeyStore.getInstance(KeyStore.getDefaultType());
                try (FileInputStream fis = new FileInputStream(tsPath)) {
                    customTs.load(fis, tsPass.toCharArray());
                }

                // Explicitly load the JRE default cacerts (not affected by
                // -Djavax.net.ssl.trustStore which overrides what "default" means).
                String javaHome = System.getProperty("java.home");
                java.io.File cacertsFile = new java.io.File(javaHome, "lib/security/cacerts");
                KeyStore cacerts = KeyStore.getInstance(KeyStore.getDefaultType());
                try (FileInputStream fis = new FileInputStream(cacertsFile)) {
                    cacerts.load(fis, "changeit".toCharArray());
                }

                // Build an SSLContext that trusts BOTH the JRE cacerts AND the custom truststore.
                SSLContext sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(cacerts, null)       // JRE default cacerts
                        .loadTrustMaterial(customTs, null)      // custom truststore
                        .build();

                CloseableHttpClient httpClient = HttpClients.custom()
                        .setSSLSocketFactory(new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE))
                        .build();

                this.restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
                logger.log(Level.INFO,
                        "Initialized RestTemplate with composite truststore (JRE cacerts + {0})", tsPath);
                return;
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to build composite SSL context, falling back to plain RestTemplate: " + e.getMessage(), e);
            }
        }

        this.restTemplate = new RestTemplate();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String jwtToken = (String) authentication.getCredentials();

        // Skip if credentials don't look like a JWT token (3 dot-separated parts)
        if (jwtToken == null || jwtToken.split("\\.").length != 3) {
            return null;
        }

        try {
            logger.log(Level.INFO, "Attempting JWT authentication against issuer: {0}", issuerUri);

            RSAPublicKey publicKey = fetchPublicKey();
            logger.log(Level.FINE, "Successfully fetched public key from OIDC provider");

            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(jwtToken)
                    .getBody();

            logger.log(Level.FINE, "JWT signature verified successfully. Claims: iss={0}, sub={1}, exp={2}",
                    new Object[]{claims.getIssuer(), claims.getSubject(), claims.getExpiration()});

            String username = claims.get(claimsName, String.class);
            if (username == null) {
                logger.log(Level.SEVERE, "Username claim ''{0}'' not found in JWT token. Available claims: {1}",
                        new Object[]{claimsName, claims.keySet()});
                throw new UsernameNotFoundException("Username not found in token using claim: " + claimsName);
            }

            logger.log(Level.INFO, "JWT authentication successful for user: {0}", username);

            // Assign the user the ROLE_ADMIN role so they can write (sar-admin > sar-user in hierarchy)
            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_SAR-ADMIN")
            );

            return new UsernamePasswordAuthenticationToken(username, jwtToken, authorities);

        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "JWT authentication failed: " + e.getMessage(), e);
            throw new InternalAuthenticationServiceException("Invalid JWT token: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /**
     * Download the public key from the OIDC server.
     */
    @SuppressWarnings("unchecked")
    private RSAPublicKey fetchPublicKey() {
        try {
            // Get the OIDC configuration document
            String oidcUrl = issuerUri + "/.well-known/openid-configuration";
            logger.log(Level.FINE, "Fetching OIDC configuration from: {0}", oidcUrl);
            Map<String, Object> oidcConfig = restTemplate.getForObject(oidcUrl, Map.class);
            if (oidcConfig == null) {
                throw new RuntimeException("Failed to fetch OIDC configuration from: " + oidcUrl);
            }
            String jwksUri = (String) oidcConfig.get("jwks_uri");
            logger.log(Level.FINE, "Fetching JWKS from: {0}", jwksUri);

            // Obtain the JSON Web Key Set (JWKS) from the OIDC server
            Map<String, Object> jwks = restTemplate.getForObject(jwksUri, Map.class);
            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");

            // Get the first RSA key
            Map<String, Object> key = keys.get(0);
            String kid = (String) key.get("kid");
            logger.log(Level.FINE, "Using JWKS key with kid: {0}", kid);
            String modulusBase64 = (String) key.get("n");
            String exponentBase64 = (String) key.get("e");

            // Convert the base64url-encoded modulus and exponent to RSA public key
            byte[] modulusBytes = Base64.getUrlDecoder().decode(modulusBase64);
            byte[] exponentBytes = Base64.getUrlDecoder().decode(exponentBase64);

            BigInteger modulus = new BigInteger(1, modulusBytes);
            BigInteger exponent = new BigInteger(1, exponentBytes);

            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to fetch public key from OIDC provider at " + issuerUri + ": " + e.getMessage(), e);
            throw new RuntimeException("Failed to fetch or parse public key from OIDC provider", e);
        }
    }
}
