package org.phoebus.security.authentication.oauth2;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.phoebus.security.PhoebusSecurity;
import org.phoebus.security.store.SecureStore;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;



public class Oauth2HttpApplicationServer {

    private static final java.util.logging.Logger LOGGER = Logger.getLogger(Oauth2HttpApplicationServer.class.getName());
    private static volatile Oauth2HttpApplicationServer instance = null;

    private final HttpServer server;

    private final AtomicBoolean codeProcessed = new AtomicBoolean(false);


    /** Create the application server instance
     *  @return ApplicationServer
     *  @throws Exception on error
     */
    public static Oauth2HttpApplicationServer create() throws Exception
    {
        if (instance != null)
            return instance;
        instance = new Oauth2HttpApplicationServer();
        return instance;
    }

    /** Get the existing instance, or null if not created yet */
    public static Oauth2HttpApplicationServer getInstance() {
        return instance;
    }


    private Oauth2HttpApplicationServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(PhoebusSecurity.oauth2_callback_server_port), 0);
        server.createContext(PhoebusSecurity.oauth2_callback, exchange -> {
            String query = exchange.getRequestURI().getQuery();

            // Ignora richieste senza query (es. favicon)
            if (query == null || query.isEmpty()) {
                sendResponse(exchange, 200, "");
                return;
            }

            // Evita di processare il codice due volte
            if (!codeProcessed.compareAndSet(false, true)) {
                sendResponse(exchange, 200, "Already processed");
                return;
            }


            Map<String, String> params = parseQuery(query);

            // Controlla se Keycloak ha restituito un errore
            String error = params.get("error");
            if (error != null) {
                String errorDescription = params.getOrDefault("error_description", "Unknown error");
                LOGGER.log(Level.SEVERE, "OAuth2 callback error: {0} - {1}", new Object[]{error, errorDescription});
                sendResponse(exchange, 400, "Login failed: " + errorDescription);
                showErrorDialog("Authentication Error", errorDescription);
                return;
            }

            String authCode = params.get("code");
            if (authCode == null || authCode.isEmpty()) {
                LOGGER.log(Level.SEVERE, "OAuth2 callback: missing authorization code");
                sendResponse(exchange, 400, "Login failed: missing authorization code");
                showErrorDialog("Authentication Error", "Missing authorization code");
                return;
            }

            LOGGER.log(Level.INFO, "Authorization code received");

            // Rispondi subito al browser
            sendResponse(exchange, 200, "Login successful! You can close this window.");

            // Scambia il codice con un access token
//            try {
//                JSONObject tokenResponse = getToken(authCode);
//
//                String accessToken = tokenResponse.getAsString("access_token");
//                String idToken = tokenResponse.getAsString("id_token");
//
//                if (accessToken == null || accessToken.isEmpty()) {
//                    throw new RuntimeException("Access token is missing from token response");
//                }
//
//                LOGGER.log(Level.INFO, "SecureStore target: {0}", PhoebusSecurity.secure_store_target);
//
//
//                SecureStore secureStore = new SecureStore();
//                secureStore.set(SecureStore.JWT_TOKEN_TAG, accessToken);
//                secureStore.set(SecureStore.JWT_ID_TOKEN, idToken);
//                LOGGER.log(Level.INFO, "Stored id_token: {0}", secureStore.get(SecureStore.JWT_ID_TOKEN));
//
//
//                LOGGER.log(Level.INFO, "OAuth2 login successful, tokens stored");
//
//                Platform.runLater(() -> {
//                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
//                    alert.setTitle("Authentication");
//                    alert.setHeaderText("Authentication successful");
//                    alert.showAndWait();
//                });
//
//            } catch (Exception e) {
//                LOGGER.log(Level.SEVERE, "Error storing access token", e);
//                showErrorDialog("Authentication Error", "Failed to obtain access token: " + e.getMessage());
//            }
        });

        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        LOGGER.log(Level.INFO, "OAuth2 callback server running on port {0}", PhoebusSecurity.oauth2_callback_server_port);
    }

    // Helper per inviare la risposta HTTP
    private void sendResponse(HttpExchange exchange, int statusCode, String message) {
        try {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send HTTP response", e);
        }
    }

    // Helper per mostrare un dialog di errore su JavaFX thread
    private void showErrorDialog(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }



    public static Map<String, String> parseQuery(String query) {
        Map<String, String> queryPairs = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                queryPairs.put(key, value);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return queryPairs;
    }


    public JSONObject getToken(String authCode) throws IOException {
        String tokenUrl = PhoebusSecurity.oauth2_auth_url +  "/realms/"+ PhoebusSecurity.oauth2_realm + "/protocol/openid-connect/token";
        String params = "grant_type=authorization_code"
                + "&code=" + authCode
                + "&scope=openid"
                + "&redirect_uri=http://localhost:"+ PhoebusSecurity.oauth2_callback_server_port + "/oauth2Callback"
                + "&client_id="
                + PhoebusSecurity.oauth2_client_id;

        URL url = new URL(tokenUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(params.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() == 200) {
            // Leggi la risposta e estrai il token
            JSONObject json = (JSONObject) JSONValue.parse(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            return json;
        } else {
            throw new IOException("Failed to fetch token: " + conn.getResponseCode());
        }
    }
}