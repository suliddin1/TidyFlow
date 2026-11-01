package com.tidyflow.data.config;

import com.tidyflow.core.dedupe.DiskType;
import com.tidyflow.core.dedupe.FolderExclusionFilter;
import com.tidyflow.core.dedupe.MinSizePreset;

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
        ThemeMode theme,
        MinSizePreset dedupeMinSizePreset,
        DiskType dedupeDiskType,
        List<String> dedupeExcludedFolderNames,
        List<String> dedupeExcludedPaths,
        boolean suppressDriveRootWarning
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
        if (dedupeMinSizePreset == null) {
            dedupeMinSizePreset = MinSizePreset.MB_1;
        }
        if (dedupeDiskType == null) {
            dedupeDiskType = DiskType.AUTO;
        }
        dedupeExcludedFolderNames = dedupeExcludedFolderNames != null
                ? List.copyOf(dedupeExcludedFolderNames)
                : FolderExclusionFilter.DEFAULT_EXCLUDED_NAMES;
        dedupeExcludedPaths = dedupeExcludedPaths != null
                ? List.copyOf(dedupeExcludedPaths)
                : List.of();
    }

    public AppSettings(
            List<WatchedFolderConfig> watchedFolders,
            int settleSeconds,
            int massMoveGuardThreshold,
            boolean showNotifications,
            boolean minimizeToTray,
            boolean startWithWindows,
            ScheduleConfig schedule,
            ThemeMode theme
    ) {
        this(
                watchedFolders,
                settleSeconds,
                massMoveGuardThreshold,
                showNotifications,
                minimizeToTray,
                startWithWindows,
                schedule,
                theme,
                MinSizePreset.MB_1,
                DiskType.AUTO,
                FolderExclusionFilter.DEFAULT_EXCLUDED_NAMES,
                List.of(),
                false
        );
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

    public FolderExclusionFilter dedupeExclusionFilter() {
        return new FolderExclusionFilter(dedupeExcludedFolderNames, dedupeExcludedPaths);
    }

    public AppSettings withDedupePreferences(
            MinSizePreset preset,
            DiskType diskType,
            List<String> excludedFolderNames,
            List<String> excludedPaths,
            boolean suppressWarning
    ) {
        return new AppSettings(
                watchedFolders,
                settleSeconds,
                massMoveGuardThreshold,
                showNotifications,
                minimizeToTray,
                startWithWindows,
                schedule,
                theme,
                preset != null ? preset : dedupeMinSizePreset,
                diskType != null ? diskType : dedupeDiskType,
                excludedFolderNames != null ? excludedFolderNames : dedupeExcludedFolderNames,
                excludedPaths != null ? excludedPaths : dedupeExcludedPaths,
                suppressWarning
        );
    }

    public AppSettings withSuppressDriveRootWarning(boolean suppress) {
        return withDedupePreferences(
                dedupeMinSizePreset,
                dedupeDiskType,
                dedupeExcludedFolderNames,
                dedupeExcludedPaths,
                suppress
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
                ThemeMode.SYSTEM,
                MinSizePreset.MB_1,
                DiskType.AUTO,
                FolderExclusionFilter.DEFAULT_EXCLUDED_NAMES,
                List.of(),
                false
        );
    }
}
