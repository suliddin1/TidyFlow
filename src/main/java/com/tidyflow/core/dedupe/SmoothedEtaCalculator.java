package com.tidyflow.core.dedupe;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * Pure exponentially-smoothed throughput (MB/s) and estimated time remaining (ETA) calculator.
 * Never returns an ETA before enough samples and elapsed time have been collected, and never
 * produces negative, NaN, or infinite values.
 */
public final class SmoothedEtaCalculator {

    public static final int DEFAULT_MIN_SAMPLES = 3;
    public static final long DEFAULT_MIN_ELAPSED_MILLIS = 600L;
    public static final double DEFAULT_ALPHA = 0.30;
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;
    private static final long MAX_REASONABLE_ETA_SECONDS = 365L * 24L * 3600L;

    public record EtaSnapshot(double throughputMbPerSec, OptionalLong etaSeconds) {
        public static EtaSnapshot unavailable() {
            return new EtaSnapshot(0.0, OptionalLong.empty());
        }
    }

    private final int minSamples;
    private final long minElapsedMillis;
    private final double alpha;
    private final LongSupplier clockMillis;

    private boolean started;
    private long startMillis;
    private long lastSampleMillis;
    private long lastBytesProcessed;
    private int sampleCount;
    private double smoothedBytesPerSec;

    public SmoothedEtaCalculator() {
        this(DEFAULT_MIN_SAMPLES, DEFAULT_MIN_ELAPSED_MILLIS, DEFAULT_ALPHA, () -> System.nanoTime() / 1_000_000L);
    }

    public SmoothedEtaCalculator(int minSamples, long minElapsedMillis, double alpha, LongSupplier clockMillis) {
        if (minSamples < 1) {
            throw new IllegalArgumentException("minSamples must be >= 1");
        }
        if (minElapsedMillis < 0) {
            throw new IllegalArgumentException("minElapsedMillis must be >= 0");
        }
        if (alpha <= 0.0 || alpha > 1.0 || Double.isNaN(alpha)) {
            throw new IllegalArgumentException("alpha must be in (0.0, 1.0]");
        }
        this.minSamples = minSamples;
        this.minElapsedMillis = minElapsedMillis;
        this.alpha = alpha;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis must not be null");
    }

    public synchronized void start() {
        long now = clockMillis.getAsLong();
        this.started = true;
        this.startMillis = now;
        this.lastSampleMillis = now;
        this.lastBytesProcessed = 0L;
        this.sampleCount = 0;
        this.smoothedBytesPerSec = 0.0;
    }

    public synchronized EtaSnapshot update(long rawBytesProcessed, long totalBytes) {
        long now = clockMillis.getAsLong();
        if (!started) {
            startMillis = now;
            lastSampleMillis = now;
            started = true;
        }
        if (totalBytes <= 0L) {
            return EtaSnapshot.unavailable();
        }

        long bytesProcessed = Math.max(0L, Math.min(rawBytesProcessed, totalBytes));
        long elapsedMillis = Math.max(0L, now - startMillis);
        long deltaMillis = now - lastSampleMillis;
        long deltaBytes = bytesProcessed - lastBytesProcessed;

        if (deltaMillis > 0L && deltaBytes > 0L) {
            double instBytesPerSec = (deltaBytes * 1000.0) / deltaMillis;
            if (Double.isFinite(instBytesPerSec) && instBytesPerSec > 0.0) {
                if (sampleCount == 0) {
                    smoothedBytesPerSec = elapsedMillis > 0L
                            ? (bytesProcessed * 1000.0) / elapsedMillis
                            : instBytesPerSec;
                } else {
                    smoothedBytesPerSec = alpha * instBytesPerSec + (1.0 - alpha) * smoothedBytesPerSec;
                }
                sampleCount++;
                lastSampleMillis = now;
                lastBytesProcessed = bytesProcessed;
            }
        } else if (deltaBytes > 0L && elapsedMillis > 0L && sampleCount == 0) {
            double avgBytesPerSec = (bytesProcessed * 1000.0) / elapsedMillis;
            if (Double.isFinite(avgBytesPerSec) && avgBytesPerSec > 0.0) {
                smoothedBytesPerSec = avgBytesPerSec;
                sampleCount++;
                lastSampleMillis = now;
                lastBytesProcessed = bytesProcessed;
            }
        }

        double mbPerSec = smoothedBytesPerSec / BYTES_PER_MB;
        if (!Double.isFinite(mbPerSec) || mbPerSec < 0.0) {
            mbPerSec = 0.0;
        }

        if (bytesProcessed >= totalBytes && sampleCount >= minSamples && elapsedMillis >= minElapsedMillis) {
            return new EtaSnapshot(mbPerSec, OptionalLong.of(0L));
        }

        if (sampleCount < minSamples || elapsedMillis < minElapsedMillis || smoothedBytesPerSec <= 1.0) {
            return new EtaSnapshot(mbPerSec, OptionalLong.empty());
        }

        long remainingBytes = Math.max(0L, totalBytes - bytesProcessed);
        double rawEtaSeconds = remainingBytes / smoothedBytesPerSec;
        if (!Double.isFinite(rawEtaSeconds) || rawEtaSeconds < 0.0) {
            return new EtaSnapshot(mbPerSec, OptionalLong.empty());
        }

        long clampedEta = Math.min(MAX_REASONABLE_ETA_SECONDS, Math.max(0L, Math.round(rawEtaSeconds)));
        return new EtaSnapshot(mbPerSec, OptionalLong.of(clampedEta));
    }

    public static String formatDurationSeconds(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long hours = clamped / 3600L;
        long minutes = (clamped % 3600L) / 60L;
        long seconds = clamped % 60L;
        if (hours > 0) {
            return String.format("%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }
}
