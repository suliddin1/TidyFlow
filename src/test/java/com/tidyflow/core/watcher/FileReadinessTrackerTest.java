package com.tidyflow.core.watcher;

import com.tidyflow.core.scanner.FileItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReadinessTrackerTest {

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeStatProvider implements FileStatProvider {
        private final Map<Path, FileStat> stats = new HashMap<>();

        void put(Path path, long size, Instant mtime, boolean unlocked) {
            stats.put(path.toAbsolutePath().normalize(), new FileStat(true, true, false, size, mtime, unlocked));
        }

        @Override
        public FileStat stat(Path path) {
            return stats.getOrDefault(path.toAbsolutePath().normalize(), FileStat.missing());
        }
    }

    @Test
    void growingFileIsNotReadyUntilStableForSettleSeconds() {
        Instant t0 = Instant.parse("2026-10-20T10:00:00Z");
        MutableClock clock = new MutableClock(t0);
        FakeStatProvider stats = new FakeStatProvider();
        FileReadinessTracker tracker = new FileReadinessTracker(clock, stats, Duration.ofSeconds(5));

        Path video = Path.of("C:\\Downloads\\movie.mp4").toAbsolutePath().normalize();
        stats.put(video, 1000L, t0, true);
        tracker.recordCandidate(video);

        // After 3 seconds, file grows -> stability timer resets
        clock.advanceSeconds(3);
        stats.put(video, 2500L, clock.instant(), true);
        assertTrue(tracker.pollReadyFiles().isEmpty());

        // 4 seconds after growth -> still < 5 seconds settle time
        clock.advanceSeconds(4);
        assertTrue(tracker.pollReadyFiles().isEmpty());

        // 1 more second (total 5s stable) -> ready!
        clock.advanceSeconds(1);
        List<FileItem> ready = tracker.pollReadyFiles();
        assertEquals(1, ready.size());
        assertEquals("movie.mp4", ready.get(0).name());
        assertEquals(2500L, ready.get(0).size());
        assertEquals(0, tracker.trackedCount());
    }

    @Test
    void tempDownloadExtensionsAreIgnored() {
        Instant t0 = Instant.parse("2026-10-20T10:00:00Z");
        MutableClock clock = new MutableClock(t0);
        FakeStatProvider stats = new FakeStatProvider();
        FileReadinessTracker tracker = new FileReadinessTracker(clock, stats, Duration.ofSeconds(5));

        for (String name : List.of("setup.exe.crdownload", "archive.zip.part", "report.tmp")) {
            Path p = Path.of("C:\\Downloads", name).toAbsolutePath().normalize();
            stats.put(p, 512L, t0, true);
            tracker.recordCandidate(p);
        }

        assertEquals(0, tracker.trackedCount());
        clock.advanceSeconds(10);
        assertTrue(tracker.pollReadyFiles().isEmpty());
    }

    @Test
    void lockedFileIsDeferredAndLaterAcceptedWhenUnlocked() {
        Instant t0 = Instant.parse("2026-10-20T10:00:00Z");
        MutableClock clock = new MutableClock(t0);
        FakeStatProvider stats = new FakeStatProvider();
        FileReadinessTracker tracker = new FileReadinessTracker(clock, stats, Duration.ofSeconds(5));

        Path doc = Path.of("C:\\Downloads\\contract.pdf").toAbsolutePath().normalize();
        stats.put(doc, 4096L, t0, false); // locked by another process
        tracker.recordCandidate(doc);

        clock.advanceSeconds(6);
        // Size/mtime settled for 6s, but still locked -> deferred, NOT dropped
        assertTrue(tracker.pollReadyFiles().isEmpty());
        assertEquals(1, tracker.trackedCount());

        // Process releases lock
        stats.put(doc, 4096L, t0, true);
        List<FileItem> ready = tracker.pollReadyFiles();
        assertEquals(1, ready.size());
        assertEquals("contract.pdf", ready.get(0).name());
        assertEquals(0, tracker.trackedCount());
    }
}
