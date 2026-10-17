# TidyFlow Architecture

TidyFlow is a Windows desktop application built with Java and JavaFX that organizes cluttered folders (such as `Downloads`) using customizable rules and safely identifies and removes duplicate files.

The application follows a strict three-layer architecture to keep UI rendering, domain logic, and I/O concerns decoupled and testable.

```
+-------------------------------------------------------------------+
|                             UI Layer                              |
|        JavaFX Views (FXML/CSS) + Controllers (com.tidyflow.ui)    |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                        Core Services Layer                        |
|  - FolderScanner   : Enumerates eligible files (com.tidyflow.core)|
|  - RuleEngine      : Evaluates rules & builds dry-run plans       |
|  - RuleValidator   : Validates rules without throwing exceptions  |
|  - FileExecutor    : Executes moves & records batches in SQLite   |
|  - UndoManager     : Safely reverses single operations or batches |
|  - DuplicateFinder : 3-stage duplicate scan with hash caching     |
|  - DuplicateRemover: Safe Recycle Bin removal & verification      |
|  - Watcher         : [Planned] Real-time directory monitoring     |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                            Data Layer                             |
|  - File System     : NIO.2 file inspection, moves & Recycle Bin   |
|  - SQLite (JDBC)   : Batches, operations & file_index hash cache  |
|  - JSON Config     : Atomic rules.json persistence & backup       |
+-------------------------------------------------------------------+
```

## 1. UI Layer (`com.tidyflow.ui`, `com.tidyflow.app`)
- **Responsibility**: Presents four tabs (**Organize**, **Duplicates**, **Rules**, and **History**) in `main-view.fxml` controlled by `MainController`.
  - **Organize Tab**: Folder selection, dry-run preview table, and Apply action using the active rules.
  - **Duplicates Tab**: Folder selection, subfolder toggle, minimum size filter (KB/MB), keeper strategy selector (`OLDEST_MODIFIED`, `NEWEST_MODIFIED`, `SHORTEST_PATH`), cancellable background scan with progress bar, `TreeTableView` of duplicate groups and files with `Keep` badges, and **Move selected to Recycle Bin** action.
  - **Rules Tab**: Table of rules with instant-save Enabled checkboxes, Priority, Name, Conditions summary, and Target folder; Add/Edit modal dialog (`RuleEditorDialog`) with live validation errors; Duplicate, Delete, Move up/down (`RulePriorityManager`), and Reset to defaults actions; plus a **Try it** dry-run preview box that tests synthetic file attributes against `RuleEngine` without touching the file system.
  - **History Tab**: Split view showing recorded `MANUAL` and `DEDUPE` batches (newest first) on the left and the selected batch's file operations on the right, with **Undo batch** and **Undo selected file** actions.

## 2. Core Services Layer (`com.tidyflow.core.*`)
- **Scanner (`com.tidyflow.core.scanner`)**:
  - `FolderScanner` reads a target directory (non-recursive by default) and produces immutable `FileItem` records (`path`, `name`, `extension`, `size`, `lastModified`), skipping subdirectories, hidden files, and in-progress downloads (`.crdownload`, `.part`, `.tmp`).
- **Rules, Validation & Rule Engine (`com.tidyflow.core.rules`)**:
  - `Rule`, `Condition` (`ExtensionCondition`, `RegexFilenameCondition`, `MinSizeCondition`, `OlderThanDaysCondition`), `Action` (`MoveAction`), `RuleEngine`, `DefaultRules`, `RulePriorityManager`, and `RuleValidator`.
  - `RuleEngine` is completely free of I/O; it evaluates `FileItem` objects against enabled rules sorted by ascending priority.
  - `RuleValidator` validates both single rules and full rule lists, returning readable error messages (non-blank case-insensitively unique name, `priority >= 1`, at least one condition, valid regex, non-empty normalized extensions without dots or spaces, positive size and days thresholds, and non-blank target folder free of illegal Windows characters and outside protected system folders).
- **Executor & Undo (`com.tidyflow.core.executor`)**:
  - `FileExecutor`: Performs planned moves, enforces protected-path guardrails (`C:\Windows`, `Program Files`), creates missing target directories, resolves collisions (`name (1).ext`), and persists each batch and its operations to SQLite (with fallback logging to `%APPDATA%\TidyFlow\fallback-moves.log`).
  - `UndoManager`: Restores `MOVE` operations with status `SUCCESS` from `dest_path` back to `source_path`. `TRASH` operations are skipped with a clear message and never fail the batch.
- **Deduplicator (`com.tidyflow.core.dedupe`)**:
  - **Three-Stage Algorithm (`DuplicateFinder`, `FileHasher`)**:
    1. **Stage 1 (Group by Size)**: Walks the directory tree and discards unique file sizes.
    2. **Stage 2 (Quick Hash)**: Computes SHA-256 of the first 4 KB + last 4 KB in parallel, reusing cached hashes from `file_index` when `(path, size_bytes, mtime)` match.
    3. **Stage 3 (Full SHA-256)**: Streams remaining candidates with a 64 KB buffer, supporting cancellation and batch-upserting `file_index` entries upon completion.
  - **Safe Removal (`DuplicateRemover`, `TrashService`, `DesktopTrashService`)**: Enforces in core logic that at least one keeper per group remains intact on disk, re-verifies `size` and `mtime` before trashing, moves files to the Windows Recycle Bin, and records a `DEDUPE` batch.

## 3. Data Layer (`com.tidyflow.data`, `com.tidyflow.data.config`)
- **JSON Rule Configuration (`RuleRepository`, `RulesConfigDocument`, `RuleLoadResult`)**:
  - Stored at `%APPDATA%\TidyFlow\rules.json` (injectable `Path` for tests).
  - Top-level `"version": 1` and `"rules"` array. Polymorphic conditions (`"type"` in `EXTENSION_LIST`, `FILENAME_REGEX`, `MIN_SIZE`, `OLDER_THAN_DAYS`) and actions (`"type": "MOVE"`).
  - **First run**: Creates `rules.json` populated with `DefaultRules.create()`.
  - **Atomic save**: Writes JSON to `rules.json.tmp` in the same folder, then atomically moves it over `rules.json`.
  - **Backup & recovery**: If `rules.json` is corrupt, fails validation, or has an unsupported `"version"`, it is renamed to `rules.json.bak-<timestamp>`, default rules are written and loaded, and a clear recovery notice is shown in the UI.
- **SQLite Database (`DatabaseManager`, `BatchRepository`, `OperationRepository`, `FileIndexRepository`)**:
  - Stored at `%APPDATA%\TidyFlow\tidyflow.db` by default (injectable `Path` for tests).
  - Versioned SQL migrations tracked in `schema_version` (`V1__init.sql`, `V2__dedupe_and_index.sql`).
