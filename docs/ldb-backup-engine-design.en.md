# LDB Backup Engine Reference-Counting Design

English | [中文](ldb-backup-engine-design.md)

## Background

LDB currently supports checkpoint, full backup/restore, complete-directory incremental backup, `BACKUP-MANIFEST.json`, `checkBackup`, old-backup cleanup, a shared object store, `OBJECT-REFS.json`, and cleanup dry-run planning. This document records the production constraints for object storage and reference counts. Compared with RocksDB BackupEngine, the remaining gaps are long backup-chain soak, cross-filesystem performance baselines, low-disk fault matrices, and long-term repository maintenance tooling.

## Goals

- Freeze the shared object store design so every backup directory does not need full file copies.
- Freeze reference counts and backup-chain GC so unreferenced objects can be safely removed.
- Keep existing `createBackup`, `restoreBackup`, and complete-directory backups compatible.
- Keep dry-run planning and explainable reports for `purgeOldBackups`, and make long-chain soak a later acceptance gate.

## Non-Goals

- No compatibility with RocksDB BackupEngine directory format.
- No remote object storage; the object store remains local to the backup root.
- No change to database WAL/SST/MANIFEST formats.

## Current Flow

| Capability | Current state |
| --- | --- |
| Full backup | Copies a complete DB file set into `backup-000001` |
| Incremental backup | Publishes a complete restorable directory and reuses SST/WAL/meta objects from the shared object store |
| Verification | `checkBackup` performs offline check on the backup directory and archives the backup manifest, object refs file, and checked object files in `CheckReport.checkedFiles` |
| Cleanup | `planPurgeBackups(root, keepLast)` creates a dry-run; `purgeOldBackups(root, keepLast)` executes cleanup |
| Reports | `BackupReport`, `BACKUP-MANIFEST.json`, `RESTORE-REPORT.json`, `OBJECT-REFS.json` |

## Core Constraints

- Every published backup must be restorable; unfinished temp directories must never be treated as backups.
- Cleanup must have a dry-run plan that lists objects and backups to delete or keep.
- Object reference counts must be rebuildable from manifests, not trusted only from a counter file.
- Restore must verify object integrity before creating the target DB directory.
- Hard-link failure on Windows must fall back to copy without changing backup semantics.

## Interface Design

| API/Command | Semantics |
| --- | --- |
| `createIncrementalBackup(source, root, options)` | Remains available; new mode writes object store plus backup manifest |
| `restoreBackup(backup, target, options)` | Restores from complete-directory or object-store-backed view |
| `checkBackup(backup, options)` | Verifies manifest, object references, and content checksums, with checked backup metadata and object files listed in the report |
| `planPurgeBackups(root, keepLast)` | New dry-run plan for backups and objects to remove |
| `purgeOldBackups(root, keepLast)` | Executes a plan and returns actual result |

## Data Structure

Current layout:

```text
backup-root/
  objects/
    sst/000001.sst
    wal/000123.log
    meta/MANIFEST-000456
  backups/
    backup-000001/BACKUP-MANIFEST.json
    backup-000002/BACKUP-MANIFEST.json
  refs/
    OBJECT-REFS.json
```

Core `BACKUP-MANIFEST.json` fields:

| Field | Meaning |
| --- | --- |
| `formatVersion` | Backup manifest format version |
| `backupId` | Monotonic backup id |
| `parentBackupId` | Logical parent backup, optional |
| `objects[]` | Object id, type, length, checksum, source file name |
| `databaseFiles[]` | Mapping from restored DB file name to object id |
| `sourceCheck` | Source DB check summary |
| `published` | Publish completion marker |

`OBJECT-REFS.json` is a cache and can be rebuilt from published manifests:

| Field | Meaning |
| --- | --- |
| `objectId` | Content-addressed or stable-file-number-derived object id |
| `refCount` | Number of published backups referencing this object |
| `backups[]` | Backup ids referencing the object |
| `bytes` | Object size |

## State Machine

| State | Trigger | Behavior |
| --- | --- | --- |
| `PLANNING` | Start backup | Scan source live files and previous backup manifest |
| `STAGING_OBJECTS` | Copy/link objects | Write temp object names and fsync |
| `WRITING_MANIFEST` | Objects ready | Write temp manifest |
| `VERIFYING` | Manifest complete | Verify objects and restorable view |
| `PUBLISHING` | Verification passes | Atomically publish backup manifest |
| `PUBLISHED` | Publish complete | Included in refs rebuild |
| `FAILED_TEMP` | Any failure | Temp files remain diagnostic only, not restorable |

## Sequence

### Incremental Backup

1. Run `check` on the source database; fail without creating a backup if unhealthy.
2. Freeze the source file set and compute object id/checksum.
3. Reuse existing objects; copy or hard-link missing objects to temp object paths.
4. Write `BACKUP-MANIFEST.json.tmp` with database-file-to-object mapping.
5. Build a temporary restorable view and run `checkBackup`.
6. Publish the manifest and refresh refs cache.

### Cleanup

1. Enumerate published backup manifests.
2. Select backups to remove based on `keepLast`.
3. Rebuild object references from remaining manifests.
4. Dry-run reports backups to delete, objects to delete, objects to keep, and bytes to reclaim.
5. Execution deletes backup manifests first, then objects with refCount 0.

## Failure Handling

- Corrupt manifest: backup is not restorable and `checkBackup` fails.
- Missing object: restore/checkBackup fails with missing object id.
- Corrupt refs cache: rebuild from published manifests instead of trusting the cache.
- Object delete failure: report failure and keep refs so cleanup can retry.

## Idempotency

- Repeated backups of the same source create new backup ids but may reuse objects.
- `planPurgeBackups` has no side effects.
- `purgeOldBackups` can be retried; already-deleted objects are reported as skipped, not fatal.

## Rollback

- Keep support for current complete-directory backup format.
- Use `formatVersion` to distinguish object-store-backed backups.
- If object-store mode has issues, disable it and fall back to complete-directory backup.

## Compatibility

- Old backup directories are restored/checked through current logic.
- New backup directories require new tooling that understands object mapping.
- Old tools must fail clearly on new manifests instead of treating the object-store directory as a full DB.

## Test Plan

- First object-store backup, consecutive incrementals, object reuse.
- restore/checkBackup for missing object, corrupt object, corrupt manifest.
- Dry-run cleanup matches actual purge.
- refs cache can be rebuilt after deletion.
- Hard-link failure on Windows falls back to copy.

## Risks

| Risk | Mitigation |
| --- | --- |
| Wrong ref count deletes live objects | Rebuild refs from manifests and require dry-run before delete |
| Restore depends on missing object | Force checkBackup before restore |
| Object id collision | Use checksum + length + type; move to content addressing if needed |

## Phased Plan

| Phase | Scope | Acceptance |
| --- | --- | --- |
| 1 | Add object-store format and manifest design tests | Done: new/old formats are clearly distinguished |
| 2 | Implement object-reuse backup without cleanup | Done: consecutive incremental restore/check pass |
| 3 | Implement refs rebuild and purge dry-run | Done: dry-run report is explainable |
| 4 | Implement safe purge | Done: all remaining backups restore after deletion |
| 5 | Soak long backup chains | Future: long-chain check/restore/purge reports stay stable |
| 6 | Cross-filesystem, low-disk, and permission fault matrix | Future: failures do not pollute the object store and reports identify the cause |
