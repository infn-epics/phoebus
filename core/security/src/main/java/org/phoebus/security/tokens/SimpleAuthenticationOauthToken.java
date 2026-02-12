package org.phoebus.security.tokens;

import com.auth0.jwt.JWT;
import net.minidev.json.JSONValue;
import org.phoebus.security.PhoebusSecurity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimpleAuthenticationOauthToken {

    private static final Logger LOGGER = Logger.getLogger(SimpleAuthenticationOauthToken.class.getName());

    /**
     * Shared HttpClient for OIDC/JWKS requests. Uses the JVM default SSLContext
     * (which may be trust-all if configured by OlogHttpClient) and HTTP/1.1 to
     * avoid selector manager issues. Reusing a single instance prevents creation
     * of multiple ephemeral HttpClient instances that waste file descriptors.
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private AuthenticationScope scope;

    private String jwtToken;

    public SimpleAuthenticationOauthToken(String jwtToken) {
        this.jwtToken = jwtToken;
    }

    public SimpleAuthenticationOauthToken(AuthenticationScope scope, String jwtToken) {
        this.scope = scope;
        this.jwtToken = jwtToken;
    }

    /**
     * Check if the JWT token is still valid by verifying signature and expiration.
     */
    public boolean checkJwtToken() {
            RSAPublicKey publicKey = fetchPublicKey();

            com.auth0.jwt.interfaces.DecodedJWT decodedJWT = JWT.require(com.auth0.jwt.algorithms.Algorithm.RSA256(publicKey, null))
                    .build()
                    .verify(jwtToken);

            Date expiration = decodedJWT.getExpiresAt();
            return expiration.after(new Date());
    }

    /**
     * Download the public key from the OIDC server.
     */
    private RSAPublicKey fetchPublicKey() {
        try {
            HttpRequest request =
                    HttpRequest
                            .newBuilder()
                            .uri(URI.create(PhoebusSecurity.oauth2_auth_url + "/realms/" + PhoebusSecurity.oauth2_realm  + "/.well-known/openid-configuration"))
                            .GET()
                            .build();

            HttpResponse<String> response = SHARED_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> oidcConfig = (Map<String, Object>) JSONValue.parse(response.body());
            if (oidcConfig == null) {
                throw new RuntimeException("Failed to fetch OIDC configuration");
            }
            String jwksUri = (String) oidcConfig.get("jwks_uri");

            request =
                    HttpRequest
                            .newBuilder()
                            .uri(URI.create(jwksUri))
                            .GET()
                            .build();

            response = SHARED_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> jwks = (Map<String, Object>) JSONValue.parse(response.body());

            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");

            Map<String, Object> key = keys.get(0);
            String modulusBase64 = (String) key.get("n");
            String exponentBase64 = (String) key.get("e");

            byte[] modulusBytes = java.util.Base64.getUrlDecoder().decode(modulusBase64);
            byte[] exponentBytes = java.util.Base64.getUrlDecoder().decode(exponentBase64);

            java.math.BigInteger modulus = new java.math.BigInteger(1, modulusBytes);
            java.math.BigInteger exponent = new java.math.BigInteger(1, exponentBytes);

            return (RSAPublicKey) java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.RSAPublicKeySpec(modulus, exponent));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to fetch or parse public key from OIDC provider", e);
            throw new RuntimeException("Failed to fetch or parse public key from OIDC provider", e);
        }
    }



    public String getJwtToken() {
        return jwtToken;
    }

    public void setJwtToken(String jwtToken) {
        this.jwtToken = jwtToken;
    }
}
