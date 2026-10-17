package com.tidyflow.core.executor;

import com.tidyflow.core.rules.PlannedOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExecutorTest {

    private final FileExecutor executor = new FileExecutor();

    @Test
    void movesFileAndCreatesMissingTargetDirectory(@TempDir Path tempDir) throws Exception {
        Path source = Files.writeString(tempDir.resolve("report.pdf"), "pdf-content");
        Path destination = tempDir.resolve("Documents").resolve("report.pdf");

        List<OperationResult> results = executor.execute(List.of(
                new PlannedOperation(source, destination, "Documents")
        ));

        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals(destination, results.get(0).actualDestination());
        assertFalse(Files.exists(source));
        assertEquals("pdf-content", Files.readString(destination));
    }

    @Test
    void resolvesFilenameConflictsWithoutOverwriting(@TempDir Path tempDir) throws Exception {
        Path targetDir = Files.createDirectories(tempDir.resolve("Images"));
        Path existingOriginal = Files.writeString(targetDir.resolve("photo.jpg"), "original");
        Path existingFirstCopy = Files.writeString(targetDir.resolve("photo (1).jpg"), "copy-1");

        Path incoming = Files.writeString(tempDir.resolve("photo.jpg"), "copy-2");
        Path plannedDestination = targetDir.resolve("photo.jpg");

        List<OperationResult> results = executor.execute(List.of(
                new PlannedOperation(incoming, plannedDestination, "Images")
        ));

        Path expectedResolved = targetDir.resolve("photo (2).jpg");
        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals(expectedResolved, results.get(0).actualDestination());

        // Existing files must remain untouched
        assertEquals("original", Files.readString(existingOriginal));
        assertEquals("copy-1", Files.readString(existingFirstCopy));
        assertEquals("copy-2", Files.readString(expectedResolved));
    }

    @Test
    void refusesToTouchProtectedDirectories(@TempDir Path tempDir) throws Exception {
        Path validFile = Files.writeString(tempDir.resolve("installer.exe"), "binary");

        PlannedOperation intoWindows = new PlannedOperation(
                validFile,
                Path.of("C:\\Windows\\System32\\installer.exe"),
                "ProtectedRule"
        );
        PlannedOperation fromProgramFiles = new PlannedOperation(
                Path.of("C:\\Program Files\\SomeApp\\app.exe"),
                tempDir.resolve("Installers").resolve("app.exe"),
                "ProtectedRule"
        );

        List<OperationResult> results = executor.execute(List.of(intoWindows, fromProgramFiles));

        assertEquals(2, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).reason().toLowerCase().contains("protected"));
        assertTrue(Files.exists(validFile));

        assertFalse(results.get(1).success());
        assertTrue(results.get(1).reason().toLowerCase().contains("protected"));
    }

    @Test
    void singleFailureDoesNotStopRemainingOperations(@TempDir Path tempDir) throws Exception {
        Path missingSource = tempDir.resolve("missing.txt");
        Path validSource = Files.writeString(tempDir.resolve("notes.txt"), "hello");

        PlannedOperation failingOp = new PlannedOperation(
                missingSource,
                tempDir.resolve("Documents").resolve("missing.txt"),
                "Documents"
        );
        PlannedOperation succeedingOp = new PlannedOperation(
                validSource,
                tempDir.resolve("Documents").resolve("notes.txt"),
                "Documents"
        );

        List<OperationResult> results = executor.execute(List.of(failingOp, succeedingOp));

        assertEquals(2, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(1).success());
        assertTrue(Files.exists(tempDir.resolve("Documents").resolve("notes.txt")));
    }
}
