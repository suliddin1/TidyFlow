# TidyFlow

TidyFlow is a Windows desktop application built with Java and JavaFX that organizes cluttered folders (such as `Downloads`) using customizable rules, finds duplicate files safely, and tracks every operation in a local SQLite history.

## Screenshots

> **[TODO: Add application screenshots here — Organize tab, Duplicates tab, Rules editor tab, and History tab]**

## Features
- **Safe Non-Recursive Scanning**: Scans a target folder while skipping subdirectories, hidden files, and active downloads (`.crdownload`, `.part`, `.tmp`).
- **Rule-Based Dry-Run Preview**: Matches files against prioritized rules (`Extension`, `Filename Regex`, `Minimum Size`, `Older Than N Days`) and previews planned moves before touching any files.
- **Custom Rule Editor & Live Dry-Run Tester**: Create, edit, duplicate, delete, reorder, and toggle rules in the **Rules** tab, or test sample file names, sizes, and ages in the **Try it** box. Rules are validated and saved atomically to `rules.json`.
- **Safe File Execution**: Moves files in the background, creates missing target directories, renames conflicting files (`name (1).ext`, `name (2).ext`), and blocks modifications inside protected Windows directories (`C:\Windows`, `Program Files`).
- **Fast Three-Stage Duplicate Finder**: Groups files by size, 4 KB head/tail quick hash, and full streaming 64 KB SHA-256 hash in parallel, caching hashes in SQLite (`file_index`) so unchanged files are not re-read on future scans.
- **Keeper Strategies & Safe Recycle Bin Removal**: Automatically suggests which copy to keep (`Oldest modified`, `Newest modified`, or `Shortest path`), guarantees at least one copy per group is kept, re-verifies file size and modified time before removal, and sends selected duplicates to the Windows Recycle Bin.
- **SQLite History & Undo**: Browse past `Organize` and `Dedupe` batches in the **History** tab and safely undo file moves without overwriting existing files or deleting folders.

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
TidyFlow stores its configuration, SQLite database, and fallback log in your Windows Roaming AppData folder (`%APPDATA%\TidyFlow`):
- **Rules Configuration**: `%APPDATA%\TidyFlow\rules.json` (corrupt or unsupported files are automatically backed up as `rules.json.bak-<timestamp>`)
- **SQLite Database**: `%APPDATA%\TidyFlow\tidyflow.db`
- **Fallback Move Log**: `%APPDATA%\TidyFlow\fallback-moves.log`

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
