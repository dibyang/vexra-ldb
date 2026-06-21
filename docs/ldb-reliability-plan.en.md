# LDB Reliability Improvement Plan

English | [中文](ldb-reliability-plan.md)

## Background

LDB already has basic WAL, MemTable, SSTable, checkpoint, column-family, and plugin support, but several boundary semantics are still weak, including unsupported write operations entering the WAL path, swallowed close failures, manual compaction suspension without a timeout, and mutable internal state exposed by Options.

## Goals

- Add reliability guards that do not change the on-disk format.
- Reduce partial-write, resource-leak, caller-misuse, and troubleshooting risks.
- Provide a stronger local storage foundation for ADB extensions on top of LDB.

## Non-Goals

- This phase does not implement real range tombstones.
- This phase does not change SSTable/WAL persistence formats.
- This phase does not redesign compaction scheduling.

## Current Flow

- Writes currently allocate sequences and append WAL before applying the batch to MemTable.
- `deleteRange` now has baseline semantics in `LdbWriteBatch`, WAL, MemTable, SST, read paths, snapshots, and compaction.
- `close()` attempts to close WAL, VersionSet, TableCache, ColumnFamilyState, database lock, and plugins, but several failure paths are silently ignored.
- `suspendCompactions()` queues a suspension task in the single-thread compaction executor, but it has no timeout.

## Core Constraints

- Keep JDK8 compatibility.
- Every new behavior must have unit tests.
- Preserve ADB compatibility first.
- Plugin `afterWrite` still means a post-commit notification and failures do not roll back committed data.
- Keep the current globally shared WAL instead of switching to per-CF WAL. Cross-CF batch atomicity, global sequence ordering, and a simple recovery path take priority. Per-CF WAL remains only a candidate for the later WAL lifecycle enhancement phase.

## Interface Design

- `deleteRange` supports half-open range tombstones with conservative compaction cleanup.
- `Options#getColumnFamilies` returns an immutable snapshot, and callers must register families through `addColumnFamily`.
- `readOnly(true)` supports real read-only open, creates no WAL/MANIFEST, and rejects writes, compaction, and checkpoint.
- `getProperty` exposes diagnostics such as database directory, last sequence, column families, background exception, pending outputs, WAL/compaction/backup/checkpoint/API compatibility, and related operational properties.

## Data Structures

- Later phases avoid new on-disk structures by default; landed formats such as range tombstones, column-family tombstones, and the backup object store must keep compatibility and old-version fail-fast evidence.
- Add in-memory batch validation for unsupported operations.
- Add close-failure aggregation to preserve resource-close causes.
- Add a compaction suspension timeout option.

## State Machine

`OPENING -> OPEN -> CLOSING -> CLOSED`

After entering `CLOSING`, LDB stops new compaction work, waits for existing background work, then closes WAL, VersionSet, TableCache, ColumnFamilyState, database lock, and plugins.

## Sequence Flow

1. Validate unsupported batch operations.
2. Run plugin `beforeWrite`.
3. Acquire the write mutex and allocate sequence numbers.
4. Append WAL and sync when requested.
5. Apply MemTable changes.
6. Run plugin `afterWrite`.

## Error Handling

- Close no longer silently swallows failures. It logs the resource name and database directory.
- After attempting to release all resources, close throws a `DBException` with the original cause when any step fails.
- Compaction suspension timeout or a closed executor throws `DBException`.

## Idempotency

- `close()` remains idempotent.
- `resumeCompactions()` logs a warning when there is no active suspension and never lets the counter go negative.

## Rollback Strategy

This phase has no disk-format changes. If a guard affects callers, revert the related code and tests; existing data remains readable by older versions.

## Compatibility

- `deleteRange` may have failed halfway before, but now has range tombstone semantics; older versions must fail fast or follow documented downgrade limits on new-format data.
- `Options#getColumnFamilies` changes from a mutable internal list to a read-only snapshot.
- `readOnly(true)` used to have no practical effect; it is now a real read-only open to avoid accidental writes from inspection tools.

## Rollout And Migration

No data migration is required. Validate with LDB unit tests and ADB LdbStore tests before wider integration testing.

## Test Plan

- Cover `deleteRange` range tombstone writes, reads, recovery, snapshots, and compaction semantics.
- Cover read-only open that does not write the directory and rejects mutating APIs.
- Cover property queries.
- Cover immutable column-family snapshots.
- Keep existing WAL recovery, checkpoint, plugin lifecycle, and column-family isolation regression tests.

## Risks

- The `deleteRange` format may reveal old-version compatibility or downgrade boundaries.
- Close failures are now visible, so callers need to handle close exceptions.
- The default compaction suspension timeout needs later calibration through stress tests.

