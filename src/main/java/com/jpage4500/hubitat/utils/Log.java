package com.jpage4500.hubitat.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.jpage4500.hubitat.settings.HubitatToolWindowFactory;

public class Log {
    private static final Logger log = Logger.getInstance(Log.class);

    public static void debug(String message) {
        log.debug(message);
        HubitatToolWindowFactory.log(message);
    }

    public static void error(String message) {
        log.error(message);
        HubitatToolWindowFactory.log(message);
    }
}
