package com.tidyflow.core.rules;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Dry-run preview entry describing a planned file move produced by the RuleEngine.
 */
public record PlannedOperation(
        Path source,
        Path destination,
        String ruleName
) {
    public PlannedOperation {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(ruleName, "ruleName must not be null");
    }
}
