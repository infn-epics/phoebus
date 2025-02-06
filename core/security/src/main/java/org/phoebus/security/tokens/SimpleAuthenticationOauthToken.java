package org.phoebus.security.tokens;

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


    public String getJwtToken() {
        return jwtToken;
    }

    public void setJwtToken(String jwtToken) {
        this.jwtToken = jwtToken;
    }
}
