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
    void smallFilesEightKbOrLessAreOpenedAtMostOnceUsingCountingHasher() throws Exception {
        java.util.concurrent.ConcurrentHashMap<Path, java.util.concurrent.atomic.AtomicInteger> openCounts =
                new java.util.concurrent.ConcurrentHashMap<>();

        DuplicateFinder.FileHashStrategy countingStrategy = new DuplicateFinder.FileHashStrategy() {
            @Override
            public String computeQuickHash(Path path, long sizeBytes, java.util.function.BooleanSupplier isCancelled)
                    throws java.io.IOException {
                openCounts.computeIfAbsent(path, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                return FileHasher.computeQuickHash(path, sizeBytes, isCancelled);
            }

            @Override
            public String computeFullSha256(
                    Path path,
                    java.util.function.BooleanSupplier isCancelled,
                    java.util.function.LongConsumer bytesReadCallback
            ) throws java.io.IOException {
                openCounts.computeIfAbsent(path, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                return FileHasher.computeFullSha256(path, isCancelled, bytesReadCallback);
            }
        };

        DuplicateFinder countingFinder = new DuplicateFinder(
                indexRepo,
                DuplicateFinder.DEFAULT_CACHE_FLUSH_BATCH_SIZE,
                DuplicateFinder.DEFAULT_CACHE_FLUSH_INTERVAL_MILLIS,
                countingStrategy
        );

        // 1) Duplicate pair <= 8 KB (exact 8 KB boundary)
        byte[] dup8k = new byte[8 * 1024];
        for (int i = 0; i < dup8k.length; i++) {
            dup8k[i] = (byte) (i % 251);
        }
        Path s1 = Files.write(scanDir.resolve("small_dup_a.bin"), dup8k).toAbsolutePath().normalize();
        Path s2 = Files.write(scanDir.resolve("small_dup_b.bin"), dup8k).toAbsolutePath().normalize();

        // 2) Same-size non-duplicate pair <= 8 KB (3 KB)
        byte[] diff3kA = new byte[3 * 1024];
        byte[] diff3kB = new byte[3 * 1024];
        diff3kA[100] = 11;
        diff3kB[100] = 22;
        Path s3 = Files.write(scanDir.resolve("small_diff_a.bin"), diff3kA).toAbsolutePath().normalize();
        Path s4 = Files.write(scanDir.resolve("small_diff_b.bin"), diff3kB).toAbsolutePath().normalize();

        // 3) Duplicate pair > 8 KB (16 KB) -> requires quick hash + full hash (2 opens)
        byte[] dup16k = new byte[16 * 1024];
        for (int i = 0; i < dup16k.length; i++) {
            dup16k[i] = (byte) ((i * 7) & 0xFF);
        }
        Path m1 = Files.write(scanDir.resolve("med_dup_a.bin"), dup16k).toAbsolutePath().normalize();
        Path m2 = Files.write(scanDir.resolve("med_dup_b.bin"), dup16k).toAbsolutePath().normalize();

        DedupeScanResult result = countingFinder.findDuplicates(scanDir, DedupeScanOptions.defaults());

        assertEquals(2, result.groupCount());
        assertEquals(1, openCounts.get(s1).get(), "8 KB duplicate must be opened at most once");
        assertEquals(1, openCounts.get(s2).get(), "8 KB duplicate must be opened at most once");
        assertEquals(1, openCounts.get(s3).get(), "3 KB non-duplicate must be opened at most once");
        assertEquals(1, openCounts.get(s4).get(), "3 KB non-duplicate must be opened at most once");
        assertEquals(2, openCounts.get(m1).get(), "16 KB duplicate (> 8 KB) should be opened in Stage 2 and Stage 3");
        assertEquals(2, openCounts.get(m2).get(), "16 KB duplicate (> 8 KB) should be opened in Stage 2 and Stage 3");

        FileIndexEntry cachedS1 = indexRepo.findByPath(s1).orElseThrow();
        assertNotNull(cachedS1.quickHash());
        assertEquals(cachedS1.quickHash(), cachedS1.sha256());
    }

    @Test
    void producesIdenticalDuplicateResultsAcrossSmallMediumAndLargeFiles() throws Exception {
        Path subA = Files.createDirectories(scanDir.resolve("subA"));
        Path subB = Files.createDirectories(scanDir.resolve("subB"));

        // Small duplicates (2 KB) + small same-size non-duplicate
        byte[] smallDup = new byte[2 * 1024];
        byte[] smallDecoy = new byte[2 * 1024];
        for (int i = 0; i < smallDup.length; i++) {
            smallDup[i] = (byte) (i & 0x7F);
            smallDecoy[i] = (byte) ((i + 1) & 0x7F);
        }
        Files.write(subA.resolve("s_dup1.dat"), smallDup);
        Files.write(subB.resolve("s_dup2.dat"), smallDup);
        Files.write(subB.resolve("s_decoy.dat"), smallDecoy);

        // Medium duplicates (128 KB) + decoy with identical 4 KB head/tail but different middle
        byte[] medDup = new byte[128 * 1024];
        for (int i = 0; i < medDup.length; i++) {
            medDup[i] = (byte) ((i * 31) & 0xFF);
        }
        byte[] medDecoy = medDup.clone();
        medDecoy[64 * 1024] ^= 0x55;
        Files.write(subA.resolve("m_dup1.dat"), medDup);
        Files.write(subB.resolve("m_dup2.dat"), medDup);
        Files.write(subB.resolve("m_decoy.dat"), medDecoy);

        // Large duplicates (2 MB) + unique file
        byte[] largeDup = new byte[2 * 1024 * 1024];
        for (int i = 0; i < largeDup.length; i += 4096) {
            largeDup[i] = (byte) ((i >> 12) & 0xFF);
        }
        Files.write(subA.resolve("l_dup1.dat"), largeDup);
        Files.write(subB.resolve("l_dup2.dat"), largeDup);
        Files.write(subA.resolve("unique.dat"), new byte[9_999]);

        DedupeScanResult result = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());

        assertEquals(3, result.groupCount());
        assertEquals(3, result.duplicateFileCount());
        long expectedWasted = (2L * 1024L) + (128L * 1024L) + (2L * 1024L * 1024L);
        assertEquals(expectedWasted, result.wastedBytes());
        // Sorted descending by wastedBytes: large (2 MB), medium (128 KB), small (2 KB)
        assertEquals(2L * 1024L * 1024L, result.groups().get(0).sizeBytes());
        assertEquals(128L * 1024L, result.groups().get(1).sizeBytes());
        assertEquals(2L * 1024L, result.groups().get(2).sizeBytes());
    }

    @Test
    void incrementalCachePersistenceSavesHashesBeforeCancelAndReusesThemOnNextScan() throws Exception {
        // Use a small batch flush threshold (2 files) and single thread (HDD mode) for deterministic ordering
        DuplicateFinder incrementalFinder = new DuplicateFinder(indexRepo, 2, 1L);
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
