# Changelog

English | [中文](CHANGELOG.md)

This document records important changes for `vexra ldb`. It follows the spirit of Keep a Changelog and uses semantic versioning.

## [Unreleased]

## [0.9.0] - 2026-06-20

### SF-06 v2 Storage-Format Production Observability

- Added the `ldb.tableFormatPolicy` runtime property, centralizing the new-write format, v2 properties switch, legacy read policy, unknown/future fail-fast policy, rollback action, and production state.
- `LdbObservabilityTest` covers default v1, explicit v2, rollback wording, and fail-fast policy evidence.
- Added `tableFormatPolicyCoverage` to `storageFormatGates`, and require Chinese/English format references, design docs, acceptance docs, README, user manual, and operations docs to include `ldb.tableFormatPolicy` production guidance.
### RR-01 Bloom/Filter Block Random-Read Optimization

- When `BloomFilterPolicy` is enabled, SSTs write a `filter.<policyName>` metaindex entry and readers use the matching policy for full-key `mayContain`.
- Level0, LevelN, and MultiGet candidate SSTs run the filter check before opening table iterators; when Bloom returns false, the read records `filterSkips` and skips that SST.
- `LdbObservabilityTest` adds an in-range missing-key case and asserts `filterSkips>0`, `mayContainRequests>0`, and `mayContainFalse>0`.
- Added `filterBlockCoverage` to `storageFormatGates`, requiring Chinese/English format references, design docs, acceptance docs, README, user manual, and operations docs to record Bloom/filter block pre-release evidence.
- 200k warm_readrandom release-preparation comparison: LDB ead_optimized reached 247,361.396 ops/s, RocksDB JNI reached 444,235.456 ops/s, and the ratio was 55.68%, meeting the P0 target of at least 50%.
### REL-01 Release Workflow Fix

- Added `verifyUserManagedReleaseConfig` and `publishUserManagedRelease`; the latter requires a formal release version, an explicit remote release repository, and USER_MANAGED/user-managed or staging review mode.
- Added `userManagedReleaseConfig` and `gitReleaseTraceability` gates to `releaseGate`, failing release checks when the release repository is AUTOMATIC, review mode cannot be proven, or a formal upload cannot prove that the release commit and tag have been pushed.
- The ordinary release repository selection path remains fail-fast to avoid treating a plain successful `publish` as proof of user-managed publication again; `publishUserManagedRelease` now requires a clean worktree, pushed upstream commit, and pushed `v${version}` tag before central upload.
## [0.8.0]   2026 06 20

### Added

  Added opt-in SST/table v2 properties-block writes and reader-side parsing, recording table format version, feature sets, and entry/block/filter/compression/key/checksum self-description fields.
  Added `Options.tableFormatVersion`, `Options.writeTableProperties`, `Options.allowLegacyTableFormat`, `Options.failOnUnknownTableFeature`, and `OptionsView` read-only snapshots. Default writes remain v1, and v2 requires explicit opt-in.
  Added `ldb.tableFormat` and `ldb.storageFormat` diagnostic properties, plus `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables` evidence fields in check and repair reports.
  Added backup metadata schema, chainId, generation, and object-store schema evidence to improve backup-chain diagnostics and future migration anchors.
  Added Chinese and English storage-format references, the 0.8 design, and the release acceptance matrix, and release-gated README, user manual, operations runbook, and Options API contract coverage through `storageFormatDocs`.

### Changed

  The default on-disk write format remains v1, and old SST/table files remain readable by default. Future format versions, unknown incompatible features, and malformed table format versions fail fast by default to avoid silent misreads.
  `releaseGate` now includes stronger `storageFormatGates` covering docs, TableProperties, future/malformed version guards, mixed-format check, repair, backup, plugin OptionsView, and default legacy write policy.
  Switched the build version to the formal release-candidate version `0.8.0` for pre-release gates and local publication verification.

### Verification

  Before release, run `.\gradlew.bat test`, `.\gradlew.bat releaseGate`, and `.\gradlew.bat publishToMavenLocal`. Final results are recorded in `docs/release.md` under `0.8.0 pre-release verification record`.
## [0.6.0]   2026 06 18

