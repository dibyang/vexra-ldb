# vexra-ldb External Commitment for LDB consumers and Plugin Extension Developers

English | [中文](vexra-ldb-external-commitment.md)

## Background

LDB consumers depend on `vexra-ldb` as an embedded persistence component. This document records `vexra-ldb` external commitments and usage boundaries for LDB consumers. It captures the existing or expected `vexra-ldb:0.3.0` behaviors that LDB consumers rely on, so future LDB upgrades, plugin changes, checkpoint/restore changes, and longrun validation have explicit boundaries.

## Goals

- Capture LDB consumers' minimum dependencies on column families, checkpoint/restore, DbStore semantics, and version upgrades.
- Separate public contracts that LDB consumers may rely on from LDB internal implementation details.
- Define the minimum acceptance matrix for LDB consumers' dependency on `vexra-ldb:0.3.0`.

## Scope Boundary and Non-Goals

- This document does not require new LDB consumers features.
- This document does not require LDB consumers to depend on LDB internal `impl` packages.
- This document does not expose WAL, SST, MANIFEST, or CURRENT file formats as LDB consumers business contracts.
- This document does not promise multi-process concurrent writes to the same LDB directory.

## Contract Scope

| Area | LDB consumers may rely on | LDB consumers should not rely on |
| --- | --- | --- |
| Java API | Public APIs such as `LDB`, `Options`, `LdbWriteBatch`, `ReadOptions`, `WriteOptions`, and `LdbColumnFamily` | Internal classes under `net.xdob.vexra.ldb.impl` |
| Storage behavior | Public semantics of open, write, get, scan, checkpoint, backup, restore, and repair | WAL/SST internal encoding and file numbers |
| Compatibility | `0.3.0` public APIs and ability to open existing on-disk data | Fixed property field ordering and internal thread names |
| Plugins | Trusted internal plugin hook lifecycle and failure semantics | Third-party plugin security sandboxing |

## Column-Family Registration

LDB consumers depend on stable column-family registration to isolate business domains or index domains.

| Contract | Requirement |
| --- | --- |
| Static declaration | LDB consumers declare required column families before open through `Options.addColumnFamily` or plugin `configure(Options)` |
| Stable id | Column-family ids must remain stable within the same LDB consumers data directory and must not be reused for different semantics |
| Stable name | Column-family names should be stable and diagnosable for registry, logs, and check reports |
| Default column family | The default column family is always available and can be used for default metadata or compatibility paths |
| Persistent registry | LDB persists registered column-family metadata so reopen, checkpoint, backup, restore, and repair can recognize them |
| Unknown column family | Unknown column families during open, recovery, or repair should fail clearly instead of silently using wrong semantics |

Consumer-side constraints:

- LDB consumers should not bypass public APIs to modify the column-family registry file.
- When adding a column family during an consumer upgrade, existing ids and semantics must remain unchanged.
- If an LDB consumer deprecates a column-family meaning, it should migrate at the upper layer and not reuse the old id.

## checkpoint / restore Stability

LDB consumers rely on checkpoint, backup, and restore for backup, migration, recovery, and test validation.

| Contract | Requirement |
| --- | --- |
| Consistency | A successfully returned checkpoint/backup should be openable or checkable by LDB; half-built outputs must not be reported as success |
| Column-family completeness | checkpoint/backup must carry the column-family registry so restored ids and names remain recognizable |
| File-format encapsulation | LDB consumers depend only on LDB APIs to generate and restore outputs, not on concrete file lists or file numbers |
| Failure semantics | Failures should return exceptions or failure reports; an unopenable directory must not be marked as success |
| Readability after restore | After restore, opening with the same column-family declarations should expose data visible at checkpoint/backup time |
| Plugin post failure | Plugin `afterCheckpoint` failure does not necessarily mean checkpoint data is absent; callers must distinguish by exception messages and reports |

Consumer-side constraints:

- LDB consumers explicitly own the overwrite, cleanup, and retention policy for restore target directories.
- LDB consumers should not concurrently modify the target directory during checkpoint/restore.
- LDB consumers' acceptance should include at least one post-restore open and key-read check.

## DbStore Semantic Contract

LDB consumers' `DbStore` layer usually maps LDB to key-value, counter, scan, batch, and transaction-boundary capabilities. The following contracts capture LDB consumers' minimum semantic dependencies on LDB.

### Counters

| Contract | Requirement |
| --- | --- |
| Atomic increment | `addLong` or an equivalent batch increment is atomically applied within a single LDB write |
| Recovery consistency | After write success, reopen/recovery must not lose committed counter updates |
| Type boundary | Counter encoding is agreed through LDB consumers and LDB APIs; LDB consumers should not mix normal value and counter-value semantics |
| Concurrent visibility | Within the same LDB instance, committed updates are visible according to current snapshot/read-option semantics |

