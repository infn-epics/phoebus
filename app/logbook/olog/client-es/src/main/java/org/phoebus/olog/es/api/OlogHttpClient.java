/*
 * Copyright (C) 2024 European Spallation Source ERIC.
 */

package org.phoebus.olog.es.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.phoebus.logbook.Attachment;
import org.phoebus.logbook.LogClient;
import org.phoebus.logbook.LogEntry;
import org.phoebus.logbook.LogEntryChangeHandler;
import org.phoebus.logbook.LogEntryLevel;
import org.phoebus.logbook.LogTemplate;
import org.phoebus.logbook.Logbook;
import org.phoebus.logbook.LogbookException;
import org.phoebus.logbook.Messages;
import org.phoebus.logbook.Property;
import org.phoebus.logbook.SearchResult;
import org.phoebus.logbook.Tag;
import org.phoebus.olog.es.api.model.OlogLog;
import org.phoebus.olog.es.api.model.OlogObjectMappers;
import org.phoebus.olog.es.api.model.OlogSearchResult;
import org.phoebus.olog.es.authentication.LoginCredentials;
import org.phoebus.security.authorization.AuthenticationStatus;
import org.phoebus.security.store.SecureStore;
import org.phoebus.applications.logbook.authentication.OlogAuthenticationScope;
import org.phoebus.security.tokens.ScopedAuthenticationToken;
import org.phoebus.util.http.HttpRequestMultipartBody;
import org.phoebus.util.http.QueryParamsHelper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.Thread.sleep;

/**
 * Logbook client implementation using Java native APIs only. Implemented as singleton.
 */
public class OlogHttpClient implements LogClient {

    private final HttpClient httpClient;
    private static final ObjectMapper OBJECT_MAPPER;
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String OLOG_CLIENT_INFO_HEADER = "X-Olog-Client-Info";
    private static final String CLIENT_INFO =
            "CS Studio " + Messages.AppVersion + " on " + System.getProperty("os.name");

    private static final Logger LOGGER = Logger.getLogger(OlogHttpClient.class.getName());
    private final List<LogEntryChangeHandler> changeHandlers = new ArrayList<>();

    private String basicAuthenticationHeader;

    static {
        System.getProperties().setProperty("jdk.internal.httpclient.disableHostnameVerification",
                Boolean.toString(Preferences.permissive_hostname_verifier));
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // Set JVM-wide default SSLContext for trust-all if the olog URL is HTTPS.
        // This avoids passing a custom SSLContext per HttpClient.Builder, which can
        // cause "selector manager closed" errors in Java's HttpClient implementation.
        if (Preferences.olog_url != null && Preferences.olog_url.toLowerCase().startsWith("https")) {
            SSLContext sslContext = createTrustAllSslContext();
            if (sslContext != null) {
                try {
                    SSLContext.setDefault(sslContext);
                    LOGGER.log(Level.INFO, "Set JVM-wide trust-all SSLContext for HTTPS olog URL");
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to set default SSLContext", e);
                }
            }
        }
    }

    public static class Builder {
        private String username = null;
        private String password = null;
        private String jwtToken = null;

        private Builder() {

        }

