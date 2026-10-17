package com.tidyflow.core.dedupe;

import java.util.List;
import java.util.Objects;

/**
 * A group of two or more files with identical size and full SHA-256 content hash.
 */
public record DuplicateGroup(
        String sha256,
        long sizeBytes,
        List<FileEntry> files
) {
    public DuplicateGroup {
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(files, "files must not be null");
        files = List.copyOf(files);
    }

    public long wastedBytes() {
        if (files.size() <= 1) {
            return 0L;
        }
        return (long) (files.size() - 1) * sizeBytes;
    }
}
