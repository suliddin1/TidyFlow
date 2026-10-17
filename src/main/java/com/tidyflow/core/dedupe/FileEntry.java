package com.tidyflow.core.dedupe;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single file inside a duplicate group.
 */
public record FileEntry(
        Path path,
        long sizeBytes,
        Instant lastModified,
        boolean suggestedKeeper
) {
    public FileEntry {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(lastModified, "lastModified must not be null");
    }

    public FileEntry withSuggestedKeeper(boolean keeper) {
        return new FileEntry(path, sizeBytes, lastModified, keeper);
    }

    public String fileName() {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }

    public String parentFolder() {
        Path parent = path.getParent();
        return parent != null ? parent.toString() : "";
    }
}
