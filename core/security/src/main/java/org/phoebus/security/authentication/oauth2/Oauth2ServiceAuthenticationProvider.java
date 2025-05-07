package org.phoebus.security.authentication.oauth2;

import org.phoebus.security.authorization.ServiceAuthenticationProvider;
import org.phoebus.security.tokens.AuthenticationScope;

public class Oauth2ServiceAuthenticationProvider implements ServiceAuthenticationProvider {
    @Override
    public void authenticate(String username, String password) {

    }

    @Override
    public void logout(String token) {

    }

    @Override
    public AuthenticationScope getAuthenticationScope() {
        return null;
    }
}
