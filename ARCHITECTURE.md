# TidyFlow Architecture

TidyFlow is a Windows desktop application built with Java and JavaFX that provides a statistics Dashboard, organizes cluttered folders (such as `Downloads`) using customizable rules, watches folders in the background, runs daily scheduled passes, and safely identifies and removes duplicate files.

The application follows a strict three-layer architecture to keep UI rendering, domain logic, and I/O concerns decoupled and testable.

```
+-------------------------------------------------------------------+
|                             UI Layer                              |
|  JavaFX Views (FXML/CSS), ThemeManager, Controllers & SystemTray  |
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
|  - SQLite (JDBC)   : Batches, operations, stats & file_index      |
|  - JSON Config     : Atomic rules.json & settings.json + backups  |
+-------------------------------------------------------------------+
```

## 1. UI Layer (`com.tidyflow.ui`, `com.tidyflow.app`)
- **Responsibility**: Presents six tabs (**Dashboard**, **Organize**, **Duplicates**, **Rules**, **Automation**, and **History**) in `main-view.fxml` controlled by `MainController`, plus AWT system tray integration (`SystemTrayManager`), visual theme management (`ThemeManager`), and single-instance enforcement (`SingleInstanceLock`).
  - **Dashboard Tab** (default home screen):
    - Range selector (`Last 7 days`, `Last 30 days` default, `Last 90 days`, `All time`).
    - Six summary cards (`Files organized`, `Space organized`, `Duplicates removed`, `Space freed`, `Batches`, and `Last activity`).
    - Two built-in JavaFX `BarChart` panels: **Files organized per day** (or per month for `All time`, zero-filled via `DailyBucketer`) and a horizontal **Files by rule** chart (top 8 rules + `"Other"`).
    - **Recent activity** list showing the last 10 batches with an **Open History** shortcut, plus an empty-state welcome card when the database has no batches.
    - All dashboard queries run asynchronously on a background `Task` with sequence-number cancellation so stale results from rapid range switching are ignored.
  - **Organize Tab**: Folder selection, dry-run preview table, and Apply action using the active rules.
  - **Duplicates Tab**: Folder selection, subfolder toggle, minimum size filter (KB/MB), keeper strategy selector (`OLDEST_MODIFIED`, `NEWEST_MODIFIED`, `SHORTEST_PATH`), cancellable background scan with progress bar, `TreeTableView` of duplicate groups and files with `Keep` badges, and **Move selected to Recycle Bin** action.
  - **Rules Tab**: Table of rules with instant-save Enabled checkboxes, Priority, Name, Conditions summary, and Target folder; Add/Edit modal dialog (`RuleEditorDialog`) with live validation errors; Duplicate, Delete, Move up/down (`RulePriorityManager`), and Reset to defaults actions; plus a **Try it** dry-run preview box.
  - **Automation Tab**: Watched folders table (`Folder Path`, `Mode` dropdown `OFF`/`ASK`/`AUTO` with confirmation on `AUTO`, and live `Status` `Watching`/`Paused`/`Unavailable`/`Off`); Pause/Resume toggle; Recent Activity list (last 50 in-memory events); validated automation settings (`settleSeconds`, `massMoveGuardThreshold`, `showNotifications`, `minimizeToTray`, `startWithWindows`, and daily `schedule`); and an **Application** section with the **Theme** selector (`SYSTEM`, `LIGHT`, `DARK`) and version label (`TidyFlow <version>`).
  - **History Tab**: Split view showing recorded `MANUAL` (`[Organize]`), `AUTO` (`[Auto]`), `SCHEDULED` (`[Scheduled]`), and `DEDUPE` (`[Dedupe]`) batches (newest first) on the left and the selected batch's file operations on the right, with **Undo batch** and **Undo selected file** actions.

### CSS Design System & Theme Detection (`ThemeManager`, `WindowsThemeDetector`)
- **Design System**:
  - `base.css`: 8 px spacing grid, Segoe UI typography hierarchy, buttons, tables, cards (`.card`), badges (`.keep-badge`), toggle pills (`.range-toggle`), and chart styling.
  - `light.css` and `dark.css`: define JavaFX looked-up color tokens (`-tf-bg`, `-tf-surface`, `-tf-text`, `-tf-muted-text`, `-tf-accent`, `-tf-danger`, `-tf-border`) on `.root`. Switching themes swaps only the token stylesheet on the active `Scene` and dialogs (`RuleEditorDialog` and `Alert`s).
