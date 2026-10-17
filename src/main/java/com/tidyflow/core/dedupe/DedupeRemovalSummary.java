package com.tidyflow.core.dedupe;

import java.util.List;
import java.util.Objects;

/**
 * Outcome summary of moving selected duplicate files to the Recycle Bin.
 */
public record DedupeRemovalSummary(
        int removedCount,
        int skippedCount,
        int failedCount,
        long bytesFreed,
        Long batchId,
        List<String> details
) {
    public DedupeRemovalSummary {
        Objects.requireNonNull(details, "details must not be null");
        details = List.copyOf(details);
    }

    public String formatSummary() {
        return String.format("%d removed, %d skipped, %d failed", removedCount, skippedCount, failedCount);
    }
}
