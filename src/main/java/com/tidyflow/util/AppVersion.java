package com.tidyflow.util;

import java.io.InputStream;
import java.util.Properties;

/**
 * Loads the application version from /version.properties on the classpath,
 * falling back to "dev" if the resource is missing or unreadable.
 */
public final class AppVersion {

    public static final String DEFAULT_RESOURCE_PATH = "/version.properties";
    public static final String FALLBACK_VERSION = "dev";

    private AppVersion() {
    }

    public static String load() {
        return loadFromResource(DEFAULT_RESOURCE_PATH);
    }

    public static String loadFromResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return FALLBACK_VERSION;
        }
        try (InputStream in = AppVersion.class.getResourceAsStream(resourcePath)) {
            return loadFromStream(in);
        } catch (Exception e) {
            return FALLBACK_VERSION;
        }
    }

    public static String loadFromStream(InputStream in) {
        if (in == null) {
            return FALLBACK_VERSION;
        }
        try {
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version");
            if (version == null || version.isBlank() || version.contains("${")) {
                return FALLBACK_VERSION;
            }
            return version.trim();
        } catch (Exception e) {
            return FALLBACK_VERSION;
        }
    }
}
