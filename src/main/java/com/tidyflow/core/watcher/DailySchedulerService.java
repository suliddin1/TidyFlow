package com.tidyflow.core.watcher;

import com.tidyflow.core.scanner.FileItem;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.data.config.AppSettings;
import com.tidyflow.data.config.ScheduleConfig;
import com.tidyflow.data.config.SettingsValidator;
import com.tidyflow.data.config.WatchMode;
import com.tidyflow.data.config.WatchedFolderConfig;
import com.tidyflow.util.PathSafety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a daily scheduled organization pass at schedule.timeOfDay in the system time zone,
 * correctly handling daylight saving time transitions and never catching up on missed runs.
 */
public class DailySchedulerService implements AutoCloseable {

    private final FolderScanner scanner;
    private final AutomationPipeline pipeline;
    private final Clock clock;
    private final ScheduledExecutorService executor;

    private volatile AppSettings currentSettings = AppSettings.defaults();
    private volatile ScheduledFuture<?> scheduledTask;

    public DailySchedulerService(FolderScanner scanner, AutomationPipeline pipeline) {
        this(scanner, pipeline, Clock.systemDefaultZone());
    }

    public DailySchedulerService(FolderScanner scanner, AutomationPipeline pipeline, Clock clock) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tidyflow-daily-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Pure function computing the next run ZonedDateTime for a given current ZonedDateTime and "HH:mm" string.
     * Handles before today's time, after today's time (tomorrow), and daylight saving time changes.
     */
    public static ZonedDateTime nextRun(ZonedDateTime now, String timeOfDay) {
        Objects.requireNonNull(now, "now must not be null");
        LocalTime targetTime = SettingsValidator.parseTimeOfDay(timeOfDay);

        ZonedDateTime candidateToday = now.toLocalDate().atTime(targetTime).atZone(now.getZone());
        if (candidateToday.isAfter(now)) {
            return candidateToday;
        }
        return now.toLocalDate().plusDays(1).atTime(targetTime).atZone(now.getZone());
    }

    public synchronized void updateSettings(AppSettings settings) {
        this.currentSettings = Objects.requireNonNull(settings, "settings must not be null");
        rescheduleNext();
    }

    private synchronized void rescheduleNext() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }

        ScheduleConfig schedule = currentSettings.schedule();
        if (schedule == null || !schedule.enabled() || !SettingsValidator.isValidTimeOfDay(schedule.timeOfDay())) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime next = nextRun(now, schedule.timeOfDay());
        long delayMillis = Math.max(0L, Duration.between(now.toInstant(), next.toInstant()).toMillis());

        scheduledTask = executor.schedule(this::onScheduledTrigger, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void onScheduledTrigger() {
        try {
            runScheduledPass();
        } finally {
            synchronized (this) {
                if (!executor.isShutdown()) {
                    rescheduleNext();
                }
            }
        }
    }

    /**
     * Executes one scheduled pass across all enabled watched folders whose mode is not OFF.
     */
    public void runScheduledPass() {
        if (pipeline.isPaused()) {
            return;
        }

        AppSettings settings = this.currentSettings;
        int totalMoved = 0;
        boolean anyAskOrGuard = false;

        for (WatchedFolderConfig folderCfg : settings.watchedFolders()) {
            if (folderCfg == null || !folderCfg.enabled() || folderCfg.mode() == WatchMode.OFF) {
                continue;
            }
            Path folder = folderCfg.path().toAbsolutePath().normalize();
            if (!Files.isDirectory(folder) || PathSafety.isProtectedPath(folder)) {
                continue;
            }
            try {
                List<FileItem> items = scanner.scan(folder);
                FlushOutcome outcome = pipeline.flush(folder, folderCfg.mode(), items, "SCHEDULED");
                totalMoved += outcome.movedCount();
                if (outcome.emittedAskReadyEvent() || outcome.downgradedByMassMoveGuard()) {
                    anyAskOrGuard = true;
                }
            } catch (Exception ignored) {
                // Skip unavailable folder during scheduled pass
            }
        }

        if (!anyAskOrGuard) {
            pipeline.recordActivity("Scheduled run: " + totalMoved + " files organized");
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
            }
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
