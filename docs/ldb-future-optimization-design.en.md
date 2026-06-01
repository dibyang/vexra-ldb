# LDB Future Performance and Reliability Evaluation

English | [中文](ldb-future-optimization-design.md)

## Background

LDB now has benchmark/soak entry points, compaction pressure regression tests, a narrower range tombstone read path, configurable write-stall slowdown delay, and a recovery-loop test covering checkpoint, backup/restore, and repair. The next high-value areas are write aggregation, incremental backup, and full range-delete semantics. Each area can affect WAL, SST, MANIFEST, recovery, compaction, or operational reports, so the boundaries must be fixed before implementation.

## Goals

- Define the implementation landing points for group commit, incremental backup, and range delete.
- Separate changes that can ship without a disk-format change from changes that require explicit format review.
- Protect existing callers: default behavior remains compatible, and new behavior must be observable, disableable, and recoverable.

## Non-Goals

- This document does not implement group commit, incremental backup, or a new range-delete format.
- It does not introduce RocksDB JNI, RocksJava, or external storage services.
- It does not change the current compatibility behavior of `LDBFactory.createBackup/restoreBackup`, `LDB#checkpoint`, or `LdbWriteBatch.deleteRange`.

## Current Flow

| Area | Current capability | Main gap |
| --- | --- | --- |
| Write path | A single writer appends WAL and then applies MemTable; write-stall counters and configurable slowdown delay exist | No group commit that merges concurrent write requests into one WAL append/sync cycle |
| Backup | Offline full backup/restore, temporary-directory publish, `BACKUP-REPORT.json`, `RESTORE-REPORT.json`, and old-version purge | No shared SST/WAL files, reference counts, backup metadata index, or incremental restore verification |
| Range Delete | API, WAL/SST/MemTable/read/compaction have baseline semantics; the read path avoids unnecessary full tombstone scans | Still needs format-version boundaries, old-version compatibility rules, longer snapshot/compaction/repair matrices, and downgrade rules |

## Core Constraints

- Keep JDK8 compatibility.
- Default configuration must preserve current behavior.
- Every disk-format change requires compatibility tests: new version reading old DBs, old version failing clearly or degrading read-only for new formats, and explainable repair/check/backup reports.
- Write commit semantics must remain strict: a call succeeds only after WAL succeeds and the MemTable is applied; `WriteOptions.sync(true)` must not be silently downgraded.
- Backup publishing must keep using temporary directories, and unfinished versions must not be recognized by `purgeOldBackups`.

## Interface Design

### Group Commit

| Interface/property | Proposal | Notes |
| --- | --- | --- |
| `Options.enableGroupCommit(boolean)` | Default `false` | Rollout switch; preserves the current single-writer path |
| `Options.groupCommitMaxBatchBytes(long)` | Default to be calibrated | Prevents oversized WAL records and long tail latency |
| `Options.groupCommitMaxDelayNanos(long)` | Default to be calibrated | Bounds the collection window |
| `ldb.groupCommitStats` | New property | Exposes group count, average requests per group, max wait time, sync group count, and fallback count |

### Incremental Backup

| Interface/property | Proposal | Notes |
| --- | --- | --- |
| `LDBFactory.createIncrementalBackup(File sourceDir, File backupRoot, Options options)` | New optional API | Does not change the existing full-backup entry point |
| `LDBFactory.restoreBackup(File backupDir, File targetDir, Options options)` | Reuse | Restore should recognize full and incremental backup metadata |
| `LDBFactory.checkBackup(File backupDir, Options options)` | Evaluation item | Validates a backup set before restore |
| `BACKUP-MANIFEST.json` | New backup-root metadata | Records version, file references, checksums, parent backup, and publish state |

### Full Range Delete

| Interface/property | Proposal | Notes |
| --- | --- | --- |
| `LdbWriteBatch.deleteRange` | Keep current entry point | Strengthen semantics and tests instead of adding duplicate APIs |
| `ldb.rangeDeleteStats` | New property | Exposes MemTable/SST tombstone counts, read-path filtering, and compaction merges |
| `ldb.api.optionValues` | Extend fields | Exposes format-version and range-tombstone compatibility policy |

## Data Structures

### Group Commit In-Memory State

| Field | Meaning |
| --- | --- |
| `WriterRequest` | A caller batch, write options, completion state, and exception |
| `CommitGroup` | Requests committed by the same WAL append/sync cycle |
| `firstSequence`/`lastSequence` | Global sequence range assigned to the group |
| `requiresSync` | If any request requires sync, the whole group must sync |

### Incremental Backup Metadata

| Field | Meaning |
| --- | --- |
| `formatVersion` | Backup metadata version |
| `backupId` | Monotonic backup id |
| `parentBackupId` | Incremental baseline; empty for full backups |
| `files[]` | File name, type, length, checksum, reference count, and source backup |
| `sourceCheck` | Source database check summary |
| `published` | Set to `true` only after successful publish |

### Range Delete Metadata

| Field | Meaning |
| --- | --- |
| `beginUserKey`/`endUserKey` | Left-closed, right-open delete range |
| `sequence` | Tombstone visibility sequence |
| `cfId` | Column-family id |
| `formatVersion` | SST/WAL persistence version |

## State Machines

### Group Commit

`IDLE -> COLLECTING -> WRITING_WAL -> SYNCING -> APPLYING_MEMTABLE -> COMPLETING -> IDLE`

- `COLLECTING` waits only while owning the writer queue.
- If `WRITING_WAL` fails, the whole group fails and MemTable is not applied.
- If `SYNCING` fails, the whole group fails; later recovery depends on what actually reached WAL.
- Callers are completed only after `APPLYING_MEMTABLE`.

### Incremental Backup

