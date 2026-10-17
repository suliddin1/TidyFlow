package com.tidyflow.data;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a row in the batches table.
 */
public record BatchRecord(
        long id,
        String kind,
        Instant startedAt,
        Instant finishedAt,
        int filesCount,
        long bytesMoved
) {
    public BatchRecord {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
