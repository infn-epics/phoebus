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
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.security.PhoebusSecurity;
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

/**
 * JavaFX controller for the Credentials Management UI.
 */
public class CredentialsManagementController {

    @FXML
    private Node parent;

    @FXML
    private TableView<ServiceItem> tableView;
    @FXML
    private TableColumn<ServiceItem, Void> actionButtonColumn;
    @FXML
    private TableColumn<ServiceItem, String> usernameColumn;
    @FXML
    private TableColumn<ServiceItem, String> passwordColumn;
    @FXML
    private Button clearAllCredentialsButton;
    @FXML
    private Button loginWithOAuth2;

    @FXML
    private TableColumn scopeColumn;

    private final SimpleBooleanProperty listEmpty = new SimpleBooleanProperty(true);
    private final ObservableList<ServiceItem> serviceItems =
            FXCollections.observableArrayList();
    private final SecureStore secureStore;
    private static final Logger LOGGER = Logger.getLogger(CredentialsManagementController.class.getName());
    private final List<ServiceAuthenticationProvider> authenticationProviders;

    private Stage stage;

    private final SimpleBooleanProperty oauthLoggedIn = new SimpleBooleanProperty(false);

    public CredentialsManagementController(List<ServiceAuthenticationProvider> authenticationProviders, SecureStore secureStore) {
        this.authenticationProviders = authenticationProviders;
        this.secureStore = secureStore;
    }

    @FXML
    public void initialize() {

        if (PhoebusSecurity.enable_oauth2) {
            loginWithOAuth2.setVisible(true);
            loginWithOAuth2.textProperty().bind(
                    Bindings.when(oauthLoggedIn)
                            .then(Messages.LogoutOAuth2)      // testo quando autenticato
                            .otherwise(Messages.LoginWithOAuth2)  // testo quando non autenticato
            );
        } else {
            loginWithOAuth2.setVisible(false);
        }

        try {
            if (secureStore != null && secureStore.get(SecureStore.JWT_TOKEN_TAG) != null) {
                oauthLoggedIn.set(true);
            }

        } catch (Exception ex) {
            oauthLoggedIn.set(false);
        }

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        clearAllCredentialsButton.disableProperty().bind(listEmpty);
        Callback<TableColumn<ServiceItem, Void>, TableCell<ServiceItem, Void>> actionColumnCellFactory = new Callback<>() {
            @Override
            public TableCell<ServiceItem, Void> call(final TableColumn<ServiceItem, Void> param) {
                final TableCell<ServiceItem, Void> cell = new TableCell<>() {

                    private final Button btn = new Button(Messages.LogoutButtonText);
                    {
                        btn.getStyleClass().add("button-style");
                        btn.setOnAction((ActionEvent event) -> {
                            ServiceItem serviceItem = getTableView().getItems().get(getIndex());
                            if(serviceItem.isLoginAction()){
                                login(serviceItem);
                            }
                            else{
                                logOut(serviceItem.getAuthenticationScope());
                            }
                        });
                    }

                    @Override
                    public void updateItem(Void o, boolean empty) {
                        super.updateItem(o, empty);
                        if (empty) {
                            setGraphic(null);
                        } else {
                            if(getTableRow() != null && getTableRow().getItem() != null){
                                btn.setText(getTableRow().getItem().loginAction ?
                                        Messages.LoginButtonText : Messages.LogoutButtonText);
                            }
                            setGraphic(btn);
                        }
                    }
                };
                return cell;
            }
        };
        actionButtonColumn.setCellFactory(actionColumnCellFactory);
        usernameColumn.setCellFactory(c -> new UsernameTableCell());
        passwordColumn.setCellFactory(c -> new PasswordTableCell());

        updateTable();
    }

    @FXML
    public void logOutFromAll() {
        try {
            secureStore.deleteAllScopedAuthenticationTokens();
            oauthLoggedIn.set(false);
            updateTable();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to delete all authentication tokens from key store", e);
            ExceptionDetailsErrorDialog.openError(parent, Messages.ErrorDialogTitle, Messages.ErrorDialogBody, e);
        }
    }


