package com.tidyflow.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Guardrails for preventing file operations in protected operating system directories.
 */
public final class PathSafety {

    private static final List<String> PROTECTED_ROOTS_LOWER = List.of(
            "c:\\windows",
            "c:\\program files",
            "c:\\program files (x86)"
    );

    private PathSafety() {
    }

    /**
     * Returns true if the given path resides inside a protected Windows or Program Files directory.
     */
    public static boolean isProtectedPath(Path path) {
        if (path == null) {
            return true;
        }
        Path normalized = path.isAbsolute() ? path.normalize() : path.toAbsolutePath().normalize();
        String normalizedLower = normalized.toString().replace('/', '\\').toLowerCase(Locale.ROOT);

        for (String rootLower : PROTECTED_ROOTS_LOWER) {
            if (normalizedLower.equals(rootLower) || normalizedLower.startsWith(rootLower + "\\")) {
                return true;
            }
        }
        return false;
    }
}
