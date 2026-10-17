package com.tidyflow.core.executor;

import com.tidyflow.core.rules.PlannedOperation;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoManagerTest {

    @TempDir
    Path tempDir;

    private BatchRepository batchRepo;
    private OperationRepository opRepo;
    private FileExecutor executor;
    private UndoManager undoManager;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("test-tidyflow.db"));
        dbManager.migrate();
        batchRepo = new BatchRepository(dbManager);
        opRepo = new OperationRepository(dbManager);
        executor = new FileExecutor(batchRepo, opRepo, tempDir.resolve("fallback.log"));
        undoManager = new UndoManager(opRepo);
    }

    @Test
    void normalUndoMovesFileBackAndUpdatesDatabaseStatus() throws Exception {
        Path original = Files.writeString(tempDir.resolve("doc.pdf"), "pdf-bytes");
        Path dest = tempDir.resolve("Documents").resolve("doc.pdf");

        BatchExecutionResult execResult = executor.executeBatch(
                List.of(new PlannedOperation(original, dest, "Documents"))
        );
        long batchId = execResult.batchId();
        OperationRecord recordedOp = opRepo.findByBatchId(batchId).get(0);

        UndoSummary summary = undoManager.undoOperation(recordedOp.id());

        assertEquals(1, summary.restored());
        assertEquals(0, summary.restoredWithNewName());
        assertEquals(0, summary.failed());
        assertTrue(Files.exists(original));
        assertFalse(Files.exists(dest));
        // Target directory must not be deleted
        assertTrue(Files.isDirectory(tempDir.resolve("Documents")));

        OperationRecord afterUndo = opRepo.findById(recordedOp.id()).orElseThrow();
        assertEquals(OperationStatus.UNDONE, afterUndo.status());
        assertNotNull(afterUndo.undoneAt());
    }

    @Test
    void destinationFileMissingMarksOperationUndoFailed() throws Exception {
        Path original = Files.writeString(tempDir.resolve("gone.jpg"), "img");
        Path dest = tempDir.resolve("Images").resolve("gone.jpg");

        BatchExecutionResult execResult = executor.executeBatch(
                List.of(new PlannedOperation(original, dest, "Images"))
        );
        long batchId = execResult.batchId();
        // Simulate user deleting the moved file before undoing
        Files.delete(dest);

        UndoSummary summary = undoManager.undoBatch(batchId);

        assertEquals(0, summary.restored());
        assertEquals(0, summary.restoredWithNewName());
        assertEquals(1, summary.failed());

        OperationRecord updated = opRepo.findByBatchId(batchId).get(0);
        assertEquals(OperationStatus.UNDO_FAILED, updated.status());
        assertNotNull(updated.error());
        assertTrue(updated.error().toLowerCase().contains("no longer exists"));
    }

    @Test
    void sourcePathOccupiedRestoresWithConflictSafeName() throws Exception {
        Path original = Files.writeString(tempDir.resolve("notes.txt"), "old-version");
        Path dest = tempDir.resolve("Documents").resolve("notes.txt");

        BatchExecutionResult execResult = executor.executeBatch(
                List.of(new PlannedOperation(original, dest, "Documents"))
        );
        long batchId = execResult.batchId();

        // Create a new file at the original path so it is occupied
        Files.writeString(original, "new-version");

        UndoSummary summary = undoManager.undoBatch(batchId);

        Path conflictSafe = tempDir.resolve("notes (1).txt");
        assertEquals(0, summary.restored());
        assertEquals(1, summary.restoredWithNewName());
        assertEquals(0, summary.failed());

        assertEquals("new-version", Files.readString(original));
        assertEquals("old-version", Files.readString(conflictSafe));

        OperationRecord updated = opRepo.findByBatchId(batchId).get(0);
        assertEquals(OperationStatus.UNDONE, updated.status());
        assertNotNull(updated.undoneAt());
    }

    @Test
    void partiallyFailedBatchContinuesAndUndoesEligibleOperations() throws Exception {
        Path file1 = Files.writeString(tempDir.resolve("a.jpg"), "a");
        Path missingSource = tempDir.resolve("missing.jpg");
        Path file3 = Files.writeString(tempDir.resolve("b.jpg"), "b");

        BatchExecutionResult execResult = executor.executeBatch(List.of(
                new PlannedOperation(file1, tempDir.resolve("Images").resolve("a.jpg"), "Images"),
                new PlannedOperation(missingSource, tempDir.resolve("Images").resolve("missing.jpg"), "Images"),
                new PlannedOperation(file3, tempDir.resolve("Images").resolve("b.jpg"), "Images")
        ));
        long batchId = execResult.batchId();

        // Also delete a.jpg at destination so one undo fails while b.jpg succeeds and missing.jpg is skipped
        Files.delete(tempDir.resolve("Images").resolve("a.jpg"));

        UndoSummary summary = undoManager.undoBatch(batchId);

        assertEquals(1, summary.restored());
        assertEquals(0, summary.restoredWithNewName());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertTrue(Files.exists(file3));
    }

    @Test
    void undoingAlreadyUndoneOperationIsSkipped() throws Exception {
        Path original = Files.writeString(tempDir.resolve("archive.zip"), "zip");
        Path dest = tempDir.resolve("Archives").resolve("archive.zip");

        BatchExecutionResult execResult = executor.executeBatch(
                List.of(new PlannedOperation(original, dest, "Archives"))
        );
        long batchId = execResult.batchId();

        UndoSummary firstUndo = undoManager.undoBatch(batchId);
        assertEquals(1, firstUndo.restored());

        UndoSummary secondUndo = undoManager.undoBatch(batchId);
        assertEquals(0, secondUndo.restored());
        assertEquals(0, secondUndo.failed());
        assertEquals(1, secondUndo.skipped());
        assertTrue(Files.exists(original));
    }

    @Test
    void trashOperationsAreSkippedWithMessageAndNeverFailBatch() throws Exception {
        long batchId = batchRepo.createBatchWithOperations(
                "DEDUPE",
                java.time.Instant.now(),
                java.time.Instant.now(),
                1,
                100L,
                List.of(OperationRecord.unsavedTrash(
                        tempDir.resolve("duplicate.zip"),
                        100L,
                        OperationStatus.TRASHED,
                        null,
                        java.time.Instant.now()
                )),
                opRepo
        );

        UndoSummary summary = undoManager.undoBatch(batchId);

        assertEquals(0, summary.restored());
        assertEquals(0, summary.restoredWithNewName());
        assertEquals(0, summary.failed());
        assertEquals(1, summary.skipped());
        assertFalse(summary.messages().isEmpty());
        assertTrue(summary.messages().get(0).toLowerCase().contains("recycle bin"));
    }
}
