package com.tidyflow.data.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration for a single non-recursively watched folder.
 * Newly added folders always default to WatchMode.ASK.
 */
public record WatchedFolderConfig(
        Path path,
        WatchMode mode,
        boolean enabled
) {
    public WatchedFolderConfig {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
    }

    public static WatchedFolderConfig newDefault(Path path) {
        return new WatchedFolderConfig(path, WatchMode.ASK, true);
    }
}
