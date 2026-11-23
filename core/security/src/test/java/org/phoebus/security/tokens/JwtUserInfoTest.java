package org.phoebus.security.tokens;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JwtUserInfoTest {

    private static final String UUID = "f6a87c4d-9a4d-40f8-bcc2-472cd8dcf167";

    @Test
    public void readsIdAndDisplayName() {
        String jwt = JWT.create().withClaim("preferred_username", UUID).withClaim("name", "Mario Rossi")
                .sign(Algorithm.HMAC256("test"));
        JwtUserInfo info = JwtUserInfo.fromToken(jwt);
        assertEquals(UUID, info.id());
        assertEquals("Mario Rossi", info.label());
    }

    @Test
    public void labelFallsBackToId() {
        String jwt = JWT.create().withClaim("preferred_username", UUID).sign(Algorithm.HMAC256("test"));
        assertEquals(UUID, JwtUserInfo.fromToken(jwt).label());
    }

    @Test
    public void invalidTokenGivesNull() {
        assertNull(JwtUserInfo.fromToken("not-a-jwt"));
        assertNull(JwtUserInfo.fromToken(null));
    }
}
