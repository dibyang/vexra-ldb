# LDB Non-Empty Column-Family Drop/Rename Design

English | [中文](ldb-column-family-tombstone-design.md)

## Background

LDB now supports runtime `list/create/drop` column-family lifecycle and persists runtime metadata plus tombstone history through `COLUMN-FAMILIES` so reopen, backup, checkpoint, check, and repair can recover runtime column families. Non-empty drop and rename have a minimal implementation: drop is logical deletion, and rename keeps cfId stable. The remaining work is physical GC, migration policy, and larger-scale operational proof.

## Goals

- Freeze the implemented non-empty drop tombstone semantics: logical deletion, rejected new access, explainable historical cfId, and no cfId reuse.
- Freeze the rename identity model: cfId stays fixed while name changes.
- Define consistency rules across registry, WAL/SST, check, repair, backup, and checkpoint.
- Provide gates for future dropped-CF physical GC, migration policy, and old-version compatibility evidence.

## Non-Goals

- No column-family WAL in this phase.
- No reuse of dropped cfId unless a future epoch/generation model is introduced.
- No guarantee that older versions can read databases containing lifecycle tombstones; older versions should fail clearly or downgrade to read-only if designed later.

## Current Flow

| Capability | Current state |
| --- | --- |
| Runtime create | Persists id/name through `COLUMN-FAMILIES` |
| Runtime drop | Supports logical drop for non-default families; active views remove the family and dropped cfIds cannot be reused |
| Rename | Supported; cfId stays stable |
| MANIFEST/Registry | File edits continue using cfId, while the registry keeps active/dropped history for lifecycle interpretation |
| Check/Repair | Reads registry and dropped history, then validates whether WAL/MANIFEST cfIds are explainable |

## Core Constraints

- Non-empty drop must be logical deletion, not direct SST deletion.
- cfId is the persistent identity; rename changes only the name.
- Tombstones must be recorded in MANIFEST or an equivalent strongly consistent metadata log, otherwise crash recovery cannot prove the drop was committed.
- Backup/checkpoint must preserve lifecycle metadata so restore never resurrects a dropped family.
- Repair must understand historical SST/WAL belonging to dropped families and report active versus dropped state.

## Interface Design

| API | Current behavior | Target behavior |
| --- | --- | --- |
| `dropColumnFamily(cf)` | Writes a drop tombstone, blocks new writes, and makes historical data GC-eligible | Add fuller physical GC and long-chain operational proof |
| `renameColumnFamily(cf, newName)` | Keeps cfId stable and updates the registry name | Add migration policy and bulk-CF operational reports |
| `listColumnFamilies()` | Active families | Defaults to active families; diagnostics may include dropped families |
| `getProperty("ldb.columnFamilies")` | Active families | Adds dropped/renamed diagnostic properties |

## Data Structure

The current minimal implementation stores active/dropped history in the column-family registry view and uses cfId to keep WAL/SST history explainable. If lifecycle events later move into MANIFEST edits, use a structure like:

| Field | Meaning |
| --- | --- |
| `cfId` | Persistent column-family id |
| `operation` | `CREATE` / `DROP` / `RENAME` |
| `name` | Target name for create/rename |
| `sequence` | Global sequence for the lifecycle event |
| `timestampMillis` | Diagnostic only, not used for consistency |

`COLUMN-FAMILIES` may continue as the active registry and keep dropped history:

```text
active:<cfId>\t<name>
dropped:<cfId>\t<name>\t<dropSequence>
```

For stronger future compatibility, a separate `COLUMN-FAMILIES-HISTORY` file is also acceptable, but compatibility tests and old-version fail-fast evidence must come first.

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
2. Block new writes for the target family and ensure later APIs no longer return it as active.
3. Move the family from active registry to dropped history.
4. Historical WAL/SST remains explainable through cfId; new writes and `getColumnFamily(cfId)` fail by default.
5. Background compaction/obsolete cleanup removes SSTs in a later physical-GC phase.

### Rename

1. Verify cfId is active and newName is non-empty and not used by another active family.
2. Write a MANIFEST rename edit.
3. Update the active registry name.
4. WAL/SST continue using cfId, so historical data is unaffected by name changes.

## Failure Handling

- Tombstone write fails: family remains active and registry is unchanged.
- Registry update fails: check/repair must report lifecycle metadata inconsistency clearly. If MANIFEST lifecycle edits are introduced later, they can become the authority for registry rebuilds.
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
- Drop crash cases around registry publishing, reopen, repair, backup, and checkpoint.
- Rename survives reopen, backup/restore, and repair.
- Compatibility gate: old versions fail clearly on lifecycle edits.

## Risks

| Risk | Mitigation |
| --- | --- |
| Drop deletes SSTs still visible to snapshots | GC must depend on snapshot/version references |
| Registry and MANIFEST diverge | check/repair reports lifecycle metadata inconsistency; if MANIFEST lifecycle edits are added later, MANIFEST becomes the authoritative log |
| Older versions silently misread | Keep old-version upgrade fixtures and release-gate fail-fast evidence |

## Phased Plan

| Phase | Scope | Acceptance |
| --- | --- | --- |
| 1 | Define lifecycle registry history and compatibility boundaries | Done |
| 2 | Implement rename with stable cfId | Done: rename/reopen/backup/repair tests pass |
| 3 | Implement logical non-empty drop without physical GC | Done: dropped families reject new access and history remains explainable |
| 4 | Dropped-family file GC and migration policy | Future: snapshot/compaction/obsolete long-chain tests pass |
| 5 | Maintain old-version compatibility and release-gate evidence | Ongoing: new-format old-version fail-fast behavior is documented |
