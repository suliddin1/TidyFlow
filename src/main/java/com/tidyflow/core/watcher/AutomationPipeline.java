package com.tidyflow.core.watcher;

import com.tidyflow.core.executor.BatchExecutionResult;
import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.executor.OperationResult;
import com.tidyflow.core.rules.PlannedOperation;
import com.tidyflow.core.rules.Rule;
import com.tidyflow.core.rules.RuleEngine;
import com.tidyflow.core.scanner.FileItem;
import com.tidyflow.data.config.WatchMode;
import com.tidyflow.util.PathSafety;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Coordinates rule planning, AUTO vs ASK handling, the mass-move guard,
 * activity logging (bounded to 50 entries), and listener notifications.
 */
public class AutomationPipeline {

    public static final int MAX_ACTIVITY_ENTRIES = 50;

    private final FileExecutor fileExecutor;
    private final Supplier<List<Rule>> rulesSupplier;
    private final Clock clock;
    private final Deque<ActivityLogEntry> activityLog = new ArrayDeque<>(MAX_ACTIVITY_ENTRIES);
    private final List<AutomationListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean paused = false;
    private volatile int massMoveGuardThreshold = 50;

    public AutomationPipeline(FileExecutor fileExecutor, Supplier<List<Rule>> rulesSupplier) {
        this(fileExecutor, rulesSupplier, Clock.systemDefaultZone());
    }

    public AutomationPipeline(FileExecutor fileExecutor, Supplier<List<Rule>> rulesSupplier, Clock clock) {
        this.fileExecutor = Objects.requireNonNull(fileExecutor, "fileExecutor must not be null");
        this.rulesSupplier = Objects.requireNonNull(rulesSupplier, "rulesSupplier must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void addListener(AutomationListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(AutomationListener listener) {
        listeners.remove(listener);
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        boolean changed = this.paused != paused;
        this.paused = paused;
        if (changed) {
            recordActivity(paused ? "Automation paused" : "Automation resumed");
            notifyStatusChanged();
        }
    }

    public int massMoveGuardThreshold() {
        return massMoveGuardThreshold;
    }

    public void setMassMoveGuardThreshold(int massMoveGuardThreshold) {
        this.massMoveGuardThreshold = massMoveGuardThreshold;
    }

    public FlushOutcome flush(Path watchedFolder, WatchMode mode, List<FileItem> readyFiles, String batchKind) {
        Objects.requireNonNull(watchedFolder, "watchedFolder must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(readyFiles, "readyFiles must not be null");
        Objects.requireNonNull(batchKind, "batchKind must not be null");

        if (paused || mode == WatchMode.OFF) {
            return FlushOutcome.skippedPaused(watchedFolder);
        }

        Path normalizedFolder = watchedFolder.toAbsolutePath().normalize();
        if (PathSafety.isProtectedPath(normalizedFolder)) {
            return FlushOutcome.nothingToDo(normalizedFolder);
        }

        // Only consider files directly inside the watched folder and not in protected paths
        List<FileItem> directFiles = readyFiles.stream()
                .filter(f -> f != null && f.path() != null)
                .filter(f -> {
                    Path parent = f.path().toAbsolutePath().normalize().getParent();
                    return normalizedFolder.equals(parent) && !PathSafety.isProtectedPath(f.path());
                })
                .toList();

        if (directFiles.isEmpty()) {
            return FlushOutcome.nothingToDo(normalizedFolder);
        }

        RuleEngine engine = new RuleEngine(rulesSupplier.get());
        List<PlannedOperation> plans = engine.evaluate(directFiles).stream()
                .filter(p -> !PathSafety.isProtectedPath(p.source()) && !PathSafety.isProtectedPath(p.destination()))
                .toList();

        if (plans.isEmpty()) {
            return FlushOutcome.nothingToDo(normalizedFolder);
        }

        int threshold = this.massMoveGuardThreshold;
        if (mode == WatchMode.AUTO && (plans.size() > threshold || directFiles.size() > threshold)) {
            int count = Math.max(plans.size(), directFiles.size());
            recordActivity("Guard: " + count + " files, not moved automatically");
            notifyFilesReady(normalizedFolder, plans.size(), true, threshold);
            return new FlushOutcome(normalizedFolder, plans.size(), 0, false, true, true, false, null);
        }

        if (mode == WatchMode.ASK) {
            recordActivity(plans.size() + " files ready in " + normalizedFolder + " (waiting for you)");
            notifyFilesReady(normalizedFolder, plans.size(), false, threshold);
            return new FlushOutcome(normalizedFolder, plans.size(), 0, false, true, false, false, null);
        }

        // WatchMode.AUTO
        BatchExecutionResult execResult = fileExecutor.executeBatch(plans, batchKind);
        int movedCount = (int) execResult.movedCount();

        for (OperationResult opResult : execResult.results()) {
            if (opResult.success() && opResult.actualDestination() != null) {
                String targetDisplay = formatTargetFolder(normalizedFolder, opResult.actualDestination());
                recordActivity("Moved " + opResult.operation().source().getFileName()
                        + " -> " + targetDisplay + " (rule: " + opResult.operation().ruleName() + ")");
            }
        }

        if (movedCount > 0) {
            notifyAutoBatchCompleted(batchKind, normalizedFolder, movedCount, execResult.batchId());
        }

        return new FlushOutcome(normalizedFolder, plans.size(), movedCount, true, false, false, false, execResult);
    }

    public synchronized void recordActivity(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        ActivityLogEntry entry = new ActivityLogEntry(clock.instant(), message);
        while (activityLog.size() >= MAX_ACTIVITY_ENTRIES) {
            activityLog.removeLast();
        }
        activityLog.addFirst(entry);
        for (AutomationListener listener : listeners) {
            listener.onActivityAdded(entry);
        }
    }

    public synchronized List<ActivityLogEntry> recentActivities() {
        return List.copyOf(activityLog);
    }

    public void notifyStatusChanged() {
        for (AutomationListener listener : listeners) {
            listener.onStatusChanged();
        }
    }

    private void notifyAutoBatchCompleted(String batchKind, Path folder, int movedCount, Long batchId) {
        for (AutomationListener listener : listeners) {
            listener.onAutoBatchCompleted(batchKind, folder, movedCount, batchId);
        }
    }

    private void notifyFilesReady(Path folder, int count, boolean triggeredByGuard, int threshold) {
        for (AutomationListener listener : listeners) {
            listener.onFilesReadyForReview(folder, count, triggeredByGuard, threshold);
        }
    }

    private static String formatTargetFolder(Path watchedFolder, Path actualDestination) {
        Path destParent = actualDestination.toAbsolutePath().normalize().getParent();
        if (destParent == null) {
            return actualDestination.toString();
        }
        if (destParent.startsWith(watchedFolder)) {
            Path rel = watchedFolder.relativize(destParent);
            String relStr = rel.toString();
            if (!relStr.isEmpty()) {
                return relStr;
            }
        }
        return destParent.toString();
    }
}
