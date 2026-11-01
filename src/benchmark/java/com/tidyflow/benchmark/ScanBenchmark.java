package com.tidyflow.benchmark;

import com.tidyflow.core.dedupe.DedupeProgress;
import com.tidyflow.core.dedupe.DedupeScanOptions;
import com.tidyflow.core.dedupe.DedupeScanResult;
import com.tidyflow.core.dedupe.DuplicateFinder;
import com.tidyflow.core.dedupe.FileHasher;
import com.tidyflow.core.dedupe.KeeperStrategy;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.FileIndexRepository;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dev-only performance benchmark for the duplicate scan and related I/O paths.
 * Not shipped in the application JAR and not part of {@code gradlew test}; run with {@code gradlew benchmark}.
 * Generates synthetic data in a temp folder, prints a results table, and deletes the data afterwards.
 *
 * Note: data is freshly written, so most reads are served from the OS file cache. A temp folder on an SSD
 * cannot reproduce hard-disk seek behavior, so HDD thread counts cannot be decided from these numbers.
 */
public final class ScanBenchmark {

    private static final int SMALL_FILES = 200_000;
    private static final int SMALL_DISTINCT_SIZES = 2_000;
    private static final int SMALL_FILES_PER_DIR = 500;
    private static final int MEDIUM_TRIPLES = 100;
    private static final int FLAT_FILES = 10_000;
    private static final int BATCH_OPERATIONS = 5_000;
    private static final long ONE_GB = 1024L * 1024L * 1024L;
    private static final long MB = 1024L * 1024L;
    private static final long STALL_TIMEOUT_SECONDS = 120;