### Added

  Added Chinese and English LDB/RocksDB performance baseline documents, an `ldbDbBenchReport` task, a native `db_bench` comparison script, and a RocksDB JNI comparison script covering `fillseq`, `readrandom`, `overwrite`, and `readwhilewriting` scenarios.
  Added Chinese and English RocksDB gap and next version planning documents, confirming the `0.6.0` next development line, `11.1.1` comparison baseline, `MultiGet` as the low risk implementation item, and unsupported advanced API boundaries.
  Added batch point reads through `LDB#get(List<byte[]>)` and column family overloads, preserving input order and returning null for missing keys.
  Added `ldb.recoveryEvidence` and `ldb.backupEvidence` diagnostic properties for archiving WAL/MANIFEST, check/repair, checkpoint, backup/restore, object store, and cleanup dry run evidence conventions.
  Added `ldb.columnFamilyEvidence` for archiving column family registry state, active/dropped counts, MemTables, level files, and drop/rename policy.
  Added `ldb.prefixReadiness` for archiving PrefixExtractor, prefix bloom, and cache warmup prerequisites plus the current cache/filter configuration; this phase is observation only and does not change the read path.
  Closed the RocksDB gap plan open question defaults for performance gates, property contracts, old version access to new data, external observability, and backup storage backends.
  Added the read only `LdbTool scan <db> [limit]` diagnostic command, emitting a default CF key order base64 JSON sample with a default limit of 100 entries and no WAL/MANIFEST modification.
  Added the `0.6.0` pre release verification record to `docs/release.md`, requiring archived releaseGate, MultiGet, recovery/backup/column family evidence, `ldb.prefixReadiness`, `scan`, and open question default decisions.
  Tightened CURRENT/MANIFEST recovery validation so both `check` and `open` reject illegal manifest file names or CURRENT contents with path separators.
  Enhanced `checkBackup` evidence reporting so `CheckReport.checkedFiles` now records `BACKUP-MANIFEST.json`, `OBJECT-REFS.json`, and checked object file names.
  Enhanced the `releaseGate` report with `rocksdbGapPlan` and `rocksdbGapGates` groups for archiving the RocksDB baseline, next version target, MultiGet acceptance, and advanced API unsupported policy.
  Added Chinese and English non empty column family drop/rename/tombstone design documents covering logical deletion, stable cfId identity, MANIFEST/registry history, rollback, and compatibility boundaries.
  Added Chinese and English Backup Engine design documents for shared object storage, reference counts, backup chain GC, dry run cleanup, and publication state management.
  Added Chinese and English long run stress and benchmark report design documents covering workload matrices, machine readable reports, release thresholds, and failure preservation.
  Added Chinese and English production readiness plan documents covering old version upgrade fixtures, `releaseGate`, backup object store corruption injection, column family tombstone long stress, production gate longrun, and operations runbooks.
  Added Chinese and English operations runbooks covering release gates, upgrades, backup, restore, check/repair, column family tombstones, and incident handling order.
  Implemented the minimal runtime non empty column family drop tombstone and rename loop, with the matching features exposed through `ldb.api.supportedFeatures`.
  Enhanced incremental backup object storage with `objects/`, `OBJECT-REFS.json`, and `planPurgeBackups` dry run cleanup planning.
  Enhanced the longrun benchmark report framework with `summary.json`, `operations.csv`, `failures.json`, `properties before.json`, `properties after.json`, and explicit Gradle task entries.

### Changed

  Switched the build version to the formal release version `0.6.0` for local publication verification.

## [0.4.0]   2026 06 08

### Added

  Added a disabled by default minimal group commit implementation with `Options.groupCommitEnabled`, `groupCommitMaxDelayNanos`, `groupCommitMaxBatchBytes`, and `ldb.groupCommitStats`.
  Added longrun reporting for write strategy comparability: `workloadSyncWrites`, group commit settings, plugin async settings, and plugin callback budget settings are persisted in `state/run.properties` and copied into `report/summary.properties`.
  Added `LDBFactory.createIncrementalBackup` and `checkBackup`, with `BACKUP-MANIFEST.json` recording copied and reused files while preferentially reusing SST files from the previous backup.
  Added `LDBFactory.planRepair` and `ldb repair plan <db>` dry run entry points that output a repair plan without modifying the database directory.
  Added operation latency histogram properties, `ldb.blockCacheStats`, and `incremental backup`/`check backup` tool commands.
  Added minimal runtime column family `list/create/drop empty` support and a `COLUMN FAMILIES` registry that backup, checkpoint, check, and repair carry and validate.
  Added Chinese and English column family lifecycle design documents plus corruption matrices for registry, CURRENT, backup registry, and runtime CF WAL only repair scenarios.
  Added `LdbPluginCompatibility` as a lightweight provider/plugin compatibility testkit.
  Added `ldb.plugin.maxTotalCallbackMillis` to govern cumulative plugin callback cost per plugin.
  Added `LdbPlugin.unwrap()` as a default wrapper cooperation point for managed plugin wrappers.
  Added longrun plugin loading by config, provider discovery, version range checks, external plugin directory discovery, diagnostic plugin, and the `sample audit` example plugin/profile.

