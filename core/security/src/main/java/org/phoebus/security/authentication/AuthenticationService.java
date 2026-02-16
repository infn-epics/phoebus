package org.phoebus.security.authentication;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
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
                    boolean ok = trustStoreMgr.refreshCertificates();
                    if (ok) {
                        LOGGER.log(Level.INFO, "OIDC truststore initialized: {0}",
                                trustStoreMgr.getTruststoreFile().getAbsolutePath());
                    } else {
                        String error = trustStoreMgr.getLastConnectionError();
                        LOGGER.log(Level.WARNING, "OIDC truststore initialization failed: {0}", error);
                        Platform.runLater(() -> {
                            Alert alert = new Alert(AlertType.WARNING);
                            alert.setTitle("IDP Connection Failed");
                            alert.setHeaderText("Cannot connect to Identity Provider");
                            alert.setContentText(
                                    "Phoebus could not reach the OIDC/IDP server to fetch TLS certificates.\n\n"
                                    + (error != null ? error : "Unknown error") + "\n\n"
                                    + "OAuth2 authentication may not work until the IDP is reachable.\n"
                                    + "You can retry from Credentials Management → View Certificates → Refresh.");
                            alert.showAndWait();
                        });
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to initialize OIDC truststore", e);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(AlertType.WARNING);
                        alert.setTitle("IDP Connection Failed");
                        alert.setHeaderText("Cannot connect to Identity Provider");
                        alert.setContentText(
                                "Failed to initialize OIDC truststore:\n" + e.getMessage() + "\n\n"
                                + "OAuth2 authentication may not work.\n"
                                + "You can retry from Credentials Management → View Certificates → Refresh.");
                        alert.showAndWait();
                    });
                }

                // Initialize OAuth2 authentication server callback
                Oauth2HttpApplicationServer oauth2HttpApplicationServer = Oauth2HttpApplicationServer.create();
            }
        });
    }
}
