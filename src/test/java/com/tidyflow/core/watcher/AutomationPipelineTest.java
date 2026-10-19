package com.tidyflow.core.watcher;

import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.executor.UndoManager;
import com.tidyflow.core.executor.UndoSummary;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.scanner.FileItem;
import com.tidyflow.data.BatchRecord;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.config.WatchMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationPipelineTest {

    @TempDir
    Path tempDir;

    private BatchRepository batchRepo;
    private OperationRepository opRepo;
    private FileExecutor executor;
    private UndoManager undoManager;
    private AutomationPipeline pipeline;
    private Path watchedDir;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("tidyflow.db"));
        dbManager.migrate();
        batchRepo = new BatchRepository(dbManager);
        opRepo = new OperationRepository(dbManager);
        executor = new FileExecutor(batchRepo, opRepo, tempDir.resolve("fallback.log"));
        undoManager = new UndoManager(opRepo);
        pipeline = new AutomationPipeline(executor, DefaultRules::create);
        watchedDir = Files.createDirectories(tempDir.resolve("Downloads"));
    }

    @Test
    void autoModeExecutesMovesRecordsBatchOfKindAutoAndSupportsUndo() throws Exception {
        Path pdf = Files.writeString(watchedDir.resolve("invoice.pdf"), "pdf-data");
        FileItem item = new FileItem(pdf, "invoice.pdf", "pdf", Files.size(pdf), Instant.now());

        FlushOutcome outcome = pipeline.flush(watchedDir, WatchMode.AUTO, List.of(item), "AUTO");

        assertTrue(outcome.executedAutoMove());
        assertEquals(1, outcome.movedCount());
        assertFalse(Files.exists(pdf));
        assertTrue(Files.exists(watchedDir.resolve("Documents").resolve("invoice.pdf")));

        List<BatchRecord> batches = batchRepo.findAllNewestFirst();
        assertEquals(1, batches.size());
        assertEquals("AUTO", batches.get(0).kind());

        UndoSummary undo = undoManager.undoBatch(batches.get(0).id());
        assertEquals(1, undo.restored());
        assertTrue(Files.exists(pdf));
    }

    @Test
    void askModeMovesNothingAndEmitsFilesReadyEvent() throws Exception {
        Path jpg = Files.writeString(watchedDir.resolve("photo.jpg"), "img");
        FileItem item = new FileItem(jpg, "photo.jpg", "jpg", Files.size(jpg), Instant.now());

        AtomicReference<Path> readyFolder = new AtomicReference<>();
        AtomicInteger readyCount = new AtomicInteger();
        pipeline.addListener(new AutomationListener() {
            @Override
            public void onFilesReadyForReview(Path folder, int count, boolean triggeredByGuard, int guardThreshold) {
                readyFolder.set(folder);
                readyCount.set(count);
            }
        });

        FlushOutcome outcome = pipeline.flush(watchedDir, WatchMode.ASK, List.of(item), "AUTO");

        assertFalse(outcome.executedAutoMove());
        assertTrue(outcome.emittedAskReadyEvent());
        assertFalse(outcome.downgradedByMassMoveGuard());
        assertTrue(Files.exists(jpg));
        assertEquals(watchedDir.toAbsolutePath().normalize(), readyFolder.get());
        assertEquals(1, readyCount.get());
        assertTrue(batchRepo.findAllNewestFirst().isEmpty());
    }

    @Test
    void moreFilesThanMassMoveGuardThresholdDowngradesAutoToAsk() throws Exception {
        pipeline.setMassMoveGuardThreshold(5);
        List<FileItem> items = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            Path f = Files.writeString(watchedDir.resolve("doc" + i + ".pdf"), "data");
            items.add(new FileItem(f, f.getFileName().toString(), "pdf", 4L, Instant.now()));
        }

        AtomicReference<Boolean> guardFlag = new AtomicReference<>(false);
        pipeline.addListener(new AutomationListener() {
            @Override
            public void onFilesReadyForReview(Path folder, int count, boolean triggeredByGuard, int guardThreshold) {
                guardFlag.set(triggeredByGuard);
            }
        });

        FlushOutcome outcome = pipeline.flush(watchedDir, WatchMode.AUTO, items, "AUTO");

        assertFalse(outcome.executedAutoMove());
        assertTrue(outcome.emittedAskReadyEvent());
        assertTrue(outcome.downgradedByMassMoveGuard());
        assertTrue(guardFlag.get());
        assertNotNull(pipeline.recentActivities().get(0));
        assertTrue(pipeline.recentActivities().get(0).message().startsWith("Guard:"));
        assertTrue(batchRepo.findAllNewestFirst().isEmpty());
    }

    @Test
    void pausedStateDoesNothing() throws Exception {
        pipeline.setPaused(true);
        Path pdf = Files.writeString(watchedDir.resolve("report.pdf"), "pdf");
        FileItem item = new FileItem(pdf, "report.pdf", "pdf", 3L, Instant.now());

        FlushOutcome outcome = pipeline.flush(watchedDir, WatchMode.AUTO, List.of(item), "AUTO");

        assertTrue(outcome.skippedBecausePaused());
        assertTrue(Files.exists(pdf));
        assertTrue(batchRepo.findAllNewestFirst().isEmpty());
    }
}
