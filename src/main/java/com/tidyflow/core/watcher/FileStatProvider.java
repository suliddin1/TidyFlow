package com.tidyflow.core.watcher;

import java.nio.file.Path;

/**
 * Abstraction for querying file attributes and lock state, enabling deterministic unit tests.
 */
@FunctionalInterface
public interface FileStatProvider {
    FileStat stat(Path path);
}
