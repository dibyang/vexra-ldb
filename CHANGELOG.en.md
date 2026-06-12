# Changelog

English | [中文](CHANGELOG.md)

This document records important changes for `vexra-ldb`. It follows the spirit of Keep a Changelog and uses semantic versioning.

## [Unreleased]

### Added

- Added Chinese and English non-empty column-family drop/rename/tombstone design documents covering logical deletion, stable cfId identity, MANIFEST/registry history, rollback, and compatibility boundaries.
- Added Chinese and English Backup Engine design documents for shared object storage, reference counts, backup-chain GC, dry-run cleanup, and publication state management.
- Added Chinese and English long-run stress and benchmark report design documents covering workload matrices, machine-readable reports, release thresholds, and failure preservation.

### Changed

- Updated the release guide's current development baseline to `0.5.0-SNAPSHOT` and the next formal target version to `0.5.0`.

## [0.4.0] - 2026-06-08

### Added

- Added a disabled-by-default minimal group commit implementation with `Options.groupCommitEnabled`, `groupCommitMaxDelayNanos`, `groupCommitMaxBatchBytes`, and `ldb.groupCommitStats`.
- Added longrun reporting for write-strategy comparability: `workloadSyncWrites`, group commit settings, plugin async settings, and plugin callback budget settings are persisted in `state/run.properties` and copied into `report/summary.properties`.
- Added `LDBFactory.createIncrementalBackup` and `checkBackup`, with `BACKUP-MANIFEST.json` recording copied and reused files while preferentially reusing SST files from the previous backup.
- Added `LDBFactory.planRepair` and `ldb repair-plan <db>` dry-run entry points that output a repair plan without modifying the database directory.
- Added operation latency histogram properties, `ldb.blockCacheStats`, and `incremental-backup`/`check-backup` tool commands.
- Added minimal runtime column-family `list/create/drop-empty` support and a `COLUMN-FAMILIES` registry that backup, checkpoint, check, and repair carry and validate.
- Added Chinese and English column-family lifecycle design documents plus corruption matrices for registry, CURRENT, backup registry, and runtime-CF WAL-only repair scenarios.
- Added `LdbPluginCompatibility` as a lightweight provider/plugin compatibility testkit.
- Added `ldb.plugin.maxTotalCallbackMillis` to govern cumulative plugin callback cost per plugin.
- Added `LdbPlugin.unwrap()` as a default wrapper cooperation point for managed plugin wrappers.
- Added longrun plugin loading by config, provider discovery, version-range checks, external plugin directory discovery, diagnostic plugin, and the `sample-audit` example plugin/profile.

### Changed

- External longrun plugin providers now use a managed child-first independent classloader that is released when plugins close, while the LDB API package remains parent-loaded.
- Plugin capability enforcement now also guards metadata reads, write-batch creation through plugin context, and checkpoint hooks.
- `Options.cacheBlocks(false)` now truly disables BlockCache while keeping an observable disabled state.
- `ldb.api.supportedFeatures` now marks runtime column-family list/create/drop-empty support, while `ldb.api.unsupportedFeatures` keeps non-empty drop and rename explicit.
- Added `ldb.api.ecosystemGaps` to explain blocking reasons for MergeOperator, PrefixExtractor, transactions, TTL, custom Env, non-empty column-family drop/rename, and related ecosystem gaps.
- Updated the Chinese and English performance/reliability, project design, API compatibility, plugin, and external-commitment documents for the 0.4.0 capability set.

### Verification

- Focused plugin release verification passed with `./gradlew.bat test --tests "*LdbPluginTest" --tests "*LongRunPluginResolverTest" --tests "*LongRunConfigTest" --tests "*SmokeRunnerTest" --tests "*ReportAnalyzerTest"`.
- Full release verification remains required before publishing: `./gradlew.bat clean test` and `./gradlew.bat clean publishToMavenLocal`.

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
