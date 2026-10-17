package com.tidyflow.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Guardrails for preventing file operations in protected operating system directories.
 */
public final class PathSafety {

    private static final List<Path> PROTECTED_ROOTS = List.of(
            Path.of("C:\\Windows"),
            Path.of("C:\\Program Files"),
            Path.of("C:\\Program Files (x86)")
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
        Path normalized = path.toAbsolutePath().normalize();
        String normalizedLower = normalized.toString().replace('/', '\\').toLowerCase(Locale.ROOT);

        for (Path protectedRoot : PROTECTED_ROOTS) {
            String rootLower = protectedRoot.toAbsolutePath().normalize()
                    .toString().replace('/', '\\').toLowerCase(Locale.ROOT);
            if (normalizedLower.equals(rootLower) || normalizedLower.startsWith(rootLower + "\\")) {
                return true;
            }
        }
        return false;
    }
}