### scan

| Contract | Requirement |
| --- | --- |
| Ordered iteration | scan/cursor results are returned in key order defined by the LDB comparator |
| Range boundaries | start/limit or equivalent range semantics remain stable and do not return out-of-range keys |
| Snapshot consistency | snapshot cursor scan keeps a consistent view during cursor lifetime |
| Resource release | LDB consumers must close cursors; LDB releases related resources after close |

### batch

| Contract | Requirement |
| --- | --- |
| Atomic commit | Changes in one `LdbWriteBatch` across keys and column families are applied as one write |
| Ordering semantics | Operations in the same batch are encoded and applied in batch order; later operations may override earlier same-key results |
| Empty batch | Empty batch behavior must be explicit: either successful no-op or rejected by upper layers/plugins, never partially committed |
| No partial commit on failure | If `beforeWrite` or pre-write validation fails, LDB does not write WAL, advance sequence, or apply MemTable |

### commit / rollback

| Contract | Requirement |
| --- | --- |
| commit boundary | LDB consumers commit should map to one or more explicit successful LDB writes |
| rollback boundary | LDB consumers may discard an uncommitted batch; LDB does not provide transactional rollback after a write has succeeded |
| Exception interpretation | `afterWrite` plugin failure is post-commit notification failure; data may already be committed, so LDB consumers must retry idempotently |
| Crash recovery | Data from a successful write, subject to write-option durability semantics, should satisfy LDB recovery guarantees after reopen/recovery |

## LDB Version Upgrade Compatibility Boundary

LDB consumers depend on the following stability boundaries during LDB upgrades.

| Type | Compatibility requirement |
| --- | --- |
| Source compatibility | Do not remove public APIs used by LDB consumers; provide migration windows for necessary changes |
| Binary compatibility | Minor upgrades should keep method signatures and public types compatible where possible |
| Data compatibility | New versions should open data directories created by `0.3.0` unless release notes explicitly mark a breaking migration |
| Behavior compatibility | Public semantics of get, write, batch, scan, column family, checkpoint, and restore should not be silently broken |
| Config compatibility | Changes to `Options` defaults or meanings used by LDB consumers must be documented in release notes |
| Plugin compatibility | Old plugins with default methods should continue to run; new capability declarations should not immediately break them |

LDB consumers should not rely on:

- Internal compaction file-picking policy.
- SST level numbers or file naming details.
- Property string field ordering.
- Internal thread-pool names, log messages, or exact warning text.

## Minimum Acceptance Matrix for `vexra-ldb:0.3.0`

| ID | Area | Acceptance item | Minimum pass criteria |
| --- | --- | --- | --- |
| A1 | open/reopen | Create, close, and reopen a new DB | Data is readable and column-family registry loads |
| A2 | column family | Multi-column-family write, read, and reopen | Data is isolated by column family; ids/names remain stable |
| A3 | counter | Counter increment and reopen | Committed counter values recover consistently |
| A4 | scan | Range scan and snapshot scan | Order, boundaries, and snapshot view match expectations |
| A5 | batch | Cross-key and cross-column-family batch | Success is atomically visible; failure has no partial commit |
| A6 | commit/rollback | Discard uncommitted batch and recover committed writes | rollback does not affect committed data; successful commit recovers |
| A7 | checkpoint | Create checkpoint and open/check it | Checkpoint is openable; key data and column families are readable |
| A8 | restore | Open after backup/restore | Restored directory is openable and key data is readable |
| A9 | crash/recovery | Recover after process failure during writes | No checksum mismatch; committed data follows recovery semantics |
| A10 | compatibility | Open old `0.3.0` data directory with upgraded LDB | Opens without manual file edits or reports a clear migration error |
| A11 | plugin boundary | Plugin `beforeWrite` rejection and `afterWrite` failure | Pre-write rejection has no partial commit; post-write failure is marked post-commit |
| A12 | longrun | smoke, crash, and performance profiles | smoke/crash PASS; performance prints key metrics and component version |

### Acceptance Matrix Execution Mapping

