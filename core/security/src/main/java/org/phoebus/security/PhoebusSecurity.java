/*******************************************************************************
 * Copyright (c) 2018-2020 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.phoebus.security;

import java.util.logging.Logger;

import org.phoebus.framework.preferences.AnnotatedPreferences;
import org.phoebus.framework.preferences.Preference;
import org.phoebus.security.store.SecureStoreTarget;

/** Phoebus security logger and Preference settings
 *  @author Kay Kasemir
 */
public class PhoebusSecurity
{
    /** Shared logger */
    public static final Logger logger = Logger.getLogger(PhoebusSecurity.class.getPackageName());

    /** Preference setting */
    @Preference public static String authorization_file;

    /** Preference setting */
    @Preference public static SecureStoreTarget secure_store_target;
    @Preference public static String oauth2_auth_url;
    @Preference public static String oauth2_realm;
    @Preference public static boolean enable_oauth2;


    @Preference public static int oauth2_callback_server_port;
    @Preference public static String oauth2_client_id;
    @Preference public static String oauth2_callback;

    static
    {
        AnnotatedPreferences.initialize(PhoebusSecurity.class, "/phoebus_security_preferences.properties");
    }
}
