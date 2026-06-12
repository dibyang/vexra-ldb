# LDB Non-Empty Column-Family Drop/Rename Design

English | [中文](ldb-column-family-tombstone-design.md)

## Background

LDB now supports runtime `list/create/drop-empty` column-family lifecycle and persists runtime metadata through `COLUMN-FAMILIES` so reopen, backup, checkpoint, check, and repair can recover runtime column families. The remaining hard gap is non-empty drop and rename. If a non-empty family is removed only from the registry, older WAL, SST, and MANIFEST entries may still reference that cfId, making recovery or repair unable to explain historical data or, worse, causing data to be interpreted as another family.

## Goals

- Design recoverable, verifiable, and rollback-aware non-empty column-family drop.
- Design rename around a stable persistent identity: cfId stays fixed while name changes.
- Define consistency rules across MANIFEST tombstones, registry, WAL/SST, check, repair, and backup.
- Establish implementation gates without changing disk format in this design-only step.

## Non-Goals

- No column-family WAL in this phase.
- No reuse of dropped cfId unless a future epoch/generation model is introduced.
- No guarantee that older versions can read databases containing lifecycle tombstones; older versions should fail clearly or downgrade to read-only if designed later.

## Current Flow

| Capability | Current state |
| --- | --- |
| Runtime create | Persists id/name through `COLUMN-FAMILIES` |
| Runtime drop | Allows empty families only; non-empty drop throws `DBException` |
| Rename | Unsupported |
| MANIFEST | Records file edits and cfId, but not column-family lifecycle |
| Check/Repair | Reads registry and validates whether WAL/MANIFEST cfIds are explainable |

## Core Constraints

- Non-empty drop must be logical deletion, not direct SST deletion.
- cfId is the persistent identity; rename changes only the name.
- Tombstones must be recorded in MANIFEST or an equivalent strongly consistent metadata log, otherwise crash recovery cannot prove the drop was committed.
- Backup/checkpoint must preserve lifecycle metadata so restore never resurrects a dropped family.
- Repair must understand historical SST/WAL belonging to dropped families and report active versus dropped state.

## Interface Design

| API | Current behavior | Target behavior |
| --- | --- | --- |
| `dropColumnFamily(cf)` | Non-empty families fail | Writes a drop tombstone, blocks new writes, and makes historical data GC-eligible |
| `renameColumnFamily(cf, newName)` | Not available | Writes a rename edit, keeps cfId stable, updates registry name |
| `listColumnFamilies()` | Active families | Defaults to active families; diagnostics may include dropped families |
| `getProperty("ldb.columnFamilies")` | Active families | Adds dropped/renamed diagnostic properties |

## Data Structure

Recommended new MANIFEST lifecycle edit:

| Field | Meaning |
| --- | --- |
| `cfId` | Persistent column-family id |
| `operation` | `CREATE` / `DROP` / `RENAME` |
| `name` | Target name for create/rename |
| `sequence` | Global sequence for the lifecycle event |
| `timestampMillis` | Diagnostic only, not used for consistency |

`COLUMN-FAMILIES` may continue as the active registry, but it needs history:

```text
active:<cfId>\t<name>
dropped:<cfId>\t<name>\t<dropSequence>
```

For compatibility with the current parser, a separate `COLUMN-FAMILIES-HISTORY` file is also acceptable. Implementation must choose one path and add compatibility tests before code changes.

## State Machine

| State | Trigger | Meaning |
| --- | --- | --- |
| `ACTIVE` | create/open | Readable and writable |
| `RENAMING` | rename edit in progress | Failure rolls back to old name |
| `DROPPING` | drop edit in progress | New writes are blocked until MANIFEST is durable |
| `DROPPED` | drop edit committed | New reads/writes fail; history is retained for snapshot/recovery/repair |
| `GC_ELIGIBLE` | No active snapshot and no live SST reference | Files and historical metadata can be deleted |

## Sequence

### Non-Empty Drop

1. Acquire the DB mutex and verify the target is active and not default.
2. Block new writes for the target family and flush related MemTables.
3. Write a MANIFEST drop edit with cfId and drop sequence.
4. Move the family from active registry to dropped history.
5. Background compaction/obsolete cleanup removes SSTs only after snapshot references are gone.
6. `getColumnFamily(cfId)` fails clearly for dropped families by default.

### Rename

1. Verify cfId is active and newName is non-empty and not used by another active family.
2. Write a MANIFEST rename edit.
3. Update the active registry name.
4. WAL/SST continue using cfId, so historical data is unaffected by name changes.

## Failure Handling

- MANIFEST drop write fails: family remains active and registry is unchanged.
- Registry update fails: check/repair must rebuild the registry from MANIFEST lifecycle edits.
- WAL references after drop: recovery can explain historical records, but new writes are rejected.
- Old name after rename: cfId lookup resolves to the new name; name-based lookup must return the new name only.

## Idempotency

- Repeated drop for the same cfId: if already `DROPPED`, return a recognizable already-dropped state or fail clearly; never write duplicate tombstones.
- Rename retry: same cfId/newName can be treated as idempotent success; a different newName is a new event.

## Rollback

Once lifecycle MANIFEST edits exist, older versions must not open the database silently. Before downgrade:

1. Stop writing drop/rename events.
2. Use new-version repair/check to confirm no dropped family still requires GC.
3. If downgrade is required, generate an old-format registry and document the limitation in release notes.

## Compatibility

- New versions read old databases normally when there are no lifecycle edits.
- Old versions reading new databases must fail clearly through a format marker or unknown MANIFEST tag.
- Backup/restore must copy lifecycle metadata and `CheckReport` should report active/dropped CF counts.

## Test Plan

- Non-empty drop then reopen rejects reads/writes to the target family.
- Snapshot created before drop keeps its old view; GC waits until the snapshot is closed.
- Crash matrix: MANIFEST succeeded/registry failed and registry succeeded/MANIFEST failed.
- Rename survives reopen, backup/restore, and repair.
- Compatibility gate: old versions fail clearly on lifecycle edits.

## Risks

| Risk | Mitigation |
| --- | --- |
| Drop deletes SSTs still visible to snapshots | GC must depend on snapshot/version references |
| Registry and MANIFEST diverge | MANIFEST is authoritative; check/repair rebuild registry |
| Older versions silently misread | Add format marker or unknown-tag fail-fast |

## Phased Plan

| Phase | Scope | Acceptance |
| --- | --- | --- |
| 1 | Define lifecycle MANIFEST edit and registry history format | Docs and compatibility test plan ready |
| 2 | Implement rename with stable cfId | rename/reopen/backup/repair tests pass |
| 3 | Implement logical non-empty drop without physical GC | Dropped family rejects new reads/writes and history remains explainable |
| 4 | Implement dropped-family file GC | snapshot/compaction/obsolete tests pass |
| 5 | Add old-version compatibility gate | New-format old-version fail-fast behavior is documented |
