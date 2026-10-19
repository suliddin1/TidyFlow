package com.tidyflow.data;

import com.tidyflow.core.executor.BatchExecutionResult;
import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.rules.PlannedOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryTest {

    @Test
    void runningMigrationsTwiceIsSafe(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("sub").resolve("tidyflow.db"));

        dbManager.migrate();
        dbManager.migrate();

        assertTrue(Files.exists(dbManager.dbPath()));
        BatchRepository batchRepo = new BatchRepository(dbManager);
        assertTrue(batchRepo.findAllNewestFirst().isEmpty());
    }

    @Test
    void batchAndOperationCreateReadUpdateRoundTrip(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("tidyflow.db"));
        dbManager.migrate();

        BatchRepository batchRepo = new BatchRepository(dbManager);
        OperationRepository opRepo = new OperationRepository(dbManager);

        Instant t1 = Instant.parse("2026-10-17T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-17T10:05:00Z");

        long firstBatchId = batchRepo.createBatch("MANUAL", t1);
        batchRepo.updateTotals(firstBatchId, t1.plusSeconds(2), 1, 500L);

        List<OperationRecord> secondBatchOps = List.of(
                OperationRecord.unsaved(
                        "Images",
                        Path.of("C:\\Downloads\\a.jpg"),
                        Path.of("C:\\Downloads\\Images\\a.jpg"),
                        1200L,
                        OperationStatus.SUCCESS,
                        null,
                        t2
                ),
                OperationRecord.unsaved(
                        "Documents",
                        Path.of("C:\\Downloads\\b.pdf"),
                        Path.of("C:\\Downloads\\Documents\\b.pdf"),
                        800L,
                        OperationStatus.FAILED,
                        "Access denied",
                        t2
                )
        );

        long secondBatchId = batchRepo.createBatchWithOperations(
                "MANUAL",
                t2,
                t2.plusSeconds(3),
                1,
                1200L,
                secondBatchOps,
                opRepo
        );

        // Verify newest-first ordering
        List<BatchRecord> allBatches = batchRepo.findAllNewestFirst();
        assertEquals(2, allBatches.size());
        assertEquals(secondBatchId, allBatches.get(0).id());
        assertEquals(1, allBatches.get(0).filesCount());
        assertEquals(1200L, allBatches.get(0).bytesMoved());
        assertEquals(firstBatchId, allBatches.get(1).id());

        // Verify operations for second batch
        List<OperationRecord> ops = opRepo.findByBatchId(secondBatchId);
        assertEquals(2, ops.size());

        OperationRecord firstOp = ops.get(0);
        assertEquals("Images", firstOp.ruleName());
        assertEquals(OperationStatus.SUCCESS, firstOp.status());
        assertNull(firstOp.error());
        assertNull(firstOp.undoneAt());

        // Update status to UNDONE
        Instant undoneTime = Instant.parse("2026-10-17T11:00:00Z");
        opRepo.updateStatus(firstOp.id(), OperationStatus.UNDONE, null, undoneTime);

        OperationRecord updated = opRepo.findById(firstOp.id()).orElseThrow();
        assertEquals(OperationStatus.UNDONE, updated.status());
        assertEquals(undoneTime, updated.undoneAt());
    }

    @Test
    void logsMovedFilesToFallbackFileWhenDatabaseFails(@TempDir Path tempDir) throws Exception {
        // Do NOT run migrate() so tables do not exist and DB insert fails
        DatabaseManager uninitializedDb = new DatabaseManager(tempDir.resolve("broken.db"));
        BatchRepository batchRepo = new BatchRepository(uninitializedDb);
        OperationRepository opRepo = new OperationRepository(uninitializedDb);
        Path fallbackLog = tempDir.resolve("fallback-moves.log");

        Path sourceFile = Files.writeString(tempDir.resolve("photo.jpg"), "image-data");
        Path destFile = tempDir.resolve("Images").resolve("photo.jpg");

        FileExecutor executor = new FileExecutor(batchRepo, opRepo, fallbackLog);
        BatchExecutionResult result = executor.executeBatch(
                List.of(new PlannedOperation(sourceFile, destFile, "Images")),
                "MANUAL"
        );

        assertEquals(1L, result.movedCount());
        assertFalse(result.databaseSaved());
        assertNotNull(result.databaseError());
        assertEquals(fallbackLog, result.fallbackLogPath());
        assertTrue(Files.exists(fallbackLog));
        String logContent = Files.readString(fallbackLog);
        assertTrue(logContent.contains("photo.jpg"));
    }

    @Test
    void v1DatabaseWithExistingRowsMigratesToV2WithoutDataLoss(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("upgrade.db"));

        // 1. Migrate only up to V1 and insert V1 batch + operation rows using raw SQL
        dbManager.migrateUpTo(1);
        try (java.sql.Connection conn = dbManager.openConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO batches (id, kind, started_at, finished_at, files_count, bytes_moved)
                    VALUES (10, 'MANUAL', '2026-10-17T08:00:00Z', '2026-10-17T08:00:02Z', 1, 2048);
                    """);
            stmt.execute("""
                    INSERT INTO operations (id, batch_id, rule_name, source_path, dest_path, size_bytes, status, error, created_at, undone_at)
                    VALUES (55, 10, 'Documents', 'C:\\Downloads\\spec.pdf', 'C:\\Downloads\\Documents\\spec.pdf', 2048, 'SUCCESS', NULL, '2026-10-17T08:00:01Z', NULL);
                    """);
        }

        // 2. Run full migrate() (applies V2) twice to verify idempotency and data preservation
        dbManager.migrate();
        dbManager.migrate();

        BatchRepository batchRepo = new BatchRepository(dbManager);
        OperationRepository opRepo = new OperationRepository(dbManager);

        BatchRecord preservedBatch = batchRepo.findById(10L).orElseThrow();
        assertEquals("MANUAL", preservedBatch.kind());
        assertEquals(1, preservedBatch.filesCount());
        assertEquals(2048L, preservedBatch.bytesMoved());

        List<OperationRecord> preservedOps = opRepo.findByBatchId(10L);
        assertEquals(1, preservedOps.size());
        OperationRecord op = preservedOps.get(0);
        assertEquals(55L, op.id());
        assertEquals(OperationActionType.MOVE, op.actionType());
        assertEquals("Documents", op.ruleName());
        assertEquals(OperationStatus.SUCCESS, op.status());
    }

    @Test
    void v2DatabaseMigratesToV3AndCreatesStatsIndexes(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("upgrade-v3.db"));

        // 1. Migrate up to V2 and insert sample data
        dbManager.migrateUpTo(2);
        BatchRepository batchRepo = new BatchRepository(dbManager);
        OperationRepository opRepo = new OperationRepository(dbManager);
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        batchRepo.createBatchWithOperations(
                "MANUAL",
                now,
                now,
                1,
                512L,
                List.of(OperationRecord.unsaved(
                        "Documents",
                        Path.of("C:\\a.txt"),
                        Path.of("C:\\Docs\\a.txt"),
                        512L,
                        OperationStatus.SUCCESS,
                        null,
                        now
                )),
                opRepo
        );

        // 2. Migrate to V3 (and run twice to verify idempotency)
        dbManager.migrate();
        dbManager.migrate();

        // 3. Verify V3 indexes exist in sqlite_master
        java.util.Set<String> indexNames = new java.util.HashSet<>();
        try (java.sql.Connection conn = dbManager.openConnection();
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
            while (rs.next()) {
                indexNames.add(rs.getString("name"));
            }
        }

        assertTrue(indexNames.contains("idx_operations_status_created_at"));
        assertTrue(indexNames.contains("idx_operations_action_status_created_at"));
        assertTrue(indexNames.contains("idx_batches_finished_at"));
        assertEquals(1, batchRepo.findAllNewestFirst().size());
    }
}