## Phased Implementation Plan

1. Phase 1: Complete these guards and tests.
2. Phase 2: Add close timeout, background-task cancellation, and filesystem failure logging while keeping the disk format unchanged.
3. Phase 3: Add stress tests and fault injection.

## Phase 2 Addendum

- Close flow: add a close timeout option so shutdown does not wait forever for background compaction; cancel background work and report a `DBException` on timeout.
- File cleanup: log the file path and reason when obsolete WAL/SST/TEMP files or failed compaction outputs cannot be deleted.
- Directory durability: keep checkpoint directory `force` failures compatible, but log warnings instead of losing diagnostics silently.
- Write validation: validate batch contents before WAL append, so `deleteRange` and invalid `addLong` deltas fail before WAL and MemTable can diverge.
- Plugin boundary: validate the batch again after `beforeWrite`, so plugin-side mutation cannot bypass pre-WAL validation; `LdbWriteBatch#getColumnFamilies` returns an immutable snapshot to protect the internal touched column-family set.
- API boundary: `write(LdbWriteBatch, WriteOptions)` explicitly rejects unknown batch implementations and null options, avoiding context-free `ClassCastException`.
- Diagnostic properties: extend `getProperty` with current WAL file number, MANIFEST file number, total MemTable estimated bytes, per-column-family MemTable estimated bytes, and level file counts.
- Manual compaction: wire the minimal `compactRange(byte[], byte[])` implementation for the default column family only; flush the memtable first and then trigger the existing per-level manual compaction path for troubleshooting and compaction verification.
- Test additions: cover close timeout option validation, compaction suspension timeout option validation, repeated resume safety before a later suspend/resume cycle, and invalid `addLong` delta without persistence or sequence advancement.

## Phase 3 Addendum

- Recovery matrix: add dedicated recovery tests covering WAL batch replay, flush/SST persistence, restart after manual compaction, and checkpoint/source divergence recovery.
- Consistency assertions: each recovery case checks default column family data, custom column family data, delete, addLong, sequence/property values, and post-restart reads.
- Boundary constraints: the current matrix still uses normal close followed by reopen; process-level crash, partial WAL, corrupted MANIFEST, and corrupted SST are deferred to the later fault-injection phase.
- Fault injection: add file-level corruption tests. A truncated WAL tail should discard the incomplete tail record while preserving complete synced records; corrupted MANIFEST and SST files should fail clearly during open/read and retain the cause.

## Phase 4 Addendum

- SST verification: honor the existing `verifyChecksums` option by recalculating the masked CRC32C over `block contents + compression type` when reading data, index, meta, and filter blocks, then comparing it with `BlockTrailer`.
- Error semantics: checksum failures must throw an exception containing the SST file name, block offset, block size, expected CRC, and actual CRC so corrupt blocks are diagnosable.
- Compatibility: do not change the SST disk format; existing files already contain trailer CRC values and can be verified in place.
- Test additions: add SST block-content corruption tests covering explicit failure with `verifyChecksums(true)` and the disabled-checksum behavior with `verifyChecksums(false)`, noting that later parsing may still fail if the bytes are structurally invalid.

## Phase 5 Addendum

- Process-level crash: add child-process fault injection tests. The child writes data, intentionally skips `close()`, calls `Runtime.halt`, and the parent reopens the database to verify recovery.
- WAL recovery: cover a synced WAL batch so default column-family data, custom column-family data, and counters are recovered by WAL replay after a forced process exit.
- SST recovery: cover completed flush/compact output recorded in MANIFEST so data is recovered through MANIFEST/SST after a forced process exit.
- Boundary constraints: tests assert only completed sync or completed flush/compact data, and do not assert whether unsynced tail writes are visible because that depends on operating-system cache behavior.

## Current Safety And Reliability Increment

- Table-cache handle governance: `TableCache` now runs the table closer synchronously when an SST table is evicted, and `evict` actively triggers cache cleanup. This reduces delayed file-handle release and obsolete-SST delete failures on platforms such as Windows.
- Background-thread failure observability: uncaught compaction-thread failures no longer go to stdout/stderr. They are recorded in `backgroundException`, reflected in compaction failure counters, logged with structure, and wake waiting threads.
- Filesystem failure evidence: added `ldb.fileSystemStats`, `ldb.directoryForceFailureCount`, `ldb.fileDeleteFailureCount`, `ldb.lastDirectoryForceFailure`, and `ldb.lastFileDeleteFailure`, so best-effort directory-force failures and obsolete-file delete failures are queryable diagnostics.
- Release evidence archival: `ldb-longrun` now archives these filesystem diagnostic fields in `summary.json`, `summary.properties`, and `properties-after.json`; the default `ldb properties` output also includes `ldb.fileSystemStats`.
- Compatibility boundary: this increment does not change the WAL/SST/MANIFEST disk formats or public APIs. If synchronous table close exposes a closer failure, LDB logs a warning and continues releasing other resources.

