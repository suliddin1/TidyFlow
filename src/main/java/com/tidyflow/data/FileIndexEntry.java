package com.tidyflow.data;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a cached hash entry in the file_index table.
 */
public record FileIndexEntry(
        long id,
        Path path,
        long sizeBytes,
        Instant mtime,
        String quickHash,
        String sha256,
        Instant lastSeenAt
) {
    public FileIndexEntry {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(mtime, "mtime must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    }

    public static FileIndexEntry unsaved(
            Path path,
            long sizeBytes,
            Instant mtime,
            String quickHash,
            String sha256,
            Instant lastSeenAt
    ) {
        return new FileIndexEntry(
                0L,
                path.toAbsolutePath().normalize(),
                sizeBytes,
                mtime,
                quickHash,
                sha256,
                lastSeenAt
        );
    }

    public boolean matchesMetadata(long currentSizeBytes, Instant currentMtime) {
        return this.sizeBytes == currentSizeBytes && this.mtime.equals(currentMtime);
    }
}
