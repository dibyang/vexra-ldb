# Changelog

English | [中文](CHANGELOG.md)

This document records important changes for `vexra-ldb`. It follows the spirit of Keep a Changelog and uses semantic versioning.

## [Unreleased]

## [0.2.0] - 2026-06-01

### Added

- Added Chinese and English README files.
- Added the overall project design document and English copy.
- Added contribution guide, security policy, NOTICE, release guide, and CI configuration.
- Added benchmark/soak regression entry points covering writes, random reads, snapshot scan, manual compaction, checkpoint, and reopen workflows.
- Added compaction pressure reliability regressions covering manual compaction pressure and repeated compaction recovery after write bursts.
- Added a recovery-loop regression test that connects checkpoint, backup/restore, repair, and check.
- Added future performance and reliability evaluation documents, covering group commit, incremental backup, and range delete hardening.
- Added plugin capability enhancement design documents.
- Added plugin enhancement APIs: `LdbPluginDescriptor`, `LdbPluginFailurePolicy`, `OptionsView`, `WriteEvent`, and `WriteBatchView`.
- Added plugin observability properties: `ldb.plugins`, `ldb.pluginStats`, `ldb.plugin.<index>.stats`, and `ldb.plugin.lastFailure`.
- Added Windows + Ubuntu JDK 8 CI and local Maven publication verification.
- Added English copies for the changelog, contribution guide, security policy, code of conduct, and release guide, with language switch links across user-facing documents.

### Changed

- Fixed Maven POM metadata so the project name, description, and homepage point to `vexra-ldb`.
- Optimized the range tombstone read path to avoid unnecessary scans during ordinary point gets.
- Added `Options.writeSlowdownDelayNanos` so Level-0 slowdown delay is configurable and observable through diagnostic properties.
- Clarified plugin `afterWrite` and `afterCheckpoint` post-commit notification failure semantics so callers do not assume committed data is rolled back.
- Switched the version to the formal release version `0.2.0`.

### Fixed

- Added regression coverage for plugin post-commit failure, checkpoint post-callback failure, read-only config view, and read-only write event behavior.
- Completed local publication verification for main jar, sources jar, javadoc jar, POM, module, and matching signature files.

## [0.1.0]

### Added

- Added the basic LDB API, WAL, MemTable, SSTable, MANIFEST/CURRENT, VersionSet, and compaction.
- Added column families, snapshot cursor, checkpoint, offline check/repair/backup/restore, and tool commands.
- Added reliability, range delete, API compatibility, and overall project design documents.
