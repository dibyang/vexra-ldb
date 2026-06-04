# LDB Future Performance and Reliability Evaluation

English | [中文](ldb-future-optimization-design.md)

## Background

LDB now has benchmark/soak entry points, compaction pressure regression tests, a narrower range tombstone read path, configurable write-stall slowdown delay, and a recovery-loop test covering checkpoint, backup/restore, and repair. The next high-value areas are write aggregation, incremental backup, and full range-delete semantics. Each area can affect WAL, SST, MANIFEST, recovery, compaction, or operational reports, so the boundaries must be fixed before implementation.

## Goals

- Define the implementation landing points for group commit, incremental backup, and range delete.
- Separate changes that can ship without a disk-format change from changes that require explicit format review.
- Protect existing callers: default behavior remains compatible, and new behavior must be observable, disableable, and recoverable.

## Non-Goals

- This document records the design boundaries for group commit, incremental backup, and range-delete follow-up work. The minimal group commit, minimal incremental backup, long compaction soak entry point, and benchmark report output have landed in `0.3.0-SNAPSHOT`.
- It does not introduce RocksDB JNI, RocksJava, or external storage services.
- It does not change the current compatibility behavior of `LDBFactory.createBackup/restoreBackup`, `LDB#checkpoint`, or `LdbWriteBatch.deleteRange`.

## Current Flow

| Area | Current capability | Main gap |
| --- | --- | --- |
| Write path | A single writer appends WAL and then applies MemTable; write-stall counters, configurable slowdown delay, and a disabled-by-default minimal group commit now exist | Group commit still writes one WAL record per request; it does not yet encode multiple requests into one WAL record or provide a long-run throughput/tail-latency baseline |
| Backup | Offline full backup/restore, temporary-directory publish, `BACKUP-REPORT.json`, `RESTORE-REPORT.json`, old-version purge, `BACKUP-MANIFEST.json`, and independently restorable incremental backup directories | Incremental backup currently reuses only same-name same-length SST files, falling back to copy when hard links fail; there is no shared object store, reference count, or incremental-chain cleanup yet |
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
| `Options.groupCommitEnabled(boolean)` | Default `false` | Rollout switch; preserves the current single-writer path |
| `Options.groupCommitMaxBatchBytes(long)` | Default 1 MiB | Bounds each collection group |
| `Options.groupCommitMaxDelayNanos(long)` | Default 200 microseconds | Bounds the collection window |
| `ldb.groupCommitStats` | Added property | Exposes enabled, maxDelayNanos, maxBatchBytes, groups, requests, syncGroups, and waitNanos |

### Incremental Backup

| Interface/property | Proposal | Notes |
| --- | --- | --- |
| `LDBFactory.createIncrementalBackup(File sourceDir, File backupRoot, Options options)` | Added optional API | Does not change the existing full-backup entry point; currently publishes a complete restorable directory |
| `LDBFactory.restoreBackup(File backupDir, File targetDir, Options options)` | Reuse | Restores either a full backup or an incremental published directory |
| `LDBFactory.checkBackup(File backupDir, Options options)` | Added validation entry point | Validates a backup directory before restore |
| `BACKUP-MANIFEST.json` | Added backup metadata | Records version, backup id, parent backup, copied files, reused files, and publish state |

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
3. Hard-link reusable same-name same-length SST files; copy files when hard links fail or the file is not an SST file.
4. Write this backup manifest and report.
5. Verify the full backup view with `checkBackup`/offline check.
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
| Shared backup file reuse failure | Do not reuse it and fall back to copy; fail the backup if copy still fails |
| Restore missing parent backup | The current published directory is a complete view and can restore independently; a future shared-object-store mode must fail with a missing-chain report |
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
| Incremental backup | Full backups remain restorable | Adds backup metadata without changing DB data files; published directories remain complete restorable views | Old backup tools can restore the directory as ordinary data, but they do not understand reuse metadata |
| Range delete | Old DBs remain readable | May introduce new SST/WAL range tombstone formats | Must fail clearly or reject open |

## Rollout and Migration

1. Ship observability and stress tests before enabling behavior.
2. Gray-release group commit with a small wait window, watching p99 write latency, sync count, and throughput.
3. Incremental backup now supports complete directories plus manifest plus SST hard-link reuse; shared object storage and reference-count cleanup remain future work.
4. For range delete, add format compatibility tests and repair/check reports before enabling new-format writes.

## Test Plan

| Area | Required tests |
| --- | --- |
| Group commit | Covered now: concurrent writes, sync/non-sync mixes, reopen recovery, and stats properties; later: WAL failure injection, MemTable apply failure, and longer tail-latency reporting |
| Incremental backup | Covered now: first incremental backup, consecutive incremental backups, SST reuse, restore then reopen, and checkBackup; later: missing parent, damaged shared file, and reference cleanup |
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
| 1 | Group commit switch and stats, disabled by default | Done: single-writer behavior is unchanged and the new property is observable |
| 2 | Minimal group commit implementation | Done: concurrent write, sync, reopen, and stats tests pass |
| 3 | Incremental backup manifest and checkBackup | Done: full backup remains compatible and manifest validation is explainable |
| 4 | Minimal shared-SST incremental backup | Done: consecutive incremental backups, SST reuse, restore, and checkBackup pass; reference-count cleanup remains future work |
| 5 | Range delete format compatibility review and test matrix | New/old format boundaries, repair/check/backup docs and tests are ready |
| 6 | Full range delete compaction and recovery hardening | Snapshot, compaction, crash, and repair matrices pass |