### Changed

  External longrun plugin providers now use a managed child first independent classloader that is released when plugins close, while the LDB API package remains parent loaded.
  Plugin capability enforcement now also guards metadata reads, write batch creation through plugin context, and checkpoint hooks.
  `Options.cacheBlocks(false)` now truly disables BlockCache while keeping an observable disabled state.
  `ldb.api.supportedFeatures` now marks runtime column family list/create/drop empty support, while `ldb.api.unsupportedFeatures` keeps non empty drop and rename explicit.
  Added `ldb.api.ecosystemGaps` to explain blocking reasons for MergeOperator, PrefixExtractor, transactions, TTL, custom Env, non empty column family drop/rename, and related ecosystem gaps.
  Updated the Chinese and English performance/reliability, project design, API compatibility, plugin, and external commitment documents for the 0.4.0 capability set.

### Verification

  Focused plugin release verification passed with `./gradlew.bat test   tests "*LdbPluginTest"   tests "*LongRunPluginResolverTest"   tests "*LongRunConfigTest"   tests "*SmokeRunnerTest"   tests "*ReportAnalyzerTest"`.
  Full release verification remains required before publishing: `./gradlew.bat clean test` and `./gradlew.bat clean publishToMavenLocal`.

## [0.2.0]   2026 06 01

### Added

  Added Chinese and English README files.
  Added the overall project design document and English copy.
  Added contribution guide, security policy, NOTICE, release guide, and CI configuration.
  Added benchmark/soak regression entry points covering writes, random reads, snapshot scan, manual compaction, checkpoint, and reopen workflows.
  Added compaction pressure reliability regressions covering manual compaction pressure and repeated compaction recovery after write bursts.
  Added a recovery loop regression test that connects checkpoint, backup/restore, repair, and check.
  Added future performance and reliability evaluation documents, covering group commit, incremental backup, and range delete hardening.
  Added plugin capability enhancement design documents.
  Added plugin enhancement APIs: `LdbPluginDescriptor`, `LdbPluginFailurePolicy`, `OptionsView`, `WriteEvent`, and `WriteBatchView`.
  Added plugin observability properties: `ldb.plugins`, `ldb.pluginStats`, `ldb.plugin.<index>.stats`, and `ldb.plugin.lastFailure`.
  Added Windows + Ubuntu JDK 8 CI and local Maven publication verification.
  Added English copies for the changelog, contribution guide, security policy, code of conduct, and release guide, with language switch links across user facing documents.

### Changed

  Fixed Maven POM metadata so the project name, description, and homepage point to `vexra ldb`.
  Optimized the range tombstone read path to avoid unnecessary scans during ordinary point gets.
  Added `Options.writeSlowdownDelayNanos` so Level 0 slowdown delay is configurable and observable through diagnostic properties.
  Clarified plugin `afterWrite` and `afterCheckpoint` post commit notification failure semantics so callers do not assume committed data is rolled back.
  Switched the version to the formal release version `0.2.0`.

### Fixed

  Added regression coverage for plugin post commit failure, checkpoint post callback failure, read only config view, and read only write event behavior.
  Completed local publication verification for main jar, sources jar, javadoc jar, POM, module, and matching signature files.

## [0.1.0]

### Added

  Added the basic LDB API, WAL, MemTable, SSTable, MANIFEST/CURRENT, VersionSet, and compaction.
  Added column families, snapshot cursor, checkpoint, offline check/repair/backup/restore, and tool commands.
  Added reliability, range delete, API compatibility, and overall project design documents.

## Addendum: 0.6.0 pre release MultiGet deep optimization

### Completed in this version
  Completed the deeper MultiGet SST batching optimization: Level0 now batches unresolved keys across newest to oldest files, non L0 levels group keys by SST file, and each SST reuses one table iterator for the grouped keys to reduce repeated iterator creation and table read accounting on the MultiGet miss path.
  Fixed the SST candidateFiles profiling scope: empty levels no longer inherit the previous read statistics, so candidateFiles/tableReads/iteratorRequests better describe the current read path.
  Kept the on disk file format unchanged. This version only changes read path behavior, profiling accuracy, and benchmark traceability.

