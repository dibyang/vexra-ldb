# LDB API Compatibility and Migration Notes

English | [中文](ldb-api-compatibility-design.md)

## Background

LDB now has WAL, MemTable, SSTable, checkpoint, repair, verify/check, backup, column families, range delete, read-only open, observability properties, and plugin support. Phase 16 does not try to clone the full RocksDB API. Its first purpose is to make supported, partially supported, and explicitly unsupported boundaries visible so callers do not assume RocksDB-style options have silently taken effect.

## Goals

- Define how RocksDB/LevelDB-style Options map to LDB `Options`.
- Stabilize compatibility and statistics entry points exposed through `getProperty`.
- Clarify the current status and review boundary for MergeOperator, PrefixExtractor, and tool commands.
- Provide a migration checklist for callers moving RocksDB/LevelDB usage patterns to LDB.

## Non-Goals

- Do not implement a RocksDB JNI or RocksJava compatibility layer.
- Do not introduce MergeOperator, PrefixExtractor, transactions, TTL, secondary indexes, custom Env, or full RocksDB CLI compatibility in this phase.
- Do not change WAL, SST, MANIFEST, or CURRENT disk formats.
- Do not promise compatibility with all RocksDB property names or return formats.

## Current Flow

| Capability | Current LDB behavior | Migration note |
| --- | --- | --- |
| Basic read/write | `put`, `delete`, `get`, and `write` are supported | Failures surface as `DBException` or argument errors |
| Column families | Declared before open through `Options.addColumnFamily`, with runtime `list/create/drop`, non-empty drop tombstones, and rename support | Dropped-CF physical GC, migration policy, and large-scale multi-CF operations remain hardening items |
| Range delete | `LdbWriteBatch.deleteRange` is supported | Check old-version readability before using data with range tombstones |
| Read-only open | `Options.readOnly(true)` is supported | Read-only instances do not create new WALs and reject writes/compaction/checkpoint |
| Statistics | Exposed through `LDB.getProperty` | No native RocksDB statistics object |
| Checkpoint/Backup | Checkpoint, full backup, incremental backup, object store, and cleanup dry-run Java APIs are supported | Checkpoint target directories must be empty; cross-filesystem copies, low-disk cases, and long backup chains still need production evidence |
| Group Commit | Can be explicitly enabled through `Options.groupCommitEnabled` | Disabled by default; WAL records are still encoded per request |
| Repair/Verify | Factory repair/check APIs are supported | Repair writes a structured report |

## Core Constraints

- Keep JDK8 compatibility.
- Document facts first; do not describe unimplemented features as silently ignored.
- Treat unsupported features as explicit unsupported, not ignored.
- Any feature touching disk format, WAL semantics, read-path masking, compaction merging, or snapshot visibility requires a separate design review.

## Interface Design

### Runtime Self-Description Properties

| Property | Meaning | Stability |
| --- | --- | --- |
| `ldb.api.compatibility` | Overall compatibility policy, such as partial RocksDB Options support, unsupported config rejection, and property-based statistics | Stable |
| `ldb.api.optionsMapping` | Mapping list from RocksDB-style Options to LDB behavior | Fields may be added, meanings must not invert |
| `ldb.api.optionValues` | Current effective key option values | Fields may be added |
| `ldb.api.supportedFeatures` | Explicitly supported capability list | Fields may be added |
| `ldb.api.unsupportedFeatures` | Explicitly unsupported capability list | Implemented capabilities may move out |
| `ldb.api.ecosystemGaps` | Blocking reasons for key unsupported ecosystem features | Fields may be added |

### Options Mapping Policy

