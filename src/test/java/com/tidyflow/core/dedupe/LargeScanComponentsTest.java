package com.tidyflow.core.dedupe;

import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.FileIndexRepository;
import com.tidyflow.data.config.AppSettings;
import com.tidyflow.data.config.SettingsLoadResult;
import com.tidyflow.data.config.SettingsRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeScanComponentsTest {

    @TempDir
    Path tempDir;

    @Test
    void folderExclusionFilterMatchesDefaultNamesCaseInsensitivelyAndCustomPaths() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path nodeModules = Files.createDirectories(root.resolve("NODE_MODULES"));
        Path gitDir = Files.createDirectories(root.resolve(".Git"));
        Path nestedVenv = Files.createDirectories(root.resolve("project").resolve("Sub").resolve(".venv"));
        Path customExcludedPath = Files.createDirectories(root.resolve("archive").resolve("old-backups"));
        Path customNamedDir = Files.createDirectories(root.resolve("project").resolve("MY_CACHE"));
        Path includedDir = Files.createDirectories(root.resolve("project").resolve("src"));

        List<String> names = new ArrayList<>(FolderExclusionFilter.DEFAULT_EXCLUDED_NAMES);
        names.add(" my_cache ");
        FolderExclusionFilter filter = new FolderExclusionFilter(
                names,
                List.of(customExcludedPath.toString())
        );

        assertTrue(filter.isExcluded(nodeModules));
        assertTrue(filter.isExcluded(gitDir));
        assertTrue(filter.isExcluded(nestedVenv));
        assertTrue(filter.isExcluded(customExcludedPath));
        assertTrue(filter.isExcluded(customExcludedPath.resolve("deep-child")));
        assertTrue(filter.isExcluded(customNamedDir));
        assertFalse(filter.isExcluded(includedDir));

        // Verify DuplicateFinder never enters excluded folders and counts skipped excluded folders
        Files.writeString(nodeModules.resolve("dup1.txt"), "identical-excluded-content");
        Files.writeString(gitDir.resolve("dup2.txt"), "identical-excluded-content");
        Files.writeString(customExcludedPath.resolve("dup3.txt"), "identical-excluded-content");
        Files.writeString(includedDir.resolve("keep1.txt"), "identical-included-content");
        Files.writeString(includedDir.resolve("keep2.txt"), "identical-included-content");

        DedupeScanOptions options = new DedupeScanOptions(
                true,
                1L,
                true,
                KeeperStrategy.OLDEST_MODIFIED,
                filter,
                DiskType.AUTO
        );
        DedupeScanResult result = new DuplicateFinder().findDuplicates(root, options);

        assertEquals(1, result.groupCount());
        assertEquals(2, result.groups().get(0).files().size());
        assertTrue(result.skippedExcludedCount() >= 4,
                "Expected at least 4 excluded folders skipped, got: " + result.skippedExcludedCount());
    }

    @Test
    void minSizePresetsAndLegacySettingsJsonBackwardCompatibility() throws Exception {
        assertEquals(1L, MinSizePreset.ANY.minSizeBytes());
        assertEquals(100L * 1024L, MinSizePreset.KB_100.minSizeBytes());
        assertEquals(1024L * 1024L, MinSizePreset.MB_1.minSizeBytes());
        assertEquals(10L * 1024L * 1024L, MinSizePreset.MB_10.minSizeBytes());
        assertEquals(100L * 1024L * 1024L, MinSizePreset.MB_100.minSizeBytes());
        assertEquals(MinSizePreset.MB_1, MinSizePreset.fromString(null));
        assertEquals(MinSizePreset.MB_10, MinSizePreset.fromString("10 MB"));

        // Write a legacy v1 settings.json that lacks all new duplicate-scan fields
        Path settingsPath = tempDir.resolve("settings.json");
        String legacyJson = """
                {
                  "version": 1,
                  "watchedFolders": [],
                  "settleSeconds": 5,
                  "massMoveGuardThreshold": 50,
                  "showNotifications": true,
                  "minimizeToTray": true,
                  "startWithWindows": false,
                  "schedule": {
                    "enabled": false,
                    "timeOfDay": "18:00"
                  },
                  "theme": "SYSTEM"
                }
                """;
        Files.writeString(settingsPath, legacyJson, StandardCharsets.UTF_8);

        SettingsRepository repo = new SettingsRepository(settingsPath);
        SettingsLoadResult loaded = repo.load();
        assertFalse(loaded.recoveredFromBackup());
        AppSettings settings = loaded.settings();
        assertEquals(MinSizePreset.MB_1, settings.dedupeMinSizePreset());
        assertEquals(DiskType.AUTO, settings.dedupeDiskType());
        assertEquals(FolderExclusionFilter.DEFAULT_EXCLUDED_NAMES, settings.dedupeExcludedFolderNames());
        assertTrue(settings.dedupeExcludedPaths().isEmpty());
        assertFalse(settings.suppressDriveRootWarning());

        // Save updated dedupe preferences and reload
        AppSettings updated = settings.withDedupePreferences(
                MinSizePreset.MB_100,
                DiskType.SSD,
                List.of("node_modules", "custom_build"),
                List.of("D:\\Games"),
                true
        );
        repo.save(updated);

        AppSettings reloaded = repo.load().settings();
        assertEquals(MinSizePreset.MB_100, reloaded.dedupeMinSizePreset());
        assertEquals(DiskType.SSD, reloaded.dedupeDiskType());
        assertEquals(List.of("node_modules", "custom_build"), reloaded.dedupeExcludedFolderNames());
        assertEquals(List.of("D:\\Games"), reloaded.dedupeExcludedPaths());
        assertTrue(reloaded.suppressDriveRootWarning());
    }

    @Test
    void walkerTerminatesOnJunctionLoopAndSkipsReparsePoints() throws Exception {
        Path tree = Files.createDirectories(tempDir.resolve("junction-test"));
        Files.writeString(tree.resolve("a.txt"), "junction-test-payload");
        Files.writeString(tree.resolve("b.txt"), "junction-test-payload");

        Path sub = Files.createDirectories(tree.resolve("sub"));
        Path junction = sub.resolve("loop_back");
        int exit = runCmd("cmd", "/c", "mklink", "/J", junction.toString(), tree.toString());
        Assumptions.assumeTrue(exit == 0, "Skipping junction loop assertion because mklink /J is unavailable");

        try {
            DedupeScanResult result = new DuplicateFinder().findDuplicates(tree, DedupeScanOptions.defaults());
            assertEquals(1, result.groupCount());
            assertEquals(2, result.groups().get(0).files().size());
            boolean anyViaJunction = result.groups().stream()
                    .flatMap(g -> g.files().stream())
                    .anyMatch(f -> f.path().toString().contains("loop_back"));
            assertFalse(anyViaJunction, "Walker must never follow NTFS junction reparse points");
        } finally {
            runCmd("cmd", "/c", "rmdir", junction.toString());
        }
    }

    @Test
    void candidateOrderingByPathIsCaseInsensitiveSequential() {
        DuplicateFinder.CandidateFile c1 = new DuplicateFinder.CandidateFile(
                Path.of("D:\\z_folder\\file.bin"), 1024L, java.time.Instant.now());
        DuplicateFinder.CandidateFile c2 = new DuplicateFinder.CandidateFile(
                Path.of("D:\\A_folder\\file.bin"), 1024L, java.time.Instant.now());
        DuplicateFinder.CandidateFile c3 = new DuplicateFinder.CandidateFile(
                Path.of("D:\\b_folder\\file.bin"), 1024L, java.time.Instant.now());

        List<DuplicateFinder.CandidateFile> list = new ArrayList<>(List.of(c1, c2, c3));
        list.sort(DuplicateFinder.CANDIDATE_PATH_COMPARATOR);

        assertEquals(c2.path(), list.get(0).path());
        assertEquals(c3.path(), list.get(1).path());
        assertEquals(c1.path(), list.get(2).path());
    }

    @Test
    void progressThrottlerEnforcesMaxFiveUpdatesPerSecondWithFixedClock() {
        AtomicLong clockMs = new AtomicLong(1_000L);
        ProgressThrottler throttler = new ProgressThrottler(200L, clockMs::get);

        // First event in stage always emits
        assertTrue(throttler.shouldEmit("Scanning files", false));
        // 50 ms and 199 ms later -> suppressed
        clockMs.set(1_050L);
        assertFalse(throttler.shouldEmit("Scanning files", false));
        clockMs.set(1_199L);
        assertFalse(throttler.shouldEmit("Scanning files", false));
        // 200 ms later -> emitted
        clockMs.set(1_200L);
        assertTrue(throttler.shouldEmit("Scanning files", false));

        // Stage change emits immediately even if < 200 ms elapsed
        clockMs.set(1_250L);
        assertTrue(throttler.shouldEmit("Quick hashing", false));

        // Forced event emits immediately even if < 200 ms elapsed
        clockMs.set(1_260L);
        assertTrue(throttler.shouldEmit("Quick hashing", true));
    }

    @Test
    void smoothedEtaCalculatorRequiresEnoughSamplesAndNeverReturnsNegativeOrInfiniteValues() {
        AtomicLong clockMs = new AtomicLong(0L);
        SmoothedEtaCalculator eta = new SmoothedEtaCalculator(3, 600L, 0.30, clockMs::get);
        eta.start();

        long totalBytes = 100L * 1024L * 1024L; // 100 MB

        // Sample 1 at 200 ms (10 MB) -> not enough samples or elapsed time
        clockMs.set(200L);
        SmoothedEtaCalculator.EtaSnapshot s1 = eta.update(10L * 1024L * 1024L, totalBytes);
        assertTrue(s1.etaSeconds().isEmpty(), "Must not show ETA after only 1 sample");
        assertTrue(Double.isFinite(s1.throughputMbPerSec()) && s1.throughputMbPerSec() >= 0.0);

        // Sample 2 at 400 ms (20 MB) -> still < 3 samples
        clockMs.set(400L);
        SmoothedEtaCalculator.EtaSnapshot s2 = eta.update(20L * 1024L * 1024L, totalBytes);
        assertTrue(s2.etaSeconds().isEmpty(), "Must not show ETA after only 2 samples");

        // Sample 3 at 600 ms (30 MB, ~50 MB/s, 70 MB remaining -> ~1s ETA)
        clockMs.set(600L);
        SmoothedEtaCalculator.EtaSnapshot s3 = eta.update(30L * 1024L * 1024L, totalBytes);
        assertTrue(s3.etaSeconds().isPresent(), "ETA should be available after 3 samples and 600 ms");
        assertTrue(s3.etaSeconds().getAsLong() >= 0L);
        assertTrue(s3.throughputMbPerSec() > 40.0 && s3.throughputMbPerSec() < 60.0);

        // Edge case: bytesProcessed exceeds totalBytes -> clamped to 0 remaining, never negative
        clockMs.set(1_000L);
        SmoothedEtaCalculator.EtaSnapshot sDone = eta.update(totalBytes + 5000L, totalBytes);
        assertEquals(0L, sDone.etaSeconds().orElse(-1L));
    }

    @Test
    void driveRootDetectorIdentifiesDriveRootsAndRejectsSubfolders() {
        assertTrue(DriveRootDetector.isDriveRoot(Path.of("D:\\")));
        assertTrue(DriveRootDetector.isDriveRoot(Path.of("D:")));
        assertTrue(DriveRootDetector.isDriveRoot(Path.of("C:\\")));
        assertTrue(DriveRootDetector.isDriveRoot("E:/"));
        assertTrue(DriveRootDetector.isDriveRoot(Path.of("\\\\server\\share\\")));

        assertFalse(DriveRootDetector.isDriveRoot(Path.of("C:\\Users")));
        assertFalse(DriveRootDetector.isDriveRoot(Path.of("D:\\Projects\\UniFlow")));
        assertFalse(DriveRootDetector.isDriveRoot((Path) null));
        assertFalse(DriveRootDetector.isDriveRoot(""));
    }

    private static int runCmd(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }
}
