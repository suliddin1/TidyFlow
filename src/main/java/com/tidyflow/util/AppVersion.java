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

    /**
     * Strips leading 'v'/'V' and whitespace from a version or tag string.
     */
    public static String normalizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    /**
     * Returns true if {@code candidateVersion} is strictly newer than {@code currentVersion}
     * using numeric dot-separated segment comparison (e.g. 1.0.1 > 1.0.0, 1.2.0 > 1.1.9).
     */
    public static boolean isNewerVersion(String currentVersion, String candidateVersion) {
        int[] currentParts = parseNumericParts(currentVersion);
        int[] candidateParts = parseNumericParts(candidateVersion);
        if (candidateParts.length == 0) {
            return false;
        }
        if (currentParts.length == 0) {
            return true;
        }
        int maxLen = Math.max(currentParts.length, candidateParts.length);
        for (int i = 0; i < maxLen; i++) {
            int cur = i < currentParts.length ? currentParts[i] : 0;
            int cand = i < candidateParts.length ? candidateParts[i] : 0;
            if (cand != cur) {
                return cand > cur;
            }
        }
        return false;
    }

    private static int[] parseNumericParts(String raw) {
        String normalized = normalizeVersion(raw);
        int dashIdx = normalized.indexOf('-');
        if (dashIdx >= 0) {
            normalized = normalized.substring(0, dashIdx);
        }
        if (normalized.isEmpty()) {
            return new int[0];
        }
        String[] tokens = normalized.split("\\.");
        int[] parts = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                parts[i] = Integer.parseInt(tokens[i].trim());
                if (parts[i] < 0) {
                    return new int[0];
                }
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return parts;
    }
}
