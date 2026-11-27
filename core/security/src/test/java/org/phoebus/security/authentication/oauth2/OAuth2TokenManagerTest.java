package org.phoebus.security.authentication.oauth2;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sun.net.httpserver.HttpServer;
import net.minidev.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.security.PhoebusSecurity;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link OAuth2TokenManager} that do not touch the user's {@link org.phoebus.security.store.SecureStore}.
 */
public class OAuth2TokenManagerTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status;
    private volatile String responseBody;
    private String savedUrl, savedRealm, savedClient;

    private static String tokenExpiringAt(Instant expiry) {
        return JWT.create().withExpiresAt(expiry).sign(Algorithm.HMAC256("secret"));
    }

    @BeforeEach
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        savedUrl = PhoebusSecurity.oauth2_auth_url;
        savedRealm = PhoebusSecurity.oauth2_realm;
        savedClient = PhoebusSecurity.oauth2_client_id;
        PhoebusSecurity.oauth2_auth_url = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth";
        PhoebusSecurity.oauth2_realm = "test";
        PhoebusSecurity.oauth2_client_id = "phoebus";
    }

    @AfterEach
    public void stopServer() {
        server.stop(0);
        PhoebusSecurity.oauth2_auth_url = savedUrl;
        PhoebusSecurity.oauth2_realm = savedRealm;
        PhoebusSecurity.oauth2_client_id = savedClient;
    }

    @Test
    public void testExpiresSoon() {
        assertTrue(OAuth2TokenManager.expiresSoon(tokenExpiringAt(Instant.now().minusSeconds(10))));
        assertTrue(OAuth2TokenManager.expiresSoon(tokenExpiringAt(Instant.now().plusSeconds(30))));
        assertFalse(OAuth2TokenManager.expiresSoon(tokenExpiringAt(Instant.now().plusSeconds(600))));
        // Not a JWT: treated as expiring, so that a refresh is attempted
        assertTrue(OAuth2TokenManager.expiresSoon("not-a-jwt"));
    }

    @Test
    public void testTokenEndpoint() throws Exception {
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort()
                + "/auth/realms/test/protocol/openid-connect/token", OAuth2TokenManager.tokenEndpoint());

        status = 200;
        responseBody = "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}";
        JSONObject response = OAuth2TokenManager.postToTokenEndpoint("grant_type=refresh_token&client_id=phoebus&refresh_token=r");
        assertEquals("new-access", response.getAsString("access_token"));
        assertEquals("new-refresh", response.getAsString("refresh_token"));
        assertEquals("grant_type=refresh_token&client_id=phoebus&refresh_token=r", requestBody.get());
    }

    @Test
    public void testTokenEndpointError() {
        status = 400;
        responseBody = "{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}";
        OAuth2TokenManager.TokenEndpointException e = assertThrows(OAuth2TokenManager.TokenEndpointException.class,
                () -> OAuth2TokenManager.postToTokenEndpoint("grant_type=refresh_token"));
        assertEquals(400, e.status);
        assertTrue(e.getMessage().contains("invalid_grant"));
    }
}
