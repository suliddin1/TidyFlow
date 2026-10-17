package com.tidyflow.core.dedupe;

import java.util.List;
import java.util.Objects;

/**
 * Final outcome of a completed duplicate scan.
 */
public record DedupeScanResult(
        List<DuplicateGroup> groups,
        int groupCount,
        int duplicateFileCount,
        long wastedBytes,
        int skippedUnreadableCount,
        int cacheHits,
        int filesHashed
) {
    public DedupeScanResult {
        Objects.requireNonNull(groups, "groups must not be null");
        groups = List.copyOf(groups);
    }

    public static DedupeScanResult fromGroups(
            List<DuplicateGroup> groups,
            int skippedUnreadableCount,
            int cacheHits,
            int filesHashed
    ) {
        int groupCount = groups.size();
        int duplicateFileCount = 0;
        long wastedBytes = 0L;
        for (DuplicateGroup group : groups) {
            if (group.files().size() > 1) {
                duplicateFileCount += (group.files().size() - 1);
                wastedBytes += group.wastedBytes();
            }
        }
        return new DedupeScanResult(
                groups,
                groupCount,
                duplicateFileCount,
                wastedBytes,
                skippedUnreadableCount,
                cacheHits,
                filesHashed
        );
    }
}
