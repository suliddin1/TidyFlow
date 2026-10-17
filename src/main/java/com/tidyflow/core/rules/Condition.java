package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

/**
 * Evaluates whether a scanned file satisfies a single criterion.
 */
@FunctionalInterface
public interface Condition {
    boolean matches(FileItem file);
}