## RocksDB Gap And Follow-Up Roadmap

### Gap List

| Area | Current LDB State | Main Gap From RocksDB | Priority |
| --- | --- | --- | --- |
| Recovery and repair | WAL/SST normal recovery, file corruption, process crash, `repair`, `repair-plan`, structured reports, multi-CF repair, and corrupt-WAL quarantine have minimal closed loops | Missing online repair coordination, automated handling of complex conflicts, a real media-failure corpus, and longer-term recovery evidence archival | P2 |
| Read-only open | `readOnly(true)` is supported; it creates no WAL/MANIFEST and rejects writes, compaction, and checkpoint | Missing finer shared-lock/diagnostic inspection modes and operating guidance for concurrent backup or monitoring tools | P2 |
| Range delete | `deleteRange` supports range tombstones, WAL/SST persistence, read-path masking, snapshot semantics, and conservative compaction | Missing more aggressive tombstone/point-key cleanup, long mixed-workload reports, and archived old-version fail-fast evidence | P1 |
| Column-family lifecycle | Supports open-time registration, per-CF reads/writes, per-CF compactRange/properties, runtime list/create, non-empty drop tombstones, and rename | Missing more aggressive dropped-CF physical GC, column-family migration strategy, and large-scale multi-CF operational reports | P2 |
| Compaction policy | Supports configurable triggers, rate limiting, cancellation cleanup, per-CF scoring, and several soak/observability entry points | Missing real low-disk, high-concurrency, and long-running production-environment compaction reports plus external visualization | P2 |
| Writes and WAL | Supports WAL, sync, batch, crash recovery, partial-WAL combination tests, reopen/backup/repair after recycling, and a disabled-by-default minimal group commit | Missing WAL archive/retention policy, WAL lifecycle rules across backup/repair, and a long-run group-commit tail-latency baseline | P1 |
| Snapshot/iterator | Has snapshot cursor, reverse iteration, prefix/range scan contracts, resource counters, and long-lived snapshot-across-compaction tests | Missing a larger leak matrix, disk-retention limits under very long-lived snapshots, and production stress reports | P2 |
| Verification and integrity | Has SST checksums, offline check, `verifyOnOpen`, checkpoint reports, tool commands, and corruption-injection coverage | Missing a fuller media-failure, permission, low-disk, and cross-filesystem corruption-injection ecosystem plus operational repair/check combinations | P2 |
| Backup/checkpoint | Supports checkpoint, full/incremental backup, object store, `OBJECT-REFS.json`, dry-run cleanup, verification, restore reports, CLI, and release gates | Missing long backup-chain soak, cross-filesystem checkpoint/backup performance baselines, low-disk failure matrices, and long-term backup-repository maintenance tooling | P1 |
| Performance and observability | Has benchmarks, operation histograms, block cache stats, IO/compaction/write-stall metrics, slow-operation logs, and capacity watermark properties | Missing external visualization, long-term trend storage, and real low-disk/high-concurrency environment reports | P2 |
| API compatibility and ecosystem | Provides DBFactory/LDB APIs, compatibility self-description, migration docs, and a minimal LDB tool entry point | Missing MergeOperator, PrefixExtractor, transactions, TTL, custom Env, full RocksDB CLI compatibility, and a mature plugin ecosystem | P3 |

### Follow-Up Implementation Order

1. Phase 6: Implement the minimal `repair` loop.
   - Scan MANIFEST, WAL, and SST files, and verify readable files.
   - Rebuild MANIFEST and CURRENT from usable SST/WAL data.
   - Move corrupt files into `lost/` or `corrupt/` and emit a repair report.
   - Tests: missing MANIFEST, partially corrupted SST, truncated WAL tail, and reopen after repair.
   - 6.1 increment: first rebuild MANIFEST/CURRENT for the default column family from readable SST files, and quarantine corrupt SST/old MANIFEST files; do not rewrite WAL contents into SST yet.
   - 6.2 increment: add WAL replay into repair output for cases where only WAL exists and no usable MANIFEST is available.
   - 6.3 increment: emit a structured `REPAIR-REPORT.json` with recoverable SST/WAL files, quarantined files, discarded WAL tails, rebuilt MANIFEST/CURRENT file numbers, and the last sequence.
   - 6.4 increment: cover multi-CF repair, corrupt-WAL quarantine, and mixed SST+WAL recovery; unknown column families are recovered or quarantined according to the caller-provided `Options`.
   - 6.5 increment: add `LDBFactory.planRepair` and `ldb repair-plan <db>` dry-run entry points, scanning registry, SST, and WAL read-only and returning a JSON plan for files that would be recovered, replayed, or quarantined without writing MANIFEST/CURRENT/SST/report files.
   - Repair report fields: `databaseDir`, `recoveredSstFiles`, `replayedWalFiles`, `quarantinedFiles`, `discardedWalBytes`, `manifestFileNumber`, `currentFile`, `lastSequence`, and `nextFileNumber`; the report is diagnostic output and does not participate in database recovery semantics.

