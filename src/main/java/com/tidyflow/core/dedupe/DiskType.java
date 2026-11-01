package com.tidyflow.core.dedupe;

import java.util.Locale;

/**
 * Target storage type for duplicate scanning, controlling worker thread concurrency.
 */
public enum DiskType {
    AUTO("Auto", 2),
    SSD("SSD", 4),
    HDD("HDD", 1);

    private final String displayName;
    private final int workerThreads;

    DiskType(String displayName, int workerThreads) {
        this.displayName = displayName;
        this.workerThreads = workerThreads;
    }

    public String displayName() {
        return displayName;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public static DiskType fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        for (DiskType type : values()) {
            if (type.name().equals(trimmed) || type.displayName.equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
