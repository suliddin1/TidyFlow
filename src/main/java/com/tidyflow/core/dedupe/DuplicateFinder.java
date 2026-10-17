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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Three-stage duplicate file detector:
 * 1) Group by file size
 * 2) Group by quick hash (first 4 KB + last 4 KB)
 * 3) Group by full streaming SHA-256 (64 KB buffer)
 */
public class DuplicateFinder {

    private final FileIndexRepository fileIndexRepository;

    public DuplicateFinder() {
        this(null);
    }

    public DuplicateFinder(FileIndexRepository fileIndexRepository) {
        this.fileIndexRepository = fileIndexRepository;
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
        if (PathSafety.isProtectedPath(rootFolder)) {
            return DedupeScanResult.fromGroups(List.of(), 0, 0, 0);
        }

        AtomicInteger skippedUnreadable = new AtomicInteger(0);
        AtomicInteger cacheHits = new AtomicInteger(0);
        AtomicInteger filesHashed = new AtomicInteger(0);

        // Stage 1: Enumerate eligible files and group by size
        List<CandidateFile> allCandidates = enumerateFiles(rootFolder, options, skippedUnreadable, listener, cancelled);
        checkCancelled(cancelled);

        Map<Long, List<CandidateFile>> bySize = new LinkedHashMap<>();
        for (CandidateFile candidate : allCandidates) {
            bySize.computeIfAbsent(candidate.sizeBytes(), k -> new ArrayList<>()).add(candidate);
        }

        List<CandidateFile> stage2Candidates = new ArrayList<>();
        for (List<CandidateFile> group : bySize.values()) {
            if (group.size() >= 2) {
                stage2Candidates.addAll(group);
            }
        }

        if (stage2Candidates.isEmpty()) {
            listener.accept(new DedupeProgress("Scan complete", 0, 0));
            return DedupeScanResult.fromGroups(List.of(), skippedUnreadable.get(), 0, 0);
        }

        Map<Path, FileIndexEntry> existingCache = loadCache();
        Map<Path, FileIndexEntry> pendingUpserts = new ConcurrentHashMap<>();
        Instant now = Instant.now();

        int poolSize = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);