2. Phase 7: Implement true read-only open.
   - `Options.readOnly(true)` must not create a new WAL, write MANIFEST, or delete obsolete files.
   - Lock policy: read-only open does not acquire the exclusive `LOCK`, so an existing writer process/instance can keep the write lock; the read-only instance only reads existing CURRENT/MANIFEST/SST/WAL files and builds its own in-memory view at open time.
   - Reject put/delete/write/compact/checkpoint and other mutating operations.
   - Expose read-only diagnostics, at least including read-only state, current MANIFEST, referenced WAL files, and whether background compaction was skipped.
   - Tests: read-only open while a writer exists, read-only failure on corrupt DB, write API rejection, and no new WAL/MANIFEST created during read-only open.

3. Phase 8: Add column-family operational capabilities.
   - Add per-CF `compactRange(cf, begin, end)`, reusing the existing manual compaction scheduler. Because WAL files are currently shared globally, flush all column families before compaction to avoid losing non-target MemTable data when obsolete WAL files are removed, then run manual compaction only for the target column family.
   - Expose per-CF diagnostic `getProperty` entries: `ldb.columnFamily.<cfId>.memTableBytes` and `ldb.columnFamily.<cfId>.levelFiles`.
   - Do not introduce per-CF WAL in this phase; if finer WAL recycling is needed, design it together with Phase 14.
   - 8.3 Increment: added `docs/ldb-column-family-lifecycle-design.md` and the English copy, implemented the `COLUMN-FAMILIES` registry plus minimal runtime `list/create/drop-empty`; backup, checkpoint, check, and repair now recognize runtime-CF metadata.
   - 8.4 Increment: added a corruption-injection matrix covering corrupt registry, missing registry causing runtime-CF WAL resolution failure, CURRENT pointing to a missing MANIFEST, corrupt backup registry rejecting restore, and runtime-CF WAL-only repair.
   - 8.5 Increment: added `docs/ldb-column-family-tombstone-design.md` and its English copy, and completed the minimal non-empty drop tombstone plus rename implementation; the registry preserves dropped records, column-family ids are not reused, and API self-description exposes the capabilities.
   - Column-family migration tombstones and more aggressive physical GC still require later focused designs.
   - Tests: multi-CF compaction, unknown-CF failure, and CF directory/metadata recovery.

4. Phase 9: Design and implement range delete.
   - Design document: `docs/ldb-range-delete-design.md`; English copy: `docs/ldb-range-delete-design.en.md`.
   - First review range tombstone WAL/SST encoding, read-path masking rules, snapshot semantics, compaction merge behavior, and downgrade limits according to the design.
   - Implement MemTable range tombstones, SST persistence, snapshot semantics, and compaction merge.
   - This changes disk-format semantics, so compatibility and rollback design must come first.
   - Tests: deletion across SST/levels, snapshot old view, crash recovery, and old-data compatibility.

5. Phase 10: Add stress testing and observability.
   - Add lightweight benchmarks for write throughput, random read, scan, flush, compaction, and checkpoint.
   - Add soak tests for multi-threaded writes, mixed reads/writes, repeated reopen, and crashes.
   - Extend properties and logs for write stall, compaction backlog, block-cache hit rate, WAL/SST file counts/sizes, capacity watermarks, and slow-operation logs.
   - 10.1 increment: added a lightweight benchmark workflow test covering writes, random reads, snapshot scan, manual compaction, and checkpoint; extended `getProperty` with `ldb.fileCounts`, `ldb.fileBytes`, `ldb.totalBytes`, `ldb.walBytes`, `ldb.sstBytes`, `ldb.compactionBacklog`, `ldb.compactionScore`, and `ldb.compactionLevel`.
   - 10.2 increment: first wire slow-operation logging and basic latency histograms into the observability surface, with a configurable slow-operation threshold and `getProperty` entries for `get/write/compact/checkpoint` counts, average latency, max latency, and slow-operation counts; longer soak tests, repeated reopen tests, and write-stall semantics remain in 10.3.
   - 10.2 fix: hit-read stress testing showed that MemTable still scanned all entries for range tombstones even when no range tombstone existed, causing pure MemTable reads to degrade linearly; range tombstones now use a separate index so normal point reads go through skip-list seek only.
   - 10.3 increment: add a lightweight repeated-reopen/scan soak regression test and expose write-stall semantics: Level-0 soft triggers record slowdown delays, immutable MemTables or Level-0 stop triggers record waits, with counts, accumulated latency, and trigger thresholds available through properties.
   - 10.4 increment: add operation latency histograms and `ldb.blockCacheStats`, exposing BlockCache enabled/maxEntries/size/hits/misses/puts/evictions, and make `Options.cacheBlocks(false)` truly disable the cache.
   - 10.5 increment: added `docs/ldb-longrun-benchmark-design.md` and its English copy, and completed the minimal machine-readable longrun report implementation: `summary.json`, `operations.csv`, `failures.json`, `properties-before.json`, and `properties-after.json`; added explicit `benchmarkReport`, `longRunTest`, and `releaseSoakTest` Gradle tasks.

