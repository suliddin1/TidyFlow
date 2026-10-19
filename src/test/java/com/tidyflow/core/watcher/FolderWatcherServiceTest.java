package com.tidyflow.core.watcher;

import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.config.WatchMode;
import com.tidyflow.data.config.WatchedFolderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderWatcherServiceTest {

    @TempDir
    Path tempDir;

    private FolderScanner scanner;
    private FileExecutor executor;
    private AutomationPipeline pipeline;
    private FileReadinessTracker readinessTracker;
    private FolderWatcherService watcherService;
    private Path watchedDir;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("tidyflow.db"));
        dbManager.migrate();
        BatchRepository batchRepo = new BatchRepository(dbManager);
        OperationRepository opRepo = new OperationRepository(dbManager);

        scanner = new FolderScanner();
        executor = new FileExecutor(batchRepo, opRepo, tempDir.resolve("fallback.log"));
        pipeline = new AutomationPipeline(executor, DefaultRules::create);
        readinessTracker = new FileReadinessTracker(
                Clock.systemDefaultZone(),
                new NioFileStatProvider(),
                Duration.ofMillis(200)
        );
        watcherService = new FolderWatcherService(
                scanner,
                executor,
                pipeline,
                readinessTracker,
                Clock.systemDefaultZone(),
                Duration.ofMillis(80),
                Duration.ofMillis(120)
        );
        watchedDir = Files.createDirectories(tempDir.resolve("watched"));
    }

    @AfterEach
    void tearDown() {
        if (watcherService != null) {
            watcherService.close();
        }
    }

    @Test
    void createdFileIsDetectedBecomesReadyAfterSettleAndAutoMoves() throws Exception {
        watcherService.updateConfiguration(
                List.of(new WatchedFolderConfig(watchedDir, WatchMode.AUTO, true)),
                Duration.ofMillis(200)
        );
        watcherService.start();

        Path created = Files.writeString(watchedDir.resolve("photo.jpg"), "image-bytes");
        Path expectedDest = watchedDir.resolve("Images").resolve("photo.jpg");

        assertTrue(awaitCondition(() -> Files.exists(expectedDest) && !Files.exists(created), Duration.ofSeconds(5)));
    }

    @Test
    void selfMoveEventsAreSuppressed() throws Exception {
        watcherService.updateConfiguration(
                List.of(new WatchedFolderConfig(watchedDir, WatchMode.ASK, true)),
                Duration.ofMillis(200)
        );
        watcherService.start();

        Path selfMovedFile = watchedDir.resolve("self-moved.pdf");
        executor.recordSelfMove(selfMovedFile);
        Files.writeString(selfMovedFile, "pdf-bytes");

        // Poll briefly to let WatchService deliver the ENTRY_CREATE event
        Thread.sleep(350);
        assertEquals(0, readinessTracker.trackedCount());
        assertTrue(pipeline.recentActivities().isEmpty());
    }

    @Test
    void overflowRescanDetectsPreExistingFiles() throws Exception {
        // Create file before watcher starts so no ENTRY_CREATE event fires for it
        Path preExisting = Files.writeString(watchedDir.resolve("manual.pdf"), "pdf-content");
        Path expectedDest = watchedDir.resolve("Documents").resolve("manual.pdf");

        watcherService.updateConfiguration(
                List.of(new WatchedFolderConfig(watchedDir, WatchMode.AUTO, true)),
                Duration.ofMillis(200)
        );
        watcherService.start();

        // Trigger overflow rescan
        watcherService.rescanFolder(watchedDir);

        assertTrue(awaitCondition(() -> Files.exists(expectedDest) && !Files.exists(preExisting), Duration.ofSeconds(5)));
    }

    @Test
    void unavailableWatchedFolderRecoversWhenRecreated() throws Exception {
        Path dynamicFolder = tempDir.resolve("usb-drive-downloads");
        // Configure before folder exists -> immediately marked Unavailable
        watcherService.updateConfiguration(
                List.of(new WatchedFolderConfig(dynamicFolder, WatchMode.AUTO, true)),
                Duration.ofMillis(200)
        );
        watcherService.start();

        assertEquals(WatchedFolderStatus.UNAVAILABLE, watcherService.statusFor(dynamicFolder));

        // Recreate the folder and drop a file inside -> watcher recovers and organizes it
        Files.createDirectories(dynamicFolder);
        assertTrue(awaitCondition(
                () -> watcherService.statusFor(dynamicFolder) == WatchedFolderStatus.WATCHING,
                Duration.ofSeconds(5)
        ));

        Path archive = Files.writeString(dynamicFolder.resolve("backup.zip"), "zip-data");
        Path expectedDest = dynamicFolder.resolve("Archives").resolve("backup.zip");
        assertTrue(awaitCondition(() -> Files.exists(expectedDest) && !Files.exists(archive), Duration.ofSeconds(5)));

        // Delete the empty folder (after deleting the Archives subfolder) and verify it transitions back to Unavailable
        Files.deleteIfExists(expectedDest);
        Files.deleteIfExists(dynamicFolder.resolve("Archives"));
        Files.deleteIfExists(dynamicFolder);

        assertTrue(awaitCondition(
                () -> watcherService.statusFor(dynamicFolder) == WatchedFolderStatus.UNAVAILABLE,
                Duration.ofSeconds(5)
        ));
    }

    private static boolean awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(30);
        }
        return condition.getAsBoolean();
    }
}