| RocksDB/LevelDB concept | LDB mapping | Policy |
| --- | --- | --- |
| `create_if_missing` | `Options.createIfMissing` | supported |
| `error_if_exists` | `Options.errorIfExists` | supported |
| `write_buffer_size` | `Options.writeBufferSize` | supported |
| L0 slowdown delay | `Options.writeSlowdownDelayNanos` | supported |
| Group commit switch | `Options.groupCommitEnabled` | supported, disabled by default |
| Group commit collection delay | `Options.groupCommitMaxDelayNanos` | supported |
| Group commit collection size | `Options.groupCommitMaxBatchBytes` | supported |
| `max_open_files` | `Options.maxOpenFiles` | supported |
| `block_size` | `Options.blockSize` | supported |
| `block_restart_interval` | `Options.blockRestartInterval` | supported |
| `compression` | `Options.compressionType` | supported, limited to LDB enum values |
| `verify_checksums` | `Options.verifyChecksums` | supported |
| `comparator` | `Options.comparator` | supported; callers own ordering consistency |
| `filter_policy` | `Options.filterPolicy` | supported |
| `statistics` | `LDB.getProperty` | properties |
| `merge_operator` | none | unsupported |
| `prefix_extractor` | none | unsupported |
| `env` | none | unsupported |
| `ttl` | none | unsupported |
| LDB tool commands | `LdbTool check/properties/repair/backup/restore/checkpoint` | partial |
| RocksDB tool commands | no RocksDB CLI compatibility layer | unsupported |

## Data Structures

Runtime column-family lifecycle adds the root-level `COLUMN-FAMILIES` registry to record runtime column-family id/name pairs. This file does not change WAL, MANIFEST, or SST formats; backup, checkpoint, check, and repair recognize and carry it.

## State Machine

| State | Trigger | Behavior |
| --- | --- | --- |
| Supported | LDB has equivalent configuration or capability | Property marks it as `supported`; callers may use it |
| Partially supported | LDB has a related capability but not full RocksDB semantics | Property uses explanatory fields such as `rocksdbOptions=partial` |
| Explicitly unsupported | Not implemented or semantically risky | Property marks it as `unsupported`; later implementation must update docs and tests |

## Sequence Flow

1. Caller builds `Options` and opens LDB.
2. After open, caller reads `getProperty("ldb.api.compatibility")` to detect the compatibility policy.
3. Caller reads `ldb.api.optionsMapping` and `ldb.api.unsupportedFeatures` for startup diagnostics.
4. Operations or tests read `ldb.operationStats`, `ldb.compactionStats`, `ldb.walPolicy`, `ldb.groupCommitStats`, and `ldb.snapshotCursorStats` for stable statistics entry points.

## Error Handling

- Existing Java setters throw `IllegalArgumentException` for invalid values such as invalid block cache size, compression type, L0 thresholds, and rate limits.
- Missing properties return `null`; callers must not treat `null` as false or zero.
- There are currently no MergeOperator or PrefixExtractor setters, so migration layers must not silently drop those configs; they should reject them during adapter or startup validation.

## Idempotency

Reading properties is side-effect free and can be repeated. `ldb.api.optionValues` reflects the current effective options of the opened instance; this phase introduces no runtime interface that mutates Options.

## Rollback Strategy

- If a new property causes caller issues, callers can roll back their dependency on it; database files require no migration.
- Because no disk format changes are made, reverting code still allows old databases to open normally.
- Future MergeOperator, PrefixExtractor, or tool-command implementations must provide their own disable switch and data rollback plan.

## Compatibility

- Old data files: compatible, because this phase writes no new format.
- Old clients: unchanged if they do not read the new properties.
- New clients: if a property is missing, downgrade to "capability unknown" rather than assuming supported.
- ADB constraint: ADB should prefer LDB properties for capability detection instead of hard-coded RocksDB capability matrices.

## Rollout and Migration

1. Read `ldb.api.compatibility` in test environments and verify it is non-null.
2. Validate migrated configs against an allowlist; only `supported` or `properties` entries may enter LDB.
3. Fail startup for `unsupported` entries and ask callers to remove the config or wait for a future design.
4. After rollout, collect `ldb.operationStats` and `ldb.compactionStats` to validate runtime state.

## Test Plan

- `LdbApiCompatibilityTest` covers Options mapping, effective option values, supported/unsupported features, and unknown property `null` behavior.
- `LdbCoreBehaviorTest` continues to cover basic APIs, read-only mode, plugins, column families, and invalid config.
- `LdbObservabilityTest`, `LdbWalLifecycleTest`, and `LdbSnapshotIteratorTest` cover stable statistics properties.
- Future tool-command work must add command tests for bad arguments, corrupted databases, read-only databases, and permission failures.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Callers assume full RocksDB compatibility | Misconfiguration or semantic mismatch | Properties and docs explicitly say `partial` |
| Adapter silently ignores unsupported features | Data semantic bugs | Unsupported configs must fail startup |
| External tools over-parse property strings | Added fields may break parsing | Docs state fields may be added; callers should match key fragments |
| MergeOperator/PrefixExtractor implemented directly | Read/write and compaction semantics may break | Require a separate design review |