6. Phase 11: Add whole-DB verify/check.
   - Add offline verify/check support that scans MANIFEST, SST, and WAL files and emits file-level, block-level, and sequence-level reports.
   - Support an optional startup full-verification option; keep it disabled by default to avoid increasing normal open latency.
   - Add checkpoint verification reports covering CURRENT, MANIFEST, SST, WAL, and each verification result included in the checkpoint.
   - Tests: clean DB verification success, clear failures for corrupt SST/WAL/MANIFEST, startup verification toggling, and auditable checkpoint reports.
   - 11.1 increment: first add offline `LDBFactory.check(File, Options)` scanning CURRENT, MANIFEST, SST, and WAL files and returning a structured report; startup verification and checkpoint reports remain in 11.2.
   - 11.2 increment: add `Options.verifyOnOpen(true)` startup whole-DB verification; after checkpoint creation, write `CHECKPOINT-REPORT.json` with checked files, failures, and MANIFEST/WAL/SST counters.

7. Phase 12: Enhance backup and checkpoint.
   - Design and implement a RocksDB-backup-engine-like incremental backup directory layout that can reference SST/WAL files by backup id.
   - Add backup verification, version cleanup, restore reports, and failure rollback so partial backups do not pollute the recoverable set.
   - Keep the existing checkpoint API compatible; expose new behavior through a dedicated backup API or tool entry point first.
   - Tests: first full backup, incremental backup, old-version cleanup, corrupt-backup verification failure, and reopen after restore.
   - 12.1 increment: first add offline full-backup/restore entry points `LDBFactory.createBackup/restoreBackup`, write into a temporary directory and publish only after verification, and emit `BACKUP-REPORT.json` plus `RESTORE-REPORT.json`; incremental backups, version cleanup, and shared-SST reference counting remain in 12.2.
   - 12.2 increment: add `LDBFactory.purgeOldBackups(root, keepLast)`, only deleting published `backup-000001` style directories, retaining the newest N versions, and returning a cleanup report; shared-file reference counting remains in a later increment.
   - 12.3 increment: add `LDBFactory.createIncrementalBackup/checkBackup`, publish a complete restorable directory, write `BACKUP-MANIFEST.json`, and preferentially hard-link same-name same-length SST files from the previous backup; shared object storage and reference-count cleanup later landed in 12.5.
   - 12.4 increment: expose incremental backup and backup validation through `LdbTool incremental-backup` and `LdbTool check-backup`, reusing `BackupReport`/`CheckReport` JSON output and exit-code semantics.
   - 12.5 increment: added `docs/ldb-backup-engine-design.md` and its English copy, and completed the minimal shared object store plus reference-count implementation: backup roots maintain `objects/` and `OBJECT-REFS.json`, and `planPurgeBackups` supports dry-run cleanup impact audits.

