package com.tidyflow.core.dedupe;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User selection of files to remove within a single DuplicateGroup.
 */
public record DuplicateGroupSelection(
        DuplicateGroup group,
        Set<Path> selectedPaths
) {
    public DuplicateGroupSelection {
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(selectedPaths, "selectedPaths must not be null");
        selectedPaths = selectedPaths.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
    }
}