## Tool-Command Review

The repository now has a minimal `net.xdob.vexra.ldb.tool.LdbTool` CLI entry point with read-only `check`, `properties`, `check-backup`, `repair-plan`, and the explicit side-effecting `repair`, `backup`, `incremental-backup`, `restore`, and `checkpoint` commands. Instance-level `compactRange` is not exposed through the CLI yet. Therefore `ldbToolCommands` is partial, while `rocksdbToolCommands` remains unsupported.

### Candidate Commands

| Command | Underlying capability | Writes DB | Default lock policy | Main output |
| --- | --- | --- | --- | --- |
| `ldb check <db>` | `LDBFactory.check` | No | No writer lock | Structured check report and health-reflecting exit code |
| `ldb repair-plan <db>` | `LDBFactory.planRepair` | No | No writer lock | dry-run `RepairReport` JSON; no report file is written |
| `ldb repair <db>` | `LDBFactory.repair` | Yes | Exclusive writer lock | `REPAIR-REPORT.json`, quarantined files, rebuilt MANIFEST/CURRENT info |
| `ldb checkpoint <db> <target>` | `LDB.checkpoint` | Opens source normally, writes target through a temporary directory | Target must be absent or empty; published only after success | `CHECKPOINT-REPORT.json` with copy/link statistics |
| `ldb backup <db> <backup-root>` | `LDBFactory.createBackup` | Reads source, writes backup dir | Offline source check | `BackupReport` JSON and verification result |
| `ldb restore <backup> <target>` | `LDBFactory.restoreBackup` | Writes target DB | Target must be creatable or empty | `BackupReport` JSON and verification result |
| `ldb incremental-backup <db> <backup-root>` | `LDBFactory.createIncrementalBackup` | Reads source, writes backup dir | Offline source check | `BackupReport` JSON and verification result |
| `ldb check-backup <backup>` | `LDBFactory.checkBackup` | No | No writer lock | `CheckReport` JSON |
| `ldb properties <db> [property...]` | `LDB.getProperty` | No | Read-only open by default | Property key/value output |
| `ldb compact <db> [begin] [end]` | `LDB.compactRange` | Yes | Normal writer open | Compaction stats and errors |

### Exit Codes

| Exit code | Meaning |
| --- | --- |
| `0` | Success, and check-like commands report healthy |
| `1` | Bad arguments, unknown command, or unknown property |
| `2` | Database check failure, partial repair failure, or unrecoverable files found |
| `3` | Filesystem permission, lock conflict, non-empty directory, or unusable path |
| `4` | Internal exception, including uncategorized `DBException` or IO exception |

### Error Semantics

- `check` and `properties` must be read-only by default, create no new WALs, and avoid modifying MANIFEST.
- `repair`, `compact`, and `restore` are write commands; help text and logs must clearly state their side effects. The currently exposed `repair` command prints `REPAIR-REPORT.json` after success.
- `checkpoint` and `backup` should not change source DB semantics, but they write target directories; non-empty targets must fail. `checkpoint` builds through a temporary directory, publishes only after verification, cleans the temporary directory on failure, and preserves the failure cause. The currently exposed `backup`/`restore` commands reuse offline backup reports directly, and failed reports return exit code `2`; `checkpoint` prints the target directory's `CHECKPOINT-REPORT.json`.
- All commands should support machine-readable output, preferably JSON; human text is only an additional view.
- LDB tool commands now have minimal entries for `check`, `properties`, `repair`, `backup`, `restore`, `checkpoint`, `incremental-backup`, and `check-backup`; `rocksdbToolCommands` in `ldb.api.unsupportedFeatures` means the native RocksDB tool command set is not compatible.

### Implementation Prerequisites

1. When adding or extending CLI commands, add command parser tests and do not reuse temporary test `main` classes.
2. Cover lock conflicts, missing paths, non-empty targets, and permission failures for all write commands.
3. Cover `ldb.api.*`, statistics properties, and unknown properties in the `properties` command.
4. Command output fields must stay compatible with repair/check/backup reports instead of inventing parallel meanings.

