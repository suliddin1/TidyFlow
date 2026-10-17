package com.tidyflow.core.dedupe;

import com.tidyflow.data.BatchRecord;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationActionType;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateRemoverTest {

    @TempDir
    Path tempDir;

    private Path scanDir;
    private Path fakeTrashDir;
    private List<Path> trashedPaths;
    private BatchRepository batchRepo;
    private OperationRepository opRepo;
    private DuplicateFinder finder;
    private DuplicateRemover remover;

    @BeforeEach
    void setUp() throws Exception {
        scanDir = Files.createDirectories(tempDir.resolve("scan"));
        fakeTrashDir = Files.createDirectories(tempDir.resolve("fake-trash"));
        trashedPaths = new ArrayList<>();

        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("tidyflow.db"));
        dbManager.migrate();
        batchRepo = new BatchRepository(dbManager);
        opRepo = new OperationRepository(dbManager);
        finder = new DuplicateFinder();

        TrashService fakeTrash = path -> {
            trashedPaths.add(path);
            Files.move(path, fakeTrashDir.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        };
        remover = new DuplicateRemover(fakeTrash, batchRepo, opRepo);
    }

    @Test
    void refusesToRemoveAllFilesOfAGroup() throws Exception {
        Path f1 = Files.writeString(scanDir.resolve("a.txt"), "identical-content");
        Path f2 = Files.writeString(scanDir.resolve("b.txt"), "identical-content");

        DedupeScanResult scanResult = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        DuplicateGroup group = scanResult.groups().get(0);

        // Attempt to select EVERY file in the group
        DuplicateGroupSelection invalidSelection = new DuplicateGroupSelection(group, Set.of(f1, f2));
        DedupeRemovalSummary summary = remover.removeSelected(List.of(invalidSelection));

        assertEquals(0, summary.removedCount());
        assertEquals(2, summary.skippedCount());
        assertEquals(0, summary.failedCount());
        assertNull(summary.batchId());
        assertTrue(trashedPaths.isEmpty());
        assertTrue(Files.exists(f1));
        assertTrue(Files.exists(f2));
    }

    @Test
    void skipsFileThatChangedAfterScan() throws Exception {
        Path keeper = Files.writeString(scanDir.resolve("keeper.txt"), "identical-content");
        Path duplicate = Files.writeString(scanDir.resolve("dup.txt"), "identical-content");

        DedupeScanResult scanResult = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        DuplicateGroup group = scanResult.groups().get(0);

        // Modify dup.txt after the scan so its size/mtime no longer matches the scan snapshot
        Files.writeString(duplicate, "modified-after-scan-with-new-bytes");

        DuplicateGroupSelection selection = new DuplicateGroupSelection(group, Set.of(duplicate));
        DedupeRemovalSummary summary = remover.removeSelected(List.of(selection));

        assertEquals(0, summary.removedCount());
        assertEquals(1, summary.skippedCount());
        assertTrue(trashedPaths.isEmpty());
        assertTrue(Files.exists(keeper));
        assertTrue(Files.exists(duplicate));
    }

    @Test
    void skipsRemovalWhenNoIntactKeeperRemainsOnDisk() throws Exception {
        Path keeper = Files.writeString(scanDir.resolve("keep.txt"), "identical-content");
        Path duplicate = Files.writeString(scanDir.resolve("remove.txt"), "identical-content");

        DedupeScanResult scanResult = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        DuplicateGroup group = scanResult.groups().get(0);

        // Simulate the keeper disappearing before removal runs (moved out of scanDir)
        Files.move(keeper, fakeTrashDir.resolve("moved-keeper.txt"));

        DuplicateGroupSelection selection = new DuplicateGroupSelection(group, Set.of(duplicate));
        DedupeRemovalSummary summary = remover.removeSelected(List.of(selection));

        assertEquals(0, summary.removedCount());
        assertEquals(1, summary.skippedCount());
        assertTrue(trashedPaths.isEmpty());
        // The duplicate must remain untouched because its keeper disappeared
        assertTrue(Files.exists(duplicate));
    }

    @Test
    void removesSelectedDuplicatesAndRecordsDedupeBatchWithTrashedOperations() throws Exception {
        Path keeper = Files.writeString(scanDir.resolve("keep.txt"), "twelve-bytes");
        Path duplicate = Files.writeString(scanDir.resolve("dup.txt"), "twelve-bytes");

        DedupeScanResult scanResult = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        DuplicateGroup group = scanResult.groups().get(0);

        DuplicateGroupSelection selection = new DuplicateGroupSelection(group, Set.of(duplicate));
        DedupeRemovalSummary summary = remover.removeSelected(List.of(selection));

        assertEquals(1, summary.removedCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(0, summary.failedCount());
        assertEquals(12L, summary.bytesFreed());
        assertNotNull(summary.batchId());
        assertEquals(List.of(duplicate.toAbsolutePath().normalize()), trashedPaths);
        assertTrue(Files.exists(keeper));

        BatchRecord batch = batchRepo.findById(summary.batchId()).orElseThrow();
        assertEquals("DEDUPE", batch.kind());
        assertEquals(1, batch.filesCount());
        assertEquals(12L, batch.bytesMoved());

        List<OperationRecord> ops = opRepo.findByBatchId(summary.batchId());
        assertEquals(1, ops.size());
        OperationRecord op = ops.get(0);
        assertEquals(OperationActionType.TRASH, op.actionType());
        assertEquals(OperationStatus.TRASHED, op.status());
        assertEquals(duplicate.toAbsolutePath().normalize(), op.sourcePath());
        assertEquals(12L, op.sizeBytes());
    }
}
