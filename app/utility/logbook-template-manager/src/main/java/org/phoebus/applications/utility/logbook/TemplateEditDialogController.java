/*
 * Copyright (C) 2025 INFN - Laboratori Nazionali di Frascati.
 */
package org.phoebus.applications.utility.logbook;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.web.WebView;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.phoebus.logbook.LogTemplate;
import org.phoebus.logbook.Logbook;
import org.phoebus.logbook.Property;
import org.phoebus.logbook.Tag;
import org.phoebus.logbook.olog.ui.HtmlAwareController;
import org.phoebus.olog.es.api.Preferences;
import org.phoebus.olog.es.api.model.OlogLogbook;
import org.phoebus.olog.es.api.model.OlogProperty;
import org.phoebus.olog.es.api.model.OlogTag;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for the template create/edit dialog.
 * Includes markdown toolbar, live preview, and properties management.
 */
public class TemplateEditDialogController {

    @FXML private TextField nameField;
    @FXML private TextField titleField;
    @FXML private ComboBox<String> levelCombo;
    @FXML private TextArea sourceArea;
    @FXML private WebView previewWebView;
    @FXML private Spinner<Integer> tableRowsSpinner;
    @FXML private Spinner<Integer> tableColsSpinner;
    @FXML private ListView<String> logbooksList;
    @FXML private ListView<String> tagsList;
    @FXML private ListView<String> propertiesList;

    private LogTemplate existingTemplate;
    private final Map<String, BooleanProperty> logbookSelections = new LinkedHashMap<>();
    private final Map<String, BooleanProperty> tagSelections = new LinkedHashMap<>();
    private final Map<String, BooleanProperty> propertySelections = new LinkedHashMap<>();
    /** property name -> map of attribute key->value for selected properties */
    private final Map<String, Map<String, String>> propertyAttributes = new LinkedHashMap<>();

    private List<Property> allAvailableProperties = new ArrayList<>();
    private HtmlAwareController htmlRenderer;
    private boolean previewVisible = false;

