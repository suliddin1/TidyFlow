package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

import java.nio.file.Path;

/**
 * Defines an operation to be applied when a rule matches a file.
 * Designed as an interface so additional action types (e.g., copy, archive, delete) can be added later.
 */
public interface Action {

    /**
     * Computes the target destination path for the given file without touching the file system.
     */
    Path resolveDestination(FileItem file);
}
