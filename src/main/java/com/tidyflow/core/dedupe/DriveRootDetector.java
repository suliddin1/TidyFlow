package com.tidyflow.core.dedupe;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Pure utility for detecting whether a path represents a filesystem or drive root
 * (for example {@code D:\}, {@code D:}, or a UNC share root {@code \\server\share\}),
 * while normal subdirectories such as {@code C:\Users} return {@code false}.
 */
public final class DriveRootDetector {

    private DriveRootDetector() {
    }

    public static boolean isDriveRoot(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.normalize();
        if (normalized.getRoot() == null) {
            return false;
        }
        return normalized.getNameCount() == 0;
    }

    public static boolean isDriveRoot(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        String trimmed = rawPath.trim();
        if (trimmed.matches("^[A-Za-z]:[/\\\\]*$")) {
            return true;
        }
        try {
            return isDriveRoot(Path.of(trimmed));
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
