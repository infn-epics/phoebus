/*
 * Copyright (C) 2025 INFN - Laboratori Nazionali di Frascati.
 */
package org.phoebus.applications.utility.logbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import org.phoebus.logbook.LogTemplate;
import org.phoebus.logbook.Logbook;
import org.phoebus.logbook.Property;
import org.phoebus.logbook.Tag;
import org.phoebus.logbook.olog.ui.HtmlAwareController;
import org.phoebus.olog.es.api.Preferences;
import org.phoebus.olog.es.api.model.OlogLogbook;
import org.phoebus.olog.es.api.model.OlogProperty;
import org.phoebus.olog.es.api.model.OlogTag;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Controller for the Logbook Template Manager main UI.
 * Provides CRUD operations for Olog Templates, Logbooks, and Tags
 * with JSON import/export and preview functionality.
 */
public class LogbookTemplateManagerController {

    private static final Logger LOGGER = Logger.getLogger(LogbookTemplateManagerController.class.getName());

    // ========== FXML Bindings ==========

    // Toolbar
    @FXML private Button refreshButton;
    @FXML private Button importButton;
    @FXML private Button exportButton;
    @FXML private Label statusLabel;
    @FXML private TabPane mainTabPane;

    // Templates tab
    @FXML private TableView<LogTemplate> templatesTable;
    @FXML private TableColumn<LogTemplate, String> templateNameCol;
    @FXML private TableColumn<LogTemplate, String> templateTitleCol;
    @FXML private TableColumn<LogTemplate, String> templateLevelCol;
    @FXML private TableColumn<LogTemplate, String> templateOwnerCol;
    @FXML private WebView templatePreviewWebView;

    // Logbooks tab
    @FXML private TableView<Logbook> logbooksTable;
    @FXML private TableColumn<Logbook, String> logbookNameCol;
    @FXML private TableColumn<Logbook, String> logbookOwnerCol;
    @FXML private TextField logbookNameField;
    @FXML private TextField logbookOwnerField;
    @FXML private TextArea logbookPreviewArea;

    // Tags tab
    @FXML private TableView<Tag> tagsTable;
    @FXML private TableColumn<Tag, String> tagNameCol;
    @FXML private TableColumn<Tag, String> tagStateCol;
    @FXML private TextField tagNameField;
    @FXML private TextArea tagPreviewArea;

    // Properties tab
    @FXML private TableView<Property> propertiesTable;
    @FXML private TableColumn<Property, String> propertyNameCol;
    @FXML private TableColumn<Property, String> propertyOwnerCol;
    @FXML private TableColumn<Property, String> propertyAttrsCol;
    @FXML private TextField propertyNameField;
    @FXML private TextField propertyOwnerField;
    @FXML private TextField propertyAttrsField;
    @FXML private TextArea propertyPreviewArea;

    // Data
    private final ObservableList<LogTemplate> templatesList = FXCollections.observableArrayList();
    private final ObservableList<Logbook> logbooksList = FXCollections.observableArrayList();
    private final ObservableList<Tag> tagsList = FXCollections.observableArrayList();
    private final ObservableList<Property> propertiesList = FXCollections.observableArrayList();

    private OlogAdminClient client;
    private HtmlAwareController htmlRenderer;
    private final ObjectMapper prettyMapper;

