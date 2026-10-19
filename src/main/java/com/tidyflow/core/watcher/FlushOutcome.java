package com.tidyflow.core.watcher;

import com.tidyflow.core.executor.BatchExecutionResult;

import java.nio.file.Path;

/**
 * Outcome of processing a single folder flush or scheduled pass through AutomationPipeline.
 */
public record FlushOutcome(
        Path folder,
        int plannedCount,
        int movedCount,
        boolean executedAutoMove,
        boolean emittedAskReadyEvent,
        boolean downgradedByMassMoveGuard,
        boolean skippedBecausePaused,
        BatchExecutionResult batchExecutionResult
) {
    public static FlushOutcome skippedPaused(Path folder) {
        return new FlushOutcome(folder, 0, 0, false, false, false, true, null);
    }

    public static FlushOutcome nothingToDo(Path folder) {
        return new FlushOutcome(folder, 0, 0, false, false, false, false, null);
    }
}
