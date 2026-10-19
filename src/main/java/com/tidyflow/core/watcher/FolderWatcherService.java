package com.tidyflow.core.watcher;

import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.scanner.FileItem;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.data.config.SettingsValidator;
import com.tidyflow.data.config.WatchMode;
import com.tidyflow.data.config.WatchedFolderConfig;
import com.tidyflow.util.PathSafety;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background service that monitors enabled watched folders non-recursively on a dedicated daemon thread,
 * tracks file readiness, suppresses self-moves, recovers from OVERFLOW and unavailable folders,
 * and flushes ready files in bursts through the AutomationPipeline.
 */
public class FolderWatcherService implements AutoCloseable {

    public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(3);
    public static final Duration DEFAULT_UNAVAILABLE_RETRY_INTERVAL = Duration.ofSeconds(30);

    private final FolderScanner scanner;
    private final FileExecutor fileExecutor;
    private final AutomationPipeline pipeline;
    private final FileReadinessTracker readinessTracker;
    private final Clock clock;
    private final Duration flushInterval;
    private final Duration unavailableRetryInterval;

    private final Map<String, WatchedFolderConfig> configuredFolders = new ConcurrentHashMap<>();
    private final Map<String, WatchedFolderStatus> folderStatuses = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> keyToFolder = new HashMap<>();
    private final Map<String, WatchKey> folderKeyToWatchKey = new HashMap<>();
    private final Map<String, Instant> nextRetryAt = new HashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WatchService watchService;
    private volatile Thread watcherThread;
    private volatile Instant lastFlushAt = Instant.EPOCH;

    public FolderWatcherService(
            FolderScanner scanner,
            FileExecutor fileExecutor,
            AutomationPipeline pipeline,
            Duration initialSettleDuration
    ) {
        this(
                scanner,
                fileExecutor,
                pipeline,
                new FileReadinessTracker(Clock.systemDefaultZone(), new NioFileStatProvider(), initialSettleDuration),
                Clock.systemDefaultZone(),
                DEFAULT_FLUSH_INTERVAL,
                DEFAULT_UNAVAILABLE_RETRY_INTERVAL
        );
    }

    public FolderWatcherService(
            FolderScanner scanner,
            FileExecutor fileExecutor,
            AutomationPipeline pipeline,
            FileReadinessTracker readinessTracker,
            Clock clock,
            Duration flushInterval,
            Duration unavailableRetryInterval
    ) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.fileExecutor = Objects.requireNonNull(fileExecutor, "fileExecutor must not be null");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline must not be null");
        this.readinessTracker = Objects.requireNonNull(readinessTracker, "readinessTracker must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.flushInterval = Objects.requireNonNull(flushInterval, "flushInterval must not be null");
        this.unavailableRetryInterval = Objects.requireNonNull(unavailableRetryInterval, "unavailableRetryInterval must not be null");
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        this.watchService = FileSystems.getDefault().newWatchService();
        this.running.set(true);
        this.lastFlushAt = clock.instant();

        syncRegistrations();

        Thread thread = new Thread(this::runLoop, "tidyflow-folder-watcher");
        thread.setDaemon(true);
        this.watcherThread = thread;
        thread.start();
    }

    public synchronized void updateConfiguration(List<WatchedFolderConfig> folders, Duration settleDuration) {
        Objects.requireNonNull(folders, "folders must not be null");
        readinessTracker.setSettleDuration(settleDuration);

        configuredFolders.clear();
        for (WatchedFolderConfig folder : folders) {
            if (folder != null && folder.path() != null) {
                Path normalized = folder.path().toAbsolutePath().normalize();
                String key = SettingsValidator.normalizePathKey(normalized);
                configuredFolders.put(key, new WatchedFolderConfig(normalized, folder.mode(), folder.enabled()));
            }
        }
        folderStatuses.keySet().retainAll(configuredFolders.keySet());
        if (running.get()) {
            syncRegistrations();
        } else {
            refreshStatusesWhenStopped();
        }
        pipeline.notifyStatusChanged();
    }

    public WatchedFolderStatus statusFor(Path folder) {
        if (folder == null) {
            return WatchedFolderStatus.OFF;
        }
        String key = SettingsValidator.normalizePathKey(folder);
        WatchedFolderConfig cfg = configuredFolders.get(key);
        if (cfg == null || !cfg.enabled() || cfg.mode() == WatchMode.OFF) {
            return WatchedFolderStatus.OFF;
        }
        WatchedFolderStatus raw = folderStatuses.getOrDefault(key, WatchedFolderStatus.WATCHING);
        if (raw == WatchedFolderStatus.UNAVAILABLE) {
            return WatchedFolderStatus.UNAVAILABLE;
        }
        if (pipeline.isPaused()) {
            return WatchedFolderStatus.PAUSED;
        }
        return raw;
    }

