package com.tidyflow.core.dedupe;

import java.util.Objects;

/**
 * Configuration options for a duplicate scan.
 */
public record DedupeScanOptions(
        boolean recursive,
        long minSizeBytes,
        boolean skipHidden,
        KeeperStrategy keeperStrategy
) {
    public DedupeScanOptions {
        Objects.requireNonNull(keeperStrategy, "keeperStrategy must not be null");
        minSizeBytes = Math.max(1L, minSizeBytes);
    }

    public static DedupeScanOptions defaults() {
        return new DedupeScanOptions(true, 1L, true, KeeperStrategy.OLDEST_MODIFIED);
    }
}