`PLANNING -> COPYING_SHARED_FILES -> WRITING_METADATA -> VERIFYING -> PUBLISHING -> PUBLISHED`

Failures enter `FAILED_TEMP`: the temporary directory may remain for diagnostics, but it is not published as a `backup-000001` style directory.

### Range Delete

`BATCHED -> WAL_DURABLE -> MEMTABLE_VISIBLE -> SST_DURABLE -> COMPACTION_MERGED -> OBSOLETE`

Each transition must keep snapshot-sequence visibility explainable. A new tombstone must not hide data that an older snapshot should still see.

## Sequence Flows

### Group Commit Write

1. The caller builds a batch and enters the writer queue.
2. The queue leader collects following requests until `groupCommitMaxDelayNanos` or the size threshold is reached.
3. The group receives a continuous sequence range.
4. WAL is written in request order; if any request needs sync, the whole cycle syncs.
5. MemTable is applied in the same order.
6. Each request is completed separately, and stats are recorded.

### Incremental Backup

1. Run `check` on the source database; fail without publishing if it fails.
2. Read the previous published `BACKUP-MANIFEST.json`.
3. Reuse files whose metadata and checksum still match; copy new files into a temporary directory.
4. Write this backup manifest and report.
5. Verify the full backup view.
6. Atomically publish the backup directory and update the backup-root index.

### Range Delete

1. `deleteRange` writes WAL with column family, begin/end, and sequence.
2. MemTable stores a tombstone index; point gets and iterators apply it by snapshot sequence.
3. Flush writes an SST range-tombstone block.
4. Compaction merges tombstones and covered keys while preserving boundaries still visible to snapshots.
5. check/repair/backup recognizes and reports range tombstone file statistics.

## Error Handling

| Scenario | Behavior |
| --- | --- |
| Group commit WAL write failure | Whole group fails; MemTable is not applied; callers receive the same cause |
| Group commit sync failure | Whole group fails; `syncFailures` is recorded; reopen recovers from actual WAL contents |
| Incremental backup source check failure | No version is published; report has `ok=false` |
| Shared backup file checksum failure | Do not reuse it; optionally copy again; fail the backup if still invalid |
| Restore missing parent backup | Fail with a missing-chain report and do not create a target DB |
| Unknown range tombstone format | New versions follow the compatibility policy; old versions must fail or reject read-only open instead of silently ignoring it |

## Idempotency

- Group commit does not internally retry WAL append for callers; callers retry at the business layer if needed.
- Incremental backup uses `backupId` and temporary directory names for isolation. Re-running creates a new backup id and never overwrites a published version.
- Restore target directories must be missing or empty; failures must not leave an openable partial DB.
- Range delete uses sequence numbers for visibility. Replaying the same WAL record must not allocate a new sequence.

## Rollback Strategy

- Group commit is disabled by default. If metrics regress, revert to the single-writer path without data migration.
- Incremental backup must keep the full-backup entry point. If shared-file metadata is suspect, stop creating incremental versions and restore from existing full backups.
- Range delete format changes require an independent `formatVersion`. If rollback is needed after rollout, stop writing new tombstones first and keep new repair/check tools for existing files.

## Compatibility

| Capability | Old data | New data | Old-version behavior |
| --- | --- | --- | --- |
| Group commit | Compatible | WAL records keep the current format | Readable |
| Incremental backup | Full backups remain restorable | Adds backup metadata but does not change DB data files | Old backup tools do not recognize incremental metadata |
| Range delete | Old DBs remain readable | May introduce new SST/WAL range tombstone formats | Must fail clearly or reject open |

## Rollout and Migration

1. Ship observability and stress tests before enabling behavior.
2. Gray-release group commit with a small wait window, watching p99 write latency, sync count, and throughput.
3. For incremental backup, first add full backup plus manifest verification, then shared SST, then reference-count cleanup.
4. For range delete, add format compatibility tests and repair/check reports before enabling new-format writes.

## Test Plan

| Area | Required tests |
| --- | --- |
| Group commit | Concurrent write ordering, sync/non-sync mixes, WAL write failure, MemTable apply failure, sequence continuity after reopen, stats properties |
| Incremental backup | First full backup, consecutive incremental backups, missing parent, damaged shared file, old-version purge, restore then reopen, checkBackup |
| Range delete | Cross-MemTable/SST/level deletion, old snapshot views, crash recovery, repair/check/backup, old-format compatibility, compaction merge |

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Group commit increases tail latency | Worse write p99 | Default off, bounded wait window, stats |
| sync semantics are merged incorrectly | Lower durability | Any sync request forces group sync; test coverage |
| Incremental backup reference counts are wrong | Restore misses files or cleanup deletes live files | Manifest validation, cleanup dry-run report, fail without publish |
| Old versions silently ignore range tombstones | Deleted data becomes visible | Format version and startup validation must fail clearly |
| Compaction drops tombstones too early | Snapshot or recovery reads wrong data | Snapshot matrix and long-lived tests are merge gates |

## Phased Plan

| Phase | Deliverable | Acceptance |
| --- | --- | --- |
| 1 | Group commit switch and stats, disabled by default | Single-writer behavior unchanged; full tests pass |
| 2 | Minimal group commit implementation | Concurrent write, sync, reopen, and stats tests pass |
| 3 | Incremental backup manifest and checkBackup | Full backup remains compatible; manifest validation is explainable |
| 4 | Shared SST/WAL incremental backups and reference cleanup | Consecutive incremental, cleanup, restore, and corruption tests pass |
| 5 | Range delete format compatibility review and test matrix | New/old format boundaries, repair/check/backup docs and tests are ready |
| 6 | Full range delete compaction and recovery hardening | Snapshot, compaction, crash, and repair matrices pass |

