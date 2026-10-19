package com.tidyflow.core.watcher;

import com.tidyflow.core.scanner.FileItem;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.util.FileNameUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, testable tracker that determines when candidate files in watched folders are ready to organize.
 * A file is ready only when:
 * 1. Its extension is not an in-progress download (.crdownload, .part, .tmp),
 * 2. Its size and last-modified time have remained unchanged for at least settleDuration, and
 * 3. It can be opened for reading (not locked by another process).
 * Not-ready or locked files remain tracked and are re-checked on subsequent polls.
 */
public class FileReadinessTracker {

    private record ObservedState(long size, Instant lastModified, Instant stableSince) {
    }

    private final Clock clock;
    private final FileStatProvider statProvider;
    private volatile Duration settleDuration;
    private final Map<Path, ObservedState> candidates = new LinkedHashMap<>();

    public FileReadinessTracker(Clock clock, FileStatProvider statProvider, Duration settleDuration) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.statProvider = Objects.requireNonNull(statProvider, "statProvider must not be null");
        this.settleDuration = Objects.requireNonNull(settleDuration, "settleDuration must not be null");
    }

    public synchronized void setSettleDuration(Duration settleDuration) {
        this.settleDuration = Objects.requireNonNull(settleDuration, "settleDuration must not be null");
    }

    public Duration settleDuration() {
        return settleDuration;
    }

    /**
     * Registers or refreshes a candidate file path observed from a WatchService event or folder scan.
     */
    public synchronized void recordCandidate(Path path) {
        if (path == null || path.getFileName() == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        String fileName = normalized.getFileName().toString();
        if (fileName.startsWith(".") || FolderScanner.isInProgressFileName(fileName)) {
            candidates.remove(normalized);
            return;
        }

        FileStat stat = statProvider.stat(normalized);
        if (!stat.exists() || !stat.regularFile() || stat.hidden()) {
            candidates.remove(normalized);
            return;
        }

        Instant now = clock.instant();
        ObservedState previous = candidates.get(normalized);
        if (previous == null || previous.size() != stat.size() || !previous.lastModified().equals(stat.lastModified())) {
            candidates.put(normalized, new ObservedState(stat.size(), stat.lastModified(), now));
        }
    }

    /**
     * Checks all tracked candidates and returns those that have settled and are unlocked.
     * Files that are still growing or locked remain tracked for future polls.
     */
    public synchronized List<FileItem> pollReadyFiles() {
        List<FileItem> ready = new ArrayList<>();
        Instant now = clock.instant();
        Iterator<Map.Entry<Path, ObservedState>> iterator = candidates.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Path, ObservedState> entry = iterator.next();
            Path path = entry.getKey();
            String fileName = path.getFileName().toString();

            if (fileName.startsWith(".") || FolderScanner.isInProgressFileName(fileName)) {
                iterator.remove();
                continue;
            }

            FileStat stat = statProvider.stat(path);
            if (!stat.exists() || !stat.regularFile() || stat.hidden()) {
                iterator.remove();
                continue;
            }

            ObservedState observed = entry.getValue();
            if (observed.size() != stat.size() || !observed.lastModified().equals(stat.lastModified())) {
                entry.setValue(new ObservedState(stat.size(), stat.lastModified(), now));
                continue;
            }

            Duration elapsed = Duration.between(observed.stableSince(), now);
            if (elapsed.compareTo(settleDuration) < 0) {
                continue;
            }

            if (!stat.readableAndUnlocked()) {
                // Still locked by another process; keep in candidates to re-check later
                continue;
            }

            iterator.remove();
            ready.add(new FileItem(
                    path,
                    fileName,
                    FileNameUtils.getExtension(fileName),
                    stat.size(),
                    stat.lastModified()
            ));
        }

        return List.copyOf(ready);
    }

    public synchronized int trackedCount() {
        return candidates.size();
    }

    public synchronized void clearFolder(Path folder) {
        if (folder == null) {
            return;
        }
        Path normalizedFolder = folder.toAbsolutePath().normalize();
        candidates.keySet().removeIf(p -> normalizedFolder.equals(p.getParent()));
    }

    public synchronized void clearAll() {
        candidates.clear();
    }
}
