# TidyFlow

TidyFlow is a Windows desktop application built with Java and JavaFX that organizes cluttered folders (such as `Downloads`) using customizable rules, watches folders in the background or on a daily schedule, finds duplicate files safely, and tracks every operation in a local SQLite history with an interactive statistics Dashboard and Light/Dark themes.

## Screenshots

> **[TODO: Add application screenshots here — Dashboard tab, Organize tab, Duplicates tab, Rules tab, Automation tab, and History tab]**

## Application Tabs
1. **Dashboard**: Home screen with a date range selector (`Last 7 days`, `Last 30 days`, `Last 90 days`, `All time`), six summary cards (`Files organized`, `Space organized`, `Duplicates removed`, `Space freed`, `Batches`, `Last activity`), two charts (**Files organized per day/month** and **Files by rule**), and a **Recent activity** list.
2. **Organize**: Choose any folder, run a non-recursive dry-run scan against your prioritized rules, preview every planned file move, and apply moves in a single recorded batch.
3. **Duplicates**: Scan folders (with optional subfolder recursion and minimum file size filter) using a fast three-stage hash pipeline, choose a keeper strategy, and move selected duplicate copies to the Windows Recycle Bin.
4. **Rules**: Create, edit, duplicate, delete, reorder, and toggle rules (`Extension`, `Filename Regex`, `Minimum Size`, `Older Than N Days`), or test sample files live in the **Try it** dry-run box.
5. **Automation**: Configure watched folders (`OFF`, `ASK`, `AUTO`), pause/resume background watching, inspect live watcher activity, set a daily schedule (`HH:mm`), and switch the application **Theme** (`SYSTEM`, `LIGHT`, `DARK`).
6. **History**: Browse past `Organize`, `Auto`, `Scheduled`, and `Dedupe` batches and undo any file move or entire batch with one click.

## Features
- **Interactive Statistics Dashboard**: Aggregated SQL metrics, zero-filled daily/monthly activity bar charts, top-8 + `"Other"` rule breakdown chart, and recent batch summaries.
- **Light, Dark & System Themes**: Clean 8 px grid CSS design system (`base.css`, `light.css`, `dark.css`) that automatically detects the Windows `AppsUseLightTheme` registry setting in `SYSTEM` mode or lets you switch between `LIGHT` and `DARK` instantly.
- **Safe Non-Recursive Scanning**: Scans a target folder while skipping subdirectories, hidden files, and active downloads (`.crdownload`, `.part`, `.tmp`).
- **Rule-Based Dry-Run Preview & Custom Rule Editor**: Matches files against prioritized rules and previews planned moves before touching any files. Rules are validated and saved atomically to `rules.json`.
- **Background Folder Watcher & Daily Scheduler**: Watch folders non-recursively in `ASK` or `AUTO` mode, pause/resume watching at any time, or run an automated pass once a day at a scheduled time (`HH:mm`).
- **System Tray & Single Instance**: Minimize to the Windows system tray, receive balloon notifications when files are organized or ready for review, and optionally start with Windows in packaged builds.
- **Safe File Execution**: Moves files in the background, creates missing target directories, renames conflicting files (`name (1).ext`, `name (2).ext`), and blocks modifications inside protected Windows directories (`C:\Windows`, `Program Files`).
- **Fast Three-Stage Duplicate Finder & Recycle Bin Removal**: Groups files by size, 4 KB head/tail quick hash, and full streaming 64 KB SHA-256 hash in parallel, caching hashes in SQLite (`file_index`) and sending selected duplicates safely to the Windows Recycle Bin.
- **SQLite History & Undo**: Browse past batches in the **History** tab and safely undo file moves without overwriting existing files or deleting folders.

