# TidyFlow Architecture

TidyFlow is a Windows desktop application built with Java and JavaFX that organizes cluttered folders (such as `Downloads`) using customizable rules, watches folders in the background, runs daily scheduled passes, and safely identifies and removes duplicate files.

The application follows a strict three-layer architecture to keep UI rendering, domain logic, and I/O concerns decoupled and testable.

```
+-------------------------------------------------------------------+
|                             UI Layer                              |
|  JavaFX Views (FXML/CSS), Controllers & SystemTray (com.tidyflow) |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                        Core Services Layer                        |
|  - FolderScanner        : Enumerates eligible files               |
|  - RuleEngine           : Evaluates rules & builds dry-run plans  |
|  - RuleValidator        : Validates rules without throwing        |
|  - FileExecutor         : Moves files, suppresses self-events, DB |
|  - UndoManager          : Safely reverses operations or batches   |
|  - DuplicateFinder      : 3-stage duplicate scan with hash cache  |
|  - DuplicateRemover     : Safe Recycle Bin removal & verification |
|  - FolderWatcherService : Real-time WatchService & readiness      |
|  - DailySchedulerService: DST-safe daily scheduled pass           |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                            Data Layer                             |
|  - File System     : NIO.2 file inspection, moves & Recycle Bin   |
|  - SQLite (JDBC)   : Batches, operations & file_index hash cache  |
|  - JSON Config     : Atomic rules.json & settings.json + backups  |
+-------------------------------------------------------------------+
```

## 1. UI Layer (`com.tidyflow.ui`, `com.tidyflow.app`)
- **Responsibility**: Presents five tabs (**Organize**, **Duplicates**, **Rules**, **Automation**, and **History**) in `main-view.fxml` controlled by `MainController`, plus AWT system tray integration (`SystemTrayManager`) and single-instance enforcement (`SingleInstanceLock`).
  - **Organize Tab**: Folder selection, dry-run preview table, and Apply action using the active rules.
  - **Duplicates Tab**: Folder selection, subfolder toggle, minimum size filter (KB/MB), keeper strategy selector (`OLDEST_MODIFIED`, `NEWEST_MODIFIED`, `SHORTEST_PATH`), cancellable background scan with progress bar, `TreeTableView` of duplicate groups and files with `Keep` badges, and **Move selected to Recycle Bin** action.
  - **Rules Tab**: Table of rules with instant-save Enabled checkboxes, Priority, Name, Conditions summary, and Target folder; Add/Edit modal dialog (`RuleEditorDialog`) with live validation errors; Duplicate, Delete, Move up/down (`RulePriorityManager`), and Reset to defaults actions; plus a **Try it** dry-run preview box.
  - **Automation Tab**: Watched folders table (`Folder Path`, `Mode` dropdown `OFF`/`ASK`/`AUTO` with confirmation on `AUTO`, and live `Status` `Watching`/`Paused`/`Unavailable`/`Off`); Pause/Resume toggle; Recent Activity list (last 50 in-memory events); and validated settings (`settleSeconds`, `massMoveGuardThreshold`, `showNotifications`, `minimizeToTray`, `startWithWindows`, and daily `schedule`).
  - **History Tab**: Split view showing recorded `MANUAL` (`[Organize]`), `AUTO` (`[Auto]`), `SCHEDULED` (`[Scheduled]`), and `DEDUPE` (`[Dedupe]`) batches (newest first) on the left and the selected batch's file operations on the right, with **Undo batch** and **Undo selected file** actions.

### System Tray & Application Lifecycle (`TidyFlowApp`, `SystemTrayManager`, `SingleInstanceLock`, `WindowsRegistryAutostartService`)
- **Single Instance**: `SingleInstanceLock` acquires an OS file lock on `%APPDATA%\TidyFlow\app.lock`. A second instance shows `"TidyFlow is already running (check the system tray)"` and exits.
- **System Tray**: Calls `Platform.setImplicitExit(false)`, creates the `TrayIcon` on the AWT Event Dispatch Thread (`SwingUtilities.invokeLater`), and marshals callbacks back to JavaFX via `Platform.runLater`. If `SystemTray.isSupported()` is `false`, TidyFlow logs a notice and degrades gracefully so closing the window exits normally.
- **Minimize to Tray & Notifications**: Closing the window with `minimizeToTray` enabled hides the stage and displays a one-time notification (`"TidyFlow is still running in the background"`). Clicking `"Organized N files"` opens the **History** tab; clicking `"N files ready"` opens the **Organize** tab with that folder preselected and scanned.
- **Start with Windows**: `WindowsRegistryAutostartService` manages the `TidyFlow` value in `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` using `reg.exe` via `ProcessBuilder` when `jpackage.app-path` is present, and disables the checkbox with a tooltip when running unpacked from Gradle/IDE.

