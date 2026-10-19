package com.tidyflow.data.config;

import java.util.Objects;

/**
 * Daily schedule configuration (enabled flag and 24-hour "HH:mm" time of day).
 */
public record ScheduleConfig(
        boolean enabled,
        String timeOfDay
) {
    public static final String DEFAULT_TIME_OF_DAY = "09:00";

    public ScheduleConfig {
        Objects.requireNonNull(timeOfDay, "timeOfDay must not be null");
    }

    public static ScheduleConfig defaults() {
        return new ScheduleConfig(false, DEFAULT_TIME_OF_DAY);
    }
}