    @FXML
    public void initialize() {
        // Default levels
        levelCombo.getItems().addAll("Info", "Problem", "Request", "Suggestion", "Urgent");
        levelCombo.getSelectionModel().selectFirst();

        // Table spinners
        tableRowsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 50, 3));
        tableColsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, 3));

        // Markdown renderer
        htmlRenderer = new HtmlAwareController(Preferences.olog_url);
    }

    /**
     * Populate the dialog with data.
     */
    public void setData(LogTemplate existing, List<Logbook> availableLogbooks, List<Tag> availableTags,
                        List<Property> availableProperties) {
        this.existingTemplate = existing;
        this.allAvailableProperties = availableProperties != null ? availableProperties : new ArrayList<>();

        // Determine which logbooks/tags/properties are selected
        Set<String> selectedLogbooks = new HashSet<>();
        Set<String> selectedTags = new HashSet<>();
        Set<String> selectedProperties = new HashSet<>();
        if (existing != null) {
            if (existing.logbooks() != null)
                existing.logbooks().forEach(lb -> selectedLogbooks.add(lb.getName()));
            if (existing.tags() != null)
                existing.tags().forEach(t -> selectedTags.add(t.getName()));
            if (existing.properties() != null) {
                existing.properties().forEach(p -> {
                    selectedProperties.add(p.getName());
                    propertyAttributes.put(p.getName(),
                            p.getAttributes() != null ? new LinkedHashMap<>(p.getAttributes()) : new LinkedHashMap<>());
                });
            }
        }

        // Build logbooks checkbox list
        logbookSelections.clear();
        for (Logbook lb : availableLogbooks) {
            BooleanProperty sel = new SimpleBooleanProperty(selectedLogbooks.contains(lb.getName()));
            logbookSelections.put(lb.getName(), sel);
        }
        logbooksList.getItems().setAll(logbookSelections.keySet());
        logbooksList.setCellFactory(CheckBoxListCell.forListView(logbookSelections::get));

        // Build tags checkbox list
        tagSelections.clear();
        for (Tag t : availableTags) {
            BooleanProperty sel = new SimpleBooleanProperty(selectedTags.contains(t.getName()));
            tagSelections.put(t.getName(), sel);
        }
        tagsList.getItems().setAll(tagSelections.keySet());
        tagsList.setCellFactory(CheckBoxListCell.forListView(tagSelections::get));

        // Build properties checkbox list
        propertySelections.clear();
        for (Property p : allAvailableProperties) {
            BooleanProperty sel = new SimpleBooleanProperty(selectedProperties.contains(p.getName()));
            propertySelections.put(p.getName(), sel);
            // Ensure attribute map exists for each property
            if (!propertyAttributes.containsKey(p.getName())) {
                propertyAttributes.put(p.getName(),
                        p.getAttributes() != null ? new LinkedHashMap<>(p.getAttributes()) : new LinkedHashMap<>());
            }
        }
        propertiesList.getItems().setAll(propertySelections.keySet());
        propertiesList.setCellFactory(CheckBoxListCell.forListView(propertySelections::get));

        // Fill fields
        if (existing != null) {
            nameField.setText(existing.name() != null ? existing.name() : "");
            titleField.setText(existing.title() != null ? existing.title() : "");
            sourceArea.setText(existing.source() != null ? existing.source() : "");
            if (existing.level() != null && !existing.level().isEmpty()) {
                if (!levelCombo.getItems().contains(existing.level()))
                    levelCombo.getItems().add(existing.level());
                levelCombo.getSelectionModel().select(existing.level());
            }
        }
    }

    // ========== Markdown Toolbar ==========

    @FXML
    private void onBold() {
        wrapSelection("**", "**", "bold text");
    }

    @FXML
    private void onItalic() {
        wrapSelection("*", "*", "italic text");
    }

    @FXML
    private void onH1() {
        insertAtLineStart("# ", "Heading 1");
    }

    @FXML
    private void onH2() {
        insertAtLineStart("## ", "Heading 2");
    }

    @FXML
    private void onH3() {
        insertAtLineStart("### ", "Heading 3");
    }

    @FXML
    private void onBulletList() {
        insertAtLineStart("- ", "item");
    }

    @FXML
    private void onNumberedList() {
        insertAtLineStart("1. ", "item");
    }

    @FXML
    private void onCode() {
        String sel = sourceArea.getSelectedText();
        if (sel != null && !sel.isEmpty() && sel.contains("\n")) {
            wrapSelection("```\n", "\n```", sel);
        } else {
            wrapSelection("`", "`", "code");
        }
    }

    @FXML
    private void onLink() {
        wrapSelection("[", "](url)", "link text");
    }

    @FXML
    private void onInsertTable() {
        int rows = tableRowsSpinner.getValue();
        int cols = tableColsSpinner.getValue();

        StringBuilder table = new StringBuilder("\n");
        // Header row
        table.append("|");
        for (int c = 0; c < cols; c++) table.append(" Header ").append(c + 1).append(" |");
        table.append("\n");
        // Separator
        table.append("|");
        for (int c = 0; c < cols; c++) table.append("---|");
        table.append("\n");
        // Data rows
        for (int r = 0; r < rows; r++) {
            table.append("|");
            for (int c = 0; c < cols; c++) table.append("  |");
            table.append("\n");
        }
        table.append("\n");

        int pos = sourceArea.getCaretPosition();
        sourceArea.insertText(pos, table.toString());
    }

    @FXML
    private void onPreview() {
        previewVisible = !previewVisible;
        previewWebView.setVisible(previewVisible);
        previewWebView.setManaged(previewVisible);
        if (previewVisible) {
            updatePreview();
        }
    }

    private void updatePreview() {
        String markdown = sourceArea.getText();
        if (markdown == null || markdown.isEmpty()) {
            previewWebView.getEngine().loadContent("<html><body><i>Empty</i></body></html>");
            return;
        }
        String htmlBody = htmlRenderer.toHtml(markdown);
        String fullHtml = "<html><head><style>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; "
                + "  font-size: 13px; padding: 8px; margin: 0; }"
                + "table { border-collapse: collapse; width: 100%; margin: 8px 0; }"
                + "th, td { border: 1px solid #ccc; padding: 4px 8px; text-align: left; }"
                + "th { background-color: #f0f0f0; }"
                + "code { background-color: #f5f5f5; padding: 1px 4px; border-radius: 3px; }"
                + "pre { background-color: #f5f5f5; padding: 8px; border-radius: 4px; overflow-x: auto; }"
                + "</style></head><body>" + htmlBody + "</body></html>";
        previewWebView.getEngine().loadContent(fullHtml);
    }

    private void wrapSelection(String before, String after, String placeholder) {
        String sel = sourceArea.getSelectedText();
        int start = sourceArea.getSelection().getStart();
        int end = sourceArea.getSelection().getEnd();
        if (sel == null || sel.isEmpty()) {
            int pos = sourceArea.getCaretPosition();
            sourceArea.insertText(pos, before + placeholder + after);
            sourceArea.selectRange(pos + before.length(), pos + before.length() + placeholder.length());
        } else {
            sourceArea.replaceText(start, end, before + sel + after);
            sourceArea.selectRange(start + before.length(), start + before.length() + sel.length());
        }
    }

    private void insertAtLineStart(String prefix, String placeholder) {
        int pos = sourceArea.getCaretPosition();
        String text = sourceArea.getText();
        // Find start of current line
        int lineStart = text.lastIndexOf('\n', pos - 1) + 1;
        sourceArea.insertText(lineStart, prefix);
        if (sourceArea.getSelectedText() == null || sourceArea.getSelectedText().isEmpty()) {
            // If nothing selected, also add placeholder if line is empty
            String afterPrefix = text.substring(lineStart).trim();
            if (afterPrefix.isEmpty()) {
                sourceArea.insertText(lineStart + prefix.length(), placeholder);
                sourceArea.selectRange(lineStart + prefix.length(),
                        lineStart + prefix.length() + placeholder.length());
            }
        }
    }

    // ========== Properties Management ==========

    @FXML
    private void onAddProperty() {
        // Build a list of server-side properties NOT already in the current list
        java.util.List<String> available = allAvailableProperties.stream()
                .map(Property::getName)
                .filter(name -> !propertySelections.containsKey(name))
                .sorted()
                .collect(Collectors.toList());

        if (available.isEmpty()) {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Add Property");
            info.setHeaderText(null);
            info.setContentText("All available properties are already in the list.\n"
                    + "Use the checkboxes to select them, or create new\n"
                    + "properties on the server first via the Olog API.");
            info.showAndWait();
            return;
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(available.get(0), available);
        dialog.setTitle("Add Property");
        dialog.setHeaderText("Select a property to add");
        dialog.setContentText("Property:");
        dialog.showAndWait().ifPresent(name -> {
            if (!propertySelections.containsKey(name)) {
                BooleanProperty sel = new SimpleBooleanProperty(true);
                propertySelections.put(name, sel);
                // Pre-populate attributes from the server-side definition
                for (Property serverProp : allAvailableProperties) {
                    if (serverProp.getName().equals(name)) {
                        Map<String, String> attrs = serverProp.getAttributes() != null
                                ? new LinkedHashMap<>(serverProp.getAttributes()) : new LinkedHashMap<>();
                        propertyAttributes.put(name, attrs);
                        break;
                    }
                }
                propertiesList.getItems().add(name);
                propertiesList.setCellFactory(CheckBoxListCell.forListView(propertySelections::get));
            } else {
                // Already present — just check it
                propertySelections.get(name).set(true);
            }
        });
    }

    @FXML
    private void onEditPropertyAttrs() {
        String selected = propertiesList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Edit Attributes");
            info.setHeaderText(null);
            info.setContentText("Please select a property from the list first.");
            info.showAndWait();
            return;
        }

        // Current attribute values for this property
        Map<String, String> currentAttrs = propertyAttributes.getOrDefault(selected, new LinkedHashMap<>());

        // Find the server-side definition to know which attribute keys are expected
        Map<String, String> serverAttrs = null;
        for (Property serverProp : allAvailableProperties) {
            if (serverProp.getName().equals(selected) && serverProp.getAttributes() != null) {
                serverAttrs = serverProp.getAttributes();
                break;
            }
        }

        // Merge: ensure all server-defined keys are present (with current values or empty)
        Map<String, String> merged = new LinkedHashMap<>();
        if (serverAttrs != null) {
            for (Map.Entry<String, String> e : serverAttrs.entrySet()) {
                merged.put(e.getKey(), currentAttrs.getOrDefault(e.getKey(), ""));
            }
        }
        // Also keep any user-added keys not in the server definition
        for (Map.Entry<String, String> e : currentAttrs.entrySet()) {
            if (!merged.containsKey(e.getKey())) {
                merged.put(e.getKey(), e.getValue());
            }
        }

        // Build the text for the editor
        TextArea attrArea = new TextArea();
        attrArea.setPromptText("key1=value1\nkey2=value2\n...");
        attrArea.setWrapText(true);
        attrArea.setPrefRowCount(10);
        if (!merged.isEmpty()) {
            attrArea.setText(merged.entrySet().stream()
                    .map(e -> e.getKey() + "=" + (e.getValue() != null ? e.getValue() : ""))
                    .collect(Collectors.joining("\n")));
        }

        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Edit Property Attributes");
        dialog.setHeaderText("Attributes for: " + selected
                + "\nFormat: key=value (one per line)"
                + (serverAttrs != null ? "\nServer-defined keys are pre-filled below." : ""));
        dialog.getDialogPane().setContent(attrArea);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                Map<String, String> result = new LinkedHashMap<>();
                for (String line : attrArea.getText().split("\\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        result.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    } else {
                        result.put(line, "");
                    }
                }
                return result;
            }
            return null;
        });
        dialog.showAndWait().ifPresent(newAttrs -> propertyAttributes.put(selected, newAttrs));
    }

    @FXML
    private void onRemoveProperty() {
        String selected = propertiesList.getSelectionModel().getSelectedItem();
        if (selected != null && propertySelections.containsKey(selected)) {
            propertySelections.get(selected).set(false);
        }
    }

    // ========== Build Result ==========

    /**
     * Build a LogTemplate from the dialog fields.
     */
    public LogTemplate buildTemplate() {
        String id = existingTemplate != null ? existingTemplate.id() : null;
        String name = nameField.getText().trim();
        String title = titleField.getText().trim();
        String level = levelCombo.getSelectionModel().getSelectedItem();
        String source = sourceArea.getText();
        String owner = existingTemplate != null ? existingTemplate.owner() : null;

        // Collect selected logbooks
        List<Logbook> selectedLogbooks = logbookSelections.entrySet().stream()
                .filter(e -> e.getValue().get())
                .map(e -> (Logbook) new OlogLogbook(e.getKey(), null))
                .collect(Collectors.toList());

        // Collect selected tags
        List<Tag> selectedTags = tagSelections.entrySet().stream()
                .filter(e -> e.getValue().get())
                .map(e -> (Tag) new OlogTag(e.getKey()))
                .collect(Collectors.toList());

        // Collect selected properties with their attributes
        List<Property> selectedProperties = propertySelections.entrySet().stream()
                .filter(e -> e.getValue().get())
                .map(e -> {
                    Map<String, String> attrs = propertyAttributes.getOrDefault(e.getKey(), new LinkedHashMap<>());
                    return (Property) new OlogProperty(e.getKey(), attrs.isEmpty() ? null : attrs);
                })
                .collect(Collectors.toList());

        return new LogTemplate(
                id,
                name,
                owner,
                existingTemplate != null ? existingTemplate.createdDate() : null,
                existingTemplate != null ? existingTemplate.modifiedDate() : null,
                title.isEmpty() ? null : title,
                source.isEmpty() ? null : source,
                level,
                selectedLogbooks.isEmpty() ? null : selectedLogbooks,
                selectedTags.isEmpty() ? null : selectedTags,
                selectedProperties.isEmpty() ? null : selectedProperties
        );
    }
}
