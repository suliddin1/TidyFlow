package com.tidyflow.core.scanner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata snapshot of a scanned file.
 */
public record FileItem(
        Path path,
        String name,
        String extension,
        long size,
        Instant lastModified
) {
    public FileItem {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(extension, "extension must not be null");
        Objects.requireNonNull(lastModified, "lastModified must not be null");
    }
}
