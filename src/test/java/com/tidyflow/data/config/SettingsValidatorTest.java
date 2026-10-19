package com.tidyflow.data.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsValidatorTest {

    private final SettingsValidator validator = new SettingsValidator();

    @Test
    void defaultSettingsAreValidAndNewFolderDefaultsToAsk(@TempDir Path tempDir) {
        AppSettings defaults = AppSettings.defaults();
        assertTrue(validator.validate(defaults).isEmpty());

        WatchedFolderConfig folder = WatchedFolderConfig.newDefault(tempDir);
        assertEquals(WatchMode.ASK, folder.mode());
        assertTrue(folder.enabled());
    }

    @Test
    void rejectsNonexistentAndFileWatchedFolders(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("does-not-exist");
        Path regularFile = Files.writeString(tempDir.resolve("file.txt"), "hello");

        AppSettings withMissing = new AppSettings(
                List.of(new WatchedFolderConfig(missing, WatchMode.ASK, true)),
                5, 50, true, true, false, ScheduleConfig.defaults()
        );
        List<String> missingErrors = validator.validate(withMissing);
        assertFalse(missingErrors.isEmpty());
        assertTrue(missingErrors.get(0).toLowerCase().contains("does not exist"));

        AppSettings withFile = new AppSettings(
                List.of(new WatchedFolderConfig(regularFile, WatchMode.ASK, true)),
                5, 50, true, true, false, ScheduleConfig.defaults()
        );
        List<String> fileErrors = validator.validate(withFile);
        assertFalse(fileErrors.isEmpty());
    }

    @Test
    void rejectsProtectedWatchedFolder() {
        AppSettings settings = new AppSettings(
                List.of(new WatchedFolderConfig(Path.of("C:\\Windows\\System32"), WatchMode.ASK, true)),
                5, 50, true, true, false, ScheduleConfig.defaults()
        );
        List<String> errors = validator.validate(settings);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).toLowerCase().contains("protected"));
    }

    @Test
    void rejectsDuplicateWatchedFoldersWithDifferentCasing(@TempDir Path tempDir) throws Exception {
        Path sub = Files.createDirectories(tempDir.resolve("MyDownloads"));
        Path lowerCased = Path.of(sub.toString().toLowerCase());
        Path upperCased = Path.of(sub.toString().toUpperCase());

        AppSettings settings = new AppSettings(
                List.of(
                        new WatchedFolderConfig(lowerCased, WatchMode.ASK, true),
                        new WatchedFolderConfig(upperCased, WatchMode.AUTO, true)
                ),
                5, 50, true, true, false, ScheduleConfig.defaults()
        );
        List<String> errors = validator.validate(settings);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("duplicate")));
    }

    @Test
    void rejectsOutOfRangeNumbers() {
        AppSettings badSettleLow = new AppSettings(List.of(), 2, 50, true, true, false, ScheduleConfig.defaults());
        AppSettings badSettleHigh = new AppSettings(List.of(), 61, 50, true, true, false, ScheduleConfig.defaults());
        AppSettings badGuardLow = new AppSettings(List.of(), 5, 4, true, true, false, ScheduleConfig.defaults());
        AppSettings badGuardHigh = new AppSettings(List.of(), 5, 1001, true, true, false, ScheduleConfig.defaults());

        assertFalse(validator.validate(badSettleLow).isEmpty());
        assertFalse(validator.validate(badSettleHigh).isEmpty());
        assertFalse(validator.validate(badGuardLow).isEmpty());
        assertFalse(validator.validate(badGuardHigh).isEmpty());
    }

    @Test
    void rejectsBadTimeStringsAndAcceptsValid24HourTimes() {
        for (String bad : List.of("", "9:00", "24:00", "12:60", "ab:cd", "09-00")) {
            AppSettings s = new AppSettings(List.of(), 5, 50, true, true, false, new ScheduleConfig(true, bad));
            assertFalse(validator.validate(s).isEmpty(), "Expected error for time: " + bad);
        }

        for (String good : List.of("00:00", "09:05", "14:30", "23:59")) {
            AppSettings s = new AppSettings(List.of(), 5, 50, true, true, false, new ScheduleConfig(true, good));
            assertTrue(validator.validate(s).isEmpty(), "Expected valid time: " + good);
        }
    }
}
