package com.tidyflow.data;

import java.util.Objects;

/**
 * Represents a single time bucket (local day "yyyy-MM-dd" or local month "yyyy-MM") and its organized file count.
 */
public record TimeBucketCount(String label, long count) {
    public TimeBucketCount {
        Objects.requireNonNull(label, "label must not be null");
    }
}