        public Builder username(String userName) {
            this.username = userName;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder withBearerToken(String jwtToken) {
            this.jwtToken = jwtToken;
            return this;
        }

        public OlogHttpClient build() {
            if (this.jwtToken != null) {
                return new OlogHttpClient(this.jwtToken);
            }
            else if (this.username == null || this.password == null) {
                ScopedAuthenticationToken scopedAuthenticationToken = getCredentialsFromSecureStore();
                if (scopedAuthenticationToken != null) {
                    this.username = scopedAuthenticationToken.getUsername();
                    this.password = scopedAuthenticationToken.getPassword();
                }
            }
            return new OlogHttpClient(this.username, this.password);
        }

        private ScopedAuthenticationToken getCredentialsFromSecureStore() {
            try {
                SecureStore secureStore = new SecureStore();
                return secureStore.getScopedAuthenticationToken(new OlogAuthenticationScope());
            } catch (Exception e) {
                Logger.getLogger(OlogHttpClient.class.getName()).log(Level.WARNING, "Unable to instantiate SecureStore", e);
                return null;
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }


    private static SSLContext createTrustAllSslContext() {
        try {
            TrustManager trustAllManager = new X509ExtendedTrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllManager}, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create trust-all SSLContext", e);
            return null;
        }
    }

    private static HttpClient.Builder newHttpClientBuilder() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newCachedThreadPool())
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.ALWAYS);
        // SSLContext is set JVM-wide in the static initializer — no need to set per-builder.
        // This avoids HttpClient-internal selector manager issues with custom SSLContext.
        return builder;
    }

    private OlogHttpClient(String jwtToken) {
        HttpClient.Builder builder = newHttpClientBuilder();
        // Only set connectTimeout when a positive value is configured.
        // Omitting it gives HttpClient's default (no timeout).
        // Using Long.MAX_VALUE causes ArithmeticException (long overflow)
        // in HttpClient's internal deadline/selector manager computation.
        if (Preferences.connectTimeout > 0) {
            builder.connectTimeout(Duration.ofMillis(Preferences.connectTimeout));
        }
        httpClient = builder.build();

        this.basicAuthenticationHeader = "Bearer " + jwtToken;


        ServiceLoader<LogEntryChangeHandler> serviceLoader = ServiceLoader.load(LogEntryChangeHandler.class);
        serviceLoader.stream().forEach(p -> changeHandlers.add(p.get()));
    }

    /**
     * Disallow instantiation.
     */
    private OlogHttpClient(String userName, String password) {
        if(Preferences.connectTimeout > 0){
            httpClient = newHttpClientBuilder()
                    .connectTimeout(Duration.ofMillis(Preferences.connectTimeout))
                    .build();
        }
        else{
            httpClient = newHttpClientBuilder()
                    .build();
        }

        if (userName != null && password != null) {
            this.basicAuthenticationHeader = "Basic " + Base64.getEncoder().encodeToString((userName + ":" + password).getBytes());
        }

        ServiceLoader<LogEntryChangeHandler> serviceLoader = ServiceLoader.load(LogEntryChangeHandler.class);
        serviceLoader.stream().forEach(p -> changeHandlers.add(p.get()));
    }

    @Override
    public LogEntry set(LogEntry log) throws LogbookException {
        return save(log, null);
    }

    /**
     * Calls the back-end service to persist the log entry.
     *
     * @param log       The log entry to save.
     * @param inReplyTo If non-null, this save operation will treat the <code>log</code> parameter as a reply to
     *                  the log entry represented by <code>inReplyTo</code>.
     * @return The saved log entry.
     * @throws LogbookException E.g. due to invalid log entry data, or if attachment content type
     *                          cannot be determined.
     */
    private LogEntry save(LogEntry log, LogEntry inReplyTo) throws LogbookException {
        try {
            javax.ws.rs.core.MultivaluedMap<String, String> queryParams = new javax.ws.rs.core.MultivaluedHashMap<>();
            queryParams.putSingle("markup", "commonmark");
            if (inReplyTo != null) {
                queryParams.putSingle("inReplyTo", Long.toString(inReplyTo.getId()));
            }

            HttpRequestMultipartBody httpRequestMultipartBody = new HttpRequestMultipartBody();
            httpRequestMultipartBody.addTextPart("logEntry", OlogObjectMappers.logEntrySerializer.writeValueAsString(log), "application/json");

            for (Attachment attachment : log.getAttachments()) {
                httpRequestMultipartBody.addFilePart(attachment.getFile());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Preferences.olog_url + "/logs/multipart?" + QueryParamsHelper.mapToQueryParams(queryParams)))
                    .header("Content-Type", httpRequestMultipartBody.getContentType())
                    .header(OLOG_CLIENT_INFO_HEADER, CLIENT_INFO)
                    .header("Authorization", basicAuthenticationHeader)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(httpRequestMultipartBody.getBytes()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                String body = response.body();
                String errorMsg = "HTTP " + response.statusCode()
                        + (body != null && !body.isBlank() ? ": " + body : " (no response body)");
                LOGGER.log(Level.SEVERE, "Failed to create log entry: " + errorMsg);
                throw new LogbookException(errorMsg);
            } else {
                return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogLog.class);
            }
        } catch (LogbookException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to submit log entry, got client exception", e);
            throw new LogbookException(e);
        }
    }

    @Override
    public LogEntry reply(LogEntry log, LogEntry inReplyTo) throws LogbookException {
        return save(log, inReplyTo);
    }

    /**
     * Returns a LogEntry that exactly matches the logId <code>logId</code>
     *
     * @param logId LogEntry id
     * @return LogEntry object
     */
    @Override
    public LogEntry getLog(Long logId) {
        return findLogById(logId);
    }

    @Override
    public LogEntry findLogById(Long logId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url + "/logs/" + logId))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogLog.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<LogEntry> findLogs(Map<String, String> map) {
        throw new RuntimeException(new UnsupportedOperationException());
    }

    /**
     * Calls service to retrieve log entries based on the search parameters
     *
     * @param searchParams Potentially empty map of multi-valued search parameters.
     * @return A {@link SearchResult} containing log entries matching search parameters.
     * @throws RuntimeException If error occurs, e.g. bad request due to unsupported or malformed search parameter(s).
     */
    private SearchResult findLogs(MultivaluedMap<String, String> searchParams) throws RuntimeException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url +
                        "/logs/search?" + QueryParamsHelper.mapToQueryParams(searchParams)))
                .header(OLOG_CLIENT_INFO_HEADER, CLIENT_INFO)
