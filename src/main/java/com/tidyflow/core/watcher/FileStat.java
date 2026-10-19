package com.tidyflow.core.watcher;

import java.time.Instant;

/**
 * Snapshot of a candidate file's attributes and lock status.
 */
public record FileStat(
        boolean exists,
        boolean regularFile,
        boolean hidden,
        long size,
        Instant lastModified,
        boolean readableAndUnlocked
) {
    public static FileStat missing() {
        return new FileStat(false, false, false, 0L, Instant.EPOCH, false);
    }
}
