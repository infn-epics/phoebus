/*
 * Copyright (C) 2025 INFN - Laboratori Nazionali di Frascati.
 */
package org.phoebus.applications.utility.logbook;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import org.phoebus.logbook.LogTemplate;
import org.phoebus.logbook.Logbook;
import org.phoebus.logbook.Property;
import org.phoebus.logbook.Tag;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dialog for creating or editing a LogTemplate.
 * Shows fields for name, title, level, body (with markdown toolbar),
 * logbooks, tags, and properties.
 */
public class TemplateEditDialog extends Dialog<LogTemplate> {

    private static final Logger LOGGER = Logger.getLogger(TemplateEditDialog.class.getName());

    public TemplateEditDialog(LogTemplate existing, List<Logbook> availableLogbooks,
                              List<Tag> availableTags, List<Property> availableProperties) {
        setTitle(existing == null ? "New Template" : "Edit Template");
        setResizable(true);

        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("TemplateEditDialog.fxml"));
            Node content = loader.load();

            TemplateEditDialogController controller = loader.getController();
            controller.setData(existing, availableLogbooks, availableTags, availableProperties);

            DialogPane pane = getDialogPane();
            pane.setHeaderText(existing == null ? "Create a new template" : "Edit template: " + existing.name());
            pane.setContent(content);

            ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
            pane.getButtonTypes().addAll(ButtonType.CANCEL, saveType);

            setResultConverter(buttonType -> {
                if (buttonType == saveType) {
                    return controller.buildTemplate();
                }
                return null;
            });

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Cannot load TemplateEditDialog.fxml", e);
        }
    }
}
