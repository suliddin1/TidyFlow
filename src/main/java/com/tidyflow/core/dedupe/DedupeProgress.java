package com.tidyflow.core.dedupe;

import java.util.Objects;

/**
 * Progress snapshot emitted during a duplicate scan.
 */
public record DedupeProgress(
        String stageName,
        int processedCount,
        int totalCount
) {
    public DedupeProgress {
        Objects.requireNonNull(stageName, "stageName must not be null");
    }
}