- **Windows System Theme Detection**: When `theme` is set to `SYSTEM` (the default), `WindowsThemeDetector` reads `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize` value `AppsUseLightTheme` (`0x0` = Dark, `0x1` = Light) via `reg.exe` and `ProcessBuilder` with a 2-second timeout, falling back to `LIGHT` on any error.

### App Icon Generation & Version (`IconGeneratorMain`, `AppVersion`)
- **Icon Generation (`src/tools/java/com/tidyflow/tools/IconGeneratorMain.java`)**: A dev-only Java2D utility compiled in a separate `tools` Gradle source set (excluded from the main application JAR). Running `./gradlew generateIcons` renders TidyFlow's rounded blue gradient folder + sorting arrow icon at `16`, `32`, `48`, `64`, `128`, and `256` px (`src/main/resources/icons/icon-<size>.png`) and packs a multi-resolution Windows `.ico` file (`src/main/resources/icons/icon.ico` and `packaging/windows/icon.ico`).
- **Version Resource (`AppVersion`)**: Reads the Gradle project version (`1.0.0`) filtered into `src/main/resources/version.properties` at build time, falling back to `"dev"` if missing.

### System Tray & Application Lifecycle (`TidyFlowApp`, `SystemTrayManager`, `SingleInstanceLock`, `WindowsRegistryAutostartService`)
- **Single Instance**: `SingleInstanceLock` acquires an OS file lock on `%APPDATA%\TidyFlow\app.lock`. A second instance shows `"TidyFlow is already running (check the system tray)"` and exits.
- **System Tray**: Calls `Platform.setImplicitExit(false)`, creates the `TrayIcon` on the AWT Event Dispatch Thread (`SwingUtilities.invokeLater`), loads the 16 px / 32 px generated icon resource (with programmatic fallback), and marshals callbacks back to JavaFX via `Platform.runLater`.

## 2. Core Services Layer (`com.tidyflow.core.*`, `com.tidyflow.util`)

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

### Pure Formatting & Bucketing Helpers (`com.tidyflow.util`)
- **`ByteFormatter`**: Formats byte counts (`B`, `KB`, `MB`, `GB`, `TB`) using 1024-base units with one decimal place (`0 B`, `1.5 MB`, `3.2 GB`).
- **`DailyBucketer`**: Converts UTC `Instant` timestamps to local calendar dates (`LocalDate`) or months (`YearMonth`) using an injectable `ZoneId` so operations near local midnight are bucketed accurately, and fills missing days/months in a range with zero counts.

## 3. Data Layer (`com.tidyflow.data`, `com.tidyflow.data.config`)
- **JSON Settings Configuration (`SettingsRepository`, `SettingsConfigDocument`, `SettingsValidator`)**:
  - Stored at `%APPDATA%\TidyFlow\settings.json` (injectable `Path` for tests) with top-level `"version": 1`, including `theme` (`SYSTEM`, `LIGHT`, `DARK`; legacy files without `theme` default to `SYSTEM`).
  - Atomic save via `settings.json.tmp` followed by atomic move; corrupt or unknown-version files are backed up to `settings.json.bak-<timestamp>` and replaced with defaults.
- **JSON Rule Configuration (`RuleRepository`, `RulesConfigDocument`)**:
  - Stored at `%APPDATA%\TidyFlow\rules.json` with atomic save and `.bak-<timestamp>` recovery.
- **SQLite Database & Statistics (`DatabaseManager`, `BatchRepository`, `OperationRepository`, `FileIndexRepository`, `StatsRepository`)**:
  - Stored at `%APPDATA%\TidyFlow\tidyflow.db` with idempotent versioned migrations:
    - `V1__init.sql`: `batches` and `operations` tables.
    - `V2__dedupe_and_index.sql`: `file_index` hash cache table and `action_type` column on `operations`.
    - `V3__stats_indexes.sql`: covering indexes on `operations(status, created_at)`, `operations(action_type, status, created_at)`, and `batches(finished_at)`.
  - **`StatsRepository`**: Computes dashboard metrics using aggregated SQL queries (`COUNT`, `COALESCE(SUM(size_bytes), 0)`, `GROUP BY rule_name`, `MAX(finished_at)`) over optional `startInclusive` timestamps (`julianday(created_at) >= julianday(?)`), excluding `UNDONE` operations from organized counts and separating `TRASHED` duplicate removals from `MOVE` operations.
