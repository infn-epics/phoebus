package org.phoebus.security.authentication.oauth2;

import org.phoebus.security.authorization.AuthenticationStatus;
import org.phoebus.security.authorization.ServiceAuthenticationProvider;
import org.phoebus.security.tokens.AuthenticationScope;

public class Oauth2ServiceAuthenticationProvider implements ServiceAuthenticationProvider {
    @Override
    public AuthenticationStatus authenticate(String username, String password) {
        return AuthenticationStatus.UNDETERMINED;
    }

    @Override
    public void logout() {
        ServiceAuthenticationProvider.super.logout();
    }

    @Override
    public AuthenticationScope getAuthenticationScope() {
        return null;
    }
}
