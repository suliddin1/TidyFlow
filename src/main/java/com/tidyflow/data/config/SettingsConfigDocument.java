package com.tidyflow.data.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson serialization DTOs for settings.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SettingsConfigDocument {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private List<WatchedFolderDto> watchedFolders = new ArrayList<>();
    private int settleSeconds = AppSettings.DEFAULT_SETTLE_SECONDS;
    private int massMoveGuardThreshold = AppSettings.DEFAULT_MASS_MOVE_GUARD_THRESHOLD;
    private boolean showNotifications = true;
    private boolean minimizeToTray = true;
    private boolean startWithWindows = false;
    private ScheduleDto schedule = new ScheduleDto(false, ScheduleConfig.DEFAULT_TIME_OF_DAY);
    private ThemeMode theme = ThemeMode.SYSTEM;

    public SettingsConfigDocument() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<WatchedFolderDto> getWatchedFolders() {
        return watchedFolders;
    }

    public void setWatchedFolders(List<WatchedFolderDto> watchedFolders) {
        this.watchedFolders = watchedFolders;
    }

    public int getSettleSeconds() {
        return settleSeconds;
    }

    public void setSettleSeconds(int settleSeconds) {
        this.settleSeconds = settleSeconds;
    }

    public int getMassMoveGuardThreshold() {
        return massMoveGuardThreshold;
    }

    public void setMassMoveGuardThreshold(int massMoveGuardThreshold) {
        this.massMoveGuardThreshold = massMoveGuardThreshold;
    }

    public boolean isShowNotifications() {
        return showNotifications;
    }

    public void setShowNotifications(boolean showNotifications) {
        this.showNotifications = showNotifications;
    }

    public boolean isMinimizeToTray() {
        return minimizeToTray;
    }

    public void setMinimizeToTray(boolean minimizeToTray) {
        this.minimizeToTray = minimizeToTray;
    }

    public boolean isStartWithWindows() {
        return startWithWindows;
    }

    public void setStartWithWindows(boolean startWithWindows) {
        this.startWithWindows = startWithWindows;
    }

    public ScheduleDto getSchedule() {
        return schedule;
    }

    public void setSchedule(ScheduleDto schedule) {
        this.schedule = schedule;
    }

    public ThemeMode getTheme() {
        return theme;
    }

    public void setTheme(ThemeMode theme) {
        this.theme = theme;
    }

    public static SettingsConfigDocument fromDomain(AppSettings settings) {
        SettingsConfigDocument doc = new SettingsConfigDocument();
        doc.setVersion(CURRENT_VERSION);
        doc.setWatchedFolders(settings.watchedFolders().stream()
                .map(WatchedFolderDto::fromDomain)
                .toList());
        doc.setSettleSeconds(settings.settleSeconds());
        doc.setMassMoveGuardThreshold(settings.massMoveGuardThreshold());
        doc.setShowNotifications(settings.showNotifications());
        doc.setMinimizeToTray(settings.minimizeToTray());
        doc.setStartWithWindows(settings.startWithWindows());
        doc.setSchedule(ScheduleDto.fromDomain(settings.schedule()));
        doc.setTheme(settings.theme() != null ? settings.theme() : ThemeMode.SYSTEM);
        return doc;
    }

    public AppSettings toDomain() {
        if (watchedFolders == null) {
            throw new IllegalArgumentException("Missing 'watchedFolders' array in settings.json");
        }
        if (schedule == null) {
            throw new IllegalArgumentException("Missing 'schedule' object in settings.json");
        }
        List<WatchedFolderConfig> folders = new ArrayList<>(watchedFolders.size());
        for (WatchedFolderDto dto : watchedFolders) {
            folders.add(dto.toDomain());
        }
        return new AppSettings(
                folders,
                settleSeconds,
                massMoveGuardThreshold,
                showNotifications,
                minimizeToTray,
                startWithWindows,
                schedule.toDomain(),
                theme != null ? theme : ThemeMode.SYSTEM
        );
    }

    public static final class WatchedFolderDto {
        private String path;
        private WatchMode mode;
        private boolean enabled;

        public WatchedFolderDto() {
        }

        public WatchedFolderDto(String path, WatchMode mode, boolean enabled) {
            this.path = path;
            this.mode = mode;
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public WatchMode getMode() {
            return mode;
        }

        public void setMode(WatchMode mode) {
            this.mode = mode;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public static WatchedFolderDto fromDomain(WatchedFolderConfig folder) {
            return new WatchedFolderDto(folder.path().toString(), folder.mode(), folder.enabled());
        }

        public WatchedFolderConfig toDomain() {
            if (path == null || path.isBlank() || mode == null) {
                throw new IllegalArgumentException("Incomplete watched folder entry in settings.json");
            }
            return new WatchedFolderConfig(Path.of(path.trim()), mode, enabled);
        }
    }

    public static final class ScheduleDto {
        private boolean enabled;
        private String timeOfDay;

        public ScheduleDto() {
        }

        public ScheduleDto(boolean enabled, String timeOfDay) {
            this.enabled = enabled;
            this.timeOfDay = timeOfDay;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTimeOfDay() {
            return timeOfDay;
        }

        public void setTimeOfDay(String timeOfDay) {
            this.timeOfDay = timeOfDay;
        }

        public static ScheduleDto fromDomain(ScheduleConfig schedule) {
            return new ScheduleDto(schedule.enabled(), schedule.timeOfDay());
        }

        public ScheduleConfig toDomain() {
            if (timeOfDay == null) {
                throw new IllegalArgumentException("Missing timeOfDay in schedule");
            }
            return new ScheduleConfig(enabled, timeOfDay.trim());
        }
    }
}
