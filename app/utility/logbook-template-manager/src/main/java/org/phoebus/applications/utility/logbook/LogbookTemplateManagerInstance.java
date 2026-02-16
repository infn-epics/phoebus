/*
 * Copyright (C) 2025 INFN - Laboratori Nazionali di Frascati.
 */
package org.phoebus.applications.utility.logbook;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import org.phoebus.framework.spi.AppDescriptor;
import org.phoebus.framework.spi.AppInstance;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;

import java.io.IOException;
import java.util.logging.Level;

import static org.phoebus.applications.utility.logbook.LogbookTemplateManagerApp.logger;

/**
 * Application instance for the Logbook Template Manager.
 * Creates the DockItem containing the FXML-based UI.
 */
public class LogbookTemplateManagerInstance implements AppInstance {

    public static LogbookTemplateManagerInstance INSTANCE = null;

    private final AppDescriptor app;
    private DockItem tab;

    public LogbookTemplateManagerInstance(AppDescriptor app) {
        this.app = app;
        tab = new DockItem(this, createFxScene());
        tab.addClosedNotification(() -> INSTANCE = null);
        DockPane.getActiveDockPane().addTab(tab);
    }

    @Override
    public AppDescriptor getAppDescriptor() {
        return app;
    }

    void raise() {
        tab.select();
    }

    private Node createFxScene() {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(this.getClass().getResource("LogbookTemplateManager.fxml"));
            loader.load();
            return loader.getRoot();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot load Logbook Template Manager UI", e);
        }
        return null;
    }
}
