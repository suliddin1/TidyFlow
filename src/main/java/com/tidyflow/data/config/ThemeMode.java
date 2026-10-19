package com.tidyflow.data.config;

/**
 * Application color theme preference persisted in settings.json.
 */
public enum ThemeMode {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    private final String displayName;

    ThemeMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
