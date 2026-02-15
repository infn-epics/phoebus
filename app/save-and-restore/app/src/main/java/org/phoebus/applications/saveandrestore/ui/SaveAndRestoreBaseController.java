/*
 * Copyright (C) 2020 European Spallation Source ERIC.
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
 *
 */

package org.phoebus.applications.saveandrestore.ui;

import javafx.beans.property.SimpleStringProperty;
import org.phoebus.applications.saveandrestore.authentication.SaveAndRestoreAuthenticationScope;
import org.phoebus.security.store.SecureStore;
import org.phoebus.security.tokens.ScopedAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class SaveAndRestoreBaseController {

    protected final SimpleStringProperty userIdentity = new SimpleStringProperty();
    protected final SaveAndRestoreService saveAndRestoreService;

    public SaveAndRestoreBaseController() {
        this.saveAndRestoreService = SaveAndRestoreService.getInstance();
        try {
            SecureStore secureStore = new SecureStore();
            ScopedAuthenticationToken token =
                    secureStore.getScopedAuthenticationToken(new SaveAndRestoreAuthenticationScope());
            if (token != null) {
                userIdentity.set(token.getUsername());
            } else {
                // Check if OAuth2 login was done: auth_mode is "oauth2" and a JWT token exists
                String authMode = secureStore.getAuthMode(new SaveAndRestoreAuthenticationScope());
                if (SecureStore.AUTH_MODE_OAUTH2.equals(authMode)) {
                    String jwt = secureStore.get(SecureStore.JWT_TOKEN_TAG);
                    if (jwt != null) {
                        String username = extractUsernameFromJwt(jwt);
                        userIdentity.set(username);
                    } else {
                        userIdentity.set(null);
                    }
                } else {
                    userIdentity.set(null);
                }
            }
        } catch (Exception e) {
            Logger.getLogger(SaveAndRestoreBaseController.class.getName()).log(Level.WARNING, "Unable to retrieve authentication token for " +
                    new SaveAndRestoreAuthenticationScope().getScope() + " scope", e);
        }
    }

    /**
     * Extracts the username from a JWT token by decoding the payload and reading the "preferred_username" claim.
     *
     * @param jwt The JWT token string.
     * @return The username, or "OAuth2" if the claim is not found.
     */
    private static String extractUsernameFromJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "OAuth2";
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            // Use simple JSON parsing to avoid extra dependency
            int idx = payload.indexOf("\"preferred_username\"");
            if (idx >= 0) {
                int colonIdx = payload.indexOf(':', idx);
                int quoteStart = payload.indexOf('"', colonIdx + 1);
                int quoteEnd = payload.indexOf('"', quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    return payload.substring(quoteStart + 1, quoteEnd);
                }
            }
        } catch (Exception e) {
            Logger.getLogger(SaveAndRestoreBaseController.class.getName())
                    .log(Level.WARNING, "Failed to extract username from JWT", e);
        }
        return "OAuth2";
    }

    public void secureStoreChanged(List<ScopedAuthenticationToken> validTokens) {
        Optional<ScopedAuthenticationToken> token =
                validTokens.stream()
                        .filter(t -> t.getAuthenticationScope().getScope().equals(new SaveAndRestoreAuthenticationScope().getScope())).findFirst();
        if (token.isPresent()) {
            userIdentity.set(token.get().getUsername());
        } else {
            userIdentity.set(null);
        }
    }

    public SimpleStringProperty getUserIdentity() {
        return userIdentity;
    }

    /**
     * Performs suitable cleanup, e.g. close web socket and PVs (where applicable).
     */
    public abstract void handleTabClosed();

    /**
     * Checks if the tab may be closed, e.g. if data managed in the UI has been saved.
     *
     * @return <code>false</code> if tab contains unsaved data, otherwise <code>true</code>
     */
    public abstract boolean doCloseCheck();
}
