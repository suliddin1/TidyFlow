package com.tidyflow.app;

import com.tidyflow.data.BatchRecord;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import com.tidyflow.data.config.AppSettings;
import com.tidyflow.data.config.ScheduleConfig;
import com.tidyflow.data.config.SettingsLoadResult;
import com.tidyflow.data.config.SettingsRepository;
import com.tidyflow.data.config.ThemeMode;
import com.tidyflow.util.AppVersion;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Headless verification runner for packaged builds (--self-check).
 * Never opens windows, never creates a tray icon, never acquires the single-instance lock,
 * and never touches %APPDATA%\TidyFlow.
 */
public final class SelfCheckRunner {

    private static final List<String> REQUIRED_CLASSES = List.of(
            "javafx.scene.control.TableView",
            "javafx.fxml.FXMLLoader",
            "java.awt.SystemTray",
            "org.sqlite.JDBC"
    );

    public record SelfCheckResult(boolean passed, List<String> lines) {
        public SelfCheckResult {
            lines = List.copyOf(lines);
        }

        public int exitCode() {
            return passed ? 0 : 1;
        }
    }

    private SelfCheckRunner() {
    }

    /**
     * Runs all checks inside a fresh temporary directory (deleted afterwards) and writes the report
     * to {@code outFile} (if non-null) as well as stdout. Returns 0 on success or 1 on failure.
     */
    public static int execute(Path outFile) {
        Path tempDir = null;
        SelfCheckResult result;
        try {
            tempDir = Files.createTempDirectory("tidyflow-selfcheck-");
            result = runChecks(tempDir);
        } catch (Throwable t) {
            List<String> lines = List.of(
                    "FAIL temp-dir: " + sanitizeReason(t),
                    "RESULT FAIL"
            );
            result = new SelfCheckResult(false, lines);
        } finally {
            if (tempDir != null) {
                deleteRecursivelyQuietly(tempDir);
            }
        }

        for (String line : result.lines()) {
            System.out.println(line);
        }

        if (outFile != null) {
            try {
                Path normalizedOut = outFile.toAbsolutePath().normalize();
                Path parent = normalizedOut.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                String content = String.join(System.lineSeparator(), result.lines()) + System.lineSeparator();
                Files.writeString(normalizedOut, content, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                System.err.println("Failed to write self-check output file: " + sanitizeReason(t));
                return 1;
            }
        }

        return result.exitCode();
    }

    /**
     * Runs all self-checks using {@code dataDir} for temporary SQLite and settings I/O.
     * Never throws; captures any failure into the returned {@link SelfCheckResult}.
     */
    public static SelfCheckResult runChecks(Path dataDir) {
        List<String> lines = new ArrayList<>();
        boolean allOk = true;

        allOk &= runSingleCheck(lines, "version", SelfCheckRunner::checkVersion);
        allOk &= runSingleCheck(lines, "sqlite", () -> checkSqlite(dataDir));
        allOk &= runSingleCheck(lines, "settings", () -> checkSettings(dataDir));
        allOk &= runSingleCheck(lines, "classes", SelfCheckRunner::checkKeyClasses);

        lines.add(allOk ? "RESULT OK" : "RESULT FAIL");
        return new SelfCheckResult(allOk, lines);
    }

    private static boolean runSingleCheck(List<String> lines, String name, CheckStep step) {
        try {
            step.run();
            lines.add("OK " + name);
            return true;
        } catch (Throwable t) {
            lines.add("FAIL " + name + ": " + sanitizeReason(t));
            return false;
        }
    }

    private static void checkVersion() {
        URL res = AppVersion.class.getResource(AppVersion.DEFAULT_RESOURCE_PATH);
        if (res == null) {
            throw new IllegalStateException("Missing resource " + AppVersion.DEFAULT_RESOURCE_PATH);
        }
        String version = AppVersion.load();
        if (version == null || version.isBlank() || AppVersion.FALLBACK_VERSION.equals(version)) {
            throw new IllegalStateException("Unresolved application version: " + version);
        }
    }

    private static void checkSqlite(Path dataDir) throws Exception {
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        Path dbPath = dataDir.resolve("selfcheck.db");
        DatabaseManager dbManager = new DatabaseManager(dbPath);
        dbManager.migrate();

        BatchRepository batchRepo = new BatchRepository(dbManager);
        OperationRepository opRepo = new OperationRepository(dbManager);

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OperationRecord sampleOp = OperationRecord.unsaved(
                "SelfCheckRule",
                dataDir.resolve("source.txt"),
                dataDir.resolve("dest.txt"),
                128L,
                OperationStatus.SUCCESS,
                null,
                now
        );
        long batchId = batchRepo.createBatchWithOperations(
                "MANUAL",
                now,
                now.plusSeconds(1),
                1,
                128L,
                List.of(sampleOp),
                opRepo
        );

        List<BatchRecord> batches = batchRepo.findAllNewestFirst();
        if (batches.size() != 1 || batches.get(0).id() != batchId) {
            throw new IllegalStateException("Unexpected batch read result after SQLite write");
        }
        List<OperationRecord> ops = opRepo.findByBatchId(batchId);
        if (ops.size() != 1 || !"SelfCheckRule".equals(ops.get(0).ruleName())) {
            throw new IllegalStateException("Unexpected operation read result after SQLite write");
        }
    }

    private static void checkSettings(Path dataDir) throws Exception {
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        Path settingsPath = dataDir.resolve("settings.json");
        SettingsRepository repo = new SettingsRepository(settingsPath);

        SettingsLoadResult initialLoad = repo.load();
        if (initialLoad.recoveredFromBackup()) {
            throw new IllegalStateException("Initial settings load unexpectedly triggered backup recovery");
        }

        AppSettings custom = new AppSettings(
                List.of(),
                10,
                75,
                false,
                true,
                false,
                new ScheduleConfig(true, "08:30"),
                ThemeMode.DARK
        );
        repo.save(custom);

        SettingsLoadResult reloaded = repo.load();
        if (reloaded.recoveredFromBackup() || !custom.equals(reloaded.settings())) {
            throw new IllegalStateException("Reloaded settings did not match saved settings");
        }
    }

    private static void checkKeyClasses() throws ClassNotFoundException {
        ClassLoader loader = SelfCheckRunner.class.getClassLoader();
        for (String className : REQUIRED_CLASSES) {
            // initialize = false loads and links the class bytecode without starting a GUI display toolkit
            Class.forName(className, false, loader);
        }
    }

    private static String sanitizeReason(Throwable t) {
        String message = t.getMessage();
        String base = (message == null || message.isBlank())
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + " - " + message;
        return base.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    private interface CheckStep {
        void run() throws Exception;
    }
}