### Pre release evidence
  LDB read_optimized multiget_random 200k: 200,302.818 ops/s.
  RocksDB JNI multiget_random 200k: 243,015.078 ops/s.
  LDB/RocksDB JNI ratio: 82.42%.
  After the deeper LDB MultiGet optimization: tableReads=34,315, iteratorRequests=34,394, candidateFiles=200,000.
  Report paths: `ldb longrun/build/reports/ldb multiget optimized 200k/ldb db bench summary.json`, `build/reports/rocksdbjni comparison multiget optimized 200k/comparison.csv`.

### Deferred to the next version
  File format capabilities remain deferred: stricter key-prefix-compression metadata, restart-point metadata, compression-block extensions, partitioned index/filter, and range tombstone/merge operand format support.
## Addendum: 0.6.0 pre release full gate

### Release validation
  Completed the full pre release gate: `clean test releaseGate publishToMavenLocal`.
  Gradle build result: `BUILD SUCCESSFUL`.
  Covered tasks: main module tests, `ldb longrun:test`, `releaseGateUnitTest`, `upgradeCompatibilityTest`, `productionGateLongRun`, `releaseGate`, and `publishToMavenLocal`.
  `productionGateLongRun` result: `SUMMARY status=PASS`.

### Known non blocking items
  Gradle still reports deprecated features; Gradle 8 compatibility remains follow up work.
  Java compilation still reports deprecated/unchecked API warnings.
  Windows directory force may still emit an `AccessDeniedException` warning, but this release gate completed successfully and does not block the current release.
## Addendum: 0.7.0 formal release upload

### Release status
  Switched the project version to `0.7.0` and executed the formal release upload: `clean releaseGate publish`.
  Gradle build result: `BUILD SUCCESSFUL`.
  `releaseGate` passed under the formal `0.7.0` version.
  `productionGateLongRun` result: `SUMMARY status=PASS`.
  `publishMavenPublicationToMavenRepository` and `publish` completed successfully. The release artifacts have been uploaded to the central repository release endpoint and are waiting for manual review / follow up release confirmation.

### Next development version
  After the release upload, the workspace version has been advanced to `0.8.0-SNAPSHOT`.
## Addendum: 0.8.0 Development Line Goal

### Planning
  The `0.8.0-SNAPSHOT` version goal is now file format improvement and evolution.
  Added design documents: `docs/storage-format-0.8-design.md` and `docs/storage-format-0.8-design.en.md`.
  Initial direction: SST/table format version, feature set, properties block, old database compatibility, unknown incompatible feature fail fast behavior, and check/repair/report format evidence.
## Addendum: 0.8.0 SF 01 Storage Format Reference

### Documentation
  Added `docs/storage-format.md` and `docs/storage-format.en.md`, recording the current WAL, SST/table, MANIFEST, CURRENT, COLUMN FAMILIES, backup metadata, and check/repair file format facts.
  SF 01 is complete; next work moves to SF 02 table properties block reader and SF 03 format version/feature set.
## Addendum: 0.8.0 SF 02/SF 03 Reader Skeleton

### Changes
  Added `TableProperties` as the reader side model for SST/table properties blocks.
  `Table` now attempts to parse the metaindex `properties` entry when opening an SST; missing entries are classified as v1 legacy.
  Unknown incompatible table features fail fast by default.
  Added reader side protection switches: `Options.allowLegacyTableFormat` and `Options.failOnUnknownTableFeature`.
  Added `TableCache#getTableProperties(long)` for later check/repair/report integration.

### Boundary
- This reader increment does not change the default SST/table write format; later 0.8 increments added v2 opt-in properties writes, check/repair/report evidence, and `storageFormatGates`.
## Addendum: 0.8.0 SF 04 v2 Properties Opt In Writes

### Changes
  Added `Options.tableFormatVersion(int)`, defaulting to `1`, explicitly settable to `2`.
  Added `Options.writeTableProperties(boolean)`, affecting writes only when `tableFormatVersion=2`.
  `TableBuilder` supports opt in v2 properties block writes and records the `properties` block handle in metaindex.
  The properties block currently records format/version, feature set, entry/block/filter/compression/key/checksum self description fields.