    public int activeWatchingCount() {
        int count = 0;
        for (WatchedFolderConfig cfg : configuredFolders.values()) {
            if (cfg.enabled() && cfg.mode() != WatchMode.OFF) {
                WatchedFolderStatus st = statusFor(cfg.path());
                if (st == WatchedFolderStatus.WATCHING) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Triggers an immediate folder rescan (used when OVERFLOW occurs or in tests).
     */
    public synchronized void rescanFolder(Path folder) {
        if (folder == null) {
            return;
        }
        Path normalized = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || PathSafety.isProtectedPath(normalized)) {
            return;
        }
        try {
            List<FileItem> items = scanner.scan(normalized);
            for (FileItem item : items) {
                if (!fileExecutor.isRecentSelfMove(item.path())) {
                    readinessTracker.recordCandidate(item.path());
                }
            }
        } catch (Exception ignored) {
            // Folder may have become unavailable during scan
        }
    }

    private void runLoop() {
        long pollMillis = Math.max(25L, Math.min(200L, flushInterval.toMillis() / 2));
        while (running.get()) {
            try {
                WatchKey key = watchService.poll(pollMillis, TimeUnit.MILLISECONDS);
                if (key != null) {
                    handleWatchKey(key);
                }
                checkFolderAvailabilityAndRetries();
                flushIfDue();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Keep daemon loop alive across transient I/O errors
            }
        }
    }

    private synchronized void handleWatchKey(WatchKey key) {
        Path watchedFolder = keyToFolder.get(key);
        if (watchedFolder == null) {
            key.cancel();
            return;
        }

        boolean overflowOccurred = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                overflowOccurred = true;
                continue;
            }

            if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                Object context = event.context();
                if (context instanceof Path relativeChild) {
                    Path fullPath = watchedFolder.resolve(relativeChild).toAbsolutePath().normalize();
                    if (!fileExecutor.isRecentSelfMove(fullPath) && !PathSafety.isProtectedPath(fullPath)) {
                        readinessTracker.recordCandidate(fullPath);
                    }
                }
            }
        }

