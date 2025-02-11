package org.phoebus.ui.application.oauth2;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.phoebus.framework.preferences.AnnotatedPreferences;
import org.phoebus.framework.preferences.Preference;
import org.phoebus.security.store.SecureStore;
import org.phoebus.security.tokens.AuthenticationScope;
import org.phoebus.security.tokens.ScopedAuthenticationToken;
import org.phoebus.ui.Preferences;
import org.phoebus.util.http.QueryParamsHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class Oauth2HttpApplicationServer {

    private static volatile Oauth2HttpApplicationServer instance = null;

    private final HttpServer server;

    /** Create the application server instance
     *  @return ApplicationServer
     *  @throws Exception on error
     */
    public static Oauth2HttpApplicationServer create() throws Exception
    {
        if (instance != null)
            throw new IllegalStateException("Must create at most once");
        instance = new Oauth2HttpApplicationServer();
        return instance;
    }


    private Oauth2HttpApplicationServer() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress(Preferences.oauth2_callback_server_port), 0);
        server.createContext("/oauth2Callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);

            String authCode = params.get("code");
            System.out.println("Authorization Code: " + authCode);

            String response = "Login successful! You can close this window.";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();

            // Scambia il codice con un access token
            String accessToken = getAccessToken(authCode);
            // Inserisci il token nella sessione
            try {
                SecureStore secureStore = new SecureStore();
                secureStore.set(SecureStore.JWT_TOKEN_TAG, accessToken);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Authentication");
                    alert.setHeaderText("Authentication successful");
                    alert.showAndWait();
                });
            }  catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Authentication");
                    alert.setHeaderText("Authentication Error");
                    alert.setContentText("Error:" + e.getMessage());
                    alert.showAndWait();
                });

            }

        });
        server.setExecutor(null); // Use the default executor
        server.start();

        System.out.println("Server is running on port " + Preferences.oauth2_callback_server_port);
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


    public String getAccessToken(String authCode) throws IOException {
        String tokenUrl = Preferences.oauth2_auth_url +  "/realms/"+ Preferences.oauth2_realm + "/protocol/openid-connect/token";
        String params = "grant_type=authorization_code"
                + "&code=" + authCode
                + "&scope=open_id"
                + "&redirect_uri=http://localhost:"+ Preferences.oauth2_callback_server_port + "/oauth2Callback"
                + "&client_id=camunda";

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
            return json.getAsString("access_token");
        } else {
            throw new IOException("Failed to fetch token: " + conn.getResponseCode());
        }
    }




}