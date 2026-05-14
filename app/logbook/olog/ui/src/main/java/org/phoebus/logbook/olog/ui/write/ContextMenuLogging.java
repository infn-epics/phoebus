package org.phoebus.logbook.olog.ui.write;

import javafx.scene.Node;
import javafx.scene.image.Image;
import org.phoebus.framework.adapter.AdapterService;
import org.phoebus.framework.selection.Selection;
import org.phoebus.logbook.LogEntryImpl.LogEntryBuilder;
import org.phoebus.logbook.LogEntry;
import org.phoebus.logbook.olog.ui.menu.SendToLogBookApp;
import org.phoebus.olog.es.api.model.OlogAttachment;
import org.phoebus.ui.javafx.Screenshot;
import org.phoebus.ui.spi.ContextMenuEntry;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A headless context menu entry for creating log entries from adaptable selections.
 * @author Kunal Shroff
 *
 */
@SuppressWarnings("rawtypes")
public class ContextMenuLogging implements ContextMenuEntry {

    private static final String NAME = "Create Log";
    private static final Class<?> supportedType = LogEntry.class;
    private static final Logger LOGGER = Logger.getLogger(ContextMenuLogging.class.getName());

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Image getIcon() {
        return SendToLogBookApp.icon;
    }

    /**
     *
     * @param parent the parent Node
     * @param selection The selection to be used to execute this action
     */
    public void call(Node parent, Selection selection) {
        List<LogEntry> adaptedSelections = new ArrayList<>();
        selection.getSelections().stream().forEach(s -> {
            AdapterService.adapt(s, LogEntry.class).ifPresent(adapted -> {
                adaptedSelections.add(adapted);
            });
        });

        if (adaptedSelections.isEmpty()) {
            LOGGER.log(Level.WARNING, "Create Log invoked with no adaptable selection");
            return;
        }

        LogEntry logEntry = adaptedSelections.get(0);

        LOGGER.log(Level.INFO, "[DEBUG] Initial attachments in adapted LogEntry: " + (logEntry.getAttachments() == null ? "null" : logEntry.getAttachments().size()));

        // Fallback: if selection-based adapter did not provide a snapshot attachment,
        // capture the clicked node to ensure Create Log opens with an image attachment.
        if ((logEntry.getAttachments() == null || logEntry.getAttachments().isEmpty()) && parent != null) {
            try {
                Image image = Screenshot.imageFromNode(parent);
                if (image != null) {
                    File imageFile = new Screenshot(image).writeToTempfile("image");
                    if (imageFile != null) {
                        OlogAttachment attachment = new OlogAttachment(UUID.randomUUID().toString());
                        attachment.setFile(imageFile);
                        attachment.setContentType("image");
                        attachment.setThumbnail(false);
                        // Keep filename format consistent with attachments created from the editor UI.
                        attachment.setUniqueFilename(imageFile.getName());

                        LogEntryBuilder builder = LogEntryBuilder.log(logEntry);
                        builder.attach(attachment);
                        logEntry = builder.build();

                        LOGGER.log(Level.INFO, "[DEBUG] Fallback attachment injected: " + attachment.getUniqueFilename() + ", file=" + imageFile.getAbsolutePath());
                        LOGGER.log(Level.INFO, "[DEBUG] Attachments after fallback: " + (logEntry.getAttachments() == null ? "null" : logEntry.getAttachments().size()));
                    }
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Unable to capture fallback screenshot for Create Log", ex);
            }
        }

        // Log attachments before opening editor
        LOGGER.log(Level.INFO, "[DEBUG] Attachments passed to LogEntryEditorStage: " + (logEntry.getAttachments() == null ? "null" : logEntry.getAttachments().size()));

        new LogEntryEditorStage(logEntry, null, EditMode.NEW_LOG_ENTRY).show();
    }

    @Override
    public Class<?> getSupportedType() {
        return supportedType;
    }
}