        boolean valid = key.reset();
        if (!valid || !Files.isDirectory(watchedFolder)) {
            markFolderUnavailable(watchedFolder);
        } else if (overflowOccurred) {
            rescanFolder(watchedFolder);
        }
    }

    private synchronized void checkFolderAvailabilityAndRetries() {
        Instant now = clock.instant();

        // 1. Check currently registered folders to detect deletion/disconnection promptly
        for (WatchedFolderConfig cfg : configuredFolders.values()) {
            String key = SettingsValidator.normalizePathKey(cfg.path());
            if (!cfg.enabled() || cfg.mode() == WatchMode.OFF) {
                continue;
            }
            WatchKey wk = folderKeyToWatchKey.get(key);
            if (wk != null && (!wk.isValid() || !Files.isDirectory(cfg.path()))) {
                markFolderUnavailable(cfg.path());
            }
        }

        // 2. Retry unavailable folders whose retry timer has elapsed
        for (WatchedFolderConfig cfg : configuredFolders.values()) {
            String key = SettingsValidator.normalizePathKey(cfg.path());
            if (!cfg.enabled() || cfg.mode() == WatchMode.OFF) {
                continue;
            }
            if (folderStatuses.get(key) == WatchedFolderStatus.UNAVAILABLE) {
                Instant retryTime = nextRetryAt.getOrDefault(key, Instant.EPOCH);
                if (!now.isBefore(retryTime)) {
                    if (Files.isDirectory(cfg.path()) && !PathSafety.isProtectedPath(cfg.path())) {
                        registerSingleFolder(cfg);
                    } else {
                        nextRetryAt.put(key, now.plus(unavailableRetryInterval));
                    }
                }
            }
        }
    }

    private synchronized void flushIfDue() {
        Instant now = clock.instant();
        if (Duration.between(lastFlushAt, now).compareTo(flushInterval) < 0) {
            return;
        }
        lastFlushAt = now;

        if (pipeline.isPaused()) {
            return;
        }

        List<FileItem> readyItems = readinessTracker.pollReadyFiles();
        if (readyItems.isEmpty()) {
            return;
        }

        // Group ready files by parent folder
        Map<String, List<FileItem>> byFolder = new LinkedHashMap<>();
        for (FileItem item : readyItems) {
            Path parent = item.path().toAbsolutePath().normalize().getParent();
            if (parent != null) {
                String folderKey = SettingsValidator.normalizePathKey(parent);
                byFolder.computeIfAbsent(folderKey, k -> new ArrayList<>()).add(item);
            }
        }

        for (Map.Entry<String, List<FileItem>> entry : byFolder.entrySet()) {
            WatchedFolderConfig cfg = configuredFolders.get(entry.getKey());
            if (cfg != null && cfg.enabled() && cfg.mode() != WatchMode.OFF) {
                pipeline.flush(cfg.path(), cfg.mode(), entry.getValue(), "AUTO");
            }
        }
    }

    private synchronized void syncRegistrations() {
        if (watchService == null) {
            return;
        }

        // Unregister folders that are removed, disabled, or set to OFF
        List<String> existingKeys = new ArrayList<>(folderKeyToWatchKey.keySet());
        for (String key : existingKeys) {
            WatchedFolderConfig cfg = configuredFolders.get(key);
            if (cfg == null || !cfg.enabled() || cfg.mode() == WatchMode.OFF) {
                WatchKey wk = folderKeyToWatchKey.remove(key);
                if (wk != null) {
                    keyToFolder.remove(wk);
                    wk.cancel();
                }
                nextRetryAt.remove(key);
                folderStatuses.put(key, WatchedFolderStatus.OFF);
            }
        }

        // Register enabled folders
        for (WatchedFolderConfig cfg : configuredFolders.values()) {
            String key = SettingsValidator.normalizePathKey(cfg.path());
            if (!cfg.enabled() || cfg.mode() == WatchMode.OFF) {
                folderStatuses.put(key, WatchedFolderStatus.OFF);
                continue;
            }
            if (!folderKeyToWatchKey.containsKey(key)) {
                registerSingleFolder(cfg);
            }
        }
    }

    private synchronized void registerSingleFolder(WatchedFolderConfig cfg) {
        Path normalized = cfg.path().toAbsolutePath().normalize();
        String key = SettingsValidator.normalizePathKey(normalized);

        if (PathSafety.isProtectedPath(normalized) || !Files.isDirectory(normalized)) {
            markFolderUnavailable(normalized);
            return;
        }

        try {
            WatchKey wk = normalized.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );
            folderKeyToWatchKey.put(key, wk);
            keyToFolder.put(wk, normalized);
            nextRetryAt.remove(key);
            WatchedFolderStatus previous = folderStatuses.put(key, WatchedFolderStatus.WATCHING);
            if (previous == WatchedFolderStatus.UNAVAILABLE) {
                pipeline.recordActivity("Watched folder is available again: " + normalized);
                rescanFolder(normalized);
            }
            pipeline.notifyStatusChanged();
        } catch (IOException e) {
            markFolderUnavailable(normalized);
        }
    }

    private synchronized void markFolderUnavailable(Path folder) {
        Path normalized = folder.toAbsolutePath().normalize();
        String key = SettingsValidator.normalizePathKey(normalized);
        WatchKey wk = folderKeyToWatchKey.remove(key);
        if (wk != null) {
            keyToFolder.remove(wk);
            wk.cancel();
        }
        readinessTracker.clearFolder(normalized);
        nextRetryAt.put(key, clock.instant().plus(unavailableRetryInterval));
        WatchedFolderStatus previous = folderStatuses.put(key, WatchedFolderStatus.UNAVAILABLE);
        if (previous != WatchedFolderStatus.UNAVAILABLE) {
            pipeline.recordActivity("Watched folder unavailable: " + normalized);
            pipeline.notifyStatusChanged();
        }
    }

    private void refreshStatusesWhenStopped() {
        for (WatchedFolderConfig cfg : configuredFolders.values()) {
            String key = SettingsValidator.normalizePathKey(cfg.path());
            if (!cfg.enabled() || cfg.mode() == WatchMode.OFF) {
                folderStatuses.put(key, WatchedFolderStatus.OFF);
            } else if (!Files.isDirectory(cfg.path())) {
                folderStatuses.put(key, WatchedFolderStatus.UNAVAILABLE);
            } else {
                folderStatuses.put(key, WatchedFolderStatus.WATCHING);
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = this.watcherThread;
        if (thread != null) {
            thread.interrupt();
        }
        WatchService ws = this.watchService;
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            keyToFolder.clear();
            folderKeyToWatchKey.clear();
            readinessTracker.clearAll();
        }
    }
}
