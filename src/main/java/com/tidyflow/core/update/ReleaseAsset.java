package com.tidyflow.core.update;

import java.util.Objects;

/**
 * Metadata for a single downloadable asset attached to a GitHub Release.
 */
public record ReleaseAsset(
        String name,
        String downloadUrl,
        long sizeBytes
) {
    public ReleaseAsset {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(downloadUrl, "downloadUrl must not be null");
    }
}
