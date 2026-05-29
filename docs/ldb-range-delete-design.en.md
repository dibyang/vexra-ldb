# LDB Range Delete Design

## Background

`LdbWriteBatch` already exposes the `deleteRange` API, and `ValueType` has reserved `DELETE_RANGE(0x02)`, but the current write path rejects this operation in `LdbWriteBatchImpl.validateForWrite()`. Phase 9 moves this API from "rejected before write" to a recoverable, persistent, and compactable range tombstone feature.

Range delete changes WAL/SST visibility semantics and affects snapshots, iterators, and compaction. It must be designed independently before code implementation starts.

## Goals

- Support `LdbWriteBatch.deleteRange(cf, beginKey, endKey)` to delete visible point keys in the half-open range `[beginKey, endKey)`.
- Recover range tombstones through WAL replay after process crashes.
- Make MemTable, SST, snapshot cursors, and point reads apply sequence-aware masking correctly.
- Let compaction merge tombstones with point keys and drop covered data only when safe.
- Define disk-format compatibility, downgrade limits, and rollback rules.

## Non-Goals

- Do not introduce per-column-family WAL; Phase 9 keeps the globally shared WAL.
- Do not implement runtime create/drop column family.
- Do not introduce PrefixExtractor, MergeOperator, or the full RocksDB tombstone fragmentation model.
- Do not change existing `PUT`, `DELETE`, or `ADD_LONG` encoding semantics.

## Current Flow

- `LdbWriteBatchImpl` can collect `DELETE_RANGE` operations, but explicitly throws `DBException("deleteRange is not supported yet")` before write.
- `LdbWriteBatchLog` only decodes `VALUE`, `DELETION`, and `ADD_LONG`.
- MemTable currently stores point entries as `InternalKey -> Slice`, and the read path only handles point values and point deletions.
- The SST table format can store arbitrary `InternalKey` and value pairs, but current readers and iterators do not understand `DELETE_RANGE` masking.
- `DbSnapshotCursor` and `SnapshotSeekingIterator` only treat `DELETION` as a tombstone.

## Core Constraints

- Keep JDK8 compatibility.
- Keep global sequence numbers and the globally shared WAL to preserve cross-CF batch atomic ordering.
- The new version must read old databases that do not contain range tombstones.
- Once the new version writes `DELETE_RANGE` into WAL/SST, old versions are not guaranteed to read the database safely. Downgrade requires backup or logical export into clean data.
- Range tombstone visibility must be constrained by snapshot sequence numbers.

## Interface Design

| API | Behavior |
| --- | --- |
| `LdbWriteBatch.deleteRange(cf, beginKey, endKey)` | Writes a CF-scoped range tombstone over `[beginKey, endKey)` |
| `LdbWriteBatch.deleteRange(beginKey, endKey)` | Equivalent to range delete on the default column family |
| `LDB.write(batch, options)` | Allocates sequences, writes WAL, and applies MemTable updates for `DELETE_RANGE` |
| `LDB.getSnapshot()` | Snapshot sequence constrains tombstone visibility |
| `LDB.getProperty("ldb.rangeDelete.enabled")` | Optional diagnostic property reporting whether range delete is enabled |

Validation rules:

- `cf`, `beginKey`, and `endKey` must not be `null`.
- `beginKey` must be smaller than `endKey`; empty or reversed ranges throw `DBException`.
- Unknown column families keep the existing validation failure behavior.

## Data Structures

### WAL Encoding

Reuse the existing write-batch record frame and add an entry for `ValueType.DELETE_RANGE(0x02)`:

| Field | Type | Meaning |
| --- | --- | --- |
| `valueType` | byte | Persistent id for `DELETE_RANGE` |
| `cfId` | int | Column-family id |
| `beginKey` | length-prefixed bytes | Inclusive range start |
| `endKey` | length-prefixed bytes | Exclusive range end |

### In-Memory Model

Add `RangeTombstone`:

| Field | Meaning |
| --- | --- |
| `cf` | Owning column family |
| `beginKey` | Inclusive range start |
| `endKey` | Exclusive range end |
| `sequence` | Tombstone write sequence |

MemTable keeps its existing point-entry structure and maintains a range tombstone collection per column family. The first version may use a begin-key-sorted list and later upgrade to an interval index based on benchmark results.

### SST Persistence

Store range tombstones as normal table entries:

- key: `InternalKey(beginKey, sequence, DELETE_RANGE)`
- value: `endKey`

This reuses block, index, filter, and checksum encoding. The read path must recognize `DELETE_RANGE` and include it in tombstone masking.

## State Machine

`deleteRange` follows the existing write state machine:

`VALIDATING -> BEFORE_WRITE -> WAL_APPENDED -> MEMTABLE_APPLIED -> AFTER_WRITE -> COMMITTED`