8. Phase 13: Enhance compaction policy.
   - Add configurable trigger thresholds, rate limiting, cancellation, and per-CF scoring while preserving existing defaults.
   - Define cancellation boundaries and output-file cleanup rules for both manual and background compaction.
   - Add long-running stress tests covering multi-CF workloads, write bursts, suspend/resume, close timeouts, and low-disk-space behavior.
   - Tests: threshold triggers, effective rate limiting, recoverable cancellation, per-CF scoring selection, and read consistency through long compaction runs.
   - 13.1 increment: first make Level-0 compaction/slowdown/stop trigger thresholds configurable through `Options`, keeping the default 4/8/12 behavior and exposing the effective write-stall thresholds through properties; rate limiting, cancellation, and pending bytes remain in later increments.
   - 13.2 increment: add compaction runtime statistics properties for background running state, run/success/failure counts, latest failure reason, and SST output bytes, establishing the observability baseline for later cancellation, rate limiting, and backlog soak tests.
   - 13.3 increment: record the column family of the best global compaction candidate to avoid mis-picking under multi-CF scoring; add pending bytes, candidate details, and per-CF compaction score/pending properties.
   - 13.4 increment: add optional compaction output rate limiting, disabled by default; when enabled, delay proportionally to SST output bytes and expose rate-limit configuration plus throttle count and wait time properties.
   - 13.5 increment: tighten compaction cancellation/failure cleanup boundaries so exceptions, interrupts, or close-time cancellations clean up uninstalled output files and pending outputs; expose cancellation count and cleaned-file count properties.
   - 13.6 increment: add a multi-CF compaction consistency soak that interleaves writes to the default and a custom column family, compacts them independently, reopens the DB, and verifies per-CF scoring/properties do not break cross-CF read isolation.
   - 13.7 increment: add a read-consistency soak during rate-limited compaction, repeatedly running point gets and snapshot cursor scans while a background compact is in progress to verify long compactions do not break foreground reads.
   - 13.8 increment: add a write-burst observability soak that uses a small write buffer and repeated burst writes to verify write-stall, operation, backlog, and compaction properties remain stable and data remains intact after high-frequency writes.
   - 13.9 increment: add a suspend/resume interaction test under write pressure, verifying foreground writes observe immutable-memtable backpressure while compaction is suspended and complete with readable data after compaction is resumed.
   - 13.10 increment: add a file-capacity watermark soak covering WAL/SST/total bytes, fileBytes, pending bytes, and compaction output statistics across write, compaction, and reopen as the observability entry point for low-disk-space scenarios.
   - 13.11 increment: add a close-timeout recovery test that verifies `closeTimeoutMillis` fails explicitly while the compaction executor is occupied by suspension, and the close path still releases the lock so the DB can be reopened and committed data can be read.
   - Phase 13 completion criteria: threshold configuration, rate limiting, cancellation cleanup, per-CF scoring, write bursts, multi-CF workloads, suspend/resume, close timeout, capacity watermarks, and long-running read consistency now have implementations or independent test entry points; real low-disk injection remains an environment-level soak item.

9. Phase 14: Enhance the WAL lifecycle.
   - Design WAL archive/recycle policy and define which WAL files can be deleted, archived, or retained for repair/backup.
   - Evaluate globally shared WAL, per-CF WAL, and WAL sharding/archiving. Keep global WAL by default unless a new design can preserve cross-CF batch atomicity, global sequence recovery order, MANIFEST references, and rollback compatibility.
   - Add systematic partial-WAL combination tests, including header truncation, record truncation, checksum errors, and multi-log boundaries.
   - Evaluate write throttling and group commit semantics; define compatibility and latency/throughput tradeoffs before implementation.
   - Tests: WAL recycling does not break reopen/repair/backup, partial-WAL combinations recover or fail as expected, and throttling/group-commit metrics are observable.
   - 14.1 increment: keep the global WAL scheme unchanged and add WAL lifecycle observability properties for existing WALs, referenced WALs, recyclable WALs, and the current WAL as the acceptance entry point for later archive/recycle policy work.
   - 14.2 increment: add partial-WAL combination tests covering header truncation, checksum errors, and tail truncation across multi-WAL boundaries; the current policy skips corrupt or incomplete records while preserving preceding complete records.
   - 14.3 increment: add reopen/backup/repair acceptance coverage after WAL cleanup, verifying that once data is persisted to SST and obsolete WALs are cleaned, normal open, offline backup/restore, and repair can all read the complete data.
   - 14.4 increment: explicitly expose current WAL policy properties: global WAL, archive disabled, obsolete-WAL deletion as recycle policy, group commit disabled by default, and write throttling delegated to existing write-stall behavior; any future policy change must update compatibility docs and tests first.
   - 14.5 increment: add a minimal group commit implementation and statistics property. When enabled, concurrent writes enter a short-window aggregation queue and any sync request in the group forces the commit cycle to sync; WAL records are still encoded per request, preserving disk-format compatibility.
   - Phase 14 completion criteria: the global WAL scheme is confirmed, and WAL lifecycle observability, partial-write combinations, reopen/backup/repair after recycling, plus the current write-throttle/group-commit policy all have documentation and test entry points.