### Boundary
  The default still writes v1 SST/table files and does not change old databases or default write paths.
## Addendum: 0.8.0 SF 04 Focused Tests

### Tests
  Added `TablePropertiesTest`, covering default v1 legacy behavior, explicit v2 properties blocks, unknown incompatible feature fail fast behavior, and `tableFormatVersion` option validation.
## Addendum: 0.8.0 SF 05 Diagnostic Properties

### Changes
  Added `ldb.tableFormat`, summarizing current SST/table format versions, legacy/v2 counts, and feature sets.
  Added `ldb.storageFormat`, summarizing format policy for table/WAL/MANIFEST/CURRENT/COLUMN FAMILIES/backup metadata.
  Extended `LdbObservabilityTest` with storage format property coverage.
## Addendum: 0.8.0 SF 05 Check Report Fields

### Changes
  `CheckReport` now includes storage format evidence fields: `storageFormat`, `tableFormats`, `legacyTables`, `v2Tables`, and `incompatibleTables`.
  `LdbTool check` output automatically includes these fields.
  `LdbVerifyCheckTest` now covers check reports for v2 opt in SSTs.
## Addendum: 0.8.0 SF 05 Release Gate

### Changes
  Added a `storageFormatGates` group to `releaseGate`.
  The overall release gate result now includes checks for storage format docs, TableProperties unit coverage, CheckReport storage format evidence, and the default v1 write policy.
## 0.8.0-SNAPSHOT Storage Format Addendum

  Added backup metadata schema fields: `BACKUP-MANIFEST.json` records `schemaVersion/chainId/generation`, and `OBJECT-REFS.json` records `schemaVersion/objectStoreVersion/generatedBy`.
  Improved incremental backup chain identity by inheriting `chainId` from the parent manifest, improving diagnostic and migration stability.
## 0.8.0-SNAPSHOT Repair Report Format Evidence

  `REPAIR-REPORT.json` and `repair plan` output now include structured storage format fields for table summaries and v1/v2/incompatible counters.
  Added a repair report evidence item to release gate `storageFormatGates`.
## 0.8.0-SNAPSHOT v2 Repair Format Preservation

  Added regression coverage for preserving v2 SST format during repair; `REPAIR-REPORT.json` can show `formatVersion=2`, `table.properties`, and `v2Tables`.
## 0.8.0-SNAPSHOT Mixed Format Check Coverage

  Added check report coverage for mixed v1/v2 SSTs in one database directory, with release gate `mixedFormatCheckCoverage`.

## 0.8.0-SNAPSHOT Storage Format Acceptance Matrix

  Added Chinese and English `storage-format-0.8-acceptance` acceptance matrix documents, centralizing storage format goals, implementation evidence, test evidence, and release gates.
  Extended the `storageFormatDocs` gate to check acceptance matrix keywords.
  Added a storage format gate blocking item list to the acceptance matrix, and made release gate document checks cover all storage format gate names.
  Added table format read only getters to `OptionsView` so plugins can observe storage format policy; the new getters use Java 8 default methods for interface compatibility.
  Extended the `storageFormatDocs` gate with `OptionsView` / `failOnUnknownTableFeature` acceptance matrix checks.

## 0.8.0-SNAPSHOT OptionsView Storage-Format Snapshot Fix

- OptionsSnapshot now captures the four table-format read-only configuration fields, allowing plugins to observe the actual `tableFormatVersion/writeTableProperties/allowLegacyTableFormat/failOnUnknownTableFeature` through OptionsView.

## 0.8.0-SNAPSHOT pluginOptionsViewCoverage Gate

- Added `pluginOptionsViewCoverage` to `storageFormatGates`, making plugin `OptionsView` storage-format policy snapshot coverage a release-blocking item.

## 0.8.0-SNAPSHOT Future Table-Format Version Guard

- TableProperties.validateReadable now rejects table format versions newer than the current reader support range by default, with TablePropertiesTest coverage to reduce silent-misread risk when future formats miss incompatible feature markers.

## 0.8.0-SNAPSHOT future-version Documentation Gate

- `storageFormatDocs` now checks the `future-version` keyword, covering release-audit evidence for future table-format version fail-fast behavior.

## 0.8.0-SNAPSHOT future-version Design Documentation Gate

- `storageFormatDocs` now checks future table-format version fail-fast coverage in both design documents, keeping design evidence aligned with the acceptance matrix.
## 0.8.0-SNAPSHOT future-version Rollback Boundary Gate

