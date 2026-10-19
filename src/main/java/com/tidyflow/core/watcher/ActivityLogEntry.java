package com.tidyflow.core.watcher;

import java.time.Instant;
import java.util.Objects;

/**
 * Single entry in the in-memory Activity log (kept up to the last 50 events).
 */
public record ActivityLogEntry(
        Instant timestamp,
        String message
) {
    public ActivityLogEntry {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
