package com.tidyflow.core.watcher;

/**
 * Live status of a watched folder displayed in the Automation tab.
 */
public enum WatchedFolderStatus {
    WATCHING("Watching"),
    PAUSED("Paused"),
    UNAVAILABLE("Unavailable"),
    OFF("Off");

    private final String displayName;

    WatchedFolderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
