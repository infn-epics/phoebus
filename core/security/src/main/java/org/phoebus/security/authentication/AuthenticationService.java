package org.phoebus.security.authentication;

import org.phoebus.framework.jobs.JobManager;
import org.phoebus.security.PhoebusSecurity;
import org.phoebus.security.authentication.oauth2.Oauth2HttpApplicationServer;

import java.util.prefs.Preferences;

public class AuthenticationService {

    public static void init() throws Exception {

        JobManager.schedule("Initialize Authorization Service", (monitor) -> {
            if (PhoebusSecurity.enable_oauth2) {
                // Initialize OAuth2 authentication server callback
                Oauth2HttpApplicationServer oauth2HttpApplicationServer = Oauth2HttpApplicationServer.create();
            }
        });
    }
}
