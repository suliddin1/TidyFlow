package com.tidyflow.core.dedupe;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Pure rate-limiter that throttles progress updates to at most a fixed frequency
 * (default 5 updates per second, i.e. 200 ms minimum interval) while always emitting
 * stage transitions and forced completion/summary events.
 */
public final class ProgressThrottler {

    public static final long DEFAULT_MIN_INTERVAL_MILLIS = 200L; // 5 Hz max

    private final long minIntervalMillis;
    private final LongSupplier clockMillis;
    private long lastEmitMillis = Long.MIN_VALUE;
    private String lastStageName = null;

    public ProgressThrottler() {
        this(DEFAULT_MIN_INTERVAL_MILLIS, () -> System.nanoTime() / 1_000_000L);
    }

    public ProgressThrottler(long minIntervalMillis, LongSupplier clockMillis) {
        if (minIntervalMillis < 0) {
            throw new IllegalArgumentException("minIntervalMillis must be >= 0");
        }
        this.minIntervalMillis = minIntervalMillis;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis must not be null");
    }

    public synchronized boolean shouldEmit() {
        return shouldEmit(lastStageName, false);
    }

    public synchronized boolean shouldEmit(String stageName, boolean force) {
        long now = clockMillis.getAsLong();
        boolean stageChanged = !Objects.equals(stageName, lastStageName);
        if (force || stageChanged || lastEmitMillis == Long.MIN_VALUE || (now - lastEmitMillis) >= minIntervalMillis) {
            lastEmitMillis = now;
            lastStageName = stageName;
            return true;
        }
        return false;
    }

    public synchronized void reset() {
        lastEmitMillis = Long.MIN_VALUE;
        lastStageName = null;
    }
}