## MergeOperator/PrefixExtractor Review

LDB currently exposes no MergeOperator or PrefixExtractor configuration, and runtime properties explicitly mark both as unsupported. They must not be added as ordinary Options setters because they change core write, read, recovery, and compaction semantics.

### MergeOperator Impact

| Area | Design question | Policy before review |
| --- | --- | --- |
| WAL | Whether merge needs a new `ValueType`, and how operator names and operands are recorded | Do not write merge records |
| MemTable | Whether point reads merge in memory, and how failures surface | Do not support merge writes |
| SST | How merge operands are encoded and whether old versions can skip or reject them | Do not add SST encoding |
| Read path | Precedence among multi-version operands, put, delete, and range tombstones | Keep current point/range delete rules |
| Compaction | When to perform full merge and whether operator failures abort compaction | Do not introduce compaction merge |
| Snapshot | Operand visibility and repeated-read idempotency at a snapshot sequence | Keep current snapshot semantics |
| Repair/Check | How unknown operators or corrupt operands are reported | Keep current format checks |
| Backup/Restore | Whether backups need operator metadata | Do not add metadata |

If MergeOperator is implemented later, it must first define the operator registration model, determinism requirements, exception handling, disk metadata, old-version rejection policy, and disable/rollback plan. Operators should be pure, deterministic, side-effect free, thread-safe, and identified by a persisted operator name in MANIFEST or equivalent metadata.

### PrefixExtractor Impact

| Area | Design question | Policy before review |
| --- | --- | --- |
| Comparator | Whether prefix order is compatible with the user comparator | Do not expose prefix config |
| Bloom/Filter | How prefix bloom coexists with the existing filter policy | Keep the existing filter policy |
| Iterator | Boundaries for prefix seek, reverse iteration, and range-scan stop conditions | Callers continue to control key boundaries |
| Range Delete | How prefix scans combine with range tombstone masking | Keep full-key range rules |
| Compaction | Whether prefix partitioning affects file boundaries and candidate choice | Do not change compaction picker |
| Check/Repair | How corrupt or missing prefix metadata is verified | Add no new check item |

If PrefixExtractor is implemented later, it must first prove the combined semantics with current `DBComparator`, `FilterPolicy`, `SnapshotCursor`, range delete, and per-CF compaction. Any prefix bloom or prefix seek optimization must have a degradation path and must not allow a bad prefix configuration to cause missed reads.

### Review Entry Checklist

- Update this design document and the Chinese/English pair before adding public API.
- If a new `ValueType`, MANIFEST field, SST entry, or filter block is introduced, define old-version open behavior and rollback limits.
- Add fault injection for WAL partial writes, unknown operators, operator exceptions, inconsistent prefix config, and interrupted compaction.
- Add compatibility tests for new-version reads of old DBs, old-version rejection of new DBs, backup/restore semantics, and clear repair/check reports.
- Until the review is complete, `ldb.api.optionsMapping` must keep returning `mergeOperator=unsupported` and `prefixExtractor=unsupported`.

## Phased Plan

| Phase | Content | Acceptance |
| --- | --- | --- |
| 16.1 | Runtime compatibility self-description properties | `LdbApiCompatibilityTest` passes |
| 16.2 | API compatibility and migration documentation | Chinese/English docs are in sync and plan records the increment |
| 16.3 | Tool-command design review | Commands, error semantics, exit codes, and read/write boundaries are defined |
| 16.4 | MergeOperator/PrefixExtractor review | WAL/SST/compaction/snapshot/repair/backup impact is defined, and both remain unsupported |
| 17.1 | LDB read-only tool-command entry point | `check` and `properties` commands are covered by `LdbToolTest` |
| 17.2 | LDB repair tool command | `repair` is covered by a missing-CURRENT repair and reopen test |
| 17.3 | LDB backup/restore tool commands | `backup`/`restore` are covered by backup publication, restore reopen, and corrupt-source failure tests |
| 17.4 | LDB checkpoint tool command | `checkpoint` is covered by report output, target reopen, and non-empty target failure tests |
