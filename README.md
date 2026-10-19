# TidyFlow

TidyFlow is a Windows desktop application built with Java and JavaFX that organizes cluttered folders (such as `Downloads`) using customizable rules, watches folders in the background or on a daily schedule, finds duplicate files safely, and tracks every operation in a local SQLite history.

## Screenshots

> **[TODO: Add application screenshots here — Organize tab, Duplicates tab, Rules editor tab, Automation tab, and History tab]**

## Features
- **Safe Non-Recursive Scanning**: Scans a target folder while skipping subdirectories, hidden files, and active downloads (`.crdownload`, `.part`, `.tmp`).
- **Rule-Based Dry-Run Preview**: Matches files against prioritized rules (`Extension`, `Filename Regex`, `Minimum Size`, `Older Than N Days`) and previews planned moves before touching any files.
- **Custom Rule Editor & Live Dry-Run Tester**: Create, edit, duplicate, delete, reorder, and toggle rules in the **Rules** tab, or test sample file names, sizes, and ages in the **Try it** box. Rules are validated and saved atomically to `rules.json`.
- **Background Folder Watcher & Daily Scheduler**: Watch folders non-recursively in `ASK` or `AUTO` mode, pause/resume watching at any time, or run an automated pass once a day at a scheduled time (`HH:mm`).
- **System Tray & Single Instance**: Minimize to the Windows system tray, receive balloon notifications when files are organized or ready for review, and optionally start with Windows in packaged builds.
- **Safe File Execution**: Moves files in the background, creates missing target directories, renames conflicting files (`name (1).ext`, `name (2).ext`), and blocks modifications inside protected Windows directories (`C:\Windows`, `Program Files`).
- **Fast Three-Stage Duplicate Finder**: Groups files by size, 4 KB head/tail quick hash, and full streaming 64 KB SHA-256 hash in parallel, caching hashes in SQLite (`file_index`) so unchanged files are not re-read on future scans.
- **Keeper Strategies & Safe Recycle Bin Removal**: Automatically suggests which copy to keep (`Oldest modified`, `Newest modified`, or `Shortest path`), guarantees at least one copy per group is kept, re-verifies file size and modified time before removal, and sends selected duplicates to the Windows Recycle Bin.
- **SQLite History & Undo**: Browse past `Organize`, `Auto`, `Scheduled`, and `Dedupe` batches in the **History** tab and safely undo file moves without overwriting existing files or deleting folders.

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
  }
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
