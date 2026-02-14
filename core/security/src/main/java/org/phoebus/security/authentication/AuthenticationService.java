package org.phoebus.security.authentication;

import org.phoebus.framework.jobs.JobManager;
import org.phoebus.security.PhoebusSecurity;
import org.phoebus.security.authentication.oauth2.Oauth2HttpApplicationServer;
import org.phoebus.security.managers.OidcTrustStoreManager;

import java.util.logging.Level;
import java.util.logging.Logger;

public class AuthenticationService {

    private static final Logger LOGGER = Logger.getLogger(AuthenticationService.class.getName());

    public static void init() throws Exception {

        JobManager.schedule("Initialize Authorization Service", (monitor) -> {
            if (PhoebusSecurity.enable_oauth2) {
                // Acquire OIDC server TLS certificates and build truststore
                try {
                    OidcTrustStoreManager trustStoreMgr = OidcTrustStoreManager.getInstance();
                    trustStoreMgr.refreshCertificates();
                    LOGGER.log(Level.INFO, "OIDC truststore initialized: {0}",
                            trustStoreMgr.getTruststoreFile().getAbsolutePath());
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to initialize OIDC truststore", e);
                }

                // Initialize OAuth2 authentication server callback
                Oauth2HttpApplicationServer oauth2HttpApplicationServer = Oauth2HttpApplicationServer.create();
            }
        });
    }
}
