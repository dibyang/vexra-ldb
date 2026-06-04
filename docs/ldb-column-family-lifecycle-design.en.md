# LDB Column Family Lifecycle Design

English | [中文](ldb-column-family-lifecycle-design.md)

## Background

LDB already supports column families declared before open through `Options.addColumnFamily`, plus column-family reads, writes, snapshot cursors, diagnostic properties, and `compactRange(cf, begin, end)`. Compared with RocksDB, the remaining gap is runtime lifecycle management: callers could not list, create, or drop column families after the database was open, and backup, checkpoint, check, and repair did not have a shared metadata source for runtime column families.

## Goals

- Provide minimal runtime `list/create/drop` support.
- Keep existing WAL, SST, and MANIFEST formats compatible.
- Preserve runtime column families across reopen, backup, restore, checkpoint, check, and WAL-only repair.
- Reject non-empty drop explicitly until logical column-family deletion is designed.

## Non-Goals

- No runtime column-family rename.
- No non-empty column-family drop.
- No column-family WAL, column-family tombstone, or MANIFEST-level column-family metadata.
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
- Runtime drop only accepts empty column families: empty MemTable, no immutable MemTable, and no SST files at any level.
- `backup`, `restore`, and `checkpoint` must carry `COLUMN-FAMILIES`.
- `check` and `repair` must load the registry before interpreting runtime-CF WAL records.

## Interface Design

| API | Semantics |
| --- | --- |
| `LDB#listColumnFamilies()` | Returns an immutable snapshot sorted by id |
| `LDB#createColumnFamily(int cfId, String name)` | Creates a column family in a writable instance and persists the registry; id/name conflicts fail |
| `LDB#dropColumnFamily(LdbColumnFamily cf)` | Drops an empty column family; default or non-empty families fail |
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
| `ACTIVE_NON_EMPTY` | Registered with MemTable, immutable MemTable, or SST data | Remains active; drop fails |
| `DROPPED` | Removed from registry | Can be created again with the same id/name |

## Sequence

### Create

1. Verify the database is writable and background state is healthy.
2. Validate id/name and reject conflicts.
3. Create `ColumnFamilyState` and add it to `cfs` while holding the mutex.
4. Write `COLUMN-FAMILIES.tmp`, fsync it, and publish `COLUMN-FAMILIES`.
5. On registry write failure, remove the new state and throw `DBException`.

### Drop Empty

1. Verify the database is writable and the target is not default.
2. While holding the mutex, check MemTable, immutable MemTable, and all level file counts.
3. Throw `DBException` if the family is non-empty.
4. Remove it from `cfs` and rewrite the registry.
5. Restore memory state if registry persistence fails.

## Failure Handling

- Invalid registry format: `check` reports `COLUMN-FAMILIES`; `verifyOnOpen(true)` rejects the database.
- Missing registry with runtime-CF WAL or MANIFEST references: `check` reports unknown column-family id.
- Corrupt source registry: backup does not publish a backup directory.
- Corrupt backup registry: restore fails before creating the target directory.
- Runtime-CF WAL during repair: repair uses the registry; without it, the WAL is treated as unknown-CF and quarantined.

## Idempotency

- `listColumnFamilies` has no side effects.
- create fails on existing id/name instead of silently reusing it.
- drop fails on unknown or already-dropped families so callers do not mistake it for success.

## Rollback

`COLUMN-FAMILIES` does not change WAL/SST/MANIFEST formats. If rolling back to an older version, static `Options.addColumnFamily` can still open known families; older versions will not automatically read runtime metadata, so callers must register runtime families explicitly.

## Compatibility

- Old databases without `COLUMN-FAMILIES` still open through `Options`.
- The first writable open writes the registry so backups and checkpoints carry complete column-family definitions.
- Non-empty drop remains unsupported to avoid breaking data interpretation before column-family tombstones exist.

## Test Plan

- Runtime CF create/list/reopen/read.
- Empty drop and reopen invisibility.
- Default and non-empty drop rejection.
- Backup, restore, and checkpoint carry runtime CF metadata.
- Corruption matrix for registry damage, missing registry causing WAL/MANIFEST resolution failure, bad CURRENT, bad backup registry, and runtime-CF WAL-only repair.

## Risks

| Risk | Mitigation |
| --- | --- |
| Registry is not atomic with WAL/MANIFEST | Minimal create/drop changes metadata only; non-empty drop is rejected; check/verifyOnOpen expose corruption |
| Non-empty families cannot be dropped | Explicit unsupported boundary; future MANIFEST tombstone design is required |
| SST files cannot reveal cf name by themselves | Runtime-CF repair depends on registry or caller Options; limitation is documented |

## Phased Plan

| Phase | Scope | Status |
| --- | --- | --- |
| 1 | Add `COLUMN-FAMILIES` registry and runtime list/create/drop-empty APIs | Done |
| 2 | Teach backup/checkpoint/check/repair to recognize the registry | Done |
| 3 | Add corruption matrix coverage for registry, WAL, CURRENT, and backup/restore | Done |
| 4 | Design non-empty drop, rename, and migration tombstones | Future |