- `storageFormatDocs` now checks future-version diagnostic-read wording, ensuring the acceptance matrix states that disabling `failOnUnknownTableFeature` is not a production rollback strategy.
## 0.8.0-SNAPSHOT futureVersionFailFastCoverage Gate

- Added `futureVersionFailFastCoverage` to `storageFormatGates`, independently blocking missing future table-format version fail-fast or diagnostic-read boundary coverage.
## 0.8.0-SNAPSHOT Malformed Table-Format Version Guard

- `TableProperties.read` now reports an explicit `Invalid table format version` error for non-numeric `ldb.format.table.version` values, with malformed format-version test coverage.
## 0.8.0-SNAPSHOT malformed-version Documentation Gate

- `storageFormatDocs` now checks the `malformed-version` keyword, covering release-audit evidence for non-numeric or non-positive table format version failures.
## 0.8.0-SNAPSHOT malformed-version Design Documentation Gate

- `storageFormatDocs` now checks malformed table format version coverage in both design documents, ensuring explicit fail-fast behavior for non-numeric or non-positive table format versions remains part of release-audit evidence.
## 0.8.0-SNAPSHOT User/Operations Storage-Format Documentation Gate

- `storageFormatDocs` now checks the Chinese and English README, user manual, and operations runbook for storage-format entry points covering `ldb.tableFormat`, `ldb.storageFormat`, `tableFormatVersion`, `failOnUnknownTableFeature`, `CheckReport.storageFormat`, `RepairReport.storageFormat`, and `storage-format-0.8-acceptance`.
- Storage-format release evidence now spans implementation, design, acceptance matrices, user discoverability, and operational evidence-archiving requirements.
## 0.8.0-SNAPSHOT storageFormatDocs Acceptance Matrix Alignment

- The storage-format acceptance matrix now aligns the `storageFormatDocs` blocking condition with README, user manual, and operations runbook coverage, adding `ldb.tableFormat`, `ldb.storageFormat`, `CheckReport.storageFormat`, and `RepairReport.storageFormat` evidence requirements.
## 0.8.0-SNAPSHOT storageFormatDocs Design Documentation Alignment

- The `storageFormatDocs` description in the 0.8 storage-format design documents now matches the actual gate scope, covering the acceptance matrix, README, user manual, operations runbook, and table/storage-format evidence terms.
## 0.8.0-SNAPSHOT Storage-Format Options API Contract Documentation

- Added JavaDoc to table-format public methods in `Options` and `OptionsView`, covering default v1 behavior, v2 opt-in, legacy compatibility, future/unknown/malformed fail-fast handling, and diagnostic-read boundaries.
## 0.8.0-SNAPSHOT Options API Contract Gate

- `storageFormatDocs` now checks the table-format public methods in `Options` / `OptionsView` and the `diagnostic-only` boundary so storage-format API contract documentation remains release-gated.
## 0.8.0-SNAPSHOT Options API Contract Acceptance Alignment

- The 0.8 storage-format design documents and acceptance matrix now align with the Options API contract gate scope, adding `Options.tableFormatVersion`, `OptionsView.failOnUnknownTableFeature`, and `diagnostic-only` evidence requirements.
## 0.8.0-SNAPSHOT Options API Contract Gate Closure

- `storageFormatDocs` now checks Options API contract evidence terms across source API comments, 0.8 storage-format design documents, and the acceptance matrix so the `diagnostic-only` boundary is not present in only one artifact.
## 0.8.0-SNAPSHOT Format Reference Options API Contract

- `docs/storage-format.md` and its English copy now document the 0.8 table-format policy, Options API contract, and `diagnostic-only` boundary; `storageFormatDocs` now checks the related evidence terms in the format reference.
## Addendum: 0.8.0 formal release upload

### Release status
  Executed the formal `0.8.0` release upload with `.\gradlew.bat publish`.
  Gradle build result: `BUILD SUCCESSFUL`.
  This upload did not execute an additional automatic release/publish confirmation step; however, the Gradle output did not prove that the remote deployment was created in user-managed mode. Future remote uploads must force USER_MANAGED or an equivalent staging review mode in the release configuration before publishing.
  The Gradle output did not expose a deployment/repository id, so the user must review artifacts, POM, signatures, and dependencies in the central repository portal before confirming publication.

### Next development version
  After the release upload, the workspace version has been advanced to `0.9.0-SNAPSHOT`.
