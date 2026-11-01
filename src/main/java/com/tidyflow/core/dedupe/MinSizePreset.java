package com.tidyflow.core.dedupe;

import java.util.Locale;

/**
 * Quick minimum file size presets for the duplicate scanner.
 * Default is {@link #MB_1} (1 MB) to avoid scanning hundreds of thousands of tiny files by default.
 */
public enum MinSizePreset {
    ANY("Any", 1L),
    KB_100("100 KB", 100L * 1024L),
    MB_1("1 MB", 1024L * 1024L),
    MB_10("10 MB", 10L * 1024L * 1024L),
    MB_100("100 MB", 100L * 1024L * 1024L);

    private final String label;
    private final long minSizeBytes;

    MinSizePreset(String label, long minSizeBytes) {
        this.label = label;
        this.minSizeBytes = minSizeBytes;
    }

    public String label() {
        return label;
    }

    public long minSizeBytes() {
        return minSizeBytes;
    }

    public static MinSizePreset fromString(String value) {
        if (value == null || value.isBlank()) {
            return MB_1;
        }
        String normalized = value.trim();
        for (MinSizePreset preset : values()) {
            if (preset.name().equalsIgnoreCase(normalized)
                    || preset.label.equalsIgnoreCase(normalized)) {
                return preset;
            }
        }
        return MB_1;
    }

    public static MinSizePreset fromBytes(long bytes) {
        for (MinSizePreset preset : values()) {
            if (preset.minSizeBytes == bytes) {
                return preset;
            }
        }
        if (bytes <= 1L) {
            return ANY;
        }
        return MB_1;
    }

    @Override
    public String toString() {
        return label;
    }
}
