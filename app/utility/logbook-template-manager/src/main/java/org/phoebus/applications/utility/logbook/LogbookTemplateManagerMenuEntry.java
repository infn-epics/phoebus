/*
 * Copyright (C) 2025 INFN - Laboratori Nazionali di Frascati.
 */
package org.phoebus.applications.utility.logbook;

import org.phoebus.framework.workbench.ApplicationService;
import org.phoebus.ui.spi.MenuEntry;

/**
 * Menu entry for launching the Logbook Template Manager
 * under Applications → Utility.
 */
public class LogbookTemplateManagerMenuEntry implements MenuEntry {

    @Override
    public String getName() {
        return LogbookTemplateManagerApp.DISPLAY_NAME;
    }

    @Override
    public String getMenuPath() {
        return "Utility";
    }

    @Override
    public Void call() throws Exception {
        ApplicationService.createInstance(LogbookTemplateManagerApp.NAME);
        return null;
    }
}