| ID | Automated entry | longrun/profile | Required log or report fields |
| --- | --- | --- | --- |
| A1 | `LdbCoreBehaviorTest.shouldPersistDefaultAndCustomColumnFamiliesAcrossReopen` | `smoke.properties` | `PASS smoke`, `reopenChecks` |
| A2 | `LdbCoreBehaviorTest.shouldPersistDefaultAndCustomColumnFamiliesAcrossReopen`, `LdbColumnFamilyLifecycleTest` | `smoke.properties` | `CONFIG ldb.writeBufferSizeMb`, `ldb.columnFamilies` |
| A3 | `LdbCoreBehaviorTest.shouldPreserveCounterAddLongBatchAcrossColumnFamiliesAndReopen` | `smoke.properties` | `SUMMARY status=PASS` |
| A4 | `LdbSnapshotIteratorTest`, `LdbCoreBehaviorTest.shouldReadStableValueFromSnapshotAfterOverwrite` | `reopen.properties` | `PROGRESS ... reopenChecks=` |
| A5 | `LdbCoreBehaviorTest.shouldReplayMultiColumnFamilyBatchAfterReopen`, `shouldRejectInvalidAddLongDeltaBeforePersistingBatch` | `smoke.properties` | `RESULT phase=workload` |
| A6 | `LdbCoreBehaviorTest.shouldAbortWriteWhenPluginRejectsBeforeWrite` | `crash.properties` | `RECOVERY reconciled`, `recoveryChecks` |
| A7 | `LdbCoreBehaviorTest.shouldOpenCheckpointWithExistingData`, `LdbBackupTest` | `smoke.properties` | `FINAL phase=verify` |
| A8 | `LdbBackupTest` | `smoke.properties` | `SUMMARY status=PASS` |
| A9 | `LdbCrashRecoveryTest`, `LdbRecoveryMatrixTest` | `crash.properties` | `PASS crash recovery cycles=` |
| A10 | `LdbApiCompatibilityTest` | Pre-release old-data open gate | compatibility release note |
| A11 | `LdbPluginTest`, `SmokeRunnerTest.runsWithDiagnosticPluginAndReportsPluginState` | `plugin-sample.properties` | `PLUGIN stats=`, `pluginLastFailure` |
| A12 | `SmokeRunnerTest`, `ReportAnalyzerTest` | `smoke.properties`, `crash.properties`, `performance*.properties` | `COMPONENT`, `RESULT`, `SUMMARY`, `PASS` |

## External Commitment Validation

Before an LDB upgrade is consumed by LDB consumers, confirm at least:

- Public APIs used by LDB consumers compile.
- Existing LDB consumers data directories open with the new LDB.
- Column-family registry has no incompatible change.
- Key data is readable after checkpoint/restore.
- counter, scan, batch, commit/rollback semantic tests pass.
- longrun smoke/crash/performance baselines do not show obvious regressions.
- Release notes mention any config default, recovery semantic, or plugin semantic change that affects LDB consumers.

### Upgrade Compatibility Gate

| Gate | Requirement | Evidence |
| --- | --- | --- |
| Old-data open | Open a data directory created by `0.3.0` with the upgraded version | Opens successfully, or release notes document a clear migration error and action |
| checkpoint / restore | Create checkpoint/backup from old data, restore to a new directory, and reopen | Key data and column families are readable; report status is PASS |
| Plugin default methods | Start with an old plugin or a plugin that only implements default hooks | New hooks or capability fields do not break startup |
| longrun baseline | Run smoke, crash, and performance profiles | `COMPONENT`, `RESULT`, `SUMMARY`, and `PASS` fields are present |
| Write strategy record | Record `syncWrites`, group commit, and plugin async config | Report exposes the exact performance-comparison basis |
| Breaking change | If disk format, public API, or default config changes incompatibly | Release notes include migration steps, rollback boundary, and minimum acceptance result |

## Commitment Status (Current Release)

The following table records the implementation status of each public commitment in the current release:

| Commitment | Status | Notes |
| --- | --- | --- |
| Column-family registration stability and persistence | Implemented | APIs and recovery paths are usable; behavior has been verified in reopen/checkpoint/restore flows. |
| checkpoint / restore behavior | Implemented | Successful outputs are openable and verifiable; corrupt backups or unrestorable directories are not published as successful results, and diagnostics are preserved through reports or exceptions. |
| DbStore counter semantics | Implemented | Counter write durability and recovery consistency are covered by current acceptance scope. |
| DbStore scan semantics | Implemented | Ordered range and snapshot consistency can be verified in baseline checks. |
| DbStore batch atomicity and failure behavior | Implemented | Cross-key and cross-column-family batches are committed as one write; `beforeWrite` rejection, invalid `addLong`, and other pre-write failures do not write WAL, advance sequence, or apply MemTable changes. |
| commit / rollback boundaries | Implemented | LDB explicitly commits only successful writes; discarding an uncommitted batch is the rollback boundary, and successfully returned writes do not provide transactional rollback. |
| LDB upgrade and config compatibility boundaries | Implemented | Public APIs, existing data opening, config defaults, and plugin default methods remain compatible within this document's boundary; breaking changes must be documented in release notes and acceptance gates. |
| Minimum acceptance matrix for `vexra-ldb:0.3.0` | Implemented | smoke/crash/performance and core semantics have executable baseline checks. |
| Plugin lifecycle constraints | Implemented | `configure`, `beforeWrite`, `afterWrite`, `beforeCheckpoint`, `afterCheckpoint`, and `close` ordering, failure policies, sync/async boundaries, and stats properties are part of the public constraint set. |

