package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Matches files whose last-modified timestamp is older than N days from the current time.
 */
public final class OlderThanDaysCondition implements Condition {

    private final long days;
    private final Clock clock;

    public OlderThanDaysCondition(long days) {
        this(days, Clock.systemUTC());
    }

    public OlderThanDaysCondition(long days, Clock clock) {
        this.days = days;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public long days() {
        return days;
    }

    @Override
    public boolean matches(FileItem file) {
        if (file == null || days <= 0) {
            return false;
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(days));
        return file.lastModified().isBefore(cutoff);
    }
}
