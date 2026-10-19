package com.tidyflow.data.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tidyflow.data.DatabaseManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Persists and loads application settings in %APPDATA%\TidyFlow\settings.json using atomic writes
 * and automatic backup recovery when the file is corrupt or has an unknown version.
 */
public class SettingsRepository {

    private final Path configPath;
    private final SettingsValidator validator;
    private final ObjectMapper mapper;
    private final RuleRepository.AtomicFileMover fileMover;

    public SettingsRepository() {
        this(defaultSettingsPath());
    }

    public SettingsRepository(Path configPath) {
        this(configPath, new SettingsValidator(), SettingsRepository::defaultAtomicMove);
    }

    public SettingsRepository(
            Path configPath,
            SettingsValidator validator,
            RuleRepository.AtomicFileMover fileMover
    ) {
        this.configPath = Objects.requireNonNull(configPath, "configPath must not be null")
                .toAbsolutePath().normalize();
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.fileMover = Objects.requireNonNull(fileMover, "fileMover must not be null");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static Path defaultSettingsPath() {
        return DatabaseManager.defaultAppDataDirectory().resolve("settings.json");
    }

    public Path configPath() {
        return configPath;
    }

    public SettingsLoadResult load() throws IOException {
        if (!Files.exists(configPath)) {
            AppSettings defaults = AppSettings.defaults();
            save(defaults);
            return SettingsLoadResult.normal(defaults);
        }

        try {
            SettingsConfigDocument doc = mapper.readValue(configPath.toFile(), SettingsConfigDocument.class);
            if (doc == null || doc.getVersion() != SettingsConfigDocument.CURRENT_VERSION) {
                int ver = doc != null ? doc.getVersion() : -1;
                return backupAndRestoreDefaults("Unsupported settings.json version: " + ver);
            }

            AppSettings loaded = doc.toDomain();
            // Note: on load, a watched folder might be temporarily disconnected/unavailable,
            // so we still validate structural/range/protected/duplicate constraints, or full validation.
            // Wait: if a user's USB drive is unplugged when loading settings.json, should load() wipe settings.json?
            // Let's check: Part A says "same corrupt-file handling as rules.json: back it up as settings.json.bak-<timestamp>, load defaults, notify the user"
            // and Part B says "If a watched folder becomes unavailable (deleted, drive disconnected), mark its status as Unavailable, keep running, and retry every 30 seconds; resume when it is back."
            List<String> validationErrors = validateLoadedSettings(loaded);
            if (!validationErrors.isEmpty()) {
                return backupAndRestoreDefaults("Invalid settings in settings.json: " + String.join("; ", validationErrors));
            }
            return SettingsLoadResult.normal(loaded);
        } catch (Exception e) {
            return backupAndRestoreDefaults("Corrupt settings.json (" + e.getMessage() + ")");
        }
    }

    public void save(AppSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings must not be null");
        List<String> errors = validator.validate(settings);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Cannot save invalid settings: " + String.join("; ", errors));
        }

        Path parent = configPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path tempFile = configPath.resolveSibling(configPath.getFileName().toString() + ".tmp");
        SettingsConfigDocument doc = SettingsConfigDocument.fromDomain(settings);
        mapper.writeValue(tempFile.toFile(), doc);
        fileMover.move(tempFile, configPath);
    }

    public AppSettings resetToDefaults() throws IOException {
        AppSettings defaults = AppSettings.defaults();
        save(defaults);
        return defaults;
    }

    private List<String> validateLoadedSettings(AppSettings settings) {
        return validator.validate(settings);
    }

    private SettingsLoadResult backupAndRestoreDefaults(String reason) throws IOException {
        String backupFileName = configPath.getFileName().toString() + ".bak-" + System.currentTimeMillis();
        Path backupPath = configPath.resolveSibling(backupFileName);
        Files.move(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

        AppSettings defaults = AppSettings.defaults();
        save(defaults);

        String notice = reason + ". The original file was backed up to "
                + backupPath.getFileName() + " and default settings were loaded.";
        return SettingsLoadResult.recovered(defaults, backupPath, notice);
    }

    private static void defaultAtomicMove(Path tempFile, Path targetFile) throws IOException {
        try {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