    public LogbookTemplateManagerController() {
        prettyMapper = OlogAdminClient.getObjectMapper().copy();
        prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @FXML
    public void initialize() {
        client = new OlogAdminClient();
        htmlRenderer = new HtmlAwareController(Preferences.olog_url);

        // Templates table columns
        templateNameCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().name()));
        templateTitleCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().title() != null ? cd.getValue().title() : ""));
        templateLevelCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().level() != null ? cd.getValue().level() : ""));
        templateOwnerCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().owner() != null ? cd.getValue().owner() : ""));
        templatesTable.setItems(templatesList);
        templatesTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> showTemplatePreview(sel));

        // Logbooks table columns
        logbookNameCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getName()));
        logbookOwnerCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getOwner() != null ? cd.getValue().getOwner() : ""));
        logbooksTable.setItems(logbooksList);
        logbooksTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> showLogbookPreview(sel));

        // Tags table columns
        tagNameCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getName()));
        tagStateCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getState() != null ? cd.getValue().getState() : ""));
        tagsTable.setItems(tagsList);
        tagsTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> showTagPreview(sel));

        // Properties table columns
        propertyNameCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getName()));
        propertyOwnerCol.setCellValueFactory(cd -> {
            // OlogProperty stores owner internally
            if (cd.getValue() instanceof OlogProperty op) {
                return new SimpleStringProperty(op.getOwner() != null ? op.getOwner() : "");
            }
            return new SimpleStringProperty("");
        });
        propertyAttrsCol.setCellValueFactory(cd -> {
            Map<String, String> attrs = cd.getValue().getAttributes();
            String text = attrs != null ? String.join(", ", attrs.keySet()) : "";
            return new SimpleStringProperty(text);
        });
        propertiesTable.setItems(propertiesList);
        propertiesTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> showPropertyPreview(sel));

        // Initial load
        onRefresh();
    }

    // ========== TOOLBAR ACTIONS ==========

    @FXML
    private void onRefresh() {
        setStatus("Loading...");
        CompletableFuture.runAsync(() -> {
            try {
                client.resolveAuth();
                Collection<LogTemplate> templates = client.listTemplates();
                Collection<Logbook> logbooks = client.listLogbooks();
                Collection<Tag> tags = client.listTags();
                Collection<Property> properties = client.listProperties();
                Platform.runLater(() -> {
                    templatesList.setAll(templates);
                    logbooksList.setAll(logbooks);
                    tagsList.setAll(tags);
                    propertiesList.setAll(properties);
                    setStatus("Loaded: " + templates.size() + " templates, "
                            + logbooks.size() + " logbooks, " + tags.size() + " tags, "
                            + properties.size() + " properties");
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to refresh data", e);
                Platform.runLater(() -> setStatus("Error: " + e.getMessage()));
            }
        });
    }

    @FXML
    private void onImportJson() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import JSON");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON files", "*.json"));
        File file = chooser.showOpenDialog(mainTabPane.getScene().getWindow());
        if (file == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                String json = Files.readString(file.toPath());
                ObjectMapper mapper = OlogAdminClient.getObjectMapper();

                // Determine which tab is active to know what to import
                int tabIndex = mainTabPane.getSelectionModel().getSelectedIndex();

                switch (tabIndex) {
                    case 0: // Templates
                        importTemplates(json, mapper);
                        break;
                    case 1: // Logbooks
                        importLogbooks(json, mapper);
                        break;
                    case 2: // Tags
                        importTags(json, mapper);
                        break;
                    case 3: // Properties
                        importProperties(json, mapper);
                        break;
                }

                Platform.runLater(this::onRefresh);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to import JSON", e);
                Platform.runLater(() -> {
                    setStatus("Import error: " + e.getMessage());
                    showError("Import Failed", e.getMessage());
                });
            }
        });
    }

    private void importTemplates(String json, ObjectMapper mapper) throws Exception {
        // Try array first, then single object
        List<LogTemplate> templates;
        try {
            templates = mapper.readValue(json, new TypeReference<List<LogTemplate>>() {});
        } catch (Exception e) {
            LogTemplate single = mapper.readValue(json, LogTemplate.class);
            templates = List.of(single);
        }
        int count = 0;
        for (LogTemplate t : templates) {
            if (t.id() != null && !t.id().isEmpty()) {
                client.updateTemplate(t);
            } else {
                client.createTemplate(t);
            }
            count++;
        }
        final int total = count;
        Platform.runLater(() -> setStatus("Imported " + total + " template(s)"));
    }

    private void importLogbooks(String json, ObjectMapper mapper) throws Exception {
        List<OlogLogbook> logbooks;
        try {
            logbooks = mapper.readValue(json, new TypeReference<List<OlogLogbook>>() {});
        } catch (Exception e) {
            OlogLogbook single = mapper.readValue(json, OlogLogbook.class);
            logbooks = List.of(single);
        }
        int count = 0;
        for (OlogLogbook lb : logbooks) {
            client.createLogbook(lb);
            count++;
        }
        final int total = count;
        Platform.runLater(() -> setStatus("Imported " + total + " logbook(s)"));
    }

    private void importTags(String json, ObjectMapper mapper) throws Exception {
        List<OlogTag> tags;
        try {
            tags = mapper.readValue(json, new TypeReference<List<OlogTag>>() {});
        } catch (Exception e) {
            OlogTag single = mapper.readValue(json, OlogTag.class);
            tags = List.of(single);
        }
        int count = 0;
        for (OlogTag tag : tags) {
            client.createTag(tag);
            count++;
        }
        final int total = count;
        Platform.runLater(() -> setStatus("Imported " + total + " tag(s)"));
    }

    private void importProperties(String json, ObjectMapper mapper) throws Exception {
        List<OlogProperty> props;
        try {
            props = mapper.readValue(json, new TypeReference<List<OlogProperty>>() {});
        } catch (Exception e) {
            OlogProperty single = mapper.readValue(json, OlogProperty.class);
            props = List.of(single);
        }
        int count = 0;
        for (OlogProperty prop : props) {
            List<String> attrNames = prop.getAttributes() != null
                    ? new ArrayList<>(prop.getAttributes().keySet())
                    : List.of();
            client.createPropertyWithAttributes(prop.getName(),
                    prop.getOwner() != null ? prop.getOwner() : "olog-logs",
                    attrNames);
            count++;
        }
        final int total = count;
        Platform.runLater(() -> setStatus("Imported " + total + " property(ies)"));
    }

    @FXML
    private void onExportJson() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export JSON");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON files", "*.json"));

        int tabIndex = mainTabPane.getSelectionModel().getSelectedIndex();
        switch (tabIndex) {
            case 0:
                chooser.setInitialFileName("templates.json");
                break;
            case 1:
                chooser.setInitialFileName("logbooks.json");
                break;
            case 2:
                chooser.setInitialFileName("tags.json");
                break;
            case 3:
                chooser.setInitialFileName("properties.json");
                break;
        }

        File file = chooser.showSaveDialog(mainTabPane.getScene().getWindow());
        if (file == null) return;

        try {
            String json;
            switch (tabIndex) {
                case 0:
                    json = prettyMapper.writeValueAsString(new ArrayList<>(templatesList));
                    break;
                case 1:
                    json = prettyMapper.writeValueAsString(new ArrayList<>(logbooksList));
                    break;
                case 2:
                    json = prettyMapper.writeValueAsString(new ArrayList<>(tagsList));
                    break;
                case 3:
                    json = prettyMapper.writeValueAsString(new ArrayList<>(propertiesList));
                    break;
                default:
                    return;
            }
            Files.writeString(file.toPath(), json);
            setStatus("Exported to " + file.getName());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to export JSON", e);
            setStatus("Export error: " + e.getMessage());
            showError("Export Failed", e.getMessage());
        }
    }

    // ========== TEMPLATES ACTIONS ==========

    @FXML
    private void onNewTemplate() {
        TemplateEditDialog dialog = new TemplateEditDialog(null,
                new ArrayList<>(logbooksList), new ArrayList<>(tagsList),
                new ArrayList<>(propertiesList));
        dialog.initOwner(mainTabPane.getScene().getWindow());
        Optional<LogTemplate> result = dialog.showAndWait();
        result.ifPresent(template -> {
            CompletableFuture.runAsync(() -> {
                try {
                    client.createTemplate(template);
                    Platform.runLater(() -> {
                        setStatus("Template created: " + template.name());
                        onRefresh();
                    });
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to create template", e);
                    Platform.runLater(() -> {
                        setStatus("Error: " + e.getMessage());
                        showError("Create Template Failed", e.getMessage());
                    });
                }
            });
        });
    }

    @FXML
    private void onEditTemplate() {
        LogTemplate selected = templatesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a template to edit");
            return;
        }
        TemplateEditDialog dialog = new TemplateEditDialog(selected,
                new ArrayList<>(logbooksList), new ArrayList<>(tagsList),
                new ArrayList<>(propertiesList));
        dialog.initOwner(mainTabPane.getScene().getWindow());
        Optional<LogTemplate> result = dialog.showAndWait();
        result.ifPresent(template -> {
            CompletableFuture.runAsync(() -> {
                try {
                    if (template.id() != null && !template.id().isEmpty()) {
                        client.updateTemplate(template);
                    } else {
                        client.createTemplate(template);
                    }
                    Platform.runLater(() -> {
                        setStatus("Template saved: " + template.name());
                        onRefresh();
                    });
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to save template", e);
                    Platform.runLater(() -> {
                        setStatus("Error: " + e.getMessage());
                        showError("Save Template Failed", e.getMessage());
                    });
                }
            });
        });
    }

    @FXML
    private void onDeleteTemplate() {
        LogTemplate selected = templatesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a template to delete");
            return;
        }
        if (!confirmDelete("template", selected.name())) return;

        CompletableFuture.runAsync(() -> {
            try {
                client.deleteTemplate(selected.id());
                Platform.runLater(() -> {
                    setStatus("Deleted template: " + selected.name());
                    onRefresh();
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to delete template", e);
                Platform.runLater(() -> {
                    setStatus("Error: " + e.getMessage());
                    showError("Delete Failed", e.getMessage());
                });
            }
        });
    }

    // ========== LOGBOOKS ACTIONS ==========

    @FXML
    private void onCreateLogbook() {
        String name = logbookNameField.getText().trim();
        String owner = logbookOwnerField.getText().trim();
        if (name.isEmpty()) {
            setStatus("Logbook name is required");
            return;
        }
        OlogLogbook logbook = new OlogLogbook(name, owner.isEmpty() ? null : owner);
        CompletableFuture.runAsync(() -> {
            try {
                client.createLogbook(logbook);
                Platform.runLater(() -> {
                    setStatus("Created logbook: " + name);
                    logbookNameField.clear();
                    logbookOwnerField.clear();
                    onRefresh();
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to create logbook", e);
                Platform.runLater(() -> {
                    setStatus("Error: " + e.getMessage());
                    showError("Create Logbook Failed", e.getMessage());
                });
            }
        });
    }

    @FXML
    private void onDeleteLogbook() {
        Logbook selected = logbooksTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a logbook to delete");
            return;
        }
        if (!confirmDelete("logbook", selected.getName())) return;

        CompletableFuture.runAsync(() -> {
            try {
                client.deleteLogbook(selected.getName());
                Platform.runLater(() -> {
                    setStatus("Deleted logbook: " + selected.getName());
                    onRefresh();
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to delete logbook", e);
                Platform.runLater(() -> {
                    setStatus("Error: " + e.getMessage());
                    showError("Delete Failed", e.getMessage());
                });
            }
        });
    }

    // ========== TAGS ACTIONS ==========

    @FXML
    private void onCreateTag() {
        String name = tagNameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("Tag name is required");
            return;
        }
        OlogTag tag = new OlogTag(name, "Active");
        CompletableFuture.runAsync(() -> {
            try {
                client.createTag(tag);
                Platform.runLater(() -> {
                    setStatus("Created tag: " + name);
                    tagNameField.clear();
                    onRefresh();
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to create tag", e);
                Platform.runLater(() -> {
                    setStatus("Error: " + e.getMessage());
                    showError("Create Tag Failed", e.getMessage());
                });
            }
        });
    }

    @FXML
    private void onDeleteTag() {
        Tag selected = tagsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a tag to delete");
            return;
        }
        if (!confirmDelete("tag", selected.getName())) return;

        CompletableFuture.runAsync(() -> {
            try {
                client.deleteTag(selected.getName());
                Platform.runLater(() -> {
                    setStatus("Deleted tag: " + selected.getName());
                    onRefresh();
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to delete tag", e);
                Platform.runLater(() -> {
                    setStatus("Error: " + e.getMessage());
                    showError("Delete Failed", e.getMessage());
                });
            }
        });
    }

    // ========== PREVIEW ==========

    private void showTemplatePreview(LogTemplate template) {
        if (template == null) {
            templatePreviewWebView.getEngine().loadContent("");
            return;
        }

        // Build a Markdown document with metadata header + body
        StringBuilder md = new StringBuilder();
        md.append("# ").append(template.name()).append("\n\n");
        if (template.title() != null && !template.title().isEmpty())
            md.append("**Title:** ").append(template.title()).append("  \n");
        if (template.owner() != null)
            md.append("**Owner:** ").append(template.owner()).append("  \n");
        if (template.level() != null)
            md.append("**Level:** ").append(template.level()).append("  \n");
        if (template.id() != null)
            md.append("**ID:** `").append(template.id()).append("`  \n");
        if (template.createdDate() != null)
            md.append("**Created:** ").append(template.createdDate()).append("  \n");
        if (template.modifiedDate() != null)
            md.append("**Modified:** ").append(template.modifiedDate()).append("  \n");

        if (template.logbooks() != null && !template.logbooks().isEmpty()) {
            md.append("\n**Logbooks:** ");
            md.append(template.logbooks().stream()
                    .map(Logbook::getName).collect(Collectors.joining(", ")));
            md.append("  \n");
        }
        if (template.tags() != null && !template.tags().isEmpty()) {
            md.append("**Tags:** ");
            md.append(template.tags().stream()
                    .map(Tag::getName).collect(Collectors.joining(", ")));
            md.append("  \n");
        }
        if (template.properties() != null && !template.properties().isEmpty()) {
            md.append("\n**Properties:**\n\n");
            md.append("| Property | Attributes |\n|---|---|\n");
            for (Property p : template.properties()) {
                String attrs = p.getAttributes() != null
                        ? p.getAttributes().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(", "))
                        : "";
                md.append("| ").append(p.getName()).append(" | ").append(attrs).append(" |\n");
            }
        }

        if (template.source() != null && !template.source().isEmpty()) {
            md.append("\n---\n\n");
            md.append(template.source());
        }

        // Convert Markdown to HTML and render
        String htmlBody = htmlRenderer.toHtml(md.toString());
        String fullHtml = "<html><head><style>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; "
                + "  font-size: 13px; padding: 10px; margin: 0; }"
                + "h1 { font-size: 1.4em; margin-top: 0; }"
                + "table { border-collapse: collapse; width: 100%; margin: 8px 0; }"
                + "th, td { border: 1px solid #ccc; padding: 4px 8px; text-align: left; }"
                + "th { background-color: #f0f0f0; }"
                + "hr { border: 0; border-top: 1px solid #ddd; margin: 12px 0; }"
                + "code { background-color: #f5f5f5; padding: 1px 4px; border-radius: 3px; }"
                + "pre { background-color: #f5f5f5; padding: 8px; border-radius: 4px; overflow-x: auto; }"
                + "</style></head><body>" + htmlBody + "</body></html>";
        templatePreviewWebView.getEngine().loadContent(fullHtml);
    }

    private void showLogbookPreview(Logbook logbook) {
        if (logbook == null) {
            logbookPreviewArea.clear();
            return;
        }
        try {
            logbookPreviewArea.setText(prettyMapper.writeValueAsString(logbook));
        } catch (Exception e) {
            logbookPreviewArea.setText("Error: " + e.getMessage());
        }
    }

    private void showTagPreview(Tag tag) {
        if (tag == null) {
            tagPreviewArea.clear();
            return;
        }
        try {
            tagPreviewArea.setText(prettyMapper.writeValueAsString(tag));
        } catch (Exception e) {
            tagPreviewArea.setText("Error: " + e.getMessage());
        }
    }

    // ========== PROPERTIES ACTIONS ==========

    @FXML
    private void onCreateProperty() {
        String name = propertyNameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("Property name is required");
            return;
        }
        String owner = propertyOwnerField.getText().trim();
        String attrsText = propertyAttrsField.getText().trim();

        // Parse comma-separated attribute names
        List<String> attrNames = new ArrayList<>();
        if (!attrsText.isEmpty()) {
            for (String a : attrsText.split(",")) {
                String trimmed = a.trim();
                if (!trimmed.isEmpty()) attrNames.add(trimmed);
            }
        }

        CompletableFuture.runAsync(() -> {
            try {
                client.createPropertyWithAttributes(name,
                        owner.isEmpty() ? "olog-logs" : owner,
                        attrNames);
                Platform.runLater(() -> {
                    setStatus("Created property: " + name);
                    propertyNameField.clear();
                    propertyOwnerField.clear();
                    propertyAttrsField.clear();
                    onRefresh();
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to create property", e);
                Platform.runLater(() -> {
                    setStatus("Error: " + e.getMessage());
                    showError("Create Property Failed", e.getMessage());
                });
            }
        });
    }

    @FXML
    private void onDeleteProperty() {
        Property selected = propertiesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a property to delete");
            return;
        }
        if (!confirmDelete("property", selected.getName())) return;

        CompletableFuture.runAsync(() -> {
            try {
                client.deleteProperty(selected.getName());
                Platform.runLater(() -> {
                    setStatus("Deleted property: " + selected.getName());
                    onRefresh();
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to delete property", e);
                Platform.runLater(() -> {
                    setStatus("Error: " + e.getMessage());
                    showError("Delete Failed", e.getMessage());
                });
            }
        });
    }

    private void showPropertyPreview(Property property) {
        if (property == null) {
            propertyPreviewArea.clear();
            return;
        }
        try {
            propertyPreviewArea.setText(prettyMapper.writeValueAsString(property));
        } catch (Exception e) {
            propertyPreviewArea.setText("Error: " + e.getMessage());
        }
    }

    // ========== UTILITY ==========

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    private boolean confirmDelete(String type, String name) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Delete");
        alert.setHeaderText("Delete " + type + "?");
        alert.setContentText("Are you sure you want to delete " + type + " '" + name + "'?\nThis action cannot be undone.");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
