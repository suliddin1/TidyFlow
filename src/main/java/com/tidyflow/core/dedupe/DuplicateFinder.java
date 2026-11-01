package com.tidyflow.core.dedupe;

import com.tidyflow.data.FileIndexEntry;
import com.tidyflow.data.FileIndexRepository;
import com.tidyflow.util.PathSafety;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;

/**
 * Three-stage duplicate file detector:
 * 1) Single-stat directory walk with folder exclusions and size grouping
 * 2) Quick hash (4 KB head + 4 KB tail via positioned FileChannel reads; for files <= 8 KB,
 *    reads the whole file once and computes its full SHA-256 in that single read)
 * 3) Full streaming SHA-256 (1 MB thread-local buffer, sorted by path; reuses the Stage 2
 *    SHA-256 for files <= 8 KB without reopening them).
 */
public class DuplicateFinder {

    public static final int DEFAULT_CACHE_FLUSH_BATCH_SIZE = 500;
    public static final long DEFAULT_CACHE_FLUSH_INTERVAL_MILLIS = 20_000L;

    static final Comparator<CandidateFile> CANDIDATE_PATH_COMPARATOR =
            Comparator.comparing(c -> c.path().toString(), String.CASE_INSENSITIVE_ORDER);

    /**
     * Pluggable hashing strategy (allows tests to count file opens per path).
     */
    public interface FileHashStrategy {
        String computeQuickHash(Path path, long sizeBytes, BooleanSupplier isCancelled) throws IOException;

        String computeFullSha256(
                Path path,
                BooleanSupplier isCancelled,
                LongConsumer bytesReadCallback
        ) throws IOException;

        static FileHashStrategy defaultStrategy() {
            return new FileHashStrategy() {
                @Override
                public String computeQuickHash(Path path, long sizeBytes, BooleanSupplier isCancelled) throws IOException {
                    return FileHasher.computeQuickHash(path, sizeBytes, isCancelled);
                }

                @Override
                public String computeFullSha256(
                        Path path,
                        BooleanSupplier isCancelled,
                        LongConsumer bytesReadCallback
                ) throws IOException {
                    return FileHasher.computeFullSha256(path, isCancelled, bytesReadCallback);
                }
            };
        }
    }

    private final FileIndexRepository fileIndexRepository;
    private final int cacheFlushBatchSize;
    private final long cacheFlushIntervalNanos;
    private final FileHashStrategy hashStrategy;

    public DuplicateFinder() {
        this(null);
    }

    public DuplicateFinder(FileIndexRepository fileIndexRepository) {
        this(
                fileIndexRepository,
                DEFAULT_CACHE_FLUSH_BATCH_SIZE,
                DEFAULT_CACHE_FLUSH_INTERVAL_MILLIS,
                FileHashStrategy.defaultStrategy()
        );
    }

    public DuplicateFinder(
            FileIndexRepository fileIndexRepository,
            int cacheFlushBatchSize,
            long cacheFlushIntervalMillis
    ) {
        this(
                fileIndexRepository,
                cacheFlushBatchSize,
                cacheFlushIntervalMillis,
                FileHashStrategy.defaultStrategy()
        );
    }

    public DuplicateFinder(
            FileIndexRepository fileIndexRepository,
            FileHashStrategy hashStrategy
    ) {
        this(
                fileIndexRepository,
                DEFAULT_CACHE_FLUSH_BATCH_SIZE,
                DEFAULT_CACHE_FLUSH_INTERVAL_MILLIS,
                hashStrategy
        );
    }

    public DuplicateFinder(
            FileIndexRepository fileIndexRepository,
            int cacheFlushBatchSize,
            long cacheFlushIntervalMillis,
            FileHashStrategy hashStrategy
    ) {
        this.fileIndexRepository = fileIndexRepository;
        this.cacheFlushBatchSize = Math.max(1, cacheFlushBatchSize);
        this.cacheFlushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, cacheFlushIntervalMillis));
        this.hashStrategy = Objects.requireNonNull(hashStrategy, "hashStrategy must not be null");
    }

    public DedupeScanResult findDuplicates(Path rootFolder, DedupeScanOptions options) {
        return findDuplicates(rootFolder, options, progress -> {}, () -> false);
    }

    public DedupeScanResult findDuplicates(
            Path rootFolder,
            DedupeScanOptions options,
            Consumer<DedupeProgress> progressListener,
            BooleanSupplier isCancelled
    ) {
        Objects.requireNonNull(rootFolder, "rootFolder must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Consumer<DedupeProgress> listener = progressListener != null ? progressListener : p -> {};
        BooleanSupplier cancelled = isCancelled != null ? isCancelled : () -> false;

        if (!Files.isDirectory(rootFolder)) {
            throw new IllegalArgumentException("Provided path must be an existing directory: " + rootFolder);
        }
        Path normalizedRoot = rootFolder.toAbsolutePath().normalize();
        if (PathSafety.isProtectedPath(normalizedRoot)) {
            return DedupeScanResult.fromGroups(List.of(), 0, 0, 0, 0, 0, 0L);
        }

        long scanStartNanos = System.nanoTime();
        ProgressThrottler throttler = new ProgressThrottler();
        SmoothedEtaCalculator etaCalculator = new SmoothedEtaCalculator();

        AtomicInteger skippedUnreadable = new AtomicInteger(0);
        AtomicInteger skippedExcluded = new AtomicInteger(0);
        AtomicInteger cacheHits = new AtomicInteger(0);
        AtomicInteger filesHashed = new AtomicInteger(0);
        AtomicInteger walkScannedFiles = new AtomicInteger(0);
        AtomicLong walkScannedBytes = new AtomicLong(0L);
        AtomicReference<String> currentFolder = new AtomicReference<>(normalizedRoot.toString());

        // Stage 1: Enumerate eligible files and group by size
        List<CandidateFile> allCandidates = enumerateFiles(
                normalizedRoot,
                options,
                skippedUnreadable,
                skippedExcluded,
                walkScannedFiles,
                walkScannedBytes,
                currentFolder,
                throttler,
                scanStartNanos,
                listener,
                cancelled
        );
        checkCancelled(cancelled);

        Map<Long, List<CandidateFile>> bySize = new LinkedHashMap<>();
        for (CandidateFile candidate : allCandidates) {
            bySize.computeIfAbsent(candidate.sizeBytes(), k -> new ArrayList<>()).add(candidate);
        }

        List<CandidateFile> stage2Candidates = new ArrayList<>();
        int totalCandidateFiles = 0;
        long totalCandidateBytes = 0L;

        for (Map.Entry<Long, List<CandidateFile>> entry : bySize.entrySet()) {
            List<CandidateFile> group = entry.getValue();
            if (group.size() >= 2) {
                long size = entry.getKey();
                totalCandidateFiles += group.size();
                totalCandidateBytes += size * group.size();
                stage2Candidates.addAll(group);
            }
        }

        final int candidateFilesCount = totalCandidateFiles;
        final long candidateBytesTotal = totalCandidateBytes;

        if (stage2Candidates.isEmpty()) {
            emitProgress(
                    listener,
                    throttler,
                    true,
                    "Scan complete",
                    0,
                    0,
                    0L,
                    0L,
                    currentFolder.get(),
                    scanStartNanos,
                    0.0,
                    -1L,
                    skippedUnreadable.get(),
                    skippedExcluded.get(),
                    walkScannedFiles.get(),
                    walkScannedBytes.get(),
                    0,
                    0L
            );
            return DedupeScanResult.fromGroups(
                    List.of(),
                    skippedUnreadable.get(),
                    skippedExcluded.get(),
                    0,
                    0,
                    walkScannedFiles.get(),
                    walkScannedBytes.get()
            );
        }

        Map<Path, FileIndexEntry> existingCache = loadCache();
        Map<Path, FileIndexEntry> dirtyCacheEntries = new ConcurrentHashMap<>();
        AtomicLong lastCacheFlushNanos = new AtomicLong(System.nanoTime());
        Object cacheFlushLock = new Object();
        Instant now = Instant.now();

        int desiredThreads = options.diskType().workerThreads();
        int maxCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int poolSize = Math.max(1, Math.min(desiredThreads, maxCores));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);

        try {
            // Stage 2: Compute quick hash for size-matched candidates (for <= 8 KB files, this single
            // read covers the entire file and computes the full SHA-256 at the same time)
            AtomicInteger stage2Processed = new AtomicInteger(0);
            AtomicLong stage2BytesProcessed = new AtomicLong(0L);
            int stage2Total = stage2Candidates.size();
            long stage2TotalBytes = candidateBytesTotal;

            emitProgress(
                    listener,
                    throttler,
                    true,
                    "Quick hashing",
                    0,
                    stage2Total,
                    0L,
                    stage2TotalBytes,
                    currentFolder.get(),
                    scanStartNanos,
                    0.0,
                    -1L,
                    skippedUnreadable.get(),
                    skippedExcluded.get(),
                    walkScannedFiles.get(),
                    walkScannedBytes.get(),
                    candidateFilesCount,
                    candidateBytesTotal
            );

            List<QuickHashedFile> quickHashedFiles = executeIndexedTasks(
                    pool,
                    poolSize,
                    stage2Total,
                    cancelled,
                    idx -> {
                        CandidateFile candidate = stage2Candidates.get(idx);
                        checkCancelled(cancelled);
                        boolean isSmallWholeFile = candidate.sizeBytes() <= FileHasher.SMALL_FILE_SINGLE_READ_BYTES;
                        FileIndexEntry cached = existingCache.get(candidate.path());
                        String quickHash;
                        String fullSha256 = null;
                        boolean newlyHashed = false;

                        if (cached != null && cached.matchesMetadata(candidate.sizeBytes(), candidate.mtime())
                                && cached.quickHash() != null) {
                            quickHash = cached.quickHash();
                            fullSha256 = cached.sha256() != null
                                    ? cached.sha256()
                                    : (isSmallWholeFile ? quickHash : null);
                            cacheHits.incrementAndGet();
                        } else {
                            try {
                                quickHash = hashStrategy.computeQuickHash(
                                        candidate.path(),
                                        candidate.sizeBytes(),
                                        cancelled
                                );
                                if (isSmallWholeFile) {
                                    fullSha256 = quickHash;
                                }
                                filesHashed.incrementAndGet();
                                newlyHashed = true;
                            } catch (IOException | SecurityException e) {
                                skippedUnreadable.incrementAndGet();
                                return null;
                            }
                        }

                        if (newlyHashed) {
                            FileIndexEntry entry = FileIndexEntry.unsaved(
                                    candidate.path(),
                                    candidate.sizeBytes(),
                                    candidate.mtime(),
                                    quickHash,
                                    fullSha256,
                                    now
                            );
                            dirtyCacheEntries.put(candidate.path(), entry);
                        }

                        int done = stage2Processed.incrementAndGet();
                        long bytesDone = stage2BytesProcessed.addAndGet(candidate.sizeBytes());
                        if (done == stage2Total || stage2Total <= 64 || (done & 63) == 0) {
                            Path parent = candidate.path().getParent();
                            if (parent != null) {
                                currentFolder.lazySet(parent.toString());
                            }
                            if (newlyHashed) {
                                maybeFlushCache(dirtyCacheEntries, lastCacheFlushNanos, cacheFlushLock, false);
                            }
                            emitProgress(
                                    listener,
                                    throttler,
                                    done == stage2Total,
                                    "Quick hashing",
                                    done,
                                    stage2Total,
                                    bytesDone,
                                    stage2TotalBytes,
                                    currentFolder.get(),
                                    scanStartNanos,
                                    0.0,
                                    -1L,
                                    skippedUnreadable.get(),
                                    skippedExcluded.get(),
                                    walkScannedFiles.get(),
                                    walkScannedBytes.get(),
                                    candidateFilesCount,
                                    candidateBytesTotal
                            );
                        }
                        return new QuickHashedFile(candidate, quickHash, fullSha256, !newlyHashed);
                    }
            );

            Map<SizeAndHashKey, List<QuickHashedFile>> byQuickHash = new LinkedHashMap<>();
            for (QuickHashedFile qf : quickHashedFiles) {
                if (qf != null) {
                    SizeAndHashKey key = new SizeAndHashKey(qf.candidate().sizeBytes(), qf.quickHash());
                    byQuickHash.computeIfAbsent(key, k -> new ArrayList<>()).add(qf);
                }
            }

            List<QuickHashedFile> stage3Candidates = new ArrayList<>();
            for (List<QuickHashedFile> group : byQuickHash.values()) {
                if (group.size() >= 2) {
                    stage3Candidates.addAll(group);
                }
            }

            if (stage3Candidates.isEmpty()) {
                checkCancelled(cancelled);
                maybeFlushCache(dirtyCacheEntries, lastCacheFlushNanos, cacheFlushLock, true);
                emitProgress(
                        listener,
                        throttler,
                        true,
                        "Scan complete",
                        0,
                        0,
                        0L,
                        0L,
                        currentFolder.get(),
                        scanStartNanos,
                        0.0,
                        0L,
                        skippedUnreadable.get(),
                        skippedExcluded.get(),
                        walkScannedFiles.get(),
                        walkScannedBytes.get(),
                        candidateFilesCount,
                        candidateBytesTotal
                );
                return DedupeScanResult.fromGroups(
                        List.of(),
                        skippedUnreadable.get(),
                        skippedExcluded.get(),
                        cacheHits.get(),
                        filesHashed.get(),
                        walkScannedFiles.get(),
                        walkScannedBytes.get()
                );
            }

            // Stage 3: Compute full SHA-256 for quick-hash-matched candidates sorted by path.
            // Small files (<= 8 KB) reuse the full SHA-256 already computed in Stage 2 without a second read.
            stage3Candidates.sort(Comparator.comparing(
                    qf -> qf.candidate().path().toString(),
                    String.CASE_INSENSITIVE_ORDER
            ));
            AtomicInteger stage3Processed = new AtomicInteger(0);
            AtomicLong stage3BytesProcessed = new AtomicLong(0L);
            int stage3Total = stage3Candidates.size();
            long stage3TotalBytes = stage3Candidates.stream()
                    .mapToLong(qf -> qf.candidate().sizeBytes())
                    .sum();

            etaCalculator.start();
            emitProgress(
                    listener,
                    throttler,
                    true,
                    "Full SHA-256 hashing",
                    0,
                    stage3Total,
                    0L,
                    stage3TotalBytes,
                    currentFolder.get(),
                    scanStartNanos,
                    0.0,
                    -1L,
                    skippedUnreadable.get(),
                    skippedExcluded.get(),
                    walkScannedFiles.get(),
                    walkScannedBytes.get(),
                    candidateFilesCount,
                    candidateBytesTotal
            );

            List<FullHashedFile> fullHashedFiles = executeIndexedTasks(
                    pool,
                    poolSize,
                    stage3Total,
                    cancelled,
                    idx -> {
                        QuickHashedFile qf = stage3Candidates.get(idx);
                        CandidateFile candidate = qf.candidate();
                        checkCancelled(cancelled);
                        Path parent = candidate.path().getParent();
                        if (parent != null) {
                            currentFolder.lazySet(parent.toString());
                        }

                        String quickHash = qf.quickHash();
                        String sha256;
                        boolean newlyHashed = false;

                        if (qf.precomputedFullSha256() != null) {
                            // Either <= 8 KB (whole file already hashed in Stage 2) or cached sha256
                            sha256 = qf.precomputedFullSha256();
                            if (qf.fromCache()) {
                                cacheHits.incrementAndGet();
                            }
                            stage3BytesProcessed.addAndGet(candidate.sizeBytes());
                        } else {
                            try {
                                sha256 = hashStrategy.computeFullSha256(
                                        candidate.path(),
                                        cancelled,
                                        chunkBytes -> {
                                            long bytesNow = stage3BytesProcessed.addAndGet(chunkBytes);
                                            if (throttler.shouldEmit("Full SHA-256 hashing", false)) {
                                                SmoothedEtaCalculator.EtaSnapshot snap =
                                                        etaCalculator.update(bytesNow, stage3TotalBytes);
                                                listener.accept(new DedupeProgress(
                                                        "Full SHA-256 hashing",
                                                        stage3Processed.get(),
                                                        stage3Total,
                                                        bytesNow,
                                                        stage3TotalBytes,
                                                        currentFolder.get(),
                                                        elapsedMillis(scanStartNanos),
                                                        snap.throughputMbPerSec(),
                                                        snap.etaSeconds().orElse(-1L),
                                                        skippedUnreadable.get(),
                                                        skippedExcluded.get(),
                                                        walkScannedFiles.get(),
                                                        walkScannedBytes.get(),
                                                        candidateFilesCount,
                                                        candidateBytesTotal
                                                ));
                                            }
                                        }
                                );
                                filesHashed.incrementAndGet();
                                newlyHashed = true;
                            } catch (IOException | SecurityException e) {
                                skippedUnreadable.incrementAndGet();
                                return null;
                            }
                        }

                        if (newlyHashed) {
                            FileIndexEntry entry = FileIndexEntry.unsaved(
                                    candidate.path(),
                                    candidate.sizeBytes(),
                                    candidate.mtime(),
                                    quickHash,
                                    sha256,
                                    now
                            );
                            dirtyCacheEntries.put(candidate.path(), entry);
                            maybeFlushCache(dirtyCacheEntries, lastCacheFlushNanos, cacheFlushLock, false);
                        }

                        int done = stage3Processed.incrementAndGet();
                        long bytesDone = stage3BytesProcessed.get();
                        boolean force = (done == stage3Total);
                        if (throttler.shouldEmit("Full SHA-256 hashing", force)) {
                            SmoothedEtaCalculator.EtaSnapshot snap = etaCalculator.update(bytesDone, stage3TotalBytes);
                            listener.accept(new DedupeProgress(
                                    "Full SHA-256 hashing",
                                    done,
                                    stage3Total,
                                    bytesDone,
                                    stage3TotalBytes,
                                    currentFolder.get(),
                                    elapsedMillis(scanStartNanos),
                                    snap.throughputMbPerSec(),
                                    snap.etaSeconds().orElse(-1L),
                                    skippedUnreadable.get(),
                                    skippedExcluded.get(),
                                    walkScannedFiles.get(),
                                    walkScannedBytes.get(),
                                    candidateFilesCount,
                                    candidateBytesTotal
                            ));
                        }
                        return new FullHashedFile(candidate, sha256);
                    }
            );
            checkCancelled(cancelled);

            Map<SizeAndHashKey, List<FileEntry>> byFullHash = new LinkedHashMap<>();
            for (FullHashedFile ff : fullHashedFiles) {
                if (ff != null) {
                    SizeAndHashKey key = new SizeAndHashKey(ff.candidate().sizeBytes(), ff.sha256());
                    byFullHash.computeIfAbsent(key, k -> new ArrayList<>()).add(new FileEntry(
                            ff.candidate().path(),
                            ff.candidate().sizeBytes(),
                            ff.candidate().mtime(),
                            false
                    ));
                }
            }

            List<DuplicateGroup> duplicateGroups = new ArrayList<>();
            for (Map.Entry<SizeAndHashKey, List<FileEntry>> entry : byFullHash.entrySet()) {
                List<FileEntry> files = entry.getValue();
                if (files.size() >= 2) {
                    files.sort(Comparator.comparing(f -> f.path().toString(), String.CASE_INSENSITIVE_ORDER));
                    DuplicateGroup rawGroup = new DuplicateGroup(
                            entry.getKey().hash(),
                            entry.getKey().sizeBytes(),
                            files
                    );
                    duplicateGroups.add(options.keeperStrategy().apply(rawGroup));
                }
            }

            duplicateGroups.sort(Comparator
                    .comparingLong(DuplicateGroup::wastedBytes).reversed()
                    .thenComparing(DuplicateGroup::sha256));

            checkCancelled(cancelled);
            maybeFlushCache(dirtyCacheEntries, lastCacheFlushNanos, cacheFlushLock, true);

            emitProgress(
                    listener,
                    throttler,
                    true,
                    "Scan complete",
                    stage3Total,
                    stage3Total,
                    stage3TotalBytes,
                    stage3TotalBytes,
                    currentFolder.get(),
                    scanStartNanos,
                    0.0,
                    0L,
                    skippedUnreadable.get(),
                    skippedExcluded.get(),
                    walkScannedFiles.get(),
                    walkScannedBytes.get(),
                    candidateFilesCount,
                    candidateBytesTotal
            );
            return DedupeScanResult.fromGroups(
                    duplicateGroups,
                    skippedUnreadable.get(),
                    skippedExcluded.get(),
                    cacheHits.get(),
                    filesHashed.get(),
                    walkScannedFiles.get(),
                    walkScannedBytes.get()
            );
        } finally {
            pool.shutdownNow();
            // Flush any completed file hashes even if the scan was cancelled midway
            maybeFlushCache(dirtyCacheEntries, lastCacheFlushNanos, cacheFlushLock, true);
        }
    }

    private List<CandidateFile> enumerateFiles(
            Path normalizedRoot,
            DedupeScanOptions options,
            AtomicInteger skippedUnreadable,
            AtomicInteger skippedExcluded,
            AtomicInteger walkScannedFiles,
            AtomicLong walkScannedBytes,
            AtomicReference<String> currentFolder,
            ProgressThrottler throttler,
            long scanStartNanos,
            Consumer<DedupeProgress> listener,
            BooleanSupplier cancelled
    ) {
        List<CandidateFile> candidates = new ArrayList<>();
        int maxDepth = options.recursive() ? Integer.MAX_VALUE : 1;
        FolderExclusionFilter exclusionFilter = options.exclusionFilter();

        try {
            Files.walkFileTree(normalizedRoot, Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    checkCancelled(cancelled);
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(normalizedRoot)) {
                        if (exclusionFilter.isExcluded(dir)) {
                            skippedExcluded.incrementAndGet();
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (PathSafety.isProtectedPath(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (options.skipHidden() && isHiddenFromAttrs(dir, attrs)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    currentFolder.lazySet(dir.toString());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    checkCancelled(cancelled);
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (options.skipHidden() && isHiddenFromAttrs(file, attrs)) {
                        return FileVisitResult.CONTINUE;
                    }
                    long size = attrs.size();
                    int scanned = walkScannedFiles.incrementAndGet();
                    long totalBytes = walkScannedBytes.addAndGet(size);

                    if (size >= options.minSizeBytes()) {
                        candidates.add(new CandidateFile(
                                file,
                                size,
                                attrs.lastModifiedTime().toInstant()
                        ));
                    }

                    if (throttler.shouldEmit("Scanning files", false)) {
                        listener.accept(new DedupeProgress(
                                "Scanning files",
                                candidates.size(),
                                candidates.size(),
                                totalBytes,
                                0L,
                                currentFolder.get(),
                                elapsedMillis(scanStartNanos),
                                0.0,
                                -1L,
                                skippedUnreadable.get(),
                                skippedExcluded.get(),
                                scanned,
                                totalBytes,
                                candidates.size(),
                                0L
                        ));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    skippedUnreadable.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        skippedUnreadable.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            skippedUnreadable.incrementAndGet();
        }

        emitProgress(
                listener,
                throttler,
                true,
                "Scanning files",
                candidates.size(),
                candidates.size(),
                walkScannedBytes.get(),
                0L,
                currentFolder.get(),
                scanStartNanos,
                0.0,
                -1L,
                skippedUnreadable.get(),
                skippedExcluded.get(),
                walkScannedFiles.get(),
                walkScannedBytes.get(),
                candidates.size(),
                0L
        );
        return candidates;
    }

    private static void emitProgress(
            Consumer<DedupeProgress> listener,
            ProgressThrottler throttler,
            boolean force,
            String stageName,
            int processedCount,
            int totalCount,
            long processedBytes,
            long totalBytes,
            String currentFolder,
            long scanStartNanos,
            double throughputMbPerSec,
            long etaSeconds,
            int skippedUnreadable,
            int skippedExcluded,
            int walkScannedFiles,
            long walkScannedBytes,
            int candidateFiles,
            long candidateBytes
    ) {
        if (throttler.shouldEmit(stageName, force)) {
            listener.accept(new DedupeProgress(
                    stageName,
                    processedCount,
                    totalCount,
                    processedBytes,
                    totalBytes,
                    currentFolder,
                    elapsedMillis(scanStartNanos),
                    throughputMbPerSec,
                    etaSeconds,
                    skippedUnreadable,
                    skippedExcluded,
                    walkScannedFiles,
                    walkScannedBytes,
                    candidateFiles,
                    candidateBytes
            ));
        }
    }

    private static long elapsedMillis(long scanStartNanos) {
        return Math.max(0L, (System.nanoTime() - scanStartNanos) / 1_000_000L);
    }

    /**
     * Dispatches {@code poolSize} worker loops that pull indices from a shared {@link AtomicInteger}
     * instead of allocating and queueing one {@link Future} per file.
     */
    private <T> List<T> executeIndexedTasks(
            ExecutorService pool,
            int poolSize,
            int totalItems,
            BooleanSupplier cancelled,
            IntFunction<T> itemProcessor
    ) {
        if (totalItems <= 0) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        T[] results = (T[]) new Object[totalItems];
        AtomicInteger nextIndex = new AtomicInteger(0);
        int workers = Math.min(poolSize, totalItems);
        List<Future<Void>> futures = new ArrayList<>(workers);

        for (int w = 0; w < workers; w++) {
            Callable<Void> workerTask = () -> {
                while (true) {
                    checkCancelled(cancelled);
                    int idx = nextIndex.getAndIncrement();
                    if (idx >= totalItems) {
                        break;
                    }
                    results[idx] = itemProcessor.apply(idx);
                }
                return null;
            };
            futures.add(pool.submit(workerTask));
        }

        for (Future<Void> future : futures) {
            checkCancelled(cancelled);
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelRemaining(futures);
                throw new CancellationException("Duplicate scan interrupted");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancellationException ce) {
                    cancelRemaining(futures);
                    throw ce;
                }
                throw new RuntimeException("Unexpected error during duplicate hashing", cause);
            }
        }

        List<T> list = new ArrayList<>(totalItems);
        for (T item : results) {
            if (item != null) {
                list.add(item);
            }
        }
        return list;
    }

    private static <T> void cancelRemaining(List<Future<T>> futures) {
        for (Future<T> f : futures) {
            f.cancel(true);
        }
    }

    private Map<Path, FileIndexEntry> loadCache() {
        if (fileIndexRepository == null) {
            return Map.of();
        }
        try {
            return fileIndexRepository.findAll();
        } catch (SQLException e) {
            return Map.of();
        }
    }

    private void maybeFlushCache(
            Map<Path, FileIndexEntry> dirtyCacheEntries,
            AtomicLong lastFlushNanos,
            Object flushLock,
            boolean force
    ) {
        if (fileIndexRepository == null || dirtyCacheEntries.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (!force
                && (dirtyCacheEntries.size() < cacheFlushBatchSize
                        || (now - lastFlushNanos.get()) < cacheFlushIntervalNanos)) {
            return;
        }

        synchronized (flushLock) {
            now = System.nanoTime();
            if (!force
                    && (dirtyCacheEntries.size() < cacheFlushBatchSize
                            || (now - lastFlushNanos.get()) < cacheFlushIntervalNanos)) {
                return;
            }
            if (dirtyCacheEntries.isEmpty()) {
                return;
            }
            List<FileIndexEntry> batchToFlush = new ArrayList<>(dirtyCacheEntries.values());
            for (FileIndexEntry entry : batchToFlush) {
                dirtyCacheEntries.remove(entry.path(), entry);
            }
            lastFlushNanos.set(now);
            try {
                fileIndexRepository.upsertBatch(batchToFlush);
            } catch (SQLException ignored) {
                // Best-effort cache persistence
            }
        }
    }

    private static boolean isHiddenFromAttrs(Path path, BasicFileAttributes attrs) {
        Path fileName = path.getFileName();
        if (fileName != null && fileName.toString().startsWith(".")) {
            return true;
        }
        if (attrs instanceof DosFileAttributes dosAttrs) {
            return dosAttrs.isHidden();
        }
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Duplicate scan cancelled");
        }
    }

    record CandidateFile(Path path, long sizeBytes, Instant mtime) {
    }

    private record QuickHashedFile(
            CandidateFile candidate,
            String quickHash,
            String precomputedFullSha256,
            boolean fromCache
    ) {
    }

    private record FullHashedFile(CandidateFile candidate, String sha256) {
    }

    private record SizeAndHashKey(long sizeBytes, String hash) {
    }
}
