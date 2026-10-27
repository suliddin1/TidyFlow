# Changelog

All notable changes to TidyFlow will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - Unreleased

### Added
- Non-recursive folder scanner and rule-based file organizer with dry-run preview before moving files, collision-safe renaming (`name (1).ext`), and protected Windows path checks.
- Local SQLite database with versioned migrations recording every batch and file operation, plus History tab browsing and single-file or full-batch undo.
- Three-stage duplicate file finder (file size, 4 KB head/tail quick hash, and streaming SHA-256 with SQLite `file_index` caching), keeper strategies, and safe removal to the Windows Recycle Bin.
- Custom rule editor tab with JSON persistence (`rules.json`), atomic saves, corrupt-file backup recovery, priority reordering, and a live dry-run **Try it** tester.
- Background non-recursive folder watcher (`OFF`, `ASK`, and `AUTO` modes) with file-settle readiness checks, lock verification, and a configurable mass-move guard.
- Daily scheduled organization pass (`HH:mm`), Windows system tray integration with notifications, single-instance lock, and optional background startup with Windows (`--background`).
- Interactive statistics Dashboard home tab with date-range filters, summary cards, daily/monthly and top-rules bar charts, and recent batch history.
- CSS design system supporting Light, Dark, and automatic Windows System theme detection, plus generated multi-size application icons and visible app version.
- Windows packaging pipeline (`jlink` custom runtime, per-user `jpackage` `.exe` installer, portable `.zip`, `SHA256SUMS.txt`, and headless `--self-check` verification) and GitHub Actions CI/Release workflows.
