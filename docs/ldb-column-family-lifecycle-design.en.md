# LDB Column Family Lifecycle Design

English | [中文](ldb-column-family-lifecycle-design.md)

## Background

LDB already supports column families declared before open through `Options.addColumnFamily`, plus column-family reads, writes, snapshot cursors, diagnostic properties, and `compactRange(cf, begin, end)`. Runtime lifecycle management has moved beyond the initial list/create/drop-empty baseline and now includes non-empty drop tombstones plus rename. This document keeps the minimal lifecycle design baseline; the detailed non-empty drop/rename rules live in `docs/ldb-column-family-tombstone-design.md`.

## Goals

- Provide minimal runtime `list/create/drop` support and explain how it relates to the later tombstone design.
- Keep existing WAL, SST, and MANIFEST formats compatible.
- Preserve runtime column families across reopen, backup, restore, checkpoint, check, and WAL-only repair.
- Treat column-family id/name as persistent identity metadata; dropped ids must not be reused.

## Non-Goals

- This document does not expand the complete non-empty drop/rename tombstone protocol; that is maintained in `ldb-column-family-tombstone-design.*`.
- No column-family WAL.
- No full RocksDB column-family ecosystem compatibility such as per-CF option hot updates or complex migration policy.

## Current Flow

| Flow | Current behavior |
| --- | --- |
| Open | `Options.getColumnFamilies()` includes default and `LDbImpl` creates `ColumnFamilyState` |
| Write | WAL records include column-family id and recovery resolves that id |
| SST/MANIFEST | `VersionEdit` and `FileMetaData` store cf id, but not cf name |
| Tools | check, repair, backup, and checkpoint operate on database file sets |

## Core Constraints

- JDK8 compatibility.
- `COLUMN-FAMILIES` is supplemental metadata and does not change existing disk formats.
- Runtime create updates memory state and then persists the registry; persistence failure rolls back memory state.
- Runtime drop now uses tombstones to logically delete non-default families; historical data remains explainable for recovery, repair, backup, snapshots, and later GC.
- `backup`, `restore`, and `checkpoint` must carry `COLUMN-FAMILIES`.
- `check` and `repair` must load the registry before interpreting runtime-CF WAL records.

## Interface Design

| API | Semantics |
| --- | --- |
| `LDB#listColumnFamilies()` | Returns an immutable snapshot sorted by id |
| `LDB#createColumnFamily(int cfId, String name)` | Creates a column family in a writable instance and persists the registry; id/name conflicts fail |
| `LDB#renameColumnFamily(LdbColumnFamily cf, String newName)` | Renames an active family while keeping cfId stable; newName must not conflict with another active family |
| `LDB#dropColumnFamily(LdbColumnFamily cf)` | Logically drops a non-default family; active listings remove it and the historical cfId is not reused |
| `LDB#getColumnFamily(int cfId)` | Returns a registered column family; unknown ids fail |

## Data Structure

New root-level file `COLUMN-FAMILIES`:

```text
<cfId>\t<escapedName>\n
```

- One column family per line.
- Blank lines and comment lines starting with `#` are ignored.
- Names escape `\`, tab, newline, and carriage return.
- Open first loads static `Options` families and then merges the registry; conflicting id/name pairs are treated as corruption.

## State Machine

| State | Trigger | Can transition to |
| --- | --- | --- |
| `ABSENT` | Not registered | `ACTIVE` |
| `ACTIVE_EMPTY` | Registered with no MemTable/SST data | `DROPPED` |
| `ACTIVE_NON_EMPTY` | Registered with MemTable, immutable MemTable, or SST data | `RENAMED` or `DROPPED` |
| `RENAMED` | Name changed while cfId stays stable | `ACTIVE_*` |
| `DROPPED` | Tombstone committed | Same cfId cannot be reused |

## Sequence

### Create

1. Verify the database is writable and background state is healthy.
2. Validate id/name and reject conflicts.
3. Create `ColumnFamilyState` and add it to `cfs` while holding the mutex.
4. Write `COLUMN-FAMILIES.tmp`, fsync it, and publish `COLUMN-FAMILIES`.
5. On registry write failure, remove the new state and throw `DBException`.

### Drop

1. Verify the database is writable and the target is not default.
2. While holding the mutex, write column-family tombstone metadata and block new writes.
3. Remove it from the active `cfs` view and rewrite the registry, keeping history for recovery, repair, backup, and snapshots.
4. If registry persistence fails, follow the tombstone design's rollback or check/repair interpretation rules.

### Rename

1. Verify the database is writable, the target is active, and newName is non-empty and not conflicting.
2. Keep cfId stable and update only the name while holding the mutex.
3. Rewrite the registry; keep the old name if persistence fails.

## Failure Handling

- Invalid registry format: `check` reports `COLUMN-FAMILIES`; `verifyOnOpen(true)` rejects the database.
- Missing registry with runtime-CF WAL or MANIFEST references: `check` reports unknown column-family id.
- Corrupt source registry: backup does not publish a backup directory.
- Corrupt backup registry: restore fails before creating the target directory.
- Runtime-CF WAL during repair: repair uses the registry; without it, the WAL is treated as unknown-CF and quarantined.

## Idempotency

- `listColumnFamilies` has no side effects.
- create fails on existing id/name instead of silently reusing it.
- drop fails on unknown or already-dropped families so callers do not mistake it for success; dropped cfIds cannot be reused.

## Rollback

`COLUMN-FAMILIES` does not change WAL/SST/MANIFEST formats. If rolling back to an older version, static `Options.addColumnFamily` can still open known families; older versions will not automatically read runtime metadata, so callers must register runtime families explicitly.

## Compatibility

- Old databases without `COLUMN-FAMILIES` still open through `Options`.
- The first writable open writes the registry so backups and checkpoints carry complete column-family definitions.
- Non-empty drop and rename are now governed by the tombstone design. Older versions that cannot understand the related metadata should fail fast or follow downgrade limits documented in release notes.

## Test Plan

- Runtime CF create/list/reopen/read.
- Empty and non-empty drop invisibility after reopen, with cfId reuse rejected.
- Rename keeps cfId stable and survives reopen, backup, restore, checkpoint, and repair.
- Default drop rejection.
- Backup, restore, and checkpoint carry runtime CF metadata.
- Corruption matrix for registry damage, missing registry causing WAL/MANIFEST resolution failure, bad CURRENT, bad backup registry, and runtime-CF WAL-only repair.

## Risks

| Risk | Mitigation |
| --- | --- |
| Registry is not atomic with WAL/MANIFEST | create/drop/rename failures are exposed by check/verifyOnOpen/repair; non-empty drop preserves historical explanation through tombstones |
| Historical files remain after non-empty drop | Prioritize logical deletion and recovery correctness first; physical GC remains a later production-hardening item |
| SST files cannot reveal cf name by themselves | Runtime-CF repair depends on registry or caller Options; limitation is documented |

## Phased Plan

| Phase | Scope | Status |
| --- | --- | --- |
| 1 | Add `COLUMN-FAMILIES` registry and runtime list/create/drop-empty APIs | Done |
| 2 | Teach backup/checkpoint/check/repair to recognize the registry | Done |
| 3 | Add corruption matrix coverage for registry, WAL, CURRENT, and backup/restore | Done |
| 4 | Design and implement the minimal non-empty drop tombstone plus rename loop | Done |
| 5 | Dropped-CF physical GC, migration policy, and large-scale multi-CF operations reports | Future production hardening |
