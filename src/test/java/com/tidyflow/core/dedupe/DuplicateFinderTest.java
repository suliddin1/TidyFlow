package com.tidyflow.core.dedupe;

import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.FileIndexEntry;
import com.tidyflow.data.FileIndexRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateFinderTest {

    @TempDir
    Path tempDir;

    private Path scanDir;
    private FileIndexRepository indexRepo;
    private DuplicateFinder finder;

    @BeforeEach
    void setUp() throws Exception {
        scanDir = Files.createDirectories(tempDir.resolve("scan"));
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("tidyflow.db"));
        dbManager.migrate();
        indexRepo = new FileIndexRepository(dbManager);
        finder = new DuplicateFinder(indexRepo);
    }

    @Test
    void groupsIdenticalContentAndSeparatesDifferentContentAndIgnoresZeroByteFiles() throws Exception {
        Path subDir = Files.createDirectories(scanDir.resolve("nested"));
        Files.writeString(scanDir.resolve("original.txt"), "duplicate-payload-12345");
        Files.writeString(subDir.resolve("copy.txt"), "duplicate-payload-12345");

        // Same size (23 bytes) with different content -> must NOT be grouped
        Files.writeString(scanDir.resolve("different.txt"), "different-payload-99999");

        // Zero-byte files -> must be ignored even if multiple exist
        Files.write(scanDir.resolve("empty1.txt"), new byte[0]);
        Files.write(scanDir.resolve("empty2.txt"), new byte[0]);

        DedupeScanResult result = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());

        assertEquals(1, result.groupCount());
        assertEquals(1, result.duplicateFileCount());
        assertEquals(23L, result.wastedBytes());
        assertEquals(2, result.groups().get(0).files().size());
    }

    @Test
    void separatesFilesWithSameFirstAndLast4KbButDifferentMiddleContent() throws Exception {
        // 128 KB files (> 64 KB): first 4 KB and last 4 KB are identical, middle differs
        byte[] content1 = new byte[128 * 1024];
        byte[] content2 = new byte[128 * 1024];
        for (int i = 0; i < content1.length; i++) {
            content1[i] = (byte) (i & 0x3F);
            content2[i] = (byte) (i & 0x3F);
        }
        content1[64 * 1024] = 0x11;
        content2[64 * 1024] = 0x22;

        Files.write(scanDir.resolve("middle_a.bin"), content1);
        Files.write(scanDir.resolve("middle_b.bin"), content2);

        DedupeScanResult result = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());

        assertEquals(0, result.groupCount());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void unreadableLockedFileIsSkippedAndCountedWithoutCrashing() throws Exception {
        Files.writeString(scanDir.resolve("valid1.txt"), "same-size-content");
        Files.writeString(scanDir.resolve("valid2.txt"), "same-size-content");
        Path lockedPath = Files.writeString(scanDir.resolve("locked.txt"), "same-size-content");

        // Acquire an exclusive mandatory Windows file lock so reading locked.txt throws IOException
        try (FileChannel channel = FileChannel.open(lockedPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {

            DedupeScanResult result = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());

            assertEquals(1, result.skippedUnreadableCount());
            assertEquals(1, result.groupCount());
            assertEquals(2, result.groups().get(0).files().size());
        }
    }

    @Test
    void secondScanReusesCachedHashesAndRehashesWhenMtimeOrSizeChanges() throws Exception {
        // Use > 64 KB files so both quick_hash and sha256 stages execute and cache
        byte[] payload = new byte[96 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0x7F);
        }
        Path f1 = Files.write(scanDir.resolve("one.bin"), payload);
        Path f2 = Files.write(scanDir.resolve("two.bin"), payload);

        // First scan computes hashes from disk
        DedupeScanResult firstScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(1, firstScan.groupCount());
        assertEquals(0, firstScan.cacheHits());
        assertEquals(4, firstScan.filesHashed()); // 2 quick hashes + 2 full hashes

        // Second scan with unchanged files reuses cached quick_hash and sha256 without hashing
        DedupeScanResult secondScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(1, secondScan.groupCount());
        assertEquals(0, secondScan.filesHashed());
        assertEquals(4, secondScan.cacheHits());

        // Modify mtime on f1 -> f1 must be re-hashed while f2 still hits cache
        Files.setLastModifiedTime(f1, FileTime.from(Instant.now().plusSeconds(120)));
        DedupeScanResult thirdScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(1, thirdScan.groupCount());
        assertEquals(2, thirdScan.filesHashed()); // f1 quick hash + f1 full hash
        assertEquals(2, thirdScan.cacheHits());   // f2 quick hash + f2 full hash

        // Change size/content of f2 -> no longer duplicates
        Files.writeString(f2, "completely-different-and-shorter-content");
        DedupeScanResult fourthScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(0, fourthScan.groupCount());
    }

    @Test
    void smallFilesUpTo64KbGoStraightToFullHashStageAndSkipQuickHash() throws Exception {
        byte[] smallPayload = new byte[16 * 1024]; // 16 KB <= 64 KB
        for (int i = 0; i < smallPayload.length; i++) {
            smallPayload[i] = (byte) (i % 251);
        }
        Path s1 = Files.write(scanDir.resolve("small_a.bin"), smallPayload);
        Path s2 = Files.write(scanDir.resolve("small_b.bin"), smallPayload);

        List<String> stagesSeen = new ArrayList<>();
        DedupeScanResult result = finder.findDuplicates(
                scanDir,
                DedupeScanOptions.defaults(),
                p -> {
                    synchronized (stagesSeen) {
                        if (!stagesSeen.contains(p.stageName())) {
                            stagesSeen.add(p.stageName());
                        }
                    }
                },
                () -> false
        );

        assertEquals(1, result.groupCount());
        assertEquals(2, result.filesHashed()); // Only 1 full hash per file, no quick hash
        assertTrue(!stagesSeen.contains("Quick hashing"), "Small files <= 64 KB should skip Quick hashing");
        assertTrue(stagesSeen.contains("Full SHA-256 hashing"));

        FileIndexEntry cached = indexRepo.findByPath(s1).orElseThrow();
        assertNull(cached.quickHash());
        assertNotNull(cached.sha256());
        assertNotNull(indexRepo.findByPath(s2).orElseThrow().sha256());
    }

    @Test
    void incrementalCachePersistenceSavesHashesBeforeCancelAndReusesThemOnNextScan() throws Exception {
        // Use a small batch flush threshold (2 files) and single thread (HDD mode) for deterministic ordering
        DuplicateFinder incrementalFinder = new DuplicateFinder(indexRepo, 2, 20_000L);
        byte[] payload = new byte[16 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        Files.write(scanDir.resolve("a_first.bin"), payload);
        Files.write(scanDir.resolve("b_second.bin"), payload);
        Files.write(scanDir.resolve("c_third.bin"), payload);
        Files.write(scanDir.resolve("d_fourth.bin"), payload);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        DedupeScanOptions hddOptions = new DedupeScanOptions(
                true,
                1L,
                true,
                KeeperStrategy.OLDEST_MODIFIED,
                FolderExclusionFilter.defaults(),
                DiskType.HDD
        );

        assertThrows(CancellationException.class, () -> incrementalFinder.findDuplicates(
                scanDir,
                hddOptions,
                progress -> {
                    if ("Full SHA-256 hashing".equals(progress.stageName()) && progress.processedCount() >= 2) {
                        cancelFlag.set(true);
                    }
                },
                cancelFlag::get
        ));

        // Hashes computed before cancellation must be persisted in file_index
        List<FileIndexEntry> savedAfterCancel = indexRepo.findAllEntries();
        assertTrue(savedAfterCancel.size() >= 2,
                "Expected at least 2 hashes persisted before cancel, found: " + savedAfterCancel.size());

        // Second scan must reuse the persisted hashes
        DedupeScanResult secondScan = incrementalFinder.findDuplicates(scanDir, hddOptions);
        assertEquals(1, secondScan.groupCount());
        assertEquals(3, secondScan.duplicateFileCount());
        assertTrue(secondScan.cacheHits() >= 2,
                "Expected second scan to reuse cached hashes from cancelled scan, cacheHits=" + secondScan.cacheHits());
    }

    @Test
    void cancellingDuringLargeFileFullHashStopsWithinTwoSeconds() throws Exception {
        // Generate two 200 MB files with identical head and tail so full hashing is triggered
        int megabyte = 1024 * 1024;
        int totalSize = 200 * megabyte;
        byte[] chunk = new byte[megabyte];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i & 0xFF);
        }
        Path big1 = scanDir.resolve("large1.bin");
        Path big2 = scanDir.resolve("large2.bin");
        for (Path p : List.of(big1, big2)) {
            try (var out = Files.newOutputStream(p)) {
                for (int m = 0; m < 200; m++) {
                    out.write(chunk);
                }
            }
        }

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        long[] cancelTriggeredAtNanos = {0L};

        assertThrows(CancellationException.class, () -> finder.findDuplicates(
                scanDir,
                DedupeScanOptions.defaults(),
                progress -> {
                    if ("Full SHA-256 hashing".equals(progress.stageName()) && progress.processedBytes() > 0) {
                        if (cancelTriggeredAtNanos[0] == 0L) {
                            cancelTriggeredAtNanos[0] = System.nanoTime();
                        }
                        cancelFlag.set(true);
                    }
                },
                cancelFlag::get
        ));

        long stopLatencyMs = cancelTriggeredAtNanos[0] > 0L
                ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelTriggeredAtNanos[0])
                : 0L;
        assertTrue(stopLatencyMs < 2_000L,
                "Cancellation during large file hashing took too long: " + stopLatencyMs + " ms");
    }
}