    @FXML
    public void loginWithOAuth2() {

        try {
            if (oauthLoggedIn.get()) {
                // Must logout from OAUTH2
                String logoutUrl = PhoebusSecurity.oauth2_auth_url + "/realms/" + PhoebusSecurity.oauth2_realm + "/protocol/openid-connect/logout"
                        + "?id_token_hint=" + secureStore.get(SecureStore.JWT_ID_TOKEN)
                        + "&post_logout_redirect_uri=";

                URL url = new URL(logoutUrl);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setDoOutput(true);

                int responseCode = con.getResponseCode();
                System.out.println("Logout response code: " + responseCode);
                con.disconnect();
                oauthLoggedIn.set(false);
                secureStore.delete(SecureStore.JWT_ID_TOKEN);
                secureStore.delete(SecureStore.JWT_TOKEN_TAG);
            } else {
                String authUrl = PhoebusSecurity.oauth2_auth_url  + "/realms/"+ PhoebusSecurity.oauth2_realm + "/protocol/openid-connect/auth?response_type=code&client_id="+ PhoebusSecurity.oauth2_client_id+"&scope=openid%20email&redirect_uri=http://localhost:"+ PhoebusSecurity.oauth2_callback_server_port + PhoebusSecurity.oauth2_callback;
                try {

                    String redirectUri = "http://localhost:" + PhoebusSecurity.oauth2_callback_server_port + PhoebusSecurity.oauth2_callback;

                    // Usa il tuo BrowserWithToolbar
                    OAuth2Browser.openInNewStage(authUrl, redirectUri, oauthLoggedIn, stage);



//                if (Desktop.isDesktopSupported()) {
//
//
//
//
//                    Desktop.getDesktop().browse(new URI(authUrl));
//                } else {
//                    Platform.runLater(() -> {
//                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
//                        alert.setTitle("Desktop is not supported");
//                        alert.setHeaderText("Open this URL manually: " + authUrl);
//                        alert.showAndWait();
//                    });
//                }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Unable to open browser page", e);
                    ExceptionDetailsErrorDialog.openError(parent, Messages.ErrorDialogTitle, Messages.ErrorDialogBody, e);
                }
            }



        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to open browser page", e);
            ExceptionDetailsErrorDialog.openError(parent, Messages.ErrorDialogTitle, Messages.ErrorDialogBody, e);
        }
    }

    /**
     * Attempts to sign in user based on provided credentials. If sign-in succeeds, this method will close the
     * associated UI.
     * @param serviceItem The {@link ServiceItem} defining the scope, and implicitly the authentication service.
     */
    private void login(ServiceItem serviceItem){
        try {
            serviceItem.getServiceAuthenticationProvider().authenticate(serviceItem.getUsername(), serviceItem.getPassword());
            try {
                secureStore.setScopedAuthentication(new ScopedAuthenticationToken(serviceItem.getAuthenticationScope(),
                        serviceItem.getUsername(),
                        serviceItem.getPassword()));
                stage.close();
            } catch (Exception exception) {
                LOGGER.log(Level.WARNING, "Failed to store credentials", exception);
            }
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Failed to login to service", exception);
            ExceptionDetailsErrorDialog.openError(parent, "Login Failure", "Failed to login to service", exception);
        }
    }

    private void logOut(AuthenticationScope scope) {
        try {
            secureStore.deleteScopedAuthenticationToken(scope);
            updateTable();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to logout from scope " + scope, e);
            ExceptionDetailsErrorDialog.openError(parent, Messages.ErrorDialogTitle, Messages.ErrorDialogBody, e);
        }
    }

    private void updateTable() {
        JobManager.schedule("Get Credentials", monitor -> {
            List<ScopedAuthenticationToken> savedTokens = secureStore.getAuthenticationTokens();
            // Match saved tokens with an authentication provider, where applicable
            List<ServiceItem> serviceItems = savedTokens.stream().map(token -> {
                ServiceAuthenticationProvider provider =
                        authenticationProviders.stream().filter(p-> p.getAuthenticationScope().equals(token.getAuthenticationScope())).findFirst().orElse(null);
                return new ServiceItem(provider, token.getUsername(), token.getPassword());
            }).collect(Collectors.toList());
            // Also need to add ServiceItems for providers not matched with a saved token, i.e. for logged-out services
            authenticationProviders.forEach(p -> {
                Optional<ServiceItem> serviceItem =
                        serviceItems.stream().filter(si ->
                                p.getAuthenticationScope().equals(si.getAuthenticationScope())).findFirst();
                if(serviceItem.isEmpty()){
                    serviceItems.add(new ServiceItem(p));
                }
            });
            serviceItems.sort(Comparator.comparing(ServiceItem::getAuthenticationScope));
            Platform.runLater(() -> {
                this.serviceItems.setAll(serviceItems);
                listEmpty.set(savedTokens.isEmpty());
                tableView.setItems(this.serviceItems);
            });
        });
    }

    /**
     * Model class for the table view
     */
    public static class ServiceItem {
        private final ServiceAuthenticationProvider serviceAuthenticationProvider;
        private String username;
        private String password;
        private boolean loginAction = false;

