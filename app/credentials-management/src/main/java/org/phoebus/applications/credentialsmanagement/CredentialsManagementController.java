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
 */

package org.phoebus.applications.credentialsmanagement;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.security.PhoebusSecurity;
import org.phoebus.security.authorization.AuthenticationStatus;
import org.phoebus.security.authorization.ServiceAuthenticationProvider;
import org.phoebus.security.store.SecureStore;
import org.phoebus.security.tokens.AuthenticationScope;
import org.phoebus.security.tokens.ScopedAuthenticationToken;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CredentialsManagementController {

    @SuppressWarnings("unused")
    @FXML
    private Node parent;

    @SuppressWarnings("unused")
    @FXML
    private TableView<ServiceItem> tableView;
    @SuppressWarnings("unused")
    @FXML
    private TableColumn<ServiceItem, ServiceItem> actionButtonColumn;
    @SuppressWarnings("unused")
    @FXML
    private TableColumn<ServiceItem, StringProperty> usernameColumn;
    @SuppressWarnings("unused")
    @FXML
    private TableColumn<ServiceItem, StringProperty> passwordColumn;
    @SuppressWarnings("unused")
    @FXML
    private TableColumn<ServiceItem, StringProperty> loginResultColumn;
    @SuppressWarnings("unused")
    @FXML
    private Button loginToAllButton;
    @SuppressWarnings("unused")
    @FXML
    private Button logoutFromAllButton;
    @SuppressWarnings("unused")
    @FXML
    private TextField loginToAllUsernameTextField;
    @SuppressWarnings("unused")
    @FXML
    private PasswordField loginToAllPasswordTextField;
    @SuppressWarnings("unused")
    @FXML
    private TableColumn<ServiceItem, String> scopeColumn;
    @FXML
    private Button loginWithOAuth2;

    private final SimpleBooleanProperty listEmpty = new SimpleBooleanProperty(true);
    private final ObservableList<ServiceItem> serviceItems =
            FXCollections.observableArrayList();
    private final SecureStore secureStore;
    private static final Logger LOGGER = Logger.getLogger(CredentialsManagementController.class.getName());
    private final List<ServiceAuthenticationProvider> authenticationProviders;
    private final StringProperty loginToAllUsernameProperty = new SimpleStringProperty();
    private final StringProperty loginToAllPasswordProperty = new SimpleStringProperty();
    private final IntegerProperty providerCount = new SimpleIntegerProperty(0);
    private final IntegerProperty loggedInCount = new SimpleIntegerProperty(0);
    private final SimpleBooleanProperty oauthLoggedIn = new SimpleBooleanProperty(false);

    private Stage stage;

    public CredentialsManagementController(List<ServiceAuthenticationProvider> authenticationProviders, SecureStore secureStore) {
        this.authenticationProviders = authenticationProviders;
        this.secureStore = secureStore;
        providerCount.set(this.authenticationProviders.size());
    }

    @SuppressWarnings("unused")
    @FXML
    public void initialize() {

        // OAuth2 button setup
        if (loginWithOAuth2 != null) {
            if (PhoebusSecurity.enable_oauth2) {
                loginWithOAuth2.setVisible(true);
                loginWithOAuth2.textProperty().bind(
                        Bindings.when(oauthLoggedIn)
                                .then(Messages.LogoutOAuth2)
                                .otherwise(Messages.LoginWithOAuth2)
                );
            } else {
                loginWithOAuth2.setVisible(false);
            }
        }

        try {
            if (secureStore != null) {
                String jwt = secureStore.get(SecureStore.JWT_TOKEN_TAG);
                if (jwt != null) {
                    net.minidev.json.JSONObject claims = parseJwtPayload(jwt);
                    if (claims != null && claims.containsKey("exp")) {
                        long exp = ((Number) claims.get("exp")).longValue();
                        if (System.currentTimeMillis() / 1000 < exp) {
                            oauthLoggedIn.set(true);
                            LOGGER.info("Found valid OAuth2 token in SecureStore (expires at " + exp + ")");
                        } else {
                            LOGGER.info("OAuth2 token in SecureStore has expired, clearing");
                            secureStore.delete(SecureStore.JWT_TOKEN_TAG);
                            secureStore.delete(SecureStore.JWT_ID_TOKEN);
                            oauthLoggedIn.set(false);
                        }
                    } else {
                        LOGGER.warning("Cannot verify OAuth2 token expiry, clearing stale token");
                        secureStore.delete(SecureStore.JWT_TOKEN_TAG);
                        secureStore.delete(SecureStore.JWT_ID_TOKEN);
                        oauthLoggedIn.set(false);
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Error checking stored OAuth2 token", ex);
            oauthLoggedIn.set(false);
        }

        tableView.setSelectionModel(null);
        try {
            tableView.getStylesheets().add(getClass().getResource("/css/credentials-management-style.css").toExternalForm());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not load credentials-management-style.css", e);
        }

        usernameColumn.setCellFactory(c -> new UsernameTableCell());
        passwordColumn.setCellFactory(c -> new PasswordTableCell());
        loginResultColumn.setCellFactory(c -> new LoginResultTableCell());

        loginToAllUsernameTextField.visibleProperty().bind(Bindings.createBooleanBinding(() -> providerCount.get() > 1, providerCount));
        loginToAllPasswordTextField.visibleProperty().bind(Bindings.createBooleanBinding(() -> providerCount.get() > 1, providerCount));
        loginToAllUsernameTextField.textProperty().bindBidirectional(loginToAllUsernameProperty);
        loginToAllPasswordTextField.textProperty().bindBidirectional(loginToAllPasswordProperty);

        loginToAllButton.visibleProperty().bind(Bindings.createBooleanBinding(() -> providerCount.get() > 1, providerCount));
        loginToAllButton.disableProperty().bind(Bindings.createBooleanBinding(() ->
                        loginToAllUsernameProperty.get() == null ||
                        loginToAllUsernameProperty.get().isEmpty() ||
                        loginToAllPasswordProperty.get() == null ||
                        loginToAllPasswordProperty.get().isEmpty(),
                loginToAllUsernameProperty, loginToAllPasswordProperty));

        logoutFromAllButton.disableProperty().bind(Bindings.createBooleanBinding(() -> loggedInCount.get() == 0, loggedInCount));
        actionButtonColumn.setCellValueFactory(param -> new SimpleObjectProperty<>(param.getValue()));

        configureCellFactory();

        updateTable();

        // When OAuth2 status changes, reflect it in the olog ServiceItem in the table
        oauthLoggedIn.addListener((obs, wasLoggedIn, isLoggedIn) -> {
            Platform.runLater(() -> updateOlogOAuth2Status(isLoggedIn));
        });

        Platform.runLater(() -> tableView.requestFocus());
    }

    private void configureCellFactory() {
        Callback<TableColumn<ServiceItem, ServiceItem>, TableCell<ServiceItem, ServiceItem>> actionColumnCellFactory = new Callback<>() {
            @Override
            public TableCell<ServiceItem, ServiceItem> call(final TableColumn<ServiceItem, ServiceItem> param) {
                return new TableCell<>() {

                    private final Button btn = new Button(Messages.LogoutButtonText);

                    {
                        btn.getStyleClass().add("button-style");
                        btn.setOnAction((ActionEvent event) -> {
                            ServiceItem serviceItem = getTableRow().getItem();
                            if (serviceItem != null && (serviceItem.authenticationStatus.get().equals(AuthenticationStatus.AUTHENTICATED) ||
                                    serviceItem.authenticationStatus.get().equals(AuthenticationStatus.CACHED))) {
                                logOut(serviceItem);
                            } else {
                                login(serviceItem, 1);
                            }
                        });
                    }

                    @Override
                    public void updateItem(ServiceItem serviceItem, boolean empty) {
                        super.updateItem(serviceItem, empty);
                        if (empty) {
                            setGraphic(null);
                        } else {
                            btn.textProperty().bind(serviceItem.buttonTextProperty);
                            btn.disableProperty().bind(Bindings.createBooleanBinding(() ->
                                            serviceItem.username.isNull().get() || serviceItem.username.get().isEmpty() ||
                                                    serviceItem.password.isNull().get() || serviceItem.password.get().isEmpty(),
                                    serviceItem.username, serviceItem.password));
                            setGraphic(btn);
                        }
                    }
                };
            }
        };
        actionButtonColumn.setCellFactory(actionColumnCellFactory);
    }

    @SuppressWarnings("unused")
    @FXML
    public synchronized void logoutFromAll() {
        try {
            tableView.getItems().forEach(this::logOut);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to delete all authentication tokens from key store", e);
            ExceptionDetailsErrorDialog.openError(parent, Messages.ErrorDialogTitle, Messages.ErrorDialogBody, e);
        }
    }

    @SuppressWarnings("unused")
    @FXML
    public synchronized void loginToAll() {
        logoutFromAll();
        try {
            for (ServiceItem serviceItem : tableView.getItems()) {
                serviceItem.username.set(loginToAllUsernameProperty.get());
                serviceItem.password.set(loginToAllPasswordProperty.get());
                login(serviceItem, tableView.getItems().size());
            }
            if (loggedInCount.get() == tableView.getItems().size()) {
                stage.close();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to login to all services", e);
        }
    }

    @FXML
    public void loginWithOAuth2() {
        try {
            if (oauthLoggedIn.get()) {
                String logoutUrl = PhoebusSecurity.oauth2_auth_url + "/realms/" + PhoebusSecurity.oauth2_realm
                        + "/protocol/openid-connect/logout"
                        + "?id_token_hint=" + secureStore.get(SecureStore.JWT_ID_TOKEN)
                        + "&post_logout_redirect_uri=";

                URL url = new URL(logoutUrl);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setDoOutput(true);
                int responseCode = con.getResponseCode();
                LOGGER.info("OAuth2 logout response code: " + responseCode);
                con.disconnect();

                oauthLoggedIn.set(false);
                secureStore.delete(SecureStore.JWT_ID_TOKEN);
                secureStore.delete(SecureStore.JWT_TOKEN_TAG);
            } else {
                String redirectUri = "http://localhost:" + PhoebusSecurity.oauth2_callback_server_port + PhoebusSecurity.oauth2_callback;
                String authUrl = PhoebusSecurity.oauth2_auth_url + "/realms/" + PhoebusSecurity.oauth2_realm
                        + "/protocol/openid-connect/auth?response_type=code&client_id="
                        + PhoebusSecurity.oauth2_client_id
                        + "&scope=openid%20email&redirect_uri=" + redirectUri;

                OAuth2Browser.openInNewStage(authUrl, redirectUri, oauthLoggedIn, stage);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "OAuth2 login/logout failed", e);
            ExceptionDetailsErrorDialog.openError(parent, Messages.ErrorDialogTitle, Messages.ErrorDialogBody, e);
        }
    }

    private synchronized void login(ServiceItem serviceItem, int expectedLoginCount) {
        AuthenticationStatus authenticationResult = serviceItem.login();
        if (authenticationResult.equals(AuthenticationStatus.AUTHENTICATED)) {
            loggedInCount.set(loggedInCount.get() + 1);
            if (expectedLoginCount == loggedInCount.get()) {
                stage.close();
            }
        }
    }

    private synchronized void logOut(ServiceItem serviceItem) {
        if (serviceItem.authenticationStatus.get().equals(AuthenticationStatus.AUTHENTICATED)
                || serviceItem.authenticationStatus.get().equals(AuthenticationStatus.CACHED)) {
            try {
                serviceItem.logout();
                loggedInCount.set(loggedInCount.get() - 1);
                Platform.runLater(() -> tableView.requestFocus());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to logout from service " + serviceItem.getDisplayName(), e);
            }
        }
    }

    private void updateTable() {
        JobManager.schedule("Get Credentials", monitor -> {
            List<ScopedAuthenticationToken> savedTokens = secureStore.getAuthenticationTokens();
            List<ServiceItem> serviceItems = savedTokens.stream().map(token -> {
                ServiceAuthenticationProvider provider =
                        authenticationProviders.stream()
                                .filter(p -> p.getAuthenticationScope().getScope().equals(token.getAuthenticationScope().getScope()))
                                .findFirst().orElse(null);
                loggedInCount.set(loggedInCount.get() + 1);
                return new ServiceItem(provider, AuthenticationStatus.CACHED, token.getUsername(), token.getPassword());
            }).collect(Collectors.toList());

            authenticationProviders.forEach(p -> {
                Optional<ServiceItem> serviceItem =
                        serviceItems.stream().filter(si ->
                                si.getAuthenticationScope() != null &&
                                p.getAuthenticationScope().getScope().equals(si.getAuthenticationScope().getScope())).findFirst();
                if (serviceItem.isEmpty()) {
                    serviceItems.add(new ServiceItem(p, AuthenticationStatus.UNDETERMINED, null, null));
                }
            });
            serviceItems.sort(Comparator.comparing(i ->
                    i.getAuthenticationScope() != null ? i.getAuthenticationScope().getDisplayName() : ""));

            Platform.runLater(() -> {
                this.serviceItems.setAll(serviceItems);
                listEmpty.set(savedTokens.isEmpty());
                tableView.setItems(this.serviceItems);
                // If OAuth2 is already logged in, update the olog service item
                if (oauthLoggedIn.get()) {
                    updateOlogOAuth2Status(true);
                }
            });
        });
    }

    private void updateOlogOAuth2Status(boolean loggedIn) {
        for (ServiceItem item : serviceItems) {
            if (item.getAuthenticationScope() != null &&
                    ("logbook".equals(item.getAuthenticationScope().getScope()) ||
                     "save-and-restore".equals(item.getAuthenticationScope().getScope()))) {
                if (loggedIn) {
                    try {
                        String jwt = secureStore.get(SecureStore.JWT_TOKEN_TAG);
                        String username = "OAuth2";
                        if (jwt != null) {
                            net.minidev.json.JSONObject claims = parseJwtPayload(jwt);
                            if (claims != null && claims.containsKey("preferred_username")) {
                                username = (String) claims.get("preferred_username");
                            }
                        }
                        item.username.set(username);
                        item.password.set("(OAuth2 token)");
                        item.authenticationStatus.set(AuthenticationStatus.AUTHENTICATED);
                        item.loginResultMessage.set("OAuth2 OK");
                        LOGGER.info("Olog service item updated: OAuth2 user=" + username);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to update OAuth2 status in table", e);
                    }
                } else {
                    item.authenticationStatus.set(AuthenticationStatus.UNDETERMINED);
                    item.username.set(null);
                    item.password.set(null);
                    item.loginResultMessage.set(null);
                }
            }
        }
    }

    private static net.minidev.json.JSONObject parseJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            return (net.minidev.json.JSONObject) net.minidev.json.JSONValue.parse(payload);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse JWT payload", e);
            return null;
        }
    }

    public class ServiceItem {
        private final ServiceAuthenticationProvider serviceAuthenticationProvider;
        private final StringProperty username = new SimpleStringProperty();
        private final StringProperty password = new SimpleStringProperty();
        private final StringProperty buttonTextProperty = new SimpleStringProperty();
        private final StringProperty loginResultMessage = new SimpleStringProperty();
        private final ObjectProperty<AuthenticationStatus> authenticationStatus = new SimpleObjectProperty<>();

        public ServiceItem(ServiceAuthenticationProvider serviceAuthenticationProvider,
                           AuthenticationStatus authenticationResult, String username, String password) {
            setupChangeListeners();
            this.serviceAuthenticationProvider = serviceAuthenticationProvider;
            this.username.set(username);
            this.password.set(password);
            this.authenticationStatus.set(authenticationResult);
        }

        private void setupChangeListeners() {
            this.authenticationStatus.addListener((obs, o, n) -> {
                switch (n) {
                    case UNDETERMINED -> {
                        loginResultMessage.set(null);
                        buttonTextProperty.set(Messages.LoginButtonText);
                    }
                    case CACHED -> {
                        loginResultMessage.set(null);
                        buttonTextProperty.set(Messages.LogoutButtonText);
                    }
                    case AUTHENTICATED -> {
                        loginResultMessage.set("OK");
                        buttonTextProperty.set(Messages.LogoutButtonText);
                    }
                    case BAD_CREDENTIALS -> {
                        loginResultMessage.set(Messages.UserNotAuthenticated);
                        buttonTextProperty.set(Messages.LoginButtonText);
                    }
                    case SERVICE_OFFLINE -> {
                        loginResultMessage.set(Messages.ServiceConnectionFailure);
                        buttonTextProperty.set(Messages.LoginButtonText);
                    }
                    case UNKNOWN_ERROR -> {
                        loginResultMessage.set(Messages.UnknownError);
                        buttonTextProperty.set(Messages.LoginButtonText);
                    }
                }
            });
        }

        @SuppressWarnings("unused")
        public StringProperty getLoginResultMessage() {
            return loginResultMessage;
        }

        public AuthenticationScope getAuthenticationScope() {
            return serviceAuthenticationProvider != null ?
                    serviceAuthenticationProvider.getAuthenticationScope() : null;
        }

        @SuppressWarnings("unused")
        public String getScope() {
            return serviceAuthenticationProvider != null ?
                    serviceAuthenticationProvider.getAuthenticationScope().getScope() : "";
        }

        @SuppressWarnings("unused")
        public String getDisplayName() {
            return serviceAuthenticationProvider != null ?
                    serviceAuthenticationProvider.getAuthenticationScope().getDisplayName() : "";
        }

        @SuppressWarnings("unused")
        public StringProperty getUsername() {
            return username;
        }

        @SuppressWarnings("unused")
        public StringProperty getPassword() {
            return password;
        }

        public void logout() {
            serviceAuthenticationProvider.logout();
            authenticationStatus.set(AuthenticationStatus.UNDETERMINED);
            username.set(null);
            password.set(null);
        }

        public AuthenticationStatus login() {
            try {
                AuthenticationStatus result = serviceAuthenticationProvider.authenticate(username.get(), password.get());
                if (result.equals(AuthenticationStatus.AUTHENTICATED)) {
                    try {
                        secureStore.setScopedAuthentication(new ScopedAuthenticationToken(getAuthenticationScope(),
                                username.get(), password.get()));
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to store user credentials");
                    }
                }
                this.authenticationStatus.set(result);
                return result;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Login failed for " + getDisplayName(), e);
                this.authenticationStatus.set(AuthenticationStatus.SERVICE_OFFLINE);
                return AuthenticationStatus.SERVICE_OFFLINE;
            }
        }
    }

    private class UsernameTableCell extends TableCell<ServiceItem, StringProperty> {
        @Override
        public void updateItem(StringProperty item, final boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
            } else {
                ServiceItem serviceItem = getTableRow().getItem();
                TextField textField = new TextField();
                textField.getStyleClass().add("text-field-styling");
                textField.textProperty().bindBidirectional(serviceItem.username);
                textField.disableProperty().bind(Bindings.createBooleanBinding(() ->
                                serviceItem.authenticationStatus.get().equals(AuthenticationStatus.AUTHENTICATED),
                        serviceItem.authenticationStatus));
                textField.setOnKeyPressed(keyEvent -> {
                    if (keyEvent.getCode() == KeyCode.ENTER &&
                            !serviceItem.username.isNull().get() &&
                            !serviceItem.username.get().isEmpty() &&
                            !serviceItem.password.isNull().get() &&
                            !serviceItem.password.get().isEmpty()) {
                        CredentialsManagementController.this.login(serviceItem, 1);
                    }
                });
                setGraphic(textField);
            }
        }
    }

    private class PasswordTableCell extends TableCell<ServiceItem, StringProperty> {
        @Override
        protected void updateItem(StringProperty item, final boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
            } else {
                PasswordField passwordField = new PasswordField();
                passwordField.getStyleClass().add("text-field-styling");
                ServiceItem serviceItem = getTableRow().getItem();
                passwordField.textProperty().bindBidirectional(serviceItem.password);
                passwordField.disableProperty().bind(Bindings.createBooleanBinding(() ->
                                serviceItem.authenticationStatus.get().equals(AuthenticationStatus.AUTHENTICATED) ||
                                        serviceItem.authenticationStatus.get().equals(AuthenticationStatus.CACHED),
                        serviceItem.authenticationStatus));
                serviceItem.password.set(
                        serviceItem.authenticationStatus.get().equals(AuthenticationStatus.AUTHENTICATED) ||
                                serviceItem.authenticationStatus.get().equals(AuthenticationStatus.CACHED)
                                ? "dummypass" : null);

                passwordField.setOnKeyPressed(keyEvent -> {
                    if (keyEvent.getCode() == KeyCode.ENTER &&
                            !serviceItem.username.isNull().get() &&
                            !serviceItem.username.get().isEmpty() &&
                            !serviceItem.password.isNull().get() &&
                            !serviceItem.password.get().isEmpty()) {
                        CredentialsManagementController.this.login(serviceItem, 1);
                    }
                });
                setGraphic(passwordField);
            }
        }
    }

    private static class LoginResultTableCell extends TableCell<ServiceItem, StringProperty> {
        @Override
        protected void updateItem(StringProperty item, final boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
            } else {
                ServiceItem serviceItem = getTableRow().getItem();
                Label label = new Label();
                label.textProperty().bind(serviceItem.loginResultMessage);
                serviceItem.authenticationStatus.addListener((obs, o, n) -> {
                    switch (n) {
                        case CACHED, AUTHENTICATED -> label.getStyleClass().remove("error");
                        default -> label.getStyleClass().add("error");
                    }
                    label.setTooltip(new Tooltip(serviceItem.loginResultMessage.get()));
                });
                setGraphic(label);
            }
        }
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public class OAuth2Browser {

        public static void openInNewStage(String authUrl, String redirectUri,
                                          SimpleBooleanProperty oauthLoggedIn, Stage parentStage) {
            WebView webView = new WebView();
            WebEngine webEngine = webView.getEngine();

            Stage stage = new Stage();
            BorderPane root = new BorderPane(webView);
            stage.setTitle("OAuth2 Login");
            stage.setScene(new Scene(root, 900, 600));

            webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                LOGGER.info("WebEngine state: " + newState + " url: " + webEngine.getLocation());
            });

            webEngine.getLoadWorker().exceptionProperty().addListener((obs, oldEx, newEx) -> {
                if (newEx != null) {
                    LOGGER.log(Level.SEVERE, "WebEngine load error", newEx);
                }
            });

            webEngine.setOnError(event ->
                    LOGGER.log(Level.SEVERE, "WebEngine error: " + event.getMessage()));

            webEngine.locationProperty().addListener((obs, oldLocation, newLocation) -> {
                LOGGER.info("WebEngine navigating to: " + newLocation);
                if (newLocation != null && newLocation.startsWith(redirectUri)) {
                    try {
                        java.net.URI uri = new java.net.URI(newLocation);
                        String query = uri.getQuery();
                        if (query != null) {
                            String authCode = null;
                            for (String param : query.split("&")) {
                                if (param.startsWith("code=")) {
                                    authCode = java.net.URLDecoder.decode(param.substring(5), "UTF-8");
                                    break;
                                }
                            }
                            if (authCode != null) {
                                LOGGER.info("Got authorization code, exchanging for token...");
                                var tokenHelper = org.phoebus.security.authentication.oauth2
                                        .Oauth2HttpApplicationServer.getInstance();
                                if (tokenHelper != null) {
                                    net.minidev.json.JSONObject tokenResponse = tokenHelper.getToken(authCode);
                                    SecureStore store = new SecureStore();
                                    store.set(SecureStore.JWT_TOKEN_TAG, tokenResponse.getAsString("access_token"));
                                    store.set(SecureStore.JWT_ID_TOKEN, tokenResponse.getAsString("id_token"));
                                    LOGGER.info("OAuth2 token stored successfully");
                                } else {
                                    LOGGER.warning("Oauth2HttpApplicationServer not available, trying direct exchange");
                                    exchangeTokenDirectly(authCode, redirectUri);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to exchange authorization code for token", e);
                    }

                    Platform.runLater(() -> {
                        oauthLoggedIn.set(true);
                        stage.close();
                        // Don't close parentStage — let the user see the updated OAuth2 status
                    });
                }
            });

            try {
                if (authUrl == null || authUrl.isBlank()) authUrl = "about:blank";
                LOGGER.info("Loading OAuth2 URL: " + authUrl);
                webEngine.load(authUrl);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to load OAuth2 URL", e);
                webEngine.loadContent("<h2 style=\'color:red;\'>Error loading page</h2>"
                        + "<p>URL: " + authUrl + "</p><p>" + e.getMessage() + "</p>");
            }

            stage.show();
        }

        private static void exchangeTokenDirectly(String authCode, String redirectUri) throws Exception {
            String tokenUrl = PhoebusSecurity.oauth2_auth_url + "/realms/" + PhoebusSecurity.oauth2_realm
                    + "/protocol/openid-connect/token";
            String params = "grant_type=authorization_code"
                    + "&code=" + authCode
                    + "&scope=openid"
                    + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8")
                    + "&client_id=" + PhoebusSecurity.oauth2_client_id;

            java.net.URL url = new java.net.URL(tokenUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                net.minidev.json.JSONObject json = (net.minidev.json.JSONObject) net.minidev.json.JSONValue.parse(
                        new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                SecureStore store = new SecureStore();
                store.set(SecureStore.JWT_TOKEN_TAG, json.getAsString("access_token"));
                store.set(SecureStore.JWT_ID_TOKEN, json.getAsString("id_token"));
                LOGGER.info("OAuth2 token stored successfully (direct exchange)");
            } else {
                throw new Exception("Token exchange failed with HTTP " + conn.getResponseCode());
            }
        }
    }
}
