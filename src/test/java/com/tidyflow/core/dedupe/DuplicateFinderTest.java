package com.tidyflow.core.dedupe;

import com.tidyflow.data.DatabaseManager;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Identical content with different names (in root and subfolder)
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
        // 16 KB files: first 4 KB and last 4 KB are identical, middle differs
        byte[] content1 = new byte[16 * 1024];
        byte[] content2 = new byte[16 * 1024];
        for (int i = 0; i < content1.length; i++) {
            content1[i] = (byte) (i & 0x3F);
            content2[i] = (byte) (i & 0x3F);
        }
        content1[8192] = 0x11;
        content2[8192] = 0x22;

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
        Path f1 = Files.writeString(scanDir.resolve("one.txt"), "cached-content-xyz");
        Path f2 = Files.writeString(scanDir.resolve("two.txt"), "cached-content-xyz");

        // First scan computes hashes from disk
        DedupeScanResult firstScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(1, firstScan.groupCount());
        assertEquals(0, firstScan.cacheHits());
        assertTrue(firstScan.filesHashed() > 0);

        // Second scan with unchanged files reuses cached quick_hash and sha256 without hashing
        DedupeScanResult secondScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(1, secondScan.groupCount());
        assertEquals(0, secondScan.filesHashed());
        assertEquals(4, secondScan.cacheHits()); // 2 quick hash hits + 2 full sha256 hits

        // Modify mtime on f1 -> f1 must be re-hashed while f2 still hits cache
        Files.setLastModifiedTime(f1, FileTime.from(Instant.now().plusSeconds(120)));
        DedupeScanResult thirdScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(1, thirdScan.groupCount());
        assertEquals(2, thirdScan.filesHashed()); // f1 quick hash + f1 full hash
        assertEquals(2, thirdScan.cacheHits());   // f2 quick hash + f2 full hash

        // Change size/content of f2 -> no longer duplicates
        Files.writeString(f2, "completely-different-and-longer-content");
        DedupeScanResult fourthScan = finder.findDuplicates(scanDir, DedupeScanOptions.defaults());
        assertEquals(0, fourthScan.groupCount());
    }

    @Test
    void cancellingScanStopsQuicklyAndLeavesNoPartialCacheState() throws Exception {
        byte[] payload = new byte[128 * 1024];
        Files.write(scanDir.resolve("big1.bin"), payload);
        Files.write(scanDir.resolve("big2.bin"), payload);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);

        assertThrows(CancellationException.class, () -> finder.findDuplicates(
                scanDir,
                DedupeScanOptions.defaults(),
                progress -> {
                    if ("Quick hashing".equals(progress.stageName())) {
                        cancelFlag.set(true);
                    }
                },
                cancelFlag::get
        ));

        // Cache table must remain empty when cancelled before completion
        assertTrue(indexRepo.findAllEntries().isEmpty());
    }
}