10. Phase 15: Complete snapshot and iterator semantics.
    - Add an iterator leak matrix covering unclosed cursors, interrupted iteration, and snapshots held across compaction.
    - Design and implement reverse iteration plus prefix/range scan contracts, including their relationship to snapshot sequence numbers.
    - Add long-lived snapshot stress tests to observe SST reference retention, obsolete-file cleanup, and memory usage.
    - Tests: resource release, reverse-iteration order, prefix/range boundaries, and safe compaction/cleanup while long snapshots are held.
    - 15.1 increment: complete `SnapshotCursor` reverse iteration by supporting `seekToLast`, `seekForPrev`, and `prev`; the current implementation materializes the snapshot-visible view before reverse traversal to prioritize semantic correctness.
    - 15.2 increment: add snapshot cursor resource-count properties for opened, closed, and active cursors as the leak-matrix test entry point.
    - 15.3 increment: add prefix/range scan contract tests, clarifying that scan boundaries are caller-controlled stop conditions while the cursor always returns user keys visible at the snapshot sequence.
    - 15.4 increment: add a long-lived snapshot-across-compaction test to verify compaction and cleanup do not break the old view while a cursor is held, and resource counts recover after close.
    - Phase 15 completion criteria: resource release, reverse iteration, prefix/range scan boundaries, and long-lived snapshots across compaction now have implementations or independent test entry points.

11. Phase 16: Improve API compatibility and ecosystem.
    - Evaluate common RocksDB Options mapping and define supported, ignored, and rejected configuration categories with caller-visible errors.
    - Design MergeOperator, PrefixExtractor, statistics APIs, and tool commands; any disk-format or read/write semantic change must go through separate review.
    - Provide migration and compatibility documentation covering LDB/RocksDB behavior differences and ADB usage constraints.
    - Tests: Options mapping compatibility, unknown-config rejection, stable statistics APIs, and clear tool-command error semantics.
    - 16.1 increment: first expose read-only compatibility self-description properties covering RocksDB-style Options mapping, current effective option values, supported features, and explicitly unsupported features. This increment does not change disk format or read/write semantics; MergeOperator, PrefixExtractor, and tool commands remain explicitly unsupported until a separate design review approves them.
    - 16.2 increment: add LDB/RocksDB API compatibility and migration documentation that fixes the Options mapping, property-based statistics entry points, explicitly unsupported features, rollback strategy, and review boundary for future MergeOperator, PrefixExtractor, and tool-command work. The document does not change runtime behavior, but becomes the acceptance baseline for later ecosystem compatibility increments.
    - 16.3 increment: add an LDB tool-command design review that defines arguments, read-only/write boundaries, exit codes, and error semantics for `check`, `repair`, `checkpoint`, `backup`, `restore`, `properties`, and `compact`. No CLI implementation is provided yet; `rocksdbToolCommands` remains marked unsupported until a command entry point is implemented.
    - 16.4 increment: add dedicated MergeOperator/PrefixExtractor review boundaries, making clear that they affect WAL/SST formats, MemTable merging, read-path visibility, compaction merging, snapshot semantics, repair/check/backup compatibility, and downgrade strategy. `mergeOperator` and `prefixExtractor` remain unsupported for now; future work must proceed through a separate design and migration plan.
    - 16.5 increment: add `ldb.api.ecosystemGaps`, exposing ecosystem status for MergeOperator, PrefixExtractor, transactions, TTL, custom Env, column-family drop/rename implementation state, RocksDB tool compatibility, and secondary indexes to migration layers.
    - Phase 16 completion criteria: Options mapping, runtime compatibility self-description, migration notes, tool-command semantics, and MergeOperator/PrefixExtractor review boundaries are documented, with `LdbApiCompatibilityTest` covering the core properties. Real CLI or Merge/Prefix implementations remain future independent phases and do not block closing this phase.

12. Phase 17: Minimal LDB tool-command entry point.
    - First implement non-destructive commands covering the read-only `check` and `properties` commands, reusing the exit-code and JSON-output constraints defined in Phase 16.
    - `check <db>` calls offline `LDBFactory.check(File, Options)` and does not acquire a writer lock, create WALs, or write MANIFEST; failed checks return exit code `2`.
    - `properties <db> [property...]` opens the DB with `readOnly(true)` by default and prints requested properties. Without explicit property names, it prints `ldb.api.*` and key statistics properties. Unknown properties return exit code `1`.
    - Write commands `repair`, `checkpoint`, `backup`, `restore`, and `compact` are not implemented in 17.1 and remain under the Phase 16 tool-command review to avoid exposing side-effecting commands too early.
    - Tests: bad arguments, healthy check, corrupted check, default properties output, explicit property output, and unknown property error semantics.
    - 17.1 increment: add `net.xdob.vexra.ldb.tool.LdbTool` with read-only `check` and `properties` commands. Runtime compatibility properties now expose `ldbToolCommands=partial`, while RocksDB tool commands remain `rocksdbToolCommands=unsupported`.
    - 17.2 increment: add `repair <db>` as the first explicit side-effecting tool command. It calls `LDBFactory.repair`, prints `REPAIR-REPORT.json` on success, returns exit code `4` for exceptions, and returns exit code `1` for bad arguments.
    - 17.3 increment: add `backup <db> <backupRoot>` and `restore <backupDir> <targetDir>` commands, reusing the offline backup/restore engine and printing `BackupReport` JSON. Reports with `ok=false` return exit code `2`; successful reports return `0`.
    - 17.4 increment: add `checkpoint <db> <targetDir>`, opening the source DB normally and calling the instance-level `checkpoint`; on success it prints `CHECKPOINT-REPORT.json`. Non-empty target directories or checkpoint verification failures return internal-error exit code `4`.
    - 17.5 increment: add `incremental-backup <db> <backupRoot>` and `check-backup <backupDir>`, covering complete-directory incremental backup publishing and read-only backup validation.

