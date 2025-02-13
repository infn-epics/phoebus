package org.phoebus.applications.logbook;


import com.auth0.jwt.exceptions.JWTVerificationException;
import org.phoebus.logbook.LogClient;
import org.phoebus.logbook.LogFactory;
import org.phoebus.logbook.LogbookException;
import org.phoebus.olog.es.api.OlogHttpClient;
import org.phoebus.security.tokens.SimpleAuthenticationOauthToken;
import org.phoebus.security.tokens.SimpleAuthenticationToken;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logbook client for the new es based olog.
 * TODO: in the future this client would replace the old olog client
 *
 * @author kunal
 */
public class OlogESLogbook implements LogFactory {

    private static final Logger logger = Logger.getLogger(OlogESLogbook.class.getName());
    private static final String ID = "olog-es";

    @Override
    public String getId() {
        return ID;
    }

    /**
     *
     * @return A fresh instance of the client. Instead of maintaining a client reference in this class,
     * a new instance is return since the user may have signed out or signed in thus invalidating
     * the authentication token.
     */
    @Override
    public LogClient getLogClient() {
        try {
            return OlogHttpClient.builder().build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create olog es client", e);
        }
        return null;
    }

    /**
     * @param authToken An authentication token.
     * @return A fresh instance of the client. Instead of maintaining a client reference in this class,
     * a new instance to force usage of the specified authentication token.
     */
    @Override
    public LogClient getLogClient(Object authToken) throws LogbookException {
        try {
            if (authToken instanceof SimpleAuthenticationToken) {
                SimpleAuthenticationToken token = (SimpleAuthenticationToken) authToken;
                return OlogHttpClient.builder().username(token.getUsername()).password(token.getPassword())
                        .build();
            } else if (authToken instanceof SimpleAuthenticationOauthToken){
                SimpleAuthenticationOauthToken token = (SimpleAuthenticationOauthToken) authToken;
                try {
                    boolean result = token.checkJwtToken();
                    if (result) {
                        return OlogHttpClient.builder().withBearerToken(token.getJwtToken()).build();
                    } else {
                        throw new LogbookException("Oauth2 Token has expired");
                    }
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to refresh token", e);
                    throw new LogbookException(e);
                }
            }
            else {
                return getLogClient();
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create olog client", e);
            throw new LogbookException(e);
        }
    }

}
