# TidyFlow

[![CI](https://github.com/suliddin1/TidyFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/suliddin1/TidyFlow/actions/workflows/ci.yml)

TidyFlow is a Windows desktop application built with Java and JavaFX that organizes cluttered folders (such as `Downloads`) using customizable rules, watches folders in the background or on a daily schedule, finds duplicate files safely, and tracks every operation in a local SQLite history with an interactive statistics Dashboard and Light/Dark themes.

## Screenshots

> **[TODO: Add application screenshots here — Dashboard tab, Organize tab, Duplicates tab, Rules tab, Automation tab, and History tab]**

## Install

Download the latest release from the [GitHub Releases](https://github.com/suliddin1/TidyFlow/releases) page:

- **Windows Installer (`TidyFlow-<version>.exe`)**: Per-user installation (no administrator rights required) with Start Menu entry, desktop shortcut, and installation folder chooser.
- **Portable Archive (`TidyFlow-<version>-portable.zip`)**: Extract anywhere and run `TidyFlow\TidyFlow.exe` directly without installing.

### Windows SmartScreen Notice
TidyFlow is not signed with a paid code-signing certificate. When running the installer or portable executable for the first time, Windows SmartScreen may display **"Windows protected your PC"** (unknown publisher). To continue, click **More info** and then click **Run anyway**.

### Verify Your Download
Download `SHA256SUMS.txt` from the same release and compare the SHA-256 hash in PowerShell:

```powershell
Get-FileHash .\TidyFlow-1.0.0.exe -Algorithm SHA256
Get-FileHash .\TidyFlow-1.0.0-portable.zip -Algorithm SHA256
Get-Content .\SHA256SUMS.txt
```

Confirm that the printed `Hash` value (case-insensitive) matches the corresponding entry in `SHA256SUMS.txt`.

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
- **System Tray & Background Startup**: Minimize to the Windows system tray, receive balloon notifications when files are organized or ready for review, and optionally start hidden in the tray with Windows (`--background`) in installed builds.
- **Safe File Execution**: Moves files in the background, creates missing target directories, renames conflicting files (`name (1).ext`, `name (2).ext`), and blocks modifications inside protected Windows directories (`C:\Windows`, `Program Files`).
- **Fast Three-Stage Duplicate Finder & Recycle Bin Removal**: Groups files by size, 4 KB head/tail quick hash, and full streaming 64 KB SHA-256 hash in parallel, caching hashes in SQLite (`file_index`) and sending selected duplicates safely to the Windows Recycle Bin.
- **SQLite History & Undo**: Browse past batches in the **History** tab and safely undo file moves without overwriting existing files or deleting folders.

## How Automation Works and Why It Is Safe
- **`ASK` Mode by Default**: Newly added watched folders always start in `ASK` mode—TidyFlow never moves files automatically unless you explicitly switch a folder to `AUTO` and confirm. In `ASK` mode, TidyFlow notifies you when files are ready and opens the normal preview so you stay in control.
- **Settle Time & Lock Check**: A newly created or modified file is only considered ready when its extension is not an in-progress download (`.crdownload`, `.part`, `.tmp`), its size and last-modified timestamp have remained unchanged for `settleSeconds` (default 5 seconds), and it can be opened for reading without a file lock.
- **Mass-Move Guard**: If a single flush or scheduled pass matches more files than `massMoveGuardThreshold` (default 50 files), TidyFlow automatically downgrades `AUTO` to `ASK` and notifies you instead of moving hundreds of files unexpectedly.
- **Undo from History**: Every `AUTO` and `SCHEDULED` flush is recorded as a batch in SQLite (`[Auto]` / `[Scheduled]`) and can be undone with one click in the **History** tab.
- **Never Overwrites, Never Deletes**: TidyFlow never overwrites existing destination files (appending `(1)`, `(2)`, etc.), never touches protected system folders, and never permanently deletes files.

## User Data & Uninstalling
TidyFlow stores all configuration files, its SQLite database, single-instance lock, and fallback log in your Windows Roaming AppData folder (`%APPDATA%\TidyFlow`):
- **Rules Configuration**: `%APPDATA%\TidyFlow\rules.json` (corrupt or unsupported files are automatically backed up as `rules.json.bak-<timestamp>`)
- **Settings Configuration**: `%APPDATA%\TidyFlow\settings.json` (corrupt or unsupported files are automatically backed up as `settings.json.bak-<timestamp>`)
- **SQLite Database**: `%APPDATA%\TidyFlow\tidyflow.db`
- **Fallback Move Log**: `%APPDATA%\TidyFlow\fallback-moves.log`
- **Single-Instance Lock**: `%APPDATA%\TidyFlow\app.lock`

Uninstalling TidyFlow **keeps** `%APPDATA%\TidyFlow` so your rules and operation history are preserved across reinstalls or upgrades. Delete `%APPDATA%\TidyFlow` manually if you want to remove all saved data.

> **Important before uninstalling**: Open the **Automation** tab, uncheck **Start with Windows**, and click **Save settings** before uninstalling TidyFlow. Because startup registration is managed in your user registry (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`) at runtime, the Windows uninstaller does not remove that startup entry automatically.

## Restoring Removed Duplicates
TidyFlow **never deletes files permanently**. Duplicate files removed in the **Duplicates** tab are sent to the **Windows Recycle Bin** (`action_type = TRASH`) and can be restored directly from the Windows Recycle Bin at any time.

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

## Build from Source

### Prerequisites
- **JDK 21** (Eclipse Temurin 21 recommended; includes `jdeps`, `jlink`, and `jpackage`).
- **WiX Toolset v3** (`candle.exe` and `light.exe` on `PATH` or under `C:\Program Files (x86)\WiX Toolset v3.*`) is required by JDK 21 `jpackage` to build the `.exe` installer:
  ```powershell
  choco install wixtoolset -y
  ```
  If WiX is not installed locally, the packaging task still builds the portable ZIP, writes `SHA256SUMS.txt`, and runs the packaged self-check while skipping `.exe` creation with a clear warning.

### Run the Tests
```powershell
.\gradlew test
```

### Run from Source in Development
```powershell
.\gradlew run
```

### Build Windows Release Packages (`build\installer\`)
```powershell
.\gradlew packageRelease
```
This creates a custom `jlink` runtime, packages `TidyFlow-<version>-portable.zip` (and `TidyFlow-<version>.exe` when WiX is installed) plus `SHA256SUMS.txt` under `build\installer\`, and runs `scripts\verify-package.ps1` (`--self-check`).

### Regenerate Application Icons
```powershell
.\gradlew generateIcons
```

## Making a Release (Maintainers)
1. Update `version = "X.Y.Z"` in `build.gradle.kts` (use a numeric `MAJOR.MINOR.PATCH` version compatible with Windows `jpackage`).
2. Update `CHANGELOG.md` with the new version section and changes.
3. Run `.\gradlew test` locally, then commit and push the changes to `main`.
4. Create and push a matching `vX.Y.Z` tag:
   ```powershell
   git tag v1.0.1
   git push origin v1.0.1
   ```
   The `release.yml` GitHub Actions workflow verifies that the tag matches `build.gradle.kts`, runs all unit tests, builds the `.exe` installer, portable `.zip`, and `SHA256SUMS.txt`, verifies the packaged binary via `--self-check`, and publishes the GitHub Release automatically.
