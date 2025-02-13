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

public class SimpleAuthenticationOauthToken {

    private AuthenticationScope scope;

    private String jwtToken;

    public SimpleAuthenticationOauthToken(String jwtToken) {
        this.jwtToken = jwtToken;
    }

    public SimpleAuthenticationOauthToken(AuthenticationScope scope, String jwtToken) {
        this.scope = scope;
        this.jwtToken = jwtToken;
    }



    // create a function that check if a jwt token is still valid
    public boolean checkJwtToken() {

            // check if the jwt token is still valid
            RSAPublicKey publicKey = fetchPublicKey(); // Metodo per recuperare la chiave pubblica

            com.auth0.jwt.interfaces.DecodedJWT decodedJWT = JWT.require(com.auth0.jwt.algorithms.Algorithm.RSA256(publicKey, null))
                    .build()
                    .verify(jwtToken); // Verifica il token

            Date expiration = decodedJWT.getExpiresAt();
            return expiration.after(new Date());
    }

    /**
     * Download the public key from the OIDC server.
     */
    private RSAPublicKey fetchPublicKey() {
        try {
            // Ottieni il documento di configurazione OIDC
            HttpRequest request =
                    HttpRequest
                            .newBuilder()
                            .uri(URI.create(PhoebusSecurity.oauth2_auth_url + "/realms/" + PhoebusSecurity.oauth2_realm  + "/.well-known/openid-configuration"))
                            .GET()
                            .build();

            HttpResponse<String> response = HttpClient.newBuilder().build().send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> oidcConfig = (Map<String, Object>) JSONValue.parse(response.body());
            if (oidcConfig == null) {
                throw new RuntimeException("Failed to fetch OIDC configuration");
            }
            String jwksUri = (String) oidcConfig.get("jwks_uri");


            // Ottiene il JSON Web Key Set (JWKS) dal server OIDC
            request =
                    HttpRequest
                            .newBuilder()
                            .uri(URI.create(jwksUri))
                            .GET()
                            .build();

            response = HttpClient.newBuilder().build().send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> jwks = (Map<String, Object>) JSONValue.parse(response.body());

            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");

            // Get the first key
            Map<String, Object> key = keys.get(0);
            String modulusBase64 = (String) key.get("n");
            String exponentBase64 = (String) key.get("e");

            // Convert the base64-encoded modulus and exponent to RSA public key
            byte[] modulusBytes = java.util.Base64.getUrlDecoder().decode(modulusBase64);
            byte[] exponentBytes = java.util.Base64.getUrlDecoder().decode(exponentBase64);

            java.math.BigInteger modulus = new java.math.BigInteger(1, modulusBytes);
            java.math.BigInteger exponent = new java.math.BigInteger(1, exponentBytes);

            return (RSAPublicKey) java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.RSAPublicKeySpec(modulus, exponent));

        } catch (Exception e) {
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
