package com.tidyflow.data;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Complete dashboard statistics snapshot for a selected time range.
 */
public record DashboardStats(
        long filesOrganized,
        long spaceOrganizedBytes,
        long duplicatesRemoved,
        long spaceFreedBytes,
        long batchesCount,
        Instant lastActivity,
        List<RuleCount> filesByRule,
        List<RuleCount> topRulesWithOther,
        List<TimeBucketCount> filesPerPeriod,
        boolean monthlyBuckets,
        List<BatchRecord> recentBatches
) {
    public DashboardStats {
        Objects.requireNonNull(filesByRule, "filesByRule must not be null");
        Objects.requireNonNull(topRulesWithOther, "topRulesWithOther must not be null");
        Objects.requireNonNull(filesPerPeriod, "filesPerPeriod must not be null");
        Objects.requireNonNull(recentBatches, "recentBatches must not be null");
        filesByRule = List.copyOf(filesByRule);
        topRulesWithOther = List.copyOf(topRulesWithOther);
        filesPerPeriod = List.copyOf(filesPerPeriod);
        recentBatches = List.copyOf(recentBatches);
    }

    public boolean isEmpty() {
        return filesOrganized == 0L
                && duplicatesRemoved == 0L
                && batchesCount == 0L
                && recentBatches.isEmpty();
    }
}
