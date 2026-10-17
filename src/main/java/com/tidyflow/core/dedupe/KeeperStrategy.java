package com.tidyflow.core.dedupe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Strategy for choosing which copy in a DuplicateGroup is marked as the suggested keeper.
 */
public enum KeeperStrategy {
    OLDEST_MODIFIED("Oldest modified", Comparator
            .comparing(FileEntry::lastModified)
            .thenComparing(e -> e.path().toString(), String.CASE_INSENSITIVE_ORDER)),

    NEWEST_MODIFIED("Newest modified", Comparator
            .comparing(FileEntry::lastModified, Comparator.reverseOrder())
            .thenComparing(e -> e.path().toString(), String.CASE_INSENSITIVE_ORDER)),

    SHORTEST_PATH("Shortest path", Comparator
            .comparingInt((FileEntry e) -> e.path().toString().length())
            .thenComparing(e -> e.path().toString(), String.CASE_INSENSITIVE_ORDER));

    private final String displayName;
    private final Comparator<FileEntry> comparator;

    KeeperStrategy(String displayName, Comparator<FileEntry> comparator) {
        this.displayName = displayName;
        this.comparator = comparator;
    }

    public String displayName() {
        return displayName;
    }

    public FileEntry selectKeeper(List<FileEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        return entries.stream().min(comparator).orElseThrow();
    }

    public DuplicateGroup apply(DuplicateGroup group) {
        Objects.requireNonNull(group, "group must not be null");
        if (group.files().isEmpty()) {
            return group;
        }
        FileEntry chosenKeeper = selectKeeper(group.files());
        List<FileEntry> updated = new ArrayList<>(group.files().size());
        for (FileEntry entry : group.files()) {
            boolean isKeeper = entry.path().equals(chosenKeeper.path());
            updated.add(entry.withSuggestedKeeper(isKeeper));
        }
        return new DuplicateGroup(group.sha256(), group.sizeBytes(), updated);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
