/*
 * Copyright (C) 2025 INFN - Laboratori Nazionali di Frascati.
 */
package org.phoebus.applications.utility.logbook;

import org.phoebus.framework.spi.AppDescriptor;
import org.phoebus.framework.spi.AppInstance;

import java.util.logging.Logger;

/**
 * Application descriptor for the Logbook Template Manager utility.
 * Manages Olog Templates, Logbooks, and Tags via the Olog REST API.
 */
public class LogbookTemplateManagerApp implements AppDescriptor {

    public static final String NAME = "logbook_template_manager";
    public static final String DISPLAY_NAME = "Logbook Template Manager";
    static final Logger logger = Logger.getLogger(LogbookTemplateManagerApp.class.getName());

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public AppInstance create() {
        if (LogbookTemplateManagerInstance.INSTANCE == null) {
            LogbookTemplateManagerInstance.INSTANCE = new LogbookTemplateManagerInstance(this);
        } else {
            LogbookTemplateManagerInstance.INSTANCE.raise();
        }
        return LogbookTemplateManagerInstance.INSTANCE;
    }
}