        try {
            // Stage 2: Compute quick hash for size-matched candidates
            AtomicInteger stage2Processed = new AtomicInteger(0);
            int stage2Total = stage2Candidates.size();
            listener.accept(new DedupeProgress("Quick hashing", 0, stage2Total));

            List<Callable<QuickHashedFile>> quickTasks = new ArrayList<>(stage2Total);
            for (CandidateFile candidate : stage2Candidates) {
                quickTasks.add(() -> {
                    checkCancelled(cancelled);
                    FileIndexEntry cached = existingCache.get(candidate.path());
                    String quickHash;
                    String cachedSha256 = null;

                    if (cached != null && cached.matchesMetadata(candidate.sizeBytes(), candidate.mtime())
                            && cached.quickHash() != null) {
                        quickHash = cached.quickHash();
                        cachedSha256 = cached.sha256();
                        cacheHits.incrementAndGet();
                    } else {
                        try {
                            quickHash = FileHasher.computeQuickHash(candidate.path(), candidate.sizeBytes(), cancelled);
                            filesHashed.incrementAndGet();
                        } catch (IOException | SecurityException e) {
                            skippedUnreadable.incrementAndGet();
                            return null;
                        }
                    }

                    pendingUpserts.put(candidate.path(), FileIndexEntry.unsaved(
                            candidate.path(),
                            candidate.sizeBytes(),
                            candidate.mtime(),
                            quickHash,
                            cachedSha256,
                            now
                    ));

                    int done = stage2Processed.incrementAndGet();
                    listener.accept(new DedupeProgress("Quick hashing", done, stage2Total));
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

            List<CandidateFile> stage3Candidates = new ArrayList<>();
            for (List<CandidateFile> group : byQuickHash.values()) {
                if (group.size() >= 2) {
                    stage3Candidates.addAll(group);
                }
            }

            if (stage3Candidates.isEmpty()) {
                checkCancelled(cancelled);
                persistCache(pendingUpserts.values());
                listener.accept(new DedupeProgress("Scan complete", 0, 0));
                return DedupeScanResult.fromGroups(
                        List.of(),
                        skippedUnreadable.get(),
                        cacheHits.get(),
                        filesHashed.get()
                );
            }

            // Stage 3: Compute full SHA-256 for quick-hash-matched candidates
            AtomicInteger stage3Processed = new AtomicInteger(0);
            int stage3Total = stage3Candidates.size();
            listener.accept(new DedupeProgress("Full SHA-256 hashing", 0, stage3Total));

            List<Callable<FullHashedFile>> fullTasks = new ArrayList<>(stage3Total);
            for (CandidateFile candidate : stage3Candidates) {
                fullTasks.add(() -> {
                    checkCancelled(cancelled);
                    FileIndexEntry cached = existingCache.get(candidate.path());
                    FileIndexEntry staged = pendingUpserts.get(candidate.path());
                    String quickHash = staged != null ? staged.quickHash() : null;
                    String sha256;

                    if (cached != null && cached.matchesMetadata(candidate.sizeBytes(), candidate.mtime())
                            && cached.sha256() != null) {
                        sha256 = cached.sha256();
                        cacheHits.incrementAndGet();
                    } else {
                        try {
                            sha256 = FileHasher.computeFullSha256(candidate.path(), cancelled);
                            filesHashed.incrementAndGet();
                        } catch (IOException | SecurityException e) {
                            skippedUnreadable.incrementAndGet();
                            return null;
                        }
                    }

                    pendingUpserts.put(candidate.path(), FileIndexEntry.unsaved(
                            candidate.path(),
                            candidate.sizeBytes(),
                            candidate.mtime(),
                            quickHash,
                            sha256,
                            now
                    ));

                    int done = stage3Processed.incrementAndGet();
                    listener.accept(new DedupeProgress("Full SHA-256 hashing", done, stage3Total));
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
            persistCache(pendingUpserts.values());

            listener.accept(new DedupeProgress("Scan complete", stage3Total, stage3Total));
            return DedupeScanResult.fromGroups(
                    duplicateGroups,
                    skippedUnreadable.get(),
                    cacheHits.get(),
                    filesHashed.get()
            );
        } finally {
            pool.shutdownNow();
        }
    }

    private List<CandidateFile> enumerateFiles(
            Path rootFolder,
            DedupeScanOptions options,
            AtomicInteger skippedUnreadable,
            Consumer<DedupeProgress> listener,
            BooleanSupplier cancelled
    ) {
        List<CandidateFile> candidates = new ArrayList<>();
        int maxDepth = options.recursive() ? Integer.MAX_VALUE : 1;

        try {
            Files.walkFileTree(rootFolder, Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    checkCancelled(cancelled);
                    if (attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (PathSafety.isProtectedPath(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(rootFolder) && options.skipHidden() && isHiddenSafe(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    checkCancelled(cancelled);
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (PathSafety.isProtectedPath(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (options.skipHidden() && isHiddenSafe(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    long size = attrs.size();
                    if (size < options.minSizeBytes()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!Files.isReadable(file)) {
                        skippedUnreadable.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                    candidates.add(new CandidateFile(
                            file.toAbsolutePath().normalize(),
                            size,
                            attrs.lastModifiedTime().toInstant()
                    ));
                    listener.accept(new DedupeProgress("Scanning files", candidates.size(), candidates.size()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    skippedUnreadable.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            skippedUnreadable.incrementAndGet();
        }
        return candidates;
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

    private void persistCache( java.util.Collection<FileIndexEntry> entries) {
        if (fileIndexRepository == null || entries.isEmpty()) {
            return;
        }
        try {
            fileIndexRepository.upsertBatch(entries);
        } catch (SQLException ignored) {
            // Best-effort cache persistence
        }
    }

    private static boolean isHiddenSafe(Path path) {
        Path fileName = path.getFileName();
        if (fileName != null && fileName.toString().startsWith(".")) {
            return true;
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

    private record CandidateFile(Path path, long sizeBytes, Instant mtime) {
    }

    private record QuickHashedFile(CandidateFile candidate, String quickHash) {
    }

    private record FullHashedFile(CandidateFile candidate, String sha256) {
    }

    private record SizeAndHashKey(long sizeBytes, String hash) {
    }
}
