package com.tidyflow.data.config;

import java.util.List;
import java.util.Objects;

/**
 * Immutable application settings persisted in %APPDATA%\TidyFlow\settings.json.
 */
public record AppSettings(
        List<WatchedFolderConfig> watchedFolders,
        int settleSeconds,
        int massMoveGuardThreshold,
        boolean showNotifications,
        boolean minimizeToTray,
        boolean startWithWindows,
        ScheduleConfig schedule,
        ThemeMode theme
) {
    public static final int DEFAULT_SETTLE_SECONDS = 5;
    public static final int MIN_SETTLE_SECONDS = 3;
    public static final int MAX_SETTLE_SECONDS = 60;

    public static final int DEFAULT_MASS_MOVE_GUARD_THRESHOLD = 50;
    public static final int MIN_MASS_MOVE_GUARD_THRESHOLD = 5;
    public static final int MAX_MASS_MOVE_GUARD_THRESHOLD = 1000;

    public AppSettings {
        Objects.requireNonNull(watchedFolders, "watchedFolders must not be null");
        Objects.requireNonNull(schedule, "schedule must not be null");
        watchedFolders = List.copyOf(watchedFolders);
        if (theme == null) {
            theme = ThemeMode.SYSTEM;
        }
    }

    public AppSettings(
            List<WatchedFolderConfig> watchedFolders,
            int settleSeconds,
            int massMoveGuardThreshold,
            boolean showNotifications,
            boolean minimizeToTray,
            boolean startWithWindows,
            ScheduleConfig schedule
    ) {
        this(
                watchedFolders,
                settleSeconds,
                massMoveGuardThreshold,
                showNotifications,
                minimizeToTray,
                startWithWindows,
                schedule,
                ThemeMode.SYSTEM
        );
    }

    public static AppSettings defaults() {
        return new AppSettings(
                List.of(),
                DEFAULT_SETTLE_SECONDS,
                DEFAULT_MASS_MOVE_GUARD_THRESHOLD,
                true,
                true,
                false,
                ScheduleConfig.defaults(),
                ThemeMode.SYSTEM
        );
    }
}