## Resolved Decisions

The following items are no longer open questions. The current release follows these decisions:

| Topic | Decision | External commitment |
| --- | --- | --- |
| Column-family id allocation | LDB does not maintain a global id allocation table. Each consumer or plugin owns stable id/name choices within its namespace and registers them through `Options.addColumnFamily`, plugin `configure(Options)`, or runtime `createColumnFamily(int, String)`. | LDB persists the registry and keeps id/name recognizable across reopen, checkpoint, backup, restore, and repair. Conflicting id/name pairs fail clearly within one DB, and deprecated column-family ids must not be reused for different meanings. |
| Counter encoding | LDB's public counter commitment covers only `addLong` and equivalent batch `addLong`; the encoded semantics are LDB-defined 8-byte long delta/value semantics. | Custom counter/value encodings are upper-layer business semantics and are outside LDB's counter compatibility commitment. Mixing normal values and counter values is a caller-owned migration or isolation concern. |
| Write durability defaults | `WriteOptions.sync` defaults to `false`; callers that require synchronous durability must explicitly use `new WriteOptions().sync(true)`. | LDB follows the write option durability semantics. Acceptance and performance reports must record the effective write options so async-write results are not confused with sync-write results. |
| group commit defaults | `Options.groupCommitEnabled` defaults to `false`; the default wait threshold is `200us`, and the default aggregation limit is `1MB`. These settings take effect only after group commit is explicitly enabled. | Enabling group commit does not change single-write atomicity or recovery semantics. Any group containing a sync request must follow the sync-write path and remains observable through `ldb.groupCommitStats`. |
| Plugin async defaults | `Options.pluginAsyncEnabled` defaults to `false`; when enabled, only post-commit notification-style callbacks may run asynchronously, while `beforeWrite` remains synchronous. | `beforeWrite` rejection still prevents commit and creates no partial write. Async `afterWrite` failure is a post-commit notification failure and is exposed through plugin stats and last-failure properties. |

## Improvement Plan (Executable)

The items below are not reversals of current commitments; they record the completed operationalization of evidence, governance, and developer constraints. Execution details are maintained in the [Plugin Improvement Execution Plan](ldb-plugin-improvement-execution-plan.en.md).

| ID | Improvement item | Gap | Deliverables | Acceptance | Status |
| --- | --- | --- | --- | --- | --- |
| G1 | Commitment-to-test traceability matrix | A1-A12 has test and longrun coverage, but evidence is scattered across test classes, profiles, and log conventions. | Add mappings from each acceptance item to test class / longrun profile / required log field. | Each acceptance item must map to at least one executable test or longrun profile. Non-automatable items must include manual command check and verifiable pass log. | Verified |
| G2 | Plugin combination regression coverage | beforeWrite rejection, afterWrite/afterCheckpoint failures, ordering, async notifications, timeout, provider discovery, external directory isolation, versionRange, capability enforcement, compatibility testkit, and async combinations are covered by focused tests. | Keep plugin regression tests and longrun profiles covering load failure, version mismatch, unauthorized mutation, async queue shutdown waits, managed classloader release, and stat output. | Regression must prove failure is explicit, no partial write exists, stat reasons are observable, compatibility checks are runnable, and external discovery remains off by default. | Verified |
| G3 | Column-family id/name naming constraints | LDB has no global id allocation table, so plugins and consumers need consistent local naming conventions to avoid id reuse or semantic drift. | Add id/name constraint templates in developer guide: id ranges, naming format, deprecation strategy, and conflict handling examples. | New plugin registration docs must declare required ids/names; open/create must fail on conflict instead of silently reusing ids. | Verified |
| G4 | Counter compatibility evidence | `addLong` semantics are committed, but 8-byte encoding, illegal delta handling, and reopen/recovery evidence need dedicated acceptance checks. | Add dedicated counter acceptance instructions/cases covering normal delta, illegal delta, cross-cf batch, and reopen behavior. | Counter acceptance must show normal value and counter value are not mixed; illegal delta fails before write with no partial commit. | Verified |
| G5 | Write strategy and performance report linkage | `sync=false`, group commit disabled by default, and plugin async disabled by default are clear, but logs/release notes should continuously expose options that affect comparability. | Keep longrun/performance report and release-check templates printing `syncWrites`, group commit, plugin async, and key write configs. | Reports must directly show durability and group commit settings; all relevant config changes must be documented in release notes. | Verified |
| G6 | Upgrade compatibility gate | Boundaries are defined in docs, but release-time checks are not yet fixed into repeatable gates. | Add pre-upgrade checks for `open old data`, `backup/restore`, plugin defaults, and longrun baselines in release docs or this commitment. | Each upgrade can be validated with the same checklist; breaking changes must include migration notes, rollback boundary, and minimum acceptance. | Verified |




