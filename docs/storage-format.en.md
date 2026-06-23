# LDB Storage File Format Reference

[中文](storage-format.md) | English

## Purpose

This document records the current persistent file formats in an LDB database directory. It is the factual baseline for the `0.8.0-SNAPSHOT` storage-format evolution work. This document describes current behavior and compatibility boundaries only; new v2 table properties, feature sets, and `storageFormatGates` are covered by `storage-format-0.8-design.en.md`.

## File Naming

| File | Name | Type | Meaning |
| --- | --- | --- | --- |
| WAL | `%06d.log` | LOG | Append-only write-batch records for MemTable recovery |
| SST/table | `%06d.sst` | TABLE | Persistent sorted key/value blocks |
| MANIFEST | `MANIFEST-%06d` | DESCRIPTOR | Log-record container whose logical records are VersionEdits |
| CURRENT | `CURRENT` | CURRENT | UTF-8 text pointing to the current MANIFEST file name |
| LOCK | `LOCK` | DB_LOCK | Process lock file |
| INFO LOG | `LOG` / `LOG.old` | INFO_LOG | Runtime log files |
| TEMP | `%06d.dbtmp` | TEMP | Temporary file |
| Column families | `COLUMN-FAMILIES` | registry | Text column-family registry |
| Backup manifest | `BACKUP-MANIFEST.json` | backup metadata | Metadata for one backup directory |
| Backup report | `BACKUP-REPORT.json` | backup report | Backup execution report |
| Restore report | `RESTORE-REPORT.json` | restore report | Restore execution report |
| Object refs | `OBJECT-REFS.json` | backup root metadata | Object-store reference counts |
| Object store | `objects/<objectId>` | backup object | Reusable object files under the backup root |

File numbers must be non-negative and numeric file names are zero-padded to 6 digits. `CURRENT` may only point to a legal same-directory `MANIFEST-[0-9]{6,}` file name and must not contain path separators.

## Byte Order And Primitive Encoding

| Item | Current Rule |
| --- | --- |
| Fixed integers | The implementation uses `SliceOutput.writeInt/writeLong`; this document does not declare an additional cross-language byte-order contract |
| Varint | `VariableLengthQuantity`, used by block handles and many VersionEdit fields |
| Length-prefixed bytes | varint length followed by raw bytes |
| Strings | File names, column-family names, JSON, and some manifest fields use UTF-8 |
| Checksums | WAL chunks use CRC32C; SST block trailers use masked CRC32C over block content plus compression type |

## WAL Physical Format

WAL files are named `%06d.log`. The physical layer is split into fixed 32KB blocks, and no chunk crosses a block boundary.

| Field | Length | Meaning |
| --- | ---: | --- |
| checksum | 4 bytes | CRC32C over chunk type and payload |
| length | 2 bytes | Payload length, low byte first then high byte |
| type | 1 byte | Chunk-type persistent id |
| payload | length bytes | Full logical record or fragment |

Chunk types:

| id | Name | Meaning |
| ---: | --- | --- |
| 0 | ZERO_TYPE | Padding/empty record, skipped by reader |
| 1 | FULL | One complete logical record |
| 2 | FIRST | First fragment of a logical record |
| 3 | MIDDLE | Middle fragment of a logical record |
| 4 | LAST | Last fragment of a logical record |

If the remaining bytes in a 32KB block are fewer than the 7-byte header, the writer pads the rest with zeros and starts a new block. The reader may jump from an initial offset to the next parseable block. Checksum, length, unknown type, and incomplete-fragment errors are reported through LogMonitor as corruption and skipped.

## WAL Logical Record Format

A WAL payload is a write-batch record:

| Field | Encoding | Meaning |
| --- | --- | --- |
| sequenceBegin | 8 bytes | Sequence number of the first operation in the batch |
| updateSize | 4 bytes | Number of operations in the batch |
| entries | repeated | Write operations |

Each entry:

