/*
 * Copyright (C) 2025 INFN - Laboratori Nazionali di Frascati.
 */
package org.phoebus.applications.utility.logbook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.phoebus.logbook.LogTemplate;
import org.phoebus.logbook.Logbook;
import org.phoebus.logbook.Property;
import org.phoebus.logbook.Tag;
import org.phoebus.logbook.olog.ui.LogbookUIPreferences;
import org.phoebus.olog.es.api.Preferences;
import org.phoebus.olog.es.api.model.OlogLogbook;
import org.phoebus.olog.es.api.model.OlogObjectMappers;
import org.phoebus.olog.es.api.model.OlogProperty;
import org.phoebus.olog.es.api.model.OlogTag;
import org.phoebus.security.store.SecureStore;
import org.phoebus.security.tokens.ScopedAuthenticationToken;
import org.phoebus.applications.logbook.authentication.OlogAuthenticationScope;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST client for Olog administrative CRUD operations on
 * Templates, Logbooks, Tags, and Properties.
 * <p>
 * Read operations are unauthenticated; write operations use
 * the same authentication mechanism as OlogHttpClient (Basic or Bearer JWT).
 */
public class OlogAdminClient {

    private static final Logger LOGGER = Logger.getLogger(OlogAdminClient.class.getName());
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final ObjectMapper OBJECT_MAPPER;

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private final HttpClient httpClient;
    private volatile String authHeader;

