package com.tidyflow.core.watcher;

import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.data.BatchRecord;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.config.AppSettings;
import com.tidyflow.data.config.ScheduleConfig;
import com.tidyflow.data.config.WatchMode;
import com.tidyflow.data.config.WatchedFolderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailySchedulerServiceTest {

    @Test
    void nextRunBeforeTodaysTimeReturnsToday() {
        ZoneId zone = ZoneId.of("America/New_York");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), zone); // 08:00 EDT
        ZonedDateTime now = ZonedDateTime.now(fixedClock);

        ZonedDateTime next = DailySchedulerService.nextRun(now, "09:30");

        assertEquals(now.toLocalDate(), next.toLocalDate());
        assertEquals(9, next.getHour());
        assertEquals(30, next.getMinute());
        assertEquals(zone, next.getZone());
    }

    @Test
    void nextRunAfterTodaysTimeReturnsTomorrow() {
        ZoneId zone = ZoneId.of("America/New_York");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-10-20T16:00:00Z"), zone); // 12:00 EDT
        ZonedDateTime now = ZonedDateTime.now(fixedClock);

        ZonedDateTime next = DailySchedulerService.nextRun(now, "09:30");

        assertEquals(now.toLocalDate().plusDays(1), next.toLocalDate());
        assertEquals(9, next.getHour());
        assertEquals(30, next.getMinute());
    }

    @Test
    void nextRunAcrossDaylightSavingChangeAdjustsOffsetCorrectly() {
        // US Fall-back DST transition in 2026 is on 2026-11-01
        ZoneId zone = ZoneId.of("America/New_York");
        ZonedDateTime beforeTransition = ZonedDateTime.of(2026, 10, 31, 22, 0, 0, 0, zone); // EDT (-04:00)

        ZonedDateTime next = DailySchedulerService.nextRun(beforeTransition, "09:00");

        assertEquals(2026, next.getYear());
        assertEquals(11, next.getMonthValue());
        assertEquals(1, next.getDayOfMonth());
        assertEquals(9, next.getHour());
        assertEquals(0, next.getMinute());
        // After 2 AM on Nov 1, 2026, New York is in EST (-05:00)
        assertEquals("-05:00", next.getOffset().getId());
    }

    @Test
    void nextRunRejectsInvalidTimeString() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedulerService.nextRun(now, "25:99"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedulerService.nextRun(now, "bad"));
    }

    @Test
    void scheduledPassOrganizesAutoWatchedFolderWithBatchKindScheduled(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("tidyflow.db"));
        dbManager.migrate();
        BatchRepository batchRepo = new BatchRepository(dbManager);
        OperationRepository opRepo = new OperationRepository(dbManager);
        FileExecutor executor = new FileExecutor(batchRepo, opRepo, tempDir.resolve("fallback.log"));
        AutomationPipeline pipeline = new AutomationPipeline(executor, DefaultRules::create);

        Path watched = Files.createDirectories(tempDir.resolve("Downloads"));
        Files.writeString(watched.resolve("slide.pdf"), "slides");

        try (DailySchedulerService scheduler = new DailySchedulerService(new FolderScanner(), pipeline)) {
            AppSettings settings = new AppSettings(
                    List.of(new WatchedFolderConfig(watched, WatchMode.AUTO, true)),
                    5, 50, true, true, false,
                    new ScheduleConfig(true, "09:00")
            );
            scheduler.updateSettings(settings);
            scheduler.runScheduledPass();
        }

        assertTrue(Files.exists(watched.resolve("Documents").resolve("slide.pdf")));
        List<BatchRecord> batches = batchRepo.findAllNewestFirst();
        assertEquals(1, batches.size());
        assertEquals("SCHEDULED", batches.get(0).kind());
    }
}