## 2. Core Services Layer (`com.tidyflow.core.*`)

### Watcher & Automation Pipeline (`com.tidyflow.core.watcher`)
```
WatchService (ENTRY_CREATE / ENTRY_MODIFY / OVERFLOW)
   |
   +--> Self-Move Filter (FileExecutor.isRecentSelfMove)
   |
   v
FileReadinessTracker (injectable Clock & FileStatProvider)
   - Ignores .crdownload, .part, .tmp, and hidden files
   - Waits until size & lastModified are unchanged for settleSeconds
   - Verifies file can be opened for reading (unlocked)
   |
   v
Burst Flush (~3s) / Scheduled Pass (DailySchedulerService)
   |
   v
RuleEngine (pure dry-run plan for direct child files)
   |
   +--> [Paused or Mode == OFF] ----------------> Do nothing
   |
   +--> [Mode == AUTO & count > GuardThreshold] -> Downgrade to ASK + Guard Notification
   |
   +--> [Mode == ASK] --------------------------> Move nothing + Emit "Files Ready" Notification
   |
   +--> [Mode == AUTO] -------------------------> FileExecutor (1 batch: kind AUTO or SCHEDULED)
                                                  + Activity Log + "Organized N files" Notification
```
- **Unavailable Folder Recovery**: If a watched folder is deleted or its drive disconnects, `FolderWatcherService` cancels its `WatchKey`, marks its status as `Unavailable`, retries every 30 seconds, and resumes watching when the folder returns.
- **Daily Scheduler (`DailySchedulerService`)**: Uses a daemon `ScheduledExecutorService` and pure `nextRun(ZonedDateTime now, String timeOfDay)` calculation in the system timezone (DST-safe, no catch-up on missed runs while asleep/closed).

### Scanner, Rules, Executor & Deduplicator
- **Scanner (`FolderScanner`)**: Reads a target directory non-recursively and produces immutable `FileItem` records.
- **Rules & Validation (`RuleEngine`, `RuleValidator`, `RulePriorityManager`)**: Pure rule evaluation and validation.
- **Executor & Undo (`FileExecutor`, `UndoManager`)**: Moves files safely without overwriting (`name (1).ext`), blocks protected paths (`C:\Windows`, `Program Files`), suppresses self-move watcher events, and records batches in SQLite so `MANUAL`, `AUTO`, and `SCHEDULED` batches can all be undone from History.
- **Deduplicator (`DuplicateFinder`, `DuplicateRemover`)**: Three-stage duplicate detection (size -> 4 KB head/tail quick hash -> 64 KB streaming SHA-256) backed by `file_index`, with safe Windows Recycle Bin removal.

## 3. Data Layer (`com.tidyflow.data`, `com.tidyflow.data.config`)
- **JSON Settings Configuration (`SettingsRepository`, `SettingsConfigDocument`, `SettingsValidator`)**:
  - Stored at `%APPDATA%\TidyFlow\settings.json` (injectable `Path` for tests) with top-level `"version": 1`.
  - Atomic save via `settings.json.tmp` followed by atomic move; corrupt or unknown-version files are backed up to `settings.json.bak-<timestamp>` and replaced with defaults.
- **JSON Rule Configuration (`RuleRepository`, `RulesConfigDocument`)**:
  - Stored at `%APPDATA%\TidyFlow\rules.json` with atomic save and `.bak-<timestamp>` recovery.
- **SQLite Database (`DatabaseManager`, `BatchRepository`, `OperationRepository`, `FileIndexRepository`)**:
  - Stored at `%APPDATA%\TidyFlow\tidyflow.db` with idempotent versioned migrations (`V1__init.sql`, `V2__dedupe_and_index.sql`).