## How Automation Works and Why It Is Safe
- **`ASK` Mode by Default**: Newly added watched folders always start in `ASK` mode—TidyFlow never moves files automatically unless you explicitly switch a folder to `AUTO` and confirm. In `ASK` mode, TidyFlow notifies you when files are ready and opens the normal preview so you stay in control.
- **Settle Time & Lock Check**: A newly created or modified file is only considered ready when its extension is not an in-progress download (`.crdownload`, `.part`, `.tmp`), its size and last-modified timestamp have remained unchanged for `settleSeconds` (default 5 seconds), and it can be opened for reading without a file lock.
- **Mass-Move Guard**: If a single flush or scheduled pass matches more files than `massMoveGuardThreshold` (default 50 files), TidyFlow automatically downgrades `AUTO` to `ASK` and notifies you instead of moving hundreds of files unexpectedly.
- **Undo from History**: Every `AUTO` and `SCHEDULED` flush is recorded as a batch in SQLite (`[Auto]` / `[Scheduled]`) and can be undone with one click in the **History** tab.
- **Never Overwrites, Never Deletes**: TidyFlow never overwrites existing destination files (appending `(1)`, `(2)`, etc.), never touches protected system folders, and never permanently deletes files.

## Example `settings.json`
```json
{
  "version": 1,
  "watchedFolders": [
    {
      "path": "C:\\Downloads",
      "mode": "ASK",
      "enabled": true
    }
  ],
  "settleSeconds": 5,
  "massMoveGuardThreshold": 50,
  "showNotifications": true,
  "minimizeToTray": true,
  "startWithWindows": false,
  "schedule": {
    "enabled": false,
    "timeOfDay": "09:00"
  },
  "theme": "SYSTEM"
}
```

## Example `rules.json`
```json
{
  "version": 1,
  "rules": [
    {
      "name": "Invoices",
      "enabled": true,
      "priority": 1,
      "conditions": [
        {
          "type": "EXTENSION_LIST",
          "extensions": ["pdf"]
        },
        {
          "type": "FILENAME_REGEX",
          "regex": "^invoice_.*\\.pdf$"
        }
      ],
      "action": {
        "type": "MOVE",
        "targetFolder": "Documents\\Invoices"
      }
    },
    {
      "name": "Large Archives",
      "enabled": true,
      "priority": 2,
      "conditions": [
        {
          "type": "EXTENSION_LIST",
          "extensions": ["zip", "rar", "7z"]
        },
        {
          "type": "MIN_SIZE",
          "minBytes": 10485760
        },
        {
          "type": "OLDER_THAN_DAYS",
          "days": 30
        }
      ],
      "action": {
        "type": "MOVE",
        "targetFolder": "Archives\\Old"
      }
    }
  ]
}
```

## Storage Locations
TidyFlow stores all configuration files, its SQLite database, single-instance lock, and fallback log in your Windows Roaming AppData folder (`%APPDATA%\TidyFlow`):
- **Rules Configuration**: `%APPDATA%\TidyFlow\rules.json` (corrupt or unsupported files are automatically backed up as `rules.json.bak-<timestamp>`)
- **Settings Configuration**: `%APPDATA%\TidyFlow\settings.json` (corrupt or unsupported files are automatically backed up as `settings.json.bak-<timestamp>`)
- **SQLite Database**: `%APPDATA%\TidyFlow\tidyflow.db`
- **Fallback Move Log**: `%APPDATA%\TidyFlow\fallback-moves.log`
- **Single-Instance Lock**: `%APPDATA%\TidyFlow\app.lock`

## Restoring Removed Duplicates
TidyFlow **never deletes files permanently**. Duplicate files removed in the **Duplicates** tab are sent to the **Windows Recycle Bin** (`action_type = TRASH`) and can be restored directly from the Windows Recycle Bin at any time.

## Build and Run

### Build the Project
```powershell
.\gradlew build
```

### Run the Application
```powershell
.\gradlew run
```

### Run the Tests
```powershell
.\gradlew test
```

### Regenerate Application Icons
```powershell
.\gradlew generateIcons
```
