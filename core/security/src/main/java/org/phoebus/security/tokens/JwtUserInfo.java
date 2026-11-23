package org.phoebus.security.tokens;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.phoebus.security.store.SecureStore;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User identity carried by the OAuth2 JWT kept in the {@link SecureStore}.
 * <p>
 * Services identify the user by {@code preferred_username} (e.g. the INFN UUID), which is not
 * readable; the UI should show the {@code name} claim instead, while keeping the id for
 * anything that is compared with what the services store.
 *
 * @param id          {@code preferred_username} claim, or null
 * @param displayName {@code name} claim, or null
 */
public record JwtUserInfo(String id, String displayName) {

    private static final Logger LOGGER = Logger.getLogger(JwtUserInfo.class.getName());

    /** @return readable name, falling back to the id */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : id;
    }

    /**
     * @param jwt encoded token
     * @return user info, or null when the token cannot be decoded
     */
    public static JwtUserInfo fromToken(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return null;
        }
        try {
            DecodedJWT decoded = JWT.decode(jwt);
            return new JwtUserInfo(decoded.getClaim("preferred_username").asString(),
                    decoded.getClaim("name").asString());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Cannot decode JWT", e);
            return null;
        }
    }

    /** Loading the secure store is slow and table cells ask for every row: cache briefly. */
    private static final long CACHE_MILLIS = 5_000;
    private static volatile JwtUserInfo cached;
    private static volatile long cachedAt;

    /** @return user info from the JWT in the secure store, or null when not logged in with OAuth2 */
    public static JwtUserInfo current() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS) {
            return cached;
        }
        JwtUserInfo info;
        try {
            info = fromToken(new SecureStore().get(SecureStore.JWT_TOKEN_TAG));
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Cannot read JWT from secure store", e);
            info = null;
        }
        cached = info;
        cachedAt = now;
        return info;
    }

    /**
     * @param owner owner id as stored by a service
     * @return the current user's readable name when the owner is the current user, else the owner itself
     */
    public static String readableOwner(String owner) {
        if (owner == null || owner.isEmpty()) {
            return owner;
        }
        JwtUserInfo me = current();
        return me != null && owner.equals(me.id()) ? me.label() : owner;
    }
}
