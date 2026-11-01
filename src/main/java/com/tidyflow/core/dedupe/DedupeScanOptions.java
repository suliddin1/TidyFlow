package com.tidyflow.core.dedupe;

import java.util.Objects;

/**
 * Configuration options for a duplicate scan.
 */
public record DedupeScanOptions(
        boolean recursive,
        long minSizeBytes,
        boolean skipHidden,
        KeeperStrategy keeperStrategy,
        FolderExclusionFilter exclusionFilter,
        DiskType diskType
) {
    public DedupeScanOptions {
        Objects.requireNonNull(keeperStrategy, "keeperStrategy must not be null");
        minSizeBytes = Math.max(1L, minSizeBytes);
        if (exclusionFilter == null) {
            exclusionFilter = FolderExclusionFilter.defaults();
        }
        if (diskType == null) {
            diskType = DiskType.AUTO;
        }
    }

    public DedupeScanOptions(
            boolean recursive,
            long minSizeBytes,
            boolean skipHidden,
            KeeperStrategy keeperStrategy
    ) {
        this(
                recursive,
                minSizeBytes,
                skipHidden,
                keeperStrategy,
                FolderExclusionFilter.defaults(),
                DiskType.AUTO
        );
    }

    public static DedupeScanOptions defaults() {
        return new DedupeScanOptions(
                true,
                1L,
                true,
                KeeperStrategy.OLDEST_MODIFIED,
                FolderExclusionFilter.defaults(),
                DiskType.AUTO
        );
    }

    public static DedupeScanOptions uiDefaults() {
        return new DedupeScanOptions(
                true,
                MinSizePreset.MB_1.minSizeBytes(),
                true,
                KeeperStrategy.OLDEST_MODIFIED,
                FolderExclusionFilter.defaults(),
                DiskType.AUTO
        );
    }
}