    private final Map<String, String> results = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    private ScanBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String label = args.length > 0 ? args[0] : "run";
        Path outDir = args.length > 1 ? Path.of(args[1]) : Path.of("build", "benchmark");
        new ScanBenchmark().run(label, outDir);
    }

    private void run(String label, Path outDir) throws Exception {
        Path root = Files.createTempDirectory("tidyflow-bench-");
        log("Benchmark root: " + root + "  (label=" + label + ")");
        try {
            benchmarkDuplicateScan(root);
            benchmarkFolderScanner(root);
            benchmarkBatchInsert(root);
            benchmarkBufferSizes(root);
            benchmarkThreadCounts(root);
            benchmarkStallTree(root);
        } finally {
            log("Cleaning up " + root);
            cleanup(root);
        }

        StringBuilder table = new StringBuilder();
        table.append(String.format("%n=== TidyFlow benchmark (%s) ===%n", label));
        table.append(String.format("%-52s | %s%n", "Measurement", "Result"));
        table.append("-".repeat(110)).append(System.lineSeparator());
        results.forEach((k, v) -> table.append(String.format("%-52s | %s%n", k, v)));
        table.append(System.lineSeparator()).append("Notes:").append(System.lineSeparator());
        for (String note : notes) {
            table.append(" - ").append(note).append(System.lineSeparator());
        }
        System.out.println(table);

        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("results-" + label + ".txt");
        Files.writeString(outFile, table.toString(), StandardCharsets.UTF_8);
        log("Results written to " + outFile.toAbsolutePath());
    }

    // ---------------------------------------------------------------- 1. Duplicate scan

    private void benchmarkDuplicateScan(Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("dedupe"));
        long genStart = System.nanoTime();
        long smallBytes = generateSmallFiles(data.resolve("small"));
        long mediumBytes = generateMediumFiles(data.resolve("medium"));
        long largeBytes = generateLargeFiles(data.resolve("large"));
        log(String.format("Generated dedupe data in %.1f s", seconds(System.nanoTime() - genStart)));
        results.put("1. Test data", String.format("%,d small (%s) + %d medium (%s) + 5 large (%s)",
                SMALL_FILES, mb(smallBytes), MEDIUM_TRIPLES * 3, mb(mediumBytes), mb(largeBytes)));

        DatabaseManager db = new DatabaseManager(root.resolve("dedupe-cache.db"));
        db.migrate();
        FileIndexRepository cache = new FileIndexRepository(db);

        ScanTimings cold = timedScan(new DuplicateFinder(cache), data);
        results.put("1a. Dedupe scan (cold cache) total", fmtMs(cold.totalNanos)
                + String.format("  -> %d groups, %d dup files", cold.result.groupCount(), cold.result.duplicateFileCount()));
        results.put("1b.   directory walk", fmtMs(cold.walkNanos) + pct(cold.walkNanos, cold.totalNanos)
                + String.format("  (%,d candidates)", cold.walkCount));
        results.put("1c.   size grouping + cache load", fmtMs(cold.groupNanos) + pct(cold.groupNanos, cold.totalNanos));
        results.put("1d.   quick hash", fmtMs(cold.quickNanos) + pct(cold.quickNanos, cold.totalNanos)
                + String.format("  (%,d files)", cold.quickTotal));
        results.put("1e.   full hash", fmtMs(cold.fullNanos) + pct(cold.fullNanos, cold.totalNanos)
                + String.format("  (%,d files)", cold.fullTotal));
        results.put("1f.   cache persist + grouping tail", fmtMs(cold.tailNanos) + pct(cold.tailNanos, cold.totalNanos));
        results.put("1g.   progress events / longest gap", String.format("%,d events, longest gap %s",
                cold.events, fmtMs(cold.maxGapNanos)));

        ScanTimings warm = timedScan(new DuplicateFinder(cache), data);
        results.put("1h. Dedupe scan (warm cache) total", fmtMs(warm.totalNanos)
                + String.format("  (cache hits %,d, hashed %,d)", warm.result.cacheHits(), warm.result.filesHashed()));
        deleteTree(data);
    }

    private ScanTimings timedScan(DuplicateFinder finder, Path data) {
        ScanTimings t = new ScanTimings();
        Map<String, Long> firstEvent = new LinkedHashMap<>();
        Map<String, Long> lastEvent = new LinkedHashMap<>();
        Map<String, Integer> lastTotal = new LinkedHashMap<>();
        AtomicLong lastNanos = new AtomicLong(System.nanoTime());
        AtomicLong maxGap = new AtomicLong(0);
        AtomicInteger events = new AtomicInteger();

        long start = System.nanoTime();
        lastNanos.set(start);
        DedupeScanOptions options = new DedupeScanOptions(true, 1L, true, KeeperStrategy.OLDEST_MODIFIED);
        t.result = finder.findDuplicates(data, options, (DedupeProgress p) -> {
            long now = System.nanoTime();
            long prev = lastNanos.getAndSet(now);
            maxGap.accumulateAndGet(now - prev, Math::max);
            events.incrementAndGet();
            synchronized (firstEvent) {
                firstEvent.putIfAbsent(p.stageName(), now);
                lastEvent.put(p.stageName(), now);
                lastTotal.put(p.stageName(), p.totalCount());
            }
        }, () -> false);
        long end = System.nanoTime();

        t.totalNanos = end - start;
        long walkEnd = lastEvent.getOrDefault("Scanning files", start);
        long quickStart = firstEvent.getOrDefault("Quick hashing", walkEnd);
        long quickEnd = lastEvent.getOrDefault("Quick hashing", quickStart);
        long fullStart = firstEvent.getOrDefault("Full SHA-256 hashing", quickEnd);
        long fullEnd = lastEvent.getOrDefault("Full SHA-256 hashing", fullStart);
        t.walkNanos = walkEnd - start;
        t.groupNanos = quickStart - walkEnd;
        t.quickNanos = quickEnd - quickStart;
        t.fullNanos = fullEnd - fullStart;
        t.tailNanos = end - fullEnd - (fullStart - quickEnd);
        t.tailNanos += 0;
        t.walkCount = lastTotal.getOrDefault("Scanning files", 0);
        t.quickTotal = lastTotal.getOrDefault("Quick hashing", 0);
        t.fullTotal = lastTotal.getOrDefault("Full SHA-256 hashing", 0);
        t.events = events.get();
        t.maxGapNanos = Math.max(maxGap.get(), end - lastNanos.get());
        return t;
    }

    private static final class ScanTimings {
        DedupeScanResult result;
        long totalNanos;
        long walkNanos;
        long groupNanos;
        long quickNanos;
        long fullNanos;
        long tailNanos;
        int walkCount;
        int quickTotal;
        int fullTotal;
        int events;
        long maxGapNanos;
    }

    private long generateSmallFiles(Path dir) throws IOException {
        long total = 0;
        byte[] buf = new byte[8192];
        for (int i = 0; i < SMALL_FILES; i++) {
            Path sub = dir.resolve(String.format("d%03d", i / SMALL_FILES_PER_DIR));
            if (i % SMALL_FILES_PER_DIR == 0) {
                Files.createDirectories(sub);
            }
            int size = 100 + 2 * (i % SMALL_DISTINCT_SIZES);
            // Every 50th file duplicates the file created SMALL_DISTINCT_SIZES earlier (same size by construction)
            long seed = (i % 50 == 0 && i >= SMALL_DISTINCT_SIZES) ? i - SMALL_DISTINCT_SIZES : i;
            fill(buf, size, seed);
            try (OutputStream out = Files.newOutputStream(sub.resolve("f" + i + ".dat"))) {
                out.write(buf, 0, size);
            }
            total += size;
        }
        return total;
    }

    /** Triples of equal size: A and B identical, C with same head/tail but a different middle. */
    private long generateMediumFiles(Path dir) throws IOException {
        Files.createDirectories(dir);
        SplittableRandom rnd = new SplittableRandom(42);
        long total = 0;
        for (int t = 0; t < MEDIUM_TRIPLES; t++) {
            int size = (int) (512 * 1024 + rnd.nextInt(2_560 * 1024));
            byte[] content = new byte[size];
            fill(content, size, 1_000_000L + t);
            Files.write(dir.resolve("m" + t + "_a.bin"), content);
            Files.write(dir.resolve("m" + t + "_b.bin"), content);
            content[size / 2] ^= 0x5A;
            Files.write(dir.resolve("m" + t + "_c.bin"), content);
            total += 3L * size;
        }
        return total;
    }

    /** Two duplicate pairs plus a decoy with identical head/tail to force full hashing. */
    private long generateLargeFiles(Path dir) throws IOException {
        Files.createDirectories(dir);
        long total = 0;
        total += writeLarge(dir.resolve("big1_a.bin"), 200 * MB, 7, -1);
        total += writeLarge(dir.resolve("big1_b.bin"), 200 * MB, 7, -1);
        total += writeLarge(dir.resolve("big2_a.bin"), 120 * MB, 9, -1);
        total += writeLarge(dir.resolve("big2_b.bin"), 120 * MB, 9, -1);
        total += writeLarge(dir.resolve("big2_decoy.bin"), 120 * MB, 9, 60 * MB);
        return total;
    }

    private long writeLarge(Path file, long size, long seed, long flipAt) throws IOException {
        byte[] chunk = new byte[(int) MB];
        try (OutputStream out = Files.newOutputStream(file)) {
            for (long off = 0; off < size; off += MB) {
                fill(chunk, chunk.length, seed * 100_000 + off / MB);
                if (flipAt >= off && flipAt < off + MB) {
                    chunk[(int) (flipAt - off)] ^= 0x33;
                }
                out.write(chunk, 0, (int) Math.min(MB, size - off));
            }
        }
        return size;
    }

    private static void fill(byte[] buf, int len, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int i = 0;
        while (i + 8 <= len) {
            long v = r.nextLong();
            for (int b = 0; b < 8; b++) {
                buf[i++] = (byte) (v >>> (b * 8));
            }
        }
        while (i < len) {
            buf[i++] = (byte) r.nextInt(256);
        }
    }

    // ---------------------------------------------------------------- 2. FolderScanner

    private void benchmarkFolderScanner(Path root) throws Exception {
        Path flat = Files.createDirectories(root.resolve("flat10k"));
        byte[] payload = "flat-file".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < FLAT_FILES; i++) {
            Files.write(flat.resolve("file" + i + (i % 3 == 0 ? ".pdf" : ".jpg")), payload);
        }
        FolderScanner scanner = new FolderScanner();
        scanner.scan(flat); // warm-up
        long[] runs = new long[3];
        int count = 0;
        for (int r = 0; r < runs.length; r++) {
            long s = System.nanoTime();
            count = scanner.scan(flat).size();
            runs[r] = System.nanoTime() - s;
        }
        Arrays.sort(runs);
        results.put("2. FolderScanner, 10,000 files (median of 3)", fmtMs(runs[1]) + String.format("  (%,d files)", count));
        deleteTree(flat);
    }

    // ---------------------------------------------------------------- 3. BatchRepository

    private void benchmarkBatchInsert(Path root) throws Exception {
        long[] runs = new long[3];
        for (int r = 0; r < runs.length; r++) {
            DatabaseManager db = new DatabaseManager(root.resolve("batch-" + r + ".db"));
            db.migrate();
            BatchRepository batches = new BatchRepository(db);
            OperationRepository ops = new OperationRepository(db);
            List<OperationRecord> records = new ArrayList<>(BATCH_OPERATIONS);
            Instant now = Instant.now();
            for (int i = 0; i < BATCH_OPERATIONS; i++) {
                records.add(OperationRecord.unsaved("Rule", Path.of("C:\\src\\f" + i + ".pdf"),
                        Path.of("C:\\dst\\f" + i + ".pdf"), 1024L, OperationStatus.SUCCESS, null, now));
            }
            long s = System.nanoTime();
            batches.createBatchWithOperations("MANUAL", now, now, BATCH_OPERATIONS, 1024L * BATCH_OPERATIONS, records, ops);
            runs[r] = System.nanoTime() - s;
        }
        Arrays.sort(runs);
        results.put("3. BatchRepository, 5,000 ops (median of 3)", fmtMs(runs[1]));
    }

    // ---------------------------------------------------------------- 4. Buffer sizes

    private void benchmarkBufferSizes(Path root) throws Exception {
        Path big = root.resolve("one-gb.bin");
        writeLarge(big, ONE_GB, 11, -1);
        int[] sizes = {64 * 1024, 256 * 1024, 1024 * 1024};
        hashWithBuffer(big, sizes[0]); // warm the OS cache once
        for (int size : sizes) {
            long best = Long.MAX_VALUE;
            for (int r = 0; r < 2; r++) {
                long s = System.nanoTime();
                hashWithBuffer(big, size);
                best = Math.min(best, System.nanoTime() - s);
            }
            results.put(String.format("4. SHA-256 of 1 GB, %d KB buffer (best of 2)", size / 1024),
                    fmtMs(best) + String.format("  (%.0f MB/s)", 1024.0 / seconds(best)));
        }
        Files.deleteIfExists(big);
    }

    private static String hashWithBuffer(Path file, int bufferSize) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[bufferSize];
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    // ---------------------------------------------------------------- 5. Thread counts

    private void benchmarkThreadCounts(Path root) throws Exception {
        Path dir = root.resolve("medium-threads");
        long bytes = generateMediumFiles(dir);
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.sorted().toList();
        }
        hashAll(files, 4); // warm cache
        for (int threads : new int[]{1, 2, 4}) {
            long best = Long.MAX_VALUE;
            for (int r = 0; r < 2; r++) {
                long s = System.nanoTime();
                hashAll(files, threads);
                best = Math.min(best, System.nanoTime() - s);
            }
            results.put(String.format("5. Hash medium set (%d files, %s), %d thread(s)", files.size(), mb(bytes), threads),
                    fmtMs(best) + String.format("  (%.0f MB/s)", (bytes / (double) MB) / seconds(best)));
        }
        deleteTree(dir);
    }

    private static void hashAll(List<Path> files, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Path f : files) {
                futures.add(pool.submit(() -> FileHasher.computeFullSha256(f, () -> false)));
            }
            for (Future<String> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 6. Stall tree

    private void benchmarkStallTree(Path root) throws Exception {
        Path tree = Files.createDirectories(root.resolve("stall"));
        Files.writeString(tree.resolve("top_a.txt"), "stall-dup-content");
        Files.writeString(tree.resolve("top_b.txt"), "stall-dup-content");

        Path deep = tree;
        for (int i = 0; i < 120; i++) {
            deep = deep.resolve("n" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("deep.txt"), "stall-dup-content");

        Path unreadable = Files.createDirectories(tree.resolve("locked_dir"));
        Files.writeString(unreadable.resolve("secret.txt"), "stall-dup-content");
        boolean unreadableCreated = runCmd("icacls", unreadable.toString(), "/deny",
                System.getProperty("user.name") + ":(OI)(CI)(RD,X)") == 0;

        Path loopParent = Files.createDirectories(tree.resolve("loop"));
        Path junction = loopParent.resolve("back");
        boolean junctionCreated = runCmd("cmd", "/c", "mklink", "/J", junction.toString(), tree.toString()) == 0;

        results.put("6a. Stall tree setup", String.format("deep=120 levels, unreadable=%s, junction loop=%s",
                unreadableCreated ? "created" : "SKIPPED", junctionCreated ? "created" : "SKIPPED"));

        ExecutorService exec = Executors.newSingleThreadExecutor();
        AtomicBoolean cancel = new AtomicBoolean(false);
        try {
            long s = System.nanoTime();
            Future<DedupeScanResult> f = exec.submit(() -> new DuplicateFinder().findDuplicates(tree,
                    new DedupeScanOptions(true, 1L, true, KeeperStrategy.OLDEST_MODIFIED), p -> { }, cancel::get));
            try {
                DedupeScanResult r = f.get(STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                long followed = r.groups().stream().flatMap(g -> g.files().stream())
                        .filter(e -> e.path().toString().contains("\\back\\")).count();
                results.put("6b. Dedupe walk on stall tree", String.format("terminated in %s, %d group(s), "
                                + "%d file(s), unreadable=%d, files reached via junction=%d",
                        fmtMs(System.nanoTime() - s), r.groupCount(),
                        r.groups().stream().mapToInt(g -> g.files().size()).sum(), r.skippedUnreadableCount(), followed));
            } catch (TimeoutException te) {
                cancel.set(true);
                f.cancel(true);
                results.put("6b. Dedupe walk on stall tree", "DID NOT TERMINATE within " + STALL_TIMEOUT_SECONDS + " s");
            }
        } finally {
            exec.shutdownNow();
            if (unreadableCreated) {
                runCmd("icacls", unreadable.toString(), "/remove:d", System.getProperty("user.name"));
            }
            if (junctionCreated) {
                runCmd("cmd", "/c", "rmdir", junction.toString());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int runCmd(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void cleanup(Path root) {
        try {
            deleteTree(root);
        } catch (Exception e) {
            runCmd("cmd", "/c", "rmdir", "/s", "/q", root.toString());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static String fmtMs(long nanos) {
        double ms = nanos / 1_000_000.0;
        return ms >= 10_000 ? String.format("%,.1f s", ms / 1000.0) : String.format("%,.0f ms", ms);
    }

    private static String pct(long part, long total) {
        return total <= 0 ? "" : String.format(" (%.0f%%)", 100.0 * part / total);
    }

    private static String mb(long bytes) {
        return String.format("%,.0f MB", bytes / (double) MB);
    }

    private static void log(String msg) {
        System.out.println("[benchmark] " + msg);
    }
}