| Field | Encoding | Meaning |
| --- | --- | --- |
| valueType | 1 byte | `DELETION=0`, `VALUE=1`, `DELETE_RANGE=2`, `ADD_LONG=3` |
| cfId | 4 bytes | Column-family id |
| key/beginKey | length-prefixed bytes | PUT/DELETE/ADD_LONG key, or DELETE_RANGE begin key |
| value/endKey/delta | length-prefixed bytes | PUT value, DELETE_RANGE end key, ADD_LONG 8-byte delta; absent for DELETE |

Recovery requires the decoded entry count to equal `updateSize`; otherwise the record is corrupt.

## InternalKey Format

InternalKey = user key bytes + 8-byte packed sequence/type.

| Part | Meaning |
| --- | --- |
| userKey | Raw user-key bytes |
| packed sequence/type | High 56 bits are the sequence number; low 8 bits are the ValueType persistent id |

`MAX_SEQUENCE_NUMBER = 2^56 - 1`. This format is used for SST keys, MANIFEST file-boundary keys, and compaction pointers.

## MANIFEST Format

MANIFEST files are named `MANIFEST-%06d`. The physical layer reuses the WAL log container: 32KB blocks, 7-byte chunk headers, FULL/FIRST/MIDDLE/LAST fragmentation, and CRC32C checksums.

Each logical MANIFEST record is the output of `VersionEdit.encode()`: a sequence of tag/value pairs. Each tag starts with a varint persistent id followed by its value.

| tag id | Name | Value Format |
| ---: | --- | --- |
| 1 | COMPARATOR | varint length + UTF-8 comparator name bytes |
| 2 | LOG_NUMBER | varint64 log number |
| 3 | NEXT_FILE_NUMBER | varint64 next file number |
| 4 | LAST_SEQUENCE | varint64 last sequence number |
| 5 | COMPACT_POINTER | varint cfId, varint level, length-prefixed InternalKey |
| 6 | DELETED_FILE | varint cfId, varint level, varint64 file number |
| 7 | NEW_FILE | varint cfId, varint level, varint64 file number, varint64 file size, length-prefixed smallest InternalKey, length-prefixed largest InternalKey |
| 9 | PREVIOUS_LOG_NUMBER | varint64 previous log number |

Tag id `8` is obsolete. Unknown tags fail decoding. Recovery requires descriptor metadata such as next file number, log number, and last sequence number.

## CURRENT Format

`CURRENT` is a UTF-8 text file containing the current MANIFEST file name followed by a newline, for example:

```text
MANIFEST-000123
```

Validation rules:

- Content must end with a newline.
- The file name must not be empty.
- The file name must not contain `/` or `\`.
- The file name must match `MANIFEST-[0-9]{6,}`.
- The target file must exist in the same database directory.

Updating CURRENT first writes and fsyncs a `%06d.dbtmp` temporary file, then renames it to `CURRENT`; if rename fails, the implementation falls back to writing CURRENT directly.

## SST/table Format v1

The current SST/table format is treated as `table format v1 legacy`. It follows a LevelDB-style layout: data blocks, optional filter block, metaindex block, index block, and footer.

### Overall Layout

```text
[data block 0]
[data block 1]
...
[optional filter block]
[metaindex block]
[index block]
[footer]
```

### BlockHandle

A BlockHandle is encoded as two varints:

| Field | Meaning |
| --- | --- |
| offset | Offset of the block content |
| dataSize | Length of the block content, excluding the 5-byte trailer |

Full block size is `dataSize + 5`.

### Block Trailer

Every data/index/metaindex block is followed by a 5-byte trailer:

| Field | Length | Meaning |
| --- | ---: | --- |
| compressionType | 1 byte | `NONE=0x00`, `LZ4=0x02` |
| crc32c | 4 bytes | masked CRC32C over block content and compressionType |

When `verifyChecksums` is enabled, block reads validate the trailer CRC.

### Data Block Entry Encoding

Data blocks use key prefix compression and restart points.

Each entry:

| Field | Encoding | Meaning |
| --- | --- | --- |
| sharedKeyBytes | varint | Prefix bytes shared with the previous key; restart entries use 0 |
| nonSharedKeyBytes | varint | Non-shared key-byte length |
| valueSize | varint | Value-byte length |
| key delta | bytes | Key bytes after the shared prefix |
| value | bytes | Value bytes |

Block trailer area before the 5-byte block trailer:

| Field | Meaning |
| --- | --- |
| restartPositions[] | int offsets of restart points in block content |
| restartCount | Number of restart points |

`blockRestartInterval` controls how many entries appear between restart points. An empty block writes only `restartCount=0`.

### Compression

TableBuilder attempts LZ4 compression per block. Compressed block content is:

| Field | Meaning |
| --- | --- |
| rawLength | varint original block length |
| compressed bytes | LZ4 compressed data |

LZ4 is used only if the total compressed size is at least roughly 12.5% smaller than the original block; otherwise compression type remains NONE.

### Filter Block And Metaindex

If a `FilterPolicy` is enabled and there are keys, TableBuilder collects unique user keys, calls `filterPolicy.createFilter(filterKeys)`, and writes the filter bytes as a raw block.

The metaindex block is a regular block and may contain:

| key | value |
| --- | --- |
| `filter.<policyName>` | Encoded BlockHandle of the filter block |

When reading, if Options provide a FilterPolicy with the same name, the reader finds `filter.<policyName>` in metaindex and reads the filter block. Without a matching filter, `mayContain` returns true.

Starting with 0.9.0-SNAPSHOT, the release gate uses `filterBlockCoverage` to prove that `BloomFilterPolicy` produces `mayContainFalse` and `filterSkips>0` for in-range missing keys. This strengthens acceptance only and does not change disk compatibility: the filter block remains an optional metaindex entry.

### Index Block

The index block is a regular block. Keys are shortest separators/successors at data-block boundaries; values are data-block handles. Reads seek the index iterator to the target internal key and then open the referenced data block.

### Footer

Footer length is fixed: `BlockHandle.MAX_ENCODED_LENGTH * 2 + 8 = 48 bytes`.

| Field | Meaning |
| --- | --- |
| metaindexBlockHandle | Metaindex block handle |
| indexBlockHandle | Index block handle |
| padding | Zero padding before magic |
| magic | 64-bit table magic number `0xdb4775248b80fb57`, written as two 32-bit ints |

The current footer does not contain a format version or feature set.

## COLUMN-FAMILIES Format

`COLUMN-FAMILIES` is a UTF-8 text file. If absent, open logic uses static declarations from `Options.getColumnFamilies()`.

Current write format, one record per line:

```text
A	<cfId>	<escapedName>
D	<cfId>	<escapedName>
```

| Field | Meaning |
| --- | --- |
| `A` | Active column family |
| `D` | Dropped tombstone |
| cfId | Decimal integer |
| escapedName | Column-family name with `\`, tab, LF, and CR escaped |

The reader also accepts the older two-field format:

```text
<cfId>	<escapedName>
```

The old two-field format is treated as active. Writes go to `COLUMN-FAMILIES.tmp`, fsync it, delete the old file, and rename the temp file into place.

## Backup Metadata Format

Backup directories are named `backup-%06d`. Incremental backup attempts to reuse SST hard links from the previous backup and falls back to copying. The backup root may contain an object store: `objects/` plus `OBJECT-REFS.json`.

### BACKUP-MANIFEST.json

`BACKUP-MANIFEST.json` in each backup directory is hand-written JSON with these core fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `formatVersion` | number | Currently `1` |
| `backupId` | string | Backup directory name, for example `backup-000001` |
| `parentBackupId` | string | Parent backup id for incremental backups, empty for full backup |
| `action` | string | `backup` or `incremental-backup` |
| `copiedFiles` | array | Files copied by this backup |
| `reusedFiles` | array | Files reused by this backup |
| `published` | boolean | Publish marker; must be true |

`checkBackup` requires the manifest to exist, contain `formatVersion`, have a backupId matching the directory name, and have `published=true`.

### OBJECT-REFS.json

`OBJECT-REFS.json` under the backup root records object-store references:

| Field | Type | Meaning |
| --- | --- | --- |
| `formatVersion` | number | Currently `1` |
| `objects` | array | Object reference entries |
| `objects[].objectId` | string | Object file name |
| `objects[].refCount` | number | Number of backups referencing the object |
| `objects[].backups` | array | Backup ids referencing the object |

Current object ids are `fileName-fileLength-crc32`. `checkBackup` rebuilds the expected reference set and checks object existence, refCount correctness, missing refs, and orphan objects.

### BACKUP-REPORT.json / RESTORE-REPORT.json

These files are execution reports, not core restore metadata. They are used for auditing action, source/target, copied/reused files, check report, and failures.

## Current Check/Repair Format Behavior

| Entry | Current Behavior |
| --- | --- |
| `check` | Validates CURRENT, MANIFEST, SST, WAL, and COLUMN-FAMILIES; CURRENT must end with newline and point to a legal manifest |
| `checkBackup` | Validates BACKUP-MANIFEST, OBJECT-REFS, object-file refs, and orphan objects |
| `repairPlan` | Generates a plan only; does not write MANIFEST/CURRENT/SST/report files |
| `repair` | Rebuilds MANIFEST/CURRENT from readable SST/WAL files; moves corrupt files into `corrupt/`; replays WAL into MemTables and flushes Level-0 SSTs |

repair does not rewrite SSTs in place. It writes a new VersionEdit snapshot and updates CURRENT.

## Current Format Gaps

| Area | Gap |
| --- | --- |
| Global versioning | WAL, SST, MANIFEST, COLUMN-FAMILIES, and backup metadata do not share a unified format matrix |
| SST self-description | v1 footer has no format version; no properties block; entry count, block count, filter/compression parameters are not persisted |
| Feature set | No compatible/incompatible feature list and no unified unknown-feature boundary |
| MANIFEST | VersionEdit has no feature/version tag; unknown tags fail, but error classes are not productized |
| Backup metadata | `formatVersion=1` exists, but chain id, generation, schema feature, and retention-policy fields are absent |
| Check/report | Errors can be detected, but format version, feature, and corruption classes are not yet unified release-gate evidence |

## 0.8.0 Evolution Entry Point

`0.8.0` should use this document as the factual baseline:

- Add a table properties-block reader.
- Define table format v2 and compatible/incompatible feature sets.
- Make v2 writes opt-in first, keep reading old databases, and retain legacy-write mode.
- Add storage-format evidence to check/repair/report.
- Backup metadata schema now includes `schemaVersion/chainId/generation` and object reference schema fields; retention-policy fields remain future work.

See `docs/storage-format-0.8-design.en.md` for the detailed design.
## 0.8 Backup Metadata Schema

Version 0.8 adds explicit schema fields to backup metadata without changing the main backup directory layout. The goal is to improve release checks, cross-version restore diagnostics, and future object-store evolution.

- `BACKUP-MANIFEST.json` keeps `formatVersion=1` and adds `schemaVersion=backup-metadata-v2`, `chainId`, and `generation`. `chainId` remains stable within an incremental backup chain by inheriting the parent manifest value when available; `generation` is derived from directory names such as `backup-000001`.
- `OBJECT-REFS.json` keeps the object reference array and adds `schemaVersion=backup-object-refs-v2`, `objectStoreVersion=1`, and `generatedBy=vexra-ldb`, separating the object reference schema from the backup manifest schema.
- Compatibility policy: existing backups remain checkable and restorable. The new fields are diagnostic and migration metadata; old backups do not need backfill. If the parent manifest cannot be read, `chainId` falls back to the parent backup directory name to avoid blocking legacy backup-chain operations.
## 0.8 Repair Report Format Evidence

`REPAIR-REPORT.json` and `repair-plan` dry-run output now include structured storage-format evidence fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `storageFormat` | string | Summarizes the current format policy for table/WAL/MANIFEST/CURRENT/COLUMN-FAMILIES/backup metadata |
| `tableFormats` | array | Per-recoverable-SST `formatVersion/legacy/compatible/incompatible` summary |
| `legacyTables` | number | Number of tables identified as legacy v1 in repair inputs and WAL-replay outputs |
| `v2Tables` | number | Number of tables identified as table format v2 in repair inputs and WAL-replay outputs |
| `incompatibleTables` | number | Number of tables carrying incompatible features |

Compatibility policy: repair does not rewrite v1/v2 SST formats in place; it only explains the format state in the report. New SSTs generated from WAL replay follow the current `Options.tableFormatVersion` policy and are recorded with their final on-disk format.
### v2 SST Repair Format Preservation

When repair rebuilds MANIFEST/CURRENT from an existing v2 SST, it does not rewrite that SST in place or downgrade it to v1. `REPAIR-REPORT.json` preserves evidence such as `formatVersion=2` and `compatible=[table.properties,...]` in `tableFormats`, and reports the number of v2 tables through `v2Tables`.
### v1/v2 Mixed-Format Check Evidence

During opt-in migration, one database directory may contain both v1 legacy SSTs and v2 properties SSTs. `LDBFactory.check` lists `formatVersion=1` and `formatVersion=2` entries in `tableFormats`, and exposes the mixed state through `legacyTables`, `v2Tables`, and `incompatibleTables` counters. This behavior is tracked by the `mixedFormatCheckCoverage` release gate.
## 0.8 Table Format Policy And Options API Contract

The 0.8.0-SNAPSHOT table-format policy keeps a conservative default: newly written SST/table files remain v1 by default, and v2 table properties blocks are written only when `Options.tableFormatVersion(2)` is explicitly configured. `Options.writeTableProperties(true)` affects persistence only when `tableFormatVersion=2`, avoiding a default write-path compatibility change.

Old SSTs without a properties block are classified as v1 legacy, and `Options.allowLegacyTableFormat(true)` allows old formats by default so new versions can open old databases. Disabling this option should be limited to release validation or forced-migration checks.

`Options.failOnUnknownTableFeature(true)` is enabled by default. Unknown incompatible features, future table format versions, and malformed table format versions fail fast to avoid silent misreads. Disabling this option is only for diagnostic reads (diagnostic-only) and is not a production rollback strategy; production rollback should stop new v2 writes and rely on backup, copy, and check/repair report evidence.

Plugins can observe the open-time read-only policy snapshot through `OptionsView.tableFormatVersion()`, `OptionsView.writeTableProperties()`, `OptionsView.allowLegacyTableFormat()`, and `OptionsView.failOnUnknownTableFeature()`, but cannot mutate the file-format policy.
## 0.9.0-SNAPSHOT SF-06 table format policy

`ldb.tableFormatPolicy` is the runtime property for production enablement and rollback decisions. It complements `ldb.tableFormat` and `ldb.storageFormat` by explicitly emitting `newWrites`, `configuredTableFormatVersion`, `writeTableProperties`, `legacyReads`, `unknownFeaturePolicy`, `futureVersionPolicy`, `rollback`, `existingV2`, and `productionState`.

When v2 writes are enabled for production, operators should observe `newWrites=v2-properties` and `productionState=explicit-v2`. Rolling back new writes means restoring `Options.tableFormatVersion(1)`, after which the property returns `newWrites=v1` and `productionState=default-legacy`. Disabling `failOnUnknownTableFeature` remains diagnostic-only and is not a production rollback strategy.

## v3 Block-Local Index Runtime Fallback Contract

The v3 `block.local_index.v1` feature is an opt-in SST acceleration feature. New readers keep reading v1/v2 by default; local-index directories and local-index blocks are written only when new writes explicitly use `tableFormatVersion(3)` and `writeBlockLocalIndex(true)`.

At runtime, block-local indexes are sidecar positioning structures for point get and MultiGet. Missing or corrupt local-index data, checksum failures, or malformed anchors must not change the data-block truth; the read path falls back to ordinary data-block seek and exposes the event through `blockLocalIndexFallbackCount` in `ldb.sstReadStats`. Offline check/repair remains responsible for archiving corruption classes in `storageFormat`, `tableFormats`, and block-local-index failure fields.