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

/**
 * Three-stage duplicate file detector:
 * 1) Single-stat directory walk with folder exclusions and size grouping
 * 2) Quick hash (first 4 KB + last 4 KB via positioned FileChannel reads) for files > 64 KB
 * 3) Full streaming SHA-256 (1 MB thread-local buffer, sorted by path) for <= 64 KB size matches
 *    and > 64 KB quick-hash matches, with incremental SQLite cache persistence.
 */
public class DuplicateFinder {

    public static final int DEFAULT_CACHE_FLUSH_BATCH_SIZE = 500;
    public static final long DEFAULT_CACHE_FLUSH_INTERVAL_MILLIS = 20_000L;

    static final Comparator<CandidateFile> CANDIDATE_PATH_COMPARATOR =
            Comparator.comparing(c -> c.path().toString(), String.CASE_INSENSITIVE_ORDER);

    private final FileIndexRepository fileIndexRepository;
    private final int cacheFlushBatchSize;
    private final long cacheFlushIntervalNanos;

    public DuplicateFinder() {
        this(null);
    }

    public DuplicateFinder(FileIndexRepository fileIndexRepository) {
        this(fileIndexRepository, DEFAULT_CACHE_FLUSH_BATCH_SIZE, DEFAULT_CACHE_FLUSH_INTERVAL_MILLIS);
    }

    public DuplicateFinder(
            FileIndexRepository fileIndexRepository,
            int cacheFlushBatchSize,
            long cacheFlushIntervalMillis
    ) {
        this.fileIndexRepository = fileIndexRepository;
        this.cacheFlushBatchSize = Math.max(1, cacheFlushBatchSize);
        this.cacheFlushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(100L, cacheFlushIntervalMillis));
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
        List<CandidateFile> smallDirectStage3Candidates = new ArrayList<>();
        int totalCandidateFiles = 0;
        long totalCandidateBytes = 0L;

        for (Map.Entry<Long, List<CandidateFile>> entry : bySize.entrySet()) {
            List<CandidateFile> group = entry.getValue();
            if (group.size() >= 2) {
                long size = entry.getKey();
                totalCandidateFiles += group.size();
                totalCandidateBytes += size * group.size();
                if (size <= FileHasher.SMALL_FILE_DIRECT_FULL_HASH_BYTES) {
                    smallDirectStage3Candidates.addAll(group);
                } else {
                    stage2Candidates.addAll(group);
                }
            }
        }

        final int candidateFilesCount = totalCandidateFiles;
        final long candidateBytesTotal = totalCandidateBytes;

        if (stage2Candidates.isEmpty() && smallDirectStage3Candidates.isEmpty()) {
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
        Map<Path, FileIndexEntry> stagedEntries = new ConcurrentHashMap<>();
        Map<Path, FileIndexEntry> dirtyCacheEntries = new ConcurrentHashMap<>();
        AtomicLong lastCacheFlushNanos = new AtomicLong(System.nanoTime());
        Object cacheFlushLock = new Object();
        Instant now = Instant.now();

        int desiredThreads = options.diskType().workerThreads();
        int maxCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int poolSize = Math.max(1, Math.min(desiredThreads, maxCores));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);

        try {
            List<CandidateFile> stage3Candidates = new ArrayList<>(smallDirectStage3Candidates);

            // Stage 2: Compute quick hash for size-matched candidates > 64 KB
            if (!stage2Candidates.isEmpty()) {
                stage2Candidates.sort(CANDIDATE_PATH_COMPARATOR);
                AtomicInteger stage2Processed = new AtomicInteger(0);
                AtomicLong stage2BytesProcessed = new AtomicLong(0L);
                int stage2Total = stage2Candidates.size();
                long stage2TotalBytes = stage2Candidates.stream().mapToLong(CandidateFile::sizeBytes).sum();

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

                List<Callable<QuickHashedFile>> quickTasks = new ArrayList<>(stage2Total);
                for (CandidateFile candidate : stage2Candidates) {
                    quickTasks.add(() -> {
                        checkCancelled(cancelled);
                        Path parent = candidate.path().getParent();
                        if (parent != null) {
                            currentFolder.lazySet(parent.toString());
                        }
                        FileIndexEntry cached = existingCache.get(candidate.path());
                        String quickHash;
                        String cachedSha256 = null;
                        boolean newlyHashed = false;

                        if (cached != null && cached.matchesMetadata(candidate.sizeBytes(), candidate.mtime())
                                && cached.quickHash() != null) {
                            quickHash = cached.quickHash();
                            cachedSha256 = cached.sha256();
                            cacheHits.incrementAndGet();
                        } else {
                            try {
                                quickHash = FileHasher.computeQuickHash(candidate.path(), candidate.sizeBytes(), cancelled);
                                filesHashed.incrementAndGet();
                                newlyHashed = true;
                            } catch (IOException | SecurityException e) {
                                skippedUnreadable.incrementAndGet();
                                return null;
                            }
                        }

                        FileIndexEntry entry = FileIndexEntry.unsaved(
                                candidate.path(),
                                candidate.sizeBytes(),
                                candidate.mtime(),
                                quickHash,
                                cachedSha256,
                                now
                        );
                        stagedEntries.put(candidate.path(), entry);
                        if (newlyHashed) {
                            dirtyCacheEntries.put(candidate.path(), entry);
                            maybeFlushCache(dirtyCacheEntries, lastCacheFlushNanos, cacheFlushLock, false);
                        }

                        int done = stage2Processed.incrementAndGet();
                        long bytesDone = stage2BytesProcessed.addAndGet(candidate.sizeBytes());
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
                        return new QuickHashedFile(candidate, quickHash);
                    });
                }

                List<QuickHashedFile> quickHashedFiles = executeAllTasks(pool, quickTasks, cancelled);

                Map<SizeAndHashKey, List<CandidateFile>> byQuickHash = new LinkedHashMap<>();
                for (QuickHashedFile qf : quickHashedFiles) {
                    if (qf != null) {
                        SizeAndHashKey key = new SizeAndHashKey(qf.candidate().sizeBytes(), qf.quickHash());
                        byQuickHash.computeIfAbsent(key, k -> new ArrayList<>()).add(qf.candidate());
                    }
                }

                for (List<CandidateFile> group : byQuickHash.values()) {
                    if (group.size() >= 2) {
                        stage3Candidates.addAll(group);
                    }
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

            // Stage 3: Compute full SHA-256 for candidates sorted by path (sequential disk access)
            stage3Candidates.sort(CANDIDATE_PATH_COMPARATOR);
            AtomicInteger stage3Processed = new AtomicInteger(0);
            AtomicLong stage3BytesProcessed = new AtomicLong(0L);
            int stage3Total = stage3Candidates.size();
            long stage3TotalBytes = stage3Candidates.stream().mapToLong(CandidateFile::sizeBytes).sum();

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

            List<Callable<FullHashedFile>> fullTasks = new ArrayList<>(stage3Total);
            for (CandidateFile candidate : stage3Candidates) {
                fullTasks.add(() -> {
                    checkCancelled(cancelled);
                    Path parent = candidate.path().getParent();
                    if (parent != null) {
                        currentFolder.lazySet(parent.toString());
                    }
                    FileIndexEntry cached = existingCache.get(candidate.path());
                    FileIndexEntry staged = stagedEntries.get(candidate.path());
                    String quickHash = staged != null
                            ? staged.quickHash()
                            : (cached != null && cached.matchesMetadata(candidate.sizeBytes(), candidate.mtime())
                                    ? cached.quickHash()
                                    : null);
                    String sha256;
                    boolean newlyHashed = false;

                    if (cached != null && cached.matchesMetadata(candidate.sizeBytes(), candidate.mtime())
                            && cached.sha256() != null) {
                        sha256 = cached.sha256();
                        cacheHits.incrementAndGet();
                        stage3BytesProcessed.addAndGet(candidate.sizeBytes());
                    } else {
                        try {
                            sha256 = FileHasher.computeFullSha256(
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

                    FileIndexEntry entry = FileIndexEntry.unsaved(
                            candidate.path(),
                            candidate.sizeBytes(),
                            candidate.mtime(),
                            quickHash,
                            sha256,
                            now
                    );
                    stagedEntries.put(candidate.path(), entry);
                    if (newlyHashed) {
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
                });
            }

            List<FullHashedFile> fullHashedFiles = executeAllTasks(pool, fullTasks, cancelled);
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

    private <T> List<T> executeAllTasks(
            ExecutorService pool,
            List<Callable<T>> tasks,
            BooleanSupplier cancelled
    ) {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            checkCancelled(cancelled);
            futures.add(pool.submit(task));
        }

        List<T> results = new ArrayList<>(tasks.size());
        for (Future<T> future : futures) {
            checkCancelled(cancelled);
            try {
                results.add(future.get());
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
        return results;
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
                && dirtyCacheEntries.size() < cacheFlushBatchSize
                && (now - lastFlushNanos.get()) < cacheFlushIntervalNanos) {
            return;
        }

        List<FileIndexEntry> batchToFlush;
        synchronized (flushLock) {
            now = System.nanoTime();
            if (!force
                    && dirtyCacheEntries.size() < cacheFlushBatchSize
                    && (now - lastFlushNanos.get()) < cacheFlushIntervalNanos) {
                return;
            }
            if (dirtyCacheEntries.isEmpty()) {
                return;
            }
            batchToFlush = new ArrayList<>(dirtyCacheEntries.values());
            for (FileIndexEntry entry : batchToFlush) {
                dirtyCacheEntries.remove(entry.path(), entry);
            }
            lastFlushNanos.set(now);
        }

        try {
            fileIndexRepository.upsertBatch(batchToFlush);
        } catch (SQLException ignored) {
            // Best-effort cache persistence
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

    private record QuickHashedFile(CandidateFile candidate, String quickHash) {
    }

    private record FullHashedFile(CandidateFile candidate, String sha256) {
    }

    private record SizeAndHashKey(long sizeBytes, String hash) {
    }
}
