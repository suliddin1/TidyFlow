package com.tidyflow.core.watcher;

import java.nio.file.Path;

/**
 * Listener notified of background automation events, activity entries, and status changes.
 */
public interface AutomationListener {

    default void onActivityAdded(ActivityLogEntry entry) {
    }

    default void onAutoBatchCompleted(String batchKind, Path folder, int movedCount, Long batchId) {
    }

    default void onFilesReadyForReview(Path folder, int readyCount, boolean triggeredByGuard, int guardThreshold) {
    }

    default void onStatusChanged() {
    }
}
