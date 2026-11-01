package com.tidyflow.core.dedupe;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluates whether a directory should be skipped during the duplicate scan walk.
 * Supports case-insensitive folder names and normalized full directory paths.
 */
public final class FolderExclusionFilter {

    public static final List<String> DEFAULT_EXCLUDED_NAMES = List.of(
            "node_modules",
            ".git",
            ".svn",
            ".hg",
            ".gradle",
            ".idea",
            ".m2",
            "__pycache__",
            "venv",
            ".venv",
            "site-packages",
            "steamapps",
            "SteamLibrary",
            "$RECYCLE.BIN",
            "System Volume Information"
    );

    private final Set<String> excludedNamesLower;
    private final Set<String> excludedPathsNormalized;
    private final List<String> rawFolderNames;
    private final List<String> rawFullPaths;

    public FolderExclusionFilter(Collection<String> folderNames, Collection<String> fullPaths) {
        Set<String> namesLower = new LinkedHashSet<>();
        Set<String> pathsNorm = new LinkedHashSet<>();
        Set<String> preservedNames = new LinkedHashSet<>();
        Set<String> preservedPaths = new LinkedHashSet<>();

        if (folderNames != null) {
            for (String entry : folderNames) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String trimmed = entry.trim();
                if (looksLikePath(trimmed)) {
                    String norm = normalizePathString(trimmed);
                    if (!norm.isEmpty()) {
                        pathsNorm.add(norm);
                        preservedPaths.add(trimmed);
                    }
                } else {
                    namesLower.add(trimmed.toLowerCase(Locale.ROOT));
                    preservedNames.add(trimmed);
                }
            }
        }

        if (fullPaths != null) {
            for (String pathEntry : fullPaths) {
                if (pathEntry == null || pathEntry.isBlank()) {
                    continue;
                }
                String trimmed = pathEntry.trim();
                String norm = normalizePathString(trimmed);
                if (!norm.isEmpty()) {
                    pathsNorm.add(norm);
                    preservedPaths.add(trimmed);
                }
            }
        }

        this.excludedNamesLower = Set.copyOf(namesLower);
        this.excludedPathsNormalized = Set.copyOf(pathsNorm);
        this.rawFolderNames = List.copyOf(preservedNames);
        this.rawFullPaths = List.copyOf(preservedPaths);
    }

    public static FolderExclusionFilter defaults() {
        return new FolderExclusionFilter(DEFAULT_EXCLUDED_NAMES, List.of());
    }

    public static FolderExclusionFilter none() {
        return new FolderExclusionFilter(List.of(), List.of());
    }

    public List<String> folderNames() {
        return rawFolderNames;
    }

    public List<String> fullPaths() {
        return rawFullPaths;
    }

    /**
     * Returns true if {@code directory} matches an excluded folder name (case-insensitive)
     * or matches/resides under an excluded full path.
     */
    public boolean isExcluded(Path directory) {
        if (directory == null) {
            return false;
        }
        Path fileName = directory.getFileName();
        if (fileName != null) {
            String nameLower = fileName.toString().toLowerCase(Locale.ROOT);
            if (excludedNamesLower.contains(nameLower)) {
                return true;
            }
        }
        if (!excludedPathsNormalized.isEmpty()) {
            String candidateNorm = normalizePath(directory);
            for (String excludedPath : excludedPathsNormalized) {
                if (candidateNorm.equals(excludedPath)
                        || candidateNorm.startsWith(excludedPath + "\\")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksLikePath(String value) {
        return value.indexOf('\\') >= 0 || value.indexOf('/') >= 0 || value.indexOf(':') >= 0;
    }

    public static String normalizePath(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        String raw = path.isAbsolute()
                ? path.normalize().toString()
                : path.toAbsolutePath().normalize().toString();
        return stripTrailingBackslash(raw.replace('/', '\\').toLowerCase(Locale.ROOT));
    }

    private static String normalizePathString(String rawPath) {
        try {
            return normalizePath(Path.of(rawPath));
        } catch (Exception e) {
            return stripTrailingBackslash(rawPath.trim().replace('/', '\\').toLowerCase(Locale.ROOT));
        }
    }

    private static String stripTrailingBackslash(String s) {
        if (s.length() > 3 && s.endsWith("\\")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