        public ServiceItem(ServiceAuthenticationProvider serviceAuthenticationProvider, String username, String password) {
            this.serviceAuthenticationProvider = serviceAuthenticationProvider;
            this.username = username;
            this.password = password;
        }

        public ServiceItem(ServiceAuthenticationProvider serviceAuthenticationProvider) {
            this.serviceAuthenticationProvider = serviceAuthenticationProvider;
            loginAction = true;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username){
            this.username = username;
        }

        public AuthenticationScope getAuthenticationScope() {
            return serviceAuthenticationProvider != null ?
                    serviceAuthenticationProvider.getAuthenticationScope() : null;
        }

        /**
         * @return String representation of the authentication scope.
         */
        @SuppressWarnings("unused")
        public String getScope(){
            return serviceAuthenticationProvider != null ?
                    serviceAuthenticationProvider.getAuthenticationScope().getName() : "";
        }

        public String getPassword(){
            return password;
        }

        public void setPassword(String password){
            this.password = password;
        }

        public ServiceAuthenticationProvider getServiceAuthenticationProvider() {
            return serviceAuthenticationProvider;
        }

        public boolean isLoginAction(){
            return loginAction;
        }
    }
    private class UsernameTableCell extends TableCell<ServiceItem, String>{
        private final TextField textField = new TextField();

        public UsernameTableCell(){
            textField.getStyleClass().add("text-field-styling");
            // Update model on key up
            textField.setOnKeyReleased(ke -> getTableRow().getItem().setUsername(textField.getText()));
        }

        @Override
        protected void updateItem(String item, final boolean empty)
        {
            super.updateItem(item, empty);
            if(empty){
                setGraphic(null);
            }
            else{
                textField.setText(item);
                if(getTableRow() != null && getTableRow().getItem() != null){
                    // Disable field if user is logged in.
                    textField.disableProperty().set(!getTableRow().getItem().loginAction);
                }
                setGraphic(textField);
            }
        }
    }

    private class PasswordTableCell extends TableCell<ServiceItem, String>{
        private final PasswordField passwordField = new PasswordField();

        public PasswordTableCell(){
            passwordField.getStyleClass().add("text-field-styling");
            // Update model on key up
            passwordField.setOnKeyReleased(ke -> getTableRow().getItem().setPassword(passwordField.getText()));
        }

        @Override
        protected void updateItem(String item, final boolean empty)
        {
            super.updateItem(item, empty);
            if(empty){
                setGraphic(null);
            }
            else{
                passwordField.setText(item == null ? item : "dummypass"); // Hack to not reveal password length
                if(getTableRow() != null && getTableRow().getItem() != null) {
                    // Disable field if user is logged in.
                    passwordField.disableProperty().set(!getTableRow().getItem().loginAction);
                }
                passwordField.setOnKeyPressed(keyEvent -> {
                    if (keyEvent.getCode() == KeyCode.ENTER) {
                        CredentialsManagementController.this.login(getTableRow().getItem());
                    }
                });
                setGraphic(passwordField);
            }
        }
    }

    public void setStage(Stage stage){
        this.stage = stage;
    }

    public class OAuth2Browser {

        public static void openInNewStage(String authUrl, String redirectUri, SimpleBooleanProperty oauthLoggedIn, Stage parentStage) {
            WebView webView = new WebView();
            WebEngine webEngine = webView.getEngine();

            Stage stage = new Stage();
            BorderPane root = new BorderPane(webView);
            stage.setTitle("OAuth2 Login");
            stage.setScene(new Scene(root, 900, 600));

            // Listener su cambio URL
            webEngine.locationProperty().addListener((obs, oldLocation, newLocation) -> {
                if (newLocation.startsWith(redirectUri)) {
                    // Qui il login è completato!
                    // Puoi leggere il code dalla query string se vuoi
                    // String code = new URI(newLocation).getQuery();

                    Platform.runLater(() -> {
                        oauthLoggedIn.set(true);
                        stage.close();
                        parentStage.close();
                    }); // chiudi la finestra

                }
            });

            // Carica URL
            try {
                if (authUrl == null || authUrl.isBlank()) authUrl = "about:blank";
                webEngine.load(authUrl);
            } catch (Exception e) {
                String errorPage = "<h2 style='color:red;'>Errore caricamento pagina</h2>" +
                        "<p>URL: " + authUrl + "</p>" +
                        "<p>" + e.getMessage() + "</p>";
                webEngine.loadContent(errorPage);
            }

            stage.show();
        }
    }
}