Invalid ranges, unknown column families, WAL append failures, and MemTable application failures keep the existing write-failure semantics. If the WAL append succeeds and the process crashes, recovery must replay the tombstone.

## Sequence Flow

1. `LdbWriteBatchImpl.validateForWrite()` validates `DELETE_RANGE` bounds.
2. The writer allocates sequences, incrementing per operation in batch order.
3. The WAL writer emits `DELETE_RANGE` entries with the new encoding.
4. The MemTable handler adds tombstones to the per-CF tombstone collection.
5. Point reads locate the candidate point entry and then check visible covering tombstones whose sequence is newer than the point entry and not newer than the snapshot.
6. Iterators and scans check tombstone coverage before returning point keys.
7. Flush writes MemTable tombstones as `InternalKey(begin, seq, DELETE_RANGE) -> end` into Level-0 SSTs.
8. Compaction reads point entries and tombstones together, merges them, and cleans covered data only when snapshot safety allows it.

## Error Handling

- Null keys, empty ranges, and reversed ranges throw `DBException` with a `deleteRange`-specific reason.
- Structurally incomplete `DELETE_RANGE` entries in WAL follow the existing WAL corruption policy: a complete record that cannot be decoded is not applied.
- SST entries whose value cannot be parsed as `endKey` or whose `beginKey >= endKey` must fail open/read/verify clearly and preserve file and key context.

## Idempotency

- WAL replay reconstructs tombstones with the record sequence; replaying the same committed record again must not change final visibility.
- Compaction is a semantics-preserving rewrite. Output files become visible only after MANIFEST records them; failed outputs follow the existing pending-output and obsolete-file cleanup rules.

## Rollback Strategy

- Before implementation, or before writing any `DELETE_RANGE` data, rolling back the binary is safe.
- Once a database contains `DELETE_RANGE` WAL/SST entries, old versions must not open it directly.
- To downgrade, use the new version to checkpoint/verify or logically export data, then import it into a clean database for the old version.
- If rollout finds an issue, stop calling `deleteRange`, keep the new reader available, and evaluate downgrade only after compaction/export cleanup.

## Compatibility

- The new version must read old WAL/SST files.
- Old versions may throw on unknown types or produce incorrect reads when seeing `DELETE_RANGE`, so downgrade is explicitly unsafe.
- Later repair, verify, and backup phases must treat `DELETE_RANGE` as a first-class persistent entry.

## Rollout And Migration

- No old-data migration is required.
- Enable the first rollout only in unit and fault-injection tests.
- Before ADB integration, complete crash recovery, snapshot, and compaction regression coverage.
- Keep an audit of `deleteRange` call sites during rollout so callers do not assume old-version rollback remains safe.

## Test Plan

- `LdbRangeDeleteTest`: basic put/deleteRange/get, `[begin,end)` boundaries, empty-range failure, and reversed-range failure.
- `LdbRangeDeleteRecoveryTest`: WAL replay, reopen after flush, and mixed SST+WAL recovery.
- `LdbRangeDeleteSnapshotTest`: snapshots before/after tombstone and visibility of puts after range delete.
- `LdbRangeDeleteIteratorTest`: scan masking, cross-SST/level masking, and column-family isolation.
- `LdbRangeDeleteCompactionTest`: compaction cleanup for covered point keys, tombstone retention, and long-snapshot safety.
- `LdbFaultInjectionTest` extension: WAL tail truncation and checksum errors for records containing `DELETE_RANGE`.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Incorrect tombstone ordering | Deleted data may reappear, or new data may be hidden | Make sequence semantics central in snapshot/iterator matrix tests |
| Compaction drops tombstones too early | Older lower-level keys become visible again | Keep tombstones conservatively at first, then optimize drop rules later |
| Old versions open new-format data | Undefined read behavior | Document unsafe downgrade and teach verify/repair to recognize `DELETE_RANGE` |
| Tombstone scans are slow | Large deletes increase read amplification | Prioritize correctness first; add interval indexes and fragmentation later |

## Phased Implementation Plan

1. 9.1: Complete design review, test skeletons, and `deleteRange` argument validation while still rejecting disk-format writes. (Done)
2. 9.2: Implement WAL encoding/decoding, MemTable tombstones, point-read masking, and crash-recovery tests. (Done)
3. 9.3: Implement SST tombstone persistence and reopen-time masking across SSTs and levels. (Basic persistence, point-get masking, and cursor masking after reopen are done; compaction merge remains in 9.5)
4. 9.4: Implement iterator/scan masking and the snapshot matrix. (Done)
5. 9.5: Implement compaction merge and conservative cleanup rules, then complete fault injection and compatibility documentation. (Done; the current policy conservatively keeps range tombstones and does not aggressively clean covered point keys)
