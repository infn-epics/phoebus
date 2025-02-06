package org.phoebus.application.oauth2;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpApplicationServer {

    private static volatile HttpApplicationServer instance = null;


    private final HttpServer server;

    private final Integer port;


    /** Create the application server instance
     *  @param port TCP port where server will serve resp. where this client will connect to server
     *  @return ApplicationServer
     *  @throws Exception on error
     */
    public static HttpApplicationServer create(final int port) throws Exception
    {
        if (instance != null)
            throw new IllegalStateException("Must create at most once");
        instance = new HttpApplicationServer(port);
        return instance;
    }


    private HttpApplicationServer(final int port) throws Exception
    {
        this.port = port;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/oauth2Callback", samlAssertionExchange());
    }

    public HttpHandler samlAssertionExchange() {
        return exchange -> {
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
            System.out.println("Access Token: " + accessToken);
        };
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


    public static String getAccessToken(String authCode) throws IOException {
        String tokenUrl = "https://idp-test.app.infn.it/auth/realms/aai/protocol/openid-connect/token";
        String params = "grant_type=authorization_code"
                + "&code=" + authCode
                + "&redirect_uri=http://localhost:8080/callback"
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
//            JSONObject json = (JSONObject) JSONValue.parse(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
//            return json.getAsString("access_token");
             return "OK";
//            JsonReader jsonReader = Json.createReader(conn.getInputStream());
//            JsonObject object = jsonReader.readObject();
//            jsonReader.close();
//            return object.getString("access_token");
//            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } else {
            throw new IOException("Failed to fetch token: " + conn.getResponseCode());
        }
    }


}