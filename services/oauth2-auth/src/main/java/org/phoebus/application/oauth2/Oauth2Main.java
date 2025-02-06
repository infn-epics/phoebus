package org.phoebus.application.oauth2;

import org.phoebus.framework.preferences.AnnotatedPreferences;
import org.phoebus.framework.preferences.Preference;

public class Oauth2Main {


    @Preference public static String oauth2_server_url;

    static
    {
        AnnotatedPreferences.initialize(Oauth2Main.class, "/alarm_server_logging.properties");
    }



    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
