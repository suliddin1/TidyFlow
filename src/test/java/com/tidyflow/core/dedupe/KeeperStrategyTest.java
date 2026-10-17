package com.tidyflow.core.dedupe;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeeperStrategyTest {

    private final FileEntry oldestAndLongPath = new FileEntry(
            Path.of("C:\\Downloads\\subfolder\\deep\\archive_backup_copy.zip"),
            1000L,
            Instant.parse("2026-01-01T10:00:00Z"),
            false
    );

    private final FileEntry middleAndShortPath = new FileEntry(
            Path.of("C:\\a.zip"),
            1000L,
            Instant.parse("2026-05-01T10:00:00Z"),
            false
    );

    private final FileEntry newest = new FileEntry(
            Path.of("C:\\Downloads\\latest.zip"),
            1000L,
            Instant.parse("2026-10-01T10:00:00Z"),
            false
    );

    private final DuplicateGroup rawGroup = new DuplicateGroup(
            "abc123",
            1000L,
            List.of(oldestAndLongPath, middleAndShortPath, newest)
    );

    @Test
    void oldestModifiedStrategyPicksOldestFile() {
        DuplicateGroup applied = KeeperStrategy.OLDEST_MODIFIED.apply(rawGroup);

        assertTrue(applied.files().get(0).suggestedKeeper());
        assertFalse(applied.files().get(1).suggestedKeeper());
        assertFalse(applied.files().get(2).suggestedKeeper());
        assertEquals(oldestAndLongPath.path(), KeeperStrategy.OLDEST_MODIFIED.selectKeeper(rawGroup.files()).path());
    }

    @Test
    void newestModifiedStrategyPicksNewestFile() {
        DuplicateGroup applied = KeeperStrategy.NEWEST_MODIFIED.apply(rawGroup);

        assertFalse(applied.files().get(0).suggestedKeeper());
        assertFalse(applied.files().get(1).suggestedKeeper());
        assertTrue(applied.files().get(2).suggestedKeeper());
        assertEquals(newest.path(), KeeperStrategy.NEWEST_MODIFIED.selectKeeper(rawGroup.files()).path());
    }

    @Test
    void shortestPathStrategyPicksShortestPathFile() {
        DuplicateGroup applied = KeeperStrategy.SHORTEST_PATH.apply(rawGroup);

        assertFalse(applied.files().get(0).suggestedKeeper());
        assertTrue(applied.files().get(1).suggestedKeeper());
        assertFalse(applied.files().get(2).suggestedKeeper());
        assertEquals(middleAndShortPath.path(), KeeperStrategy.SHORTEST_PATH.selectKeeper(rawGroup.files()).path());
    }
}
