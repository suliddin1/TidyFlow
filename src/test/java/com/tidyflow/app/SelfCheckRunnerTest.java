package com.tidyflow.app;

import com.tidyflow.app.SelfCheckRunner.SelfCheckResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfCheckRunnerTest {

    @Test
    void passesOnHealthyTempDirectory(@TempDir Path tempDir) {
        Path workDir = tempDir.resolve("healthy-data");

        SelfCheckResult result = SelfCheckRunner.runChecks(workDir);

        assertTrue(result.passed(), () -> "Expected self-check to pass, got:\n" + String.join("\n", result.lines()));
        assertEquals(0, result.exitCode());
        assertTrue(result.lines().contains("OK version"));
        assertTrue(result.lines().contains("OK sqlite"));
        assertTrue(result.lines().contains("OK settings"));
        assertTrue(result.lines().contains("OK classes"));
        assertEquals("RESULT OK", result.lines().get(result.lines().size() - 1));
    }

    @Test
    void reportsFailureWithoutThrowingWhenDataDirectoryIsUnwritable(@TempDir Path tempDir) throws Exception {
        // Creating a regular file where a directory is expected makes child paths unwritable across OSes.
        Path blockerFile = Files.writeString(tempDir.resolve("not-a-directory"), "locked");
        Path unwritableDataDir = blockerFile.resolve("nested");

        SelfCheckResult result = SelfCheckRunner.runChecks(unwritableDataDir);

        assertFalse(result.passed());
        assertEquals(1, result.exitCode());
        assertTrue(result.lines().stream().anyMatch(line -> line.startsWith("FAIL sqlite:")));
        assertTrue(result.lines().stream().anyMatch(line -> line.startsWith("FAIL settings:")));
        assertEquals("RESULT FAIL", result.lines().get(result.lines().size() - 1));
    }

    @Test
    void executeWritesReportFileAndNeverTouchesAppData(@TempDir Path tempDir) throws Exception {
        Path realAppData = com.tidyflow.data.DatabaseManager.defaultAppDataDirectory();
        boolean existedBefore = Files.exists(realAppData);
        var entriesBefore = existedBefore ? snapshotDirectory(realAppData) : java.util.Map.<Path, java.nio.file.attribute.FileTime>of();

        Path outFile = tempDir.resolve("out").resolve("selfcheck.txt");

        int exitCode = SelfCheckRunner.execute(outFile);

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outFile));
        List<String> fileLines = Files.readAllLines(outFile);
        assertFalse(fileLines.isEmpty());
        assertEquals("RESULT OK", fileLines.get(fileLines.size() - 1));

        assertEquals(existedBefore, Files.exists(realAppData));
        if (existedBefore) {
            assertEquals(entriesBefore, snapshotDirectory(realAppData));
        }
    }

    private static java.util.Map<Path, java.nio.file.attribute.FileTime> snapshotDirectory(Path dir) throws Exception {
        var map = new java.util.HashMap<Path, java.nio.file.attribute.FileTime>();
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                map.put(entry, Files.getLastModifiedTime(entry));
            }
        }
        return map;
    }
}
