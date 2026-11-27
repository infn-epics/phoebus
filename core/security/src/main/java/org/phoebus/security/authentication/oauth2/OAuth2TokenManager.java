package org.phoebus.security.authentication.oauth2;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.phoebus.security.PhoebusSecurity;
import org.phoebus.security.managers.OidcTrustStoreManager;
import org.phoebus.security.store.SecureStore;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the OAuth2 access token in the {@link SecureStore} valid.
 *
 * <p>The identity provider issues short-lived access tokens (minutes). At sign-in the token response
 * also carries a refresh token, which this class stores and uses to obtain a new access token shortly
 * before the current one expires, so that services (logbook, save-and-restore, ...) keep working
 * without signing in again. A daemon thread does this in the background, which also keeps the
 * identity provider session alive while Phoebus is idle; {@link #getAccessToken()} refreshes on
 * demand as well.</p>
 *
 * <p>Clients needing a bearer token should call {@link #getAccessToken()} instead of reading
 * {@link SecureStore#JWT_TOKEN_TAG} directly.</p>
 */
public final class OAuth2TokenManager {

    private static final Logger LOGGER = Logger.getLogger(OAuth2TokenManager.class.getName());

    /** Refresh when the access token expires within this many seconds */
    private static final long REFRESH_MARGIN_SECONDS = 60;

    /** Interval of the background check */
    private static final long CHECK_PERIOD_SECONDS = 30;

    private static final Object LOCK = new Object();

    private static ScheduledExecutorService scheduler;

    private OAuth2TokenManager() {
    }

    /**
     * @return The token endpoint of the configured realm
     */
    static String tokenEndpoint() {
        return PhoebusSecurity.oauth2_auth_url + "/realms/" + PhoebusSecurity.oauth2_realm
                + "/protocol/openid-connect/token";
    }

    /**
     * Posts a form to the token endpoint.
     *
     * @param form URL-encoded form parameters
     * @return The JSON response
     * @throws IOException on a transport error or a non-200 response
     */
    static JSONObject postToTokenEndpoint(String form) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(tokenEndpoint()).toURL().openConnection();
        // If the token endpoint is HTTPS, use the OIDC truststore-backed SSLContext
        if (conn instanceof HttpsURLConnection) {
            try {
                SSLContext sslContext = OidcTrustStoreManager.getInstance().getSSLContext();
                ((HttpsURLConnection) conn).setSSLSocketFactory(sslContext.getSocketFactory());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to set OIDC SSLContext on token request, using JVM default", e);
            }
        }
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(form.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status == 200) {
            return (JSONObject) JSONValue.parse(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        String error = "";
        if (conn.getErrorStream() != null) {
            error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        throw new TokenEndpointException(status, error);
    }

    /**
     * Stores the tokens of a token endpoint response (access, id and refresh token) and starts the
     * background refresh.
     *
     * @param tokenResponse Response of the token endpoint
     * @throws Exception if the {@link SecureStore} cannot be written
     */
    public static void storeTokens(JSONObject tokenResponse) throws Exception {
        SecureStore store = new SecureStore();
        synchronized (LOCK) {
            store.set(SecureStore.JWT_TOKEN_TAG, tokenResponse.getAsString("access_token"));
            String idToken = tokenResponse.getAsString("id_token");
            if (idToken != null) {
                store.set(SecureStore.JWT_ID_TOKEN, idToken);
            }
            String refreshToken = tokenResponse.getAsString("refresh_token");
            if (refreshToken != null) {
                store.set(SecureStore.JWT_REFRESH_TOKEN, refreshToken);
            } else {
                store.delete(SecureStore.JWT_REFRESH_TOKEN);
            }
        }
        startBackgroundRefresh();
    }

    /**
     * Removes the refresh token, e.g. on sign-out, so that no new access token is obtained.
     * The access and id tokens are removed by the caller as before.
     */
    public static void clearRefreshToken() {
        try {
            synchronized (LOCK) {
                new SecureStore().delete(SecureStore.JWT_REFRESH_TOKEN);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to remove refresh token", e);
        }
    }

    /**
     * @return The stored access token, refreshed first if it is about to expire and a refresh token
     * is available; <code>null</code> if no access token is stored. The returned token may still be
     * expired if refreshing is not possible: the service then answers 401 and the user must sign in.
     */
    public static String getAccessToken() {
        try {
            SecureStore store = new SecureStore();
            String accessToken = store.get(SecureStore.JWT_TOKEN_TAG);
            if (accessToken == null || accessToken.isEmpty()) {
                return null;
            }
            startBackgroundRefresh();
            if (!expiresSoon(accessToken)) {
                return accessToken;
            }
            refreshIfNeeded();
            return store.get(SecureStore.JWT_TOKEN_TAG);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read OAuth2 access token", e);
            return null;
        }
    }

    /**
     * Refreshes the access token if it expires soon and a refresh token is stored.
     *
     * @return <code>true</code> if a new access token was stored
     */
    static boolean refreshIfNeeded() {
        synchronized (LOCK) {
            try {
                SecureStore store = new SecureStore();
                String accessToken = store.get(SecureStore.JWT_TOKEN_TAG);
                String refreshToken = store.get(SecureStore.JWT_REFRESH_TOKEN);
                // Checked again under the lock: another thread may have refreshed meanwhile
                if (accessToken == null || accessToken.isEmpty() || refreshToken == null || refreshToken.isEmpty()
                        || !expiresSoon(accessToken)) {
                    return false;
                }
                String form = "grant_type=refresh_token"
                        + "&client_id=" + URLEncoder.encode(PhoebusSecurity.oauth2_client_id, StandardCharsets.UTF_8)
                        + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
                JSONObject response = postToTokenEndpoint(form);
                store.set(SecureStore.JWT_TOKEN_TAG, response.getAsString("access_token"));
                String idToken = response.getAsString("id_token");
                if (idToken != null) {
                    store.set(SecureStore.JWT_ID_TOKEN, idToken);
                }
                // The identity provider may rotate the refresh token
                String newRefreshToken = response.getAsString("refresh_token");
                if (newRefreshToken != null) {
                    store.set(SecureStore.JWT_REFRESH_TOKEN, newRefreshToken);
                }
                LOGGER.log(Level.FINE, "OAuth2 access token refreshed");
                return true;
            } catch (TokenEndpointException e) {
                if (e.status == 400 || e.status == 401) {
                    // Refresh token expired or revoked (e.g. identity provider session ended):
                    // stop trying, the user has to sign in again.
                    LOGGER.log(Level.WARNING, "OAuth2 session expired, sign in again: " + e.getMessage());
                    clearRefreshToken();
                } else {
                    LOGGER.log(Level.WARNING, "Failed to refresh OAuth2 access token: " + e.getMessage());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to refresh OAuth2 access token", e);
            }
            return false;
        }
    }

    /**
     * @param jwt A JWT
     * @return <code>true</code> if the token expires within {@link #REFRESH_MARGIN_SECONDS}, or its
     * expiry cannot be determined
     */
    static boolean expiresSoon(String jwt) {
        try {
            DecodedJWT decoded = JWT.decode(jwt);
            if (decoded.getExpiresAtAsInstant() == null) {
                return false;
            }
            return decoded.getExpiresAtAsInstant().isBefore(Instant.now().plusSeconds(REFRESH_MARGIN_SECONDS));
        } catch (Exception e) {
            return true;
        }
    }

    private static synchronized void startBackgroundRefresh() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "OAuth2 token refresh");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(OAuth2TokenManager::refreshIfNeeded,
                CHECK_PERIOD_SECONDS, CHECK_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Non-200 response of the token endpoint
     */
    static class TokenEndpointException extends IOException {
        final int status;

        TokenEndpointException(int status, String body) {
            super("token endpoint answered HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
            this.status = status;
        }
    }
}