    public OlogAdminClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newCachedThreadPool())
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.ALWAYS);

        if (Preferences.connectTimeout > 0) {
            builder.connectTimeout(Duration.ofMillis(Preferences.connectTimeout));
        }
        httpClient = builder.build();

        // Try to obtain credentials from the secure store
        resolveAuth();
    }

    /**
     * Resolve authentication from the SecureStore.
     * Checks for OAuth2 JWT token first, then falls back to Basic auth credentials.
     * Called on construction and can be called again to refresh.
     */
    public void resolveAuth() {
        try {
            SecureStore secureStore = new SecureStore();

            // First try OAuth2 JWT token (stored separately from scoped credentials)
            if (LogbookUIPreferences.oauth2_auth_olog_enabled) {
                String jwtToken = secureStore.get(SecureStore.JWT_TOKEN_TAG);
                if (jwtToken != null && !jwtToken.isEmpty()) {
                    this.authHeader = "Bearer " + jwtToken;
                    LOGGER.log(Level.FINE, "Using OAuth2 JWT token for Olog authentication");
                    return;
                }
            }

            // Fall back to scoped credentials (Basic auth)
            ScopedAuthenticationToken token = secureStore.getScopedAuthenticationToken(new OlogAuthenticationScope());
            if (token != null && token.getUsername() != null && token.getPassword() != null) {
                this.authHeader = "Basic " + Base64.getEncoder()
                        .encodeToString((token.getUsername() + ":" + token.getPassword()).getBytes(StandardCharsets.UTF_8));
                LOGGER.log(Level.FINE, "Using Basic auth for Olog authentication");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to resolve Olog credentials", e);
        }
    }

    /**
     * Set authentication from a JWT token directly.
     */
    public void setJwtToken(String jwtToken) {
        if (jwtToken != null && !jwtToken.isEmpty()) {
            this.authHeader = "Bearer " + jwtToken;
        }
    }

    /**
     * Check if authentication credentials are available.
     */
    public boolean isAuthenticated() {
        return authHeader != null && !authHeader.isEmpty();
    }

    private String baseUrl() {
        return Preferences.olog_url;
    }

    // ========== LOGBOOKS ==========

    /**
     * List all logbooks.
     */
    public Collection<Logbook> listLogbooks() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/logbooks"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "list logbooks");
        return OlogObjectMappers.logEntryDeserializer.readValue(
                response.body(), new TypeReference<List<Logbook>>() {});
    }

    /**
     * Create or update a logbook.
     */
    public Logbook createLogbook(OlogLogbook logbook) throws Exception {
        resolveAuth();
        ensureAuth("create logbook");
        String json = OBJECT_MAPPER.writeValueAsString(logbook);
        LOGGER.log(Level.INFO, "PUT /logbooks/{0}  auth={1}", new Object[]{logbook.getName(), authType()});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/logbooks/" + encode(logbook.getName())))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "create logbook");
        return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogLogbook.class);
    }

    /**
     * Delete a logbook by name.
     */
    public void deleteLogbook(String name) throws Exception {
        resolveAuth();
        ensureAuth("delete logbook");
        LOGGER.log(Level.INFO, "DELETE /logbooks/{0}  auth={1}", new Object[]{name, authType()});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/logbooks/" + encode(name)))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "delete logbook '" + name + "'");
    }

    // ========== TAGS ==========

    /**
     * List all tags.
     */
    public Collection<Tag> listTags() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/tags"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "list tags");
        return OlogObjectMappers.logEntryDeserializer.readValue(
                response.body(), new TypeReference<List<Tag>>() {});
    }

    /**
     * Create or update a tag.
     */
    public Tag createTag(OlogTag tag) throws Exception {
        resolveAuth();
        ensureAuth("create tag");
        String json = OBJECT_MAPPER.writeValueAsString(tag);
        LOGGER.log(Level.INFO, "PUT /tags/{0}  auth={1}  body={2}", new Object[]{tag.getName(), authType(), json});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/tags/" + encode(tag.getName())))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "create tag");
        return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogTag.class);
    }

    /**
     * Delete a tag by name.
     */
    public void deleteTag(String name) throws Exception {
        resolveAuth();
        ensureAuth("delete tag");
        LOGGER.log(Level.INFO, "DELETE /tags/{0}  auth={1}", new Object[]{name, authType()});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/tags/" + encode(name)))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "delete tag '" + name + "'");
    }

    // ========== TEMPLATES ==========

    /**
     * List all templates.
     */
    public Collection<LogTemplate> listTemplates() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/templates"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "list templates");
        return OlogObjectMappers.logEntryDeserializer.readValue(
                response.body(), new TypeReference<List<LogTemplate>>() {});
    }

    /**
     * Create a new template (PUT /templates).
     */
    public LogTemplate createTemplate(LogTemplate template) throws Exception {
        resolveAuth();
        ensureAuth("create template");
        String json = OlogObjectMappers.logEntrySerializer.writeValueAsString(template);
        LOGGER.log(Level.INFO, "PUT /templates  auth={0}", authType());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/templates"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "create template");
        return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), LogTemplate.class);
    }

    /**
     * Update an existing template (POST /templates/{id}).
     */
    public LogTemplate updateTemplate(LogTemplate template) throws Exception {
        if (template.id() == null || template.id().isEmpty()) {
            throw new IllegalArgumentException("Template id is required for update");
        }
        resolveAuth();
        ensureAuth("update template");
        String json = OlogObjectMappers.logEntrySerializer.writeValueAsString(template);
        LOGGER.log(Level.INFO, "POST /templates/{0}  auth={1}", new Object[]{template.id(), authType()});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/templates/" + encode(template.id())))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "update template");
        return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), LogTemplate.class);
    }

    /**
     * Delete a template by id.
     */
    public void deleteTemplate(String templateId) throws Exception {
        resolveAuth();
        ensureAuth("delete template");
        LOGGER.log(Level.INFO, "DELETE /templates/{0}  auth={1}", new Object[]{templateId, authType()});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/templates/" + encode(templateId)))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "delete template '" + templateId + "'");
    }

    // ========== PROPERTIES ==========

    /**
     * List all properties.
     */
    public Collection<Property> listProperties() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/properties"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "list properties");
        return OlogObjectMappers.logEntryDeserializer.readValue(
                response.body(), new TypeReference<List<Property>>() {});
    }

    /**
     * Create or update a property.
     */
    public Property createProperty(OlogProperty property) throws Exception {
        resolveAuth();
        ensureAuth("create property");
        String json = OBJECT_MAPPER.writeValueAsString(property);
        LOGGER.log(Level.INFO, "PUT /properties/{0}  auth={1}", new Object[]{property.getName(), authType()});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/properties/" + encode(property.getName())))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "create property");
        return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogProperty.class);
    }

    /**
     * Create a property with the correct server-side JSON format.
     * The Olog server expects attributes as: [{"name":"id","state":"Active"}, ...]
     * rather than a Map<String,String>.
     */
    public Property createPropertyWithAttributes(String name, String owner, List<String> attributeNames) throws Exception {
        resolveAuth();
        ensureAuth("create property");

        // Build JSON in the exact format the Olog server expects
        StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"").append(escapeJsonString(name)).append("\"");
        json.append(",\"owner\":\"").append(escapeJsonString(owner)).append("\"");
        json.append(",\"state\":\"Active\"");
        json.append(",\"attributes\":[");
        if (attributeNames != null) {
            for (int i = 0; i < attributeNames.size(); i++) {
                if (i > 0) json.append(",");
                json.append("{\"name\":\"").append(escapeJsonString(attributeNames.get(i))).append("\",\"state\":\"Active\"}");
            }
        }
        json.append("]}");

        LOGGER.log(Level.INFO, "PUT /properties/{0}  auth={1}  body={2}",
                new Object[]{name, authType(), json});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/properties/" + encode(name)))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "create property '" + name + "'");
        return OlogObjectMappers.logEntryDeserializer.readValue(response.body(), OlogProperty.class);
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Delete a property by name.
     */
    public void deleteProperty(String name) throws Exception {
        resolveAuth();
        ensureAuth("delete property");
        LOGGER.log(Level.INFO, "DELETE /properties/{0}  auth={1}", new Object[]{name, authType()});
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/properties/" + encode(name)))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "delete property '" + name + "'");
    }

    // ========== LOG ENTRIES SEARCH ==========

    /**
     * Search log entries with the given query parameters.
     * Parameters are appended as query string: logbooks, tags, start, end, keyword, etc.
     *
     * @param params Map of search parameters (e.g., "logbooks" -> "ops", "keyword" -> "test")
     * @return Raw JSON string of the search result
     */
    public String searchLogEntries(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder(baseUrl()).append("/logs/search");
        if (params != null && !params.isEmpty()) {
            sb.append("?");
            sb.append(params.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(java.util.stream.Collectors.joining("&")));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sb.toString()))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "search log entries");
        return response.body();
    }

    // ========== UTILITY ==========

    private void checkResponse(HttpResponse<String> response, String operation) throws Exception {
        if (response.statusCode() >= 300) {
            String body = response.body() != null ? response.body() : "";
            LOGGER.log(Level.WARNING, "HTTP {0} on {1}: {2}", new Object[]{response.statusCode(), operation, body});
            throw new Exception("Failed to " + operation + " (HTTP " + response.statusCode() + "): " + body);
        }
    }

    /**
     * Throws if no authentication is available.
     */
    private void ensureAuth(String operation) throws Exception {
        if (authHeader == null || authHeader.isEmpty()) {
            throw new Exception("No authentication credentials available for: " + operation
                    + ". Please log in via Credentials Management first.");
        }
    }

    /**
     * Returns a short label describing the current auth type (for logging).
     */
    private String authType() {
        if (authHeader == null) return "NONE";
        if (authHeader.startsWith("Bearer ")) return "Bearer(JWT)";
        if (authHeader.startsWith("Basic ")) return "Basic";
        return "Unknown";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Get the ObjectMapper for JSON import/export.
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
}