//                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            OlogSearchResult searchResult = OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogSearchResult.class);
            return SearchResult.of(new ArrayList<>(searchResult.getLogs()),
                    searchResult.getHitCount());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "failed to retrieve log entries", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<LogEntry> findLogsByLogbook(String logbookName) {
        throw new RuntimeException(new UnsupportedOperationException());
    }

    @Override
    public List<LogEntry> findLogsByProperty(String propertyName) {
        throw new RuntimeException(new UnsupportedOperationException());
    }

    @Override
    public List<LogEntry> findLogsBySearch(String pattern) {
        throw new RuntimeException(new UnsupportedOperationException());
    }

    @Override
    public List<LogEntry> findLogsByTag(String tagName) {
        throw new RuntimeException(new UnsupportedOperationException());
    }

    @Override
    public String getServiceUrl() {
        return Preferences.olog_url;
    }

    /**
     * Updates an existing {@link LogEntry}. Note that unlike the {@link #save(LogEntry, LogEntry)} API,
     * this does not support attachments, i.e. it does not set up a multipart request to the service.
     *
     * @param logEntry - the updated log entry
     * @return The updated {@link LogEntry}
     */
    @Override
    public LogEntry update(LogEntry logEntry) {

        try {
            String boundary = "----Boundary" + System.currentTimeMillis();

            String logEntryJson = OlogObjectMappers.logEntrySerializer.writeValueAsString(logEntry);

            String multipartBody =
                    "--" + boundary + "\r\n" +
                            "Content-Disposition: form-data; name=\"logEntry\"; filename=\"logEntry.json\"\r\n" +
                            "Content-Type: application/json\r\n\r\n" +
                            logEntryJson + "\r\n" +
                            "--" + boundary + "--";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Preferences.olog_url + "/logs/" + logEntry.getId() + "?markup=commonmark"))
                    .header("Authorization", basicAuthenticationHeader)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(multipartBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());


            LogEntry updated = OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogLog.class);
            changeHandlers.forEach(h -> h.logEntryChanged(updated));
            return updated;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to update log entry id=" + logEntry.getId(), e);
            return null;
        }
    }

    @Override
    public SearchResult search(Map<String, String> map) {
        MultivaluedMap<String, String> mMap = new MultivaluedHashMap<>();
        map.forEach(mMap::putSingle);
        return findLogs(mMap);
    }

    /**
     * Puts user selected log entries into a group.
     *
     * @param logEntryIds List of log entry ids
     * @throws LogbookException If operation fails, e.g. user unauthorized
     */
    @Override
    public void groupLogEntries(List<Long> logEntryIds) throws LogbookException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Preferences.olog_url + "/logs/group"))
                    .header("Content-Type", CONTENT_TYPE_JSON)
                    .header("Authorization", basicAuthenticationHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(OlogObjectMappers.logEntrySerializer.writeValueAsString(logEntryIds)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw new LogbookException("Failed to group log entries: user unauthorized");
            } else if (response.statusCode() != 200) {
                throw new LogbookException("Failed to group log entries: " + response.statusCode());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to group log entries", e);
            throw new LogbookException(e);
        }
    }

    /**
     * Logs in to the Olog service.
     *
     * @param userName Username, must not be <code>null</code>.
     * @param password Password, must not be <code>null</code>.
     * @throws Exception if the login fails, e.g. bad credentials or service off-line.
     */
    public AuthenticationStatus authenticate(String userName, String password) {

        String loginUrl = Preferences.olog_url +
                "/login";
        LOGGER.log(Level.INFO, "Authenticating user '" + userName + "' against " + loginUrl);
        try {
            String jsonBody = OBJECT_MAPPER.writeValueAsString(new LoginCredentials(userName, password));
            LOGGER.log(Level.FINE, "Login request body: " + jsonBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(loginUrl))
                    .header("Content-Type", CONTENT_TYPE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOGGER.log(Level.INFO, "Login response from " + loginUrl + ": status=" + response.statusCode() + ", body=" + response.body());
            if (response.statusCode() == 200) {
                return AuthenticationStatus.AUTHENTICATED;
            } else if (response.statusCode() == 401) {
                return AuthenticationStatus.BAD_CREDENTIALS;
            } else {
                return AuthenticationStatus.UNKNOWN_ERROR;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to authenticate against " + loginUrl, e);
            return AuthenticationStatus.SERVICE_OFFLINE;
        }
    }

    /**
     * @return A JSON string containing server side information, e.g. version, Elasticsearch status etc.
     */
    @Override
    public String serviceInfo() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "failed to obtain service info", e);
            return "";
        }
    }

    @Override
    public Collection<Attachment> listAttachments(Long logId) {
        return getLog(logId).getAttachments();
    }


    @Override
    public Collection<LogEntry> listLogs() {
        return List.of();
    }


    @Override
    public Collection<Logbook> listLogbooks() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url + "/logbooks"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), new TypeReference<List<Logbook>>() {
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to get logbooks from service", e);
            return Collections.emptySet();
        }
    }

    @Override
    public Collection<Tag> listTags() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url + "/tags"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), new TypeReference<List<Tag>>() {
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "failed to retrieve logbook tags", e);
            return Collections.emptySet();
        }
    }

    @Override
    public Collection<Property> listProperties() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url + "/properties"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), new TypeReference<List<Property>>() {
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "failed to retrieve logbook properties", e);
            return Collections.emptySet();
        }
    }


    @Override
    public InputStream getAttachment(Long logId, String attachmentName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Preferences.olog_url + "/logs/attachments/" + logId + "/" +
                            URLEncoder.encode(attachmentName, StandardCharsets.UTF_8).replace("+", "%20"))) // + char does not work in path element!
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode() >= 300) {
                LOGGER.log(Level.WARNING, "failed to obtain attachment: " + new String(response.body().readAllBytes()));
                return null;
            }
            return response.body();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "failed to obtain attachment", e);
            return null;
        }
    }

    /**
     * @param id Unique log entry id
     * @return A {@link SearchResult} containing a list of {@link LogEntry} objects representing the
     * history, i.e. previous edits, if any.
     */
    @Override
    public SearchResult getArchivedEntries(long id) {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url +
                        "/logs/archived/" + id))
                .header(OLOG_CLIENT_INFO_HEADER, CLIENT_INFO)
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            OlogSearchResult ologSearchResult = OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogSearchResult.class);
            return SearchResult.of(new ArrayList<>(ologSearchResult.getLogs()),
                    ologSearchResult.getHitCount());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "failed to retrieve archived log entries", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Collection<LogTemplate> getTemplates() {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url + "/templates"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header(OLOG_CLIENT_INFO_HEADER, CLIENT_INFO)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return OlogObjectMappers.logEntryDeserializer.readValue(
                    response.body(), new TypeReference<List<LogTemplate>>() {
                    });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to get templates from service", e);
            return Collections.emptySet();
        }
    }

    /**
     * @param template A new {@link LogTemplate}
     * @return The persisted {@link LogTemplate}
     * @throws LogbookException if operation fails
     */
    @Override
    public LogTemplate saveTemplate(LogTemplate template) throws LogbookException {

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Preferences.olog_url + "/templates"))
                    .header("Content-Type", CONTENT_TYPE_JSON)
                    .header("Authorization", basicAuthenticationHeader)
                    .PUT(HttpRequest.BodyPublishers.ofString(OlogObjectMappers.logEntrySerializer.writeValueAsString(template)))
                    .build();


            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() > 300) {
                LOGGER.log(Level.SEVERE, "Failed to create template: " + response.body());
                throw new LogbookException(response.body());
            }
            return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), LogTemplate.class);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to submit template, got client exception", e);
            throw new LogbookException(e);
        }
    }

    @Override
    public Collection<LogEntryLevel> listLevels(){
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Preferences.olog_url + "/levels"))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return OlogObjectMappers.logEntryDeserializer.readValue(
                    response.body(), new TypeReference<Set<LogEntryLevel>>() {
                    });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to get templates from service", e);
            return Collections.emptySet();
        }
    }
}
