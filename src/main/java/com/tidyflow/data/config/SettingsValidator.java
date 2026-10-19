package com.tidyflow.data.config;

import com.tidyflow.util.PathSafety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates AppSettings and returns readable error messages without throwing exceptions.
 */
public class SettingsValidator {

    private static final Pattern HH_MM_PATTERN = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter STRICT_HH_MM = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    public List<String> validate(AppSettings settings) {
        List<String> errors = new ArrayList<>();
        if (settings == null) {
            errors.add("Settings must not be null");
            return List.copyOf(errors);
        }

        // 1. Validate watched folders
        if (settings.watchedFolders() == null) {
            errors.add("Watched folders list must not be null");
        } else {
            Set<String> seenNormalizedPaths = new HashSet<>();
            for (WatchedFolderConfig folder : settings.watchedFolders()) {
                if (folder == null || folder.path() == null || folder.mode() == null) {
                    errors.add("Watched folder entry must not be null");
                    continue;
                }
                Path path = folder.path();
                if (PathSafety.isProtectedPath(path)) {
                    errors.add("Watched folder must not be a protected system folder: " + path);
                } else if (!Files.exists(path) || !Files.isDirectory(path)) {
                    errors.add("Watched folder does not exist or is not a directory: " + path);
                }

                String normalizedKey = normalizePathKey(path);
                if (!seenNormalizedPaths.add(normalizedKey)) {
                    errors.add("Duplicate watched folder: " + path);
                }
            }
        }

        // 2. Numeric ranges
        if (settings.settleSeconds() < AppSettings.MIN_SETTLE_SECONDS
                || settings.settleSeconds() > AppSettings.MAX_SETTLE_SECONDS) {
            errors.add("Settle seconds must be between "
                    + AppSettings.MIN_SETTLE_SECONDS + " and " + AppSettings.MAX_SETTLE_SECONDS);
        }

        if (settings.massMoveGuardThreshold() < AppSettings.MIN_MASS_MOVE_GUARD_THRESHOLD
                || settings.massMoveGuardThreshold() > AppSettings.MAX_MASS_MOVE_GUARD_THRESHOLD) {
            errors.add("Mass-move guard threshold must be between "
                    + AppSettings.MIN_MASS_MOVE_GUARD_THRESHOLD + " and " + AppSettings.MAX_MASS_MOVE_GUARD_THRESHOLD);
        }

        // 3. Schedule time format
        ScheduleConfig schedule = settings.schedule();
        if (schedule == null) {
            errors.add("Schedule configuration must not be null");
        } else if (!isValidTimeOfDay(schedule.timeOfDay())) {
            errors.add("Schedule time must be in 24-hour HH:mm format (00:00 to 23:59): "
                    + schedule.timeOfDay());
        }

        return List.copyOf(errors);
    }

    public List<String> validateNewWatchedFolder(Path candidate, List<WatchedFolderConfig> existingFolders) {
        List<String> errors = new ArrayList<>();
        if (candidate == null) {
            errors.add("Folder path must not be empty");
            return List.copyOf(errors);
        }
        if (PathSafety.isProtectedPath(candidate)) {
            errors.add("Folder must not be a protected system folder: " + candidate);
        } else if (!Files.exists(candidate) || !Files.isDirectory(candidate)) {
            errors.add("Folder does not exist or is not a directory: " + candidate);
        }

        if (existingFolders != null) {
            String candidateKey = normalizePathKey(candidate);
            boolean duplicate = existingFolders.stream()
                    .filter(f -> f != null && f.path() != null)
                    .anyMatch(f -> normalizePathKey(f.path()).equals(candidateKey));
            if (duplicate) {
                errors.add("Folder is already in the watched folders list: " + candidate);
            }
        }
        return List.copyOf(errors);
    }

    public static boolean isValidTimeOfDay(String timeOfDay) {
        if (timeOfDay == null) {
            return false;
        }
        String trimmed = timeOfDay.trim();
        if (!HH_MM_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        try {
            LocalTime.parse(trimmed, STRICT_HH_MM);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static LocalTime parseTimeOfDay(String timeOfDay) {
        if (!isValidTimeOfDay(timeOfDay)) {
            throw new IllegalArgumentException("Invalid timeOfDay (expected HH:mm): " + timeOfDay);
        }
        return LocalTime.parse(timeOfDay.trim(), STRICT_HH_MM);
    }

    public static String normalizePathKey(Path path) {
        return path.toAbsolutePath().normalize()
                .toString()
                .replace('/', '\\')
                .toLowerCase(Locale.ROOT);
    }
}
