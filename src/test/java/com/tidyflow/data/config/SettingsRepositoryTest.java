package com.tidyflow.data.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsRepositoryTest {

    @Test
    void missingFileCreatesDefaultSettingsOnFirstLoad(@TempDir Path tempDir) throws Exception {
        Path settingsPath = tempDir.resolve("settings.json");
        SettingsRepository repo = new SettingsRepository(settingsPath);

        SettingsLoadResult result = repo.load();

        assertFalse(result.recoveredFromBackup());
        assertTrue(Files.exists(settingsPath));
        assertEquals(AppSettings.defaults(), result.settings());
    }

    @Test
    void saveAndLoadRoundTripPreservesAllFields(@TempDir Path tempDir) throws Exception {
        Path settingsPath = tempDir.resolve("settings.json");
        Path watchedDir = Files.createDirectories(tempDir.resolve("Downloads"));
        SettingsRepository repo = new SettingsRepository(settingsPath);

        AppSettings custom = new AppSettings(
                List.of(new WatchedFolderConfig(watchedDir, WatchMode.AUTO, true)),
                12,
                120,
                false,
                true,
                true,
                new ScheduleConfig(true, "18:45")
        );

        repo.save(custom);
        SettingsLoadResult loaded = repo.load();

        assertFalse(loaded.recoveredFromBackup());
        assertEquals(custom, loaded.settings());
    }

    @Test
    void corruptJsonIsBackedUpAndDefaultsAreLoaded(@TempDir Path tempDir) throws Exception {
        Path settingsPath = tempDir.resolve("settings.json");
        Files.writeString(settingsPath, "{not valid json");

        SettingsRepository repo = new SettingsRepository(settingsPath);
        SettingsLoadResult result = repo.load();

        assertTrue(result.recoveredFromBackup());
        assertNotNull(result.backupPath());
        assertTrue(Files.exists(result.backupPath()));
        assertTrue(result.backupPath().getFileName().toString().startsWith("settings.json.bak-"));
        assertNotNull(result.warningMessage());
        assertEquals(AppSettings.defaults(), result.settings());
    }

    @Test
    void unknownVersionIsBackedUpAndDefaultsAreLoaded(@TempDir Path tempDir) throws Exception {
        Path settingsPath = tempDir.resolve("settings.json");
        Files.writeString(settingsPath, """
                {
                  "version": 99,
                  "watchedFolders": [],
                  "settleSeconds": 5,
                  "massMoveGuardThreshold": 50,
                  "showNotifications": true,
                  "minimizeToTray": true,
                  "startWithWindows": false,
                  "schedule": { "enabled": false, "timeOfDay": "09:00" }
                }
                """);

        SettingsRepository repo = new SettingsRepository(settingsPath);
        SettingsLoadResult result = repo.load();

        assertTrue(result.recoveredFromBackup());
        assertTrue(Files.exists(result.backupPath()));
        assertEquals(AppSettings.defaults(), result.settings());
    }
}
