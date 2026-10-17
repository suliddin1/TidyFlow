package com.tidyflow.core.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Result of executing a batch of file moves and persisting the batch in SQLite.
 */
public record BatchExecutionResult(
        List<OperationResult> results,
        Long batchId,
        boolean databaseSaved,
        String databaseError,
        Path fallbackLogPath
) {
    public BatchExecutionResult {
        Objects.requireNonNull(results, "results must not be null");
        results = List.copyOf(results);
    }

    public long movedCount() {
        return results.stream().filter(OperationResult::success).count();
    }

    public long failedCount() {
        return results.size() - movedCount();
    }
}