13. Phase 18: Production release gates and operational hardening. (Minimal closed loop complete; keep maintaining release evidence.)
    - Design and implement a formal `releaseGate` that aggregates regular tests, old-version upgrade fixtures, backup object-store validation, a lightweight longrun profile, and report archiving.
    - Fix `0.4.0` and later historical-version fixtures, verifying that the new version can open, read, check, and backup/restore them, or emits a clear migration error when incompatible.
    - Complete the backup object-store corruption matrix covering missing objects, wrong reference counts, orphan objects, corrupt manifests, and restore rollback.
    - Complete column-family tombstone long-lifecycle stress covering drop/rename across reopen, compaction, backup, repair, snapshot cursor, and physical-GC proof.
    - Define the production-gate longrun profile and benchmark archival rules, making `summary.json`, `operations.csv`, `failures.json`, and property snapshots release evidence.
    - Add operations runbooks covering production startup, backup, restore, upgrade, check, repair, pre-release gates, and incident-handling order.
    - 18.1 increment: old-version upgrade compatibility fixtures.
    - 18.2 increment: production-grade `releaseGate` aggregate task.
    - 18.3 increment: backup object-store corruption matrix.
    - 18.4 increment: column-family tombstone long stress and physical-GC proof.
    - 18.5 increment: production-gate longrun profile.
    - 18.6 increment: operations manual and incident runbook.
    - Design baseline: `docs/ldb-production-readiness-plan.md` and its English copy.

14. Phase 19: Solidify checkpoint/backup production evidence.
    - Add cross-filesystem checkpoint/backup baselines covering hard-link fallback to copy, copy rate limiting, temporary-directory publishing, and failure cleanup.
    - Add checkpoint/backup long-chain stress for large databases, many SSTs, multiple column families, repeated checkpoints, consecutive incremental backups, and purge dry-runs.
    - Complete fault injection for low disk, permission denial, occupied target directories, interruption, and post-commit plugin failures.
    - Include `CHECKPOINT-REPORT.json`, `BACKUP-MANIFEST.json`, `OBJECT-REFS.json`, release-gate reports, and longrun reports in a unified release-evidence index.

15. Phase 20: Productionize WAL lifecycle and write policy.
    - Design WAL archiving, retention, cleanup, and repair/backup dependencies, making clear which WALs can be deleted, retained, or included in backup evidence.
    - Add long-run group commit baselines for tail latency, sync count, throughput, and crash/reopen behavior, defining when the feature can move from disabled-by-default to gray rollout.
    - Evaluate production thresholds for write throttling and write-stall behavior, and emit auditable longrun reports.

16. Phase 21: Operational ecosystem and external observability.
    - Strengthen CLI/report indexing and align output paths plus exit-code guidance for check, repair, backup, restore, checkpoint, properties, longrun, and release gate.
    - Design metrics export or report conversion so operators can feed Prometheus, log platforms, or offline trend analysis.
    - Build a reproducible failure corpus for corrupt WAL, corrupt SST, corrupt manifest, corrupt backup objects, permission failures, and low-disk cases.

17. Phase 22: RocksDB advanced API compatibility reviews.
    - Review MergeOperator, PrefixExtractor, transactions, TTL, custom Env, and full RocksDB CLI compatibility as separate designs.
    - Identify which features would change WAL/SST/MANIFEST formats or read/write semantics, and which must remain explicitly unsupported in migration layers.
    - Do not block the `0.5.0` production release on these advanced features.

### Near-Term Priority

Phase 18.1-18.6 now has a minimal closed loop, so the plan no longer treats old-version fixtures, `releaseGate`, backup object-store corruption injection, column-family tombstone long stress, the production-gate longrun profile, or runbooks as pending phases. The next priority is Phase 19: first solidify checkpoint/backup evidence across filesystems, low-disk cases, permission failures, and long backup chains, then proceed to Phase 20 for WAL lifecycle and long group-commit baselines. MergeOperator, PrefixExtractor, transactions, TTL, custom Env, and full RocksDB CLI compatibility remain Phase 22 independent reviews.
