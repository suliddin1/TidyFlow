package com.tidyflow.data.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.rules.Rule;
import com.tidyflow.core.rules.RuleValidator;
import com.tidyflow.data.DatabaseManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Persists and loads user rules in %APPDATA%\TidyFlow\rules.json using atomic writes
 * and automatic backup recovery when the file is corrupt or has an unknown version.
 */
public class RuleRepository {

    @FunctionalInterface
    public interface AtomicFileMover {
        void move(Path tempFile, Path targetFile) throws IOException;
    }

    private final Path configPath;
    private final RuleValidator validator;
    private final ObjectMapper mapper;
    private final AtomicFileMover fileMover;

    public RuleRepository() {
        this(defaultRulesPath());
    }

    public RuleRepository(Path configPath) {
        this(configPath, new RuleValidator(), RuleRepository::defaultAtomicMove);
    }

    public RuleRepository(Path configPath, RuleValidator validator, AtomicFileMover fileMover) {
        this.configPath = Objects.requireNonNull(configPath, "configPath must not be null").toAbsolutePath().normalize();
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.fileMover = Objects.requireNonNull(fileMover, "fileMover must not be null");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static Path defaultRulesPath() {
        return DatabaseManager.defaultAppDataDirectory().resolve("rules.json");
    }

    public Path configPath() {
        return configPath;
    }

    public RuleLoadResult load() throws IOException {
        if (!Files.exists(configPath)) {
            List<Rule> defaults = DefaultRules.create();
            save(defaults);
            return RuleLoadResult.normal(defaults);
        }

        try {
            RulesConfigDocument doc = mapper.readValue(configPath.toFile(), RulesConfigDocument.class);
            if (doc == null || doc.getVersion() != RulesConfigDocument.CURRENT_VERSION) {
                int ver = doc != null ? doc.getVersion() : -1;
                return backupAndRestoreDefaults("Unsupported rules.json version: " + ver);
            }

            List<Rule> loaded = doc.toDomain();
            List<String> validationErrors = validator.validateAll(loaded);
            if (!validationErrors.isEmpty()) {
                return backupAndRestoreDefaults("Invalid rules in rules.json: " + String.join("; ", validationErrors));
            }
            return RuleLoadResult.normal(loaded);
        } catch (Exception e) {
            return backupAndRestoreDefaults("Corrupt rules.json (" + e.getMessage() + ")");
        }
    }

    public void save(List<Rule> rules) throws IOException {
        Objects.requireNonNull(rules, "rules must not be null");
        List<String> errors = validator.validateAll(rules);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Cannot save invalid rules: " + String.join("; ", errors));
        }

        Path parent = configPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path tempFile = configPath.resolveSibling(configPath.getFileName().toString() + ".tmp");
        RulesConfigDocument doc = RulesConfigDocument.fromDomain(rules);
        mapper.writeValue(tempFile.toFile(), doc);
        fileMover.move(tempFile, configPath);
    }

    public List<Rule> resetToDefaults() throws IOException {
        List<Rule> defaults = DefaultRules.create();
        save(defaults);
        return defaults;
    }

    private RuleLoadResult backupAndRestoreDefaults(String reason) throws IOException {
        String backupFileName = configPath.getFileName().toString() + ".bak-" + System.currentTimeMillis();
        Path backupPath = configPath.resolveSibling(backupFileName);
        Files.move(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

        List<Rule> defaults = DefaultRules.create();
        save(defaults);

        String notice = reason + ". The original file was backed up to "
                + backupPath.getFileName() + " and default rules were loaded.";
        return RuleLoadResult.recovered(defaults, backupPath, notice);
    }

    private static void defaultAtomicMove(Path tempFile, Path targetFile) throws IOException {
        try {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
