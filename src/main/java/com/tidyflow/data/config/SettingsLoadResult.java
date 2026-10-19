package com.tidyflow.data.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Outcome of loading settings from settings.json, including any recovery notice if the file was corrupt or had an unknown version.
 */
public record SettingsLoadResult(
        AppSettings settings,
        boolean recoveredFromBackup,
        Path backupPath,
        String warningMessage
) {
    public SettingsLoadResult {
        Objects.requireNonNull(settings, "settings must not be null");
    }

    public static SettingsLoadResult normal(AppSettings settings) {
        return new SettingsLoadResult(settings, false, null, null);
    }

    public static SettingsLoadResult recovered(AppSettings settings, Path backupPath, String warningMessage) {
        return new SettingsLoadResult(settings, true, backupPath, warningMessage);
    }
}
