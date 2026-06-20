# LDB User Manual

English | [中文](user-manual.md)

This manual is for developers and operators preparing to integrate `vexra-ldb` into applications. If you are using LDB for the first time, start with the [Quick Start](quick-start.en.md).

## Fit And Non-Goals

LDB is a pure Java local LSM/LevelDB-style KV store. It fits embedded storage, local indexes, edge caches, single-node state, and test-tool persistence. It is not a distributed database and does not provide cross-process transactions, network protocol, SQL queries, or replicated consistency.

## Database Directory And Files

An LDB database directory usually contains:

- `CURRENT`: pointer to the active MANIFEST.
- `MANIFEST-*`: version edit log.
- `*.log`: WAL files.
- `*.ldb`: SSTable files.
- `LOCK`: open protection for the local process.
- `INFO_LOG`: runtime log.
- `COLUMN-FAMILIES.json`: column-family registry.

The same database directory should have only one writer open at a time. Diagnostic commands try to use read-only behavior, but repair, restore, backup, and checkpoint can create file-system side effects.

## Basic API

Core entry point:

```java
Options options = new Options()
    .createIfMissing(true)
    .verifyChecksums(true);

try (LDB db = LDBFactory.factory.open(new File("data/app.ldb"), options)) {
  db.put(key, value);
  byte[] value = db.get(key);
  db.delete(key);
}
```

Common operations:

- `put/get/delete`: basic KV reads and writes; `get(List<byte[]>)` performs ordered batch point reads and returns null for missing keys.
- `write(LdbWriteBatch, WriteOptions)`: batch writes.
- `addLong`: atomic counter increment.
- `newSnapshotCursor`: snapshot iteration.
- `compactRange`: manual compaction.
- `checkpoint`: create a database copy.
- `getProperty`: read diagnostics.

`LDB`, `LdbWriteBatch`, and `SnapshotCursor` should all be closed after use.

## Options

Common options:

| Option | Purpose | Recommendation |
| --- | --- | --- |
| `createIfMissing` | Create the database if missing | Use `true` on first boot; use explicit `false` for recovery scenarios |
| `errorIfExists` | Fail if database exists | Useful for initialization tools; usually off for services |
| `readOnly` | Open read-only | Recommended for diagnostics and inspections |
| `verifyChecksums` | Verify checksums on read | Recommended for production |
| `verifyOnOpen` | Run full verification on open | Use before release, after restore, or when corruption is suspected |
| `writeBufferSize` | MemTable size | Trade off write throughput and memory |
| `cacheBlocks` / `blockCacheSize` | Block cache behavior | Enable and tune upward for read-heavy workloads |
| `forceLogOnClose` / `forceSstOnFlush` | Force storage sync during close or flush | Evaluate for reliability-sensitive deployments |
| `level0*Trigger` | L0 compaction, slowdown, and stop-write thresholds | Tune with monitoring for write-heavy workloads |
| `compactionRateLimitBytesPerSecond` | Compaction rate limit | Prevent background IO from starving foreground traffic |
| `checkpointCopyRateLimitBytesPerSecond` | Checkpoint copy rate limit | Limit bandwidth when hard links fail or cross-file-system copies are required |
| `groupCommitEnabled` | Merge sync writes | Consider a gray rollout when sync writes are frequent |
| `tableFormatVersion` | Format version for newly written SST/table files | Default is `1`; set to `2` only when v2 properties blocks are explicitly required |
| `writeTableProperties` | Whether to write v2 table properties | Enabled by default, but persisted only when `tableFormatVersion=2` |
| `allowLegacyTableFormat` | Whether SSTs without a properties block remain readable | Enabled by default for old-database compatibility |
| `failOnUnknownTableFeature` | Fail fast on unknown incompatible features, future table format versions, or malformed version fields | Keep enabled in production; disabling it is for diagnostic reads only and is not a rollback strategy |

Plugin options are covered by the [Plugin Documentation Index](ldb-plugin-docs-index.en.md).

## WriteOptions And ReadOptions

`WriteOptions`:

- `sync(true)`: force WAL sync before the write returns.
- `snapshot(true)`: return a `Snapshot` after the write is applied.

`ReadOptions`:

- `verifyChecksums(true)`: verify data blocks during read.
- `fillCache(false)`: avoid polluting block cache during scans or background jobs.
- `snapshot(snapshot)`: read from a fixed snapshot.

Example:

```java
db.put(key, value, new WriteOptions().sync(true));
byte[] value = db.get(key, new ReadOptions().fillCache(false));
```

## Column-Family Lifecycle

The default column family is `LdbColumnFamily.DEFAULT`, id `1`, name `default`.

Declare before open:

```java
Options options = new Options()
    .createIfMissing(true)
    .addColumnFamily(metrics);
```

Runtime management:

```java
List<LdbColumnFamily> families = db.listColumnFamilies();
LdbColumnFamily created = db.createColumnFamily(100, "metrics");
LdbColumnFamily renamed = db.renameColumnFamily(created, "metrics_v2");
db.dropColumnFamily(renamed);
```

Constraints:

- Column-family ids must be stable and cannot conflict with existing ids.
- The default column family cannot be dropped.
- The minimal runtime drop implementation only drops empty column families directly.
- Non-empty drop and rename rely on tombstone semantics. See the [Column-Family Tombstone Design](ldb-column-family-tombstone-design.en.md).
- Before migration, create a backup and record the id/name mapping.

After multi-column-family changes, backups, or restore drills, archive `ldb.columnFamilyEvidence` to confirm active/dropped counts, registry records, MemTables, level files, and drop/rename policy.

## SnapshotCursor And Iteration

`SnapshotCursor` pins a read view at cursor creation time and is suitable for consistent iteration and range scans.

```java
try (SnapshotCursor cursor = db.newSnapshotCursor()) {
  cursor.seekToFirst();
  while (cursor.isValid()) {
    consume(cursor.key(), cursor.value());
    cursor.next();
  }
}
```

Notes:

- Long-lived cursors can delay old-version resource reclamation; split long scans into batches.
- Range scans must enforce their own end boundary.
- Large scans should prefer read paths with `ReadOptions#fillCache(false)` to avoid polluting hot cache; the cursor API currently does not expose a separate fill-cache option.
- When reviewing prefix/cache tuning, archive `ldb.prefixReadiness`; the property only describes PrefixExtractor, prefix-bloom, cache-warmup readiness and current cache/filter configuration, and does not mean the prefix read path is enabled.

## Compaction And Write Backpressure

LDB runs background compaction automatically and also supports manual compaction:

```java
db.compactRange(null, null);
db.compactRange(cf, beginKey, endKey);
```

Diagnostic properties:

- `ldb.compactionStats`
- `ldb.writeStallStats`
- `ldb.fileCounts`
- `ldb.fileBytes`
- `ldb.totalBytes`

If writes become unstable, inspect `ldb.writeStallStats` and L0 file counts first, then consider increasing `writeBufferSize`, tuning L0 thresholds, or setting a compaction rate limit.

## Backup, Restore, And Checkpoint

Recommended workflow:

1. Run read-only `check` or `properties`.
2. Create a full or incremental backup.
3. Run `check-backup`.
4. Practice restore regularly.

Archive these properties in release gates or restore-drill reports:

- `ldb.recoveryEvidence`: records WAL, MANIFEST, check/repair entry points, and repair-report state for the current database.
- `ldb.backupEvidence`: records evidence conventions for checkpoint, backup, restore, object-store metadata, and cleanup dry-run.
- `ldb.tableFormat`: records SST/table format versions, v1 legacy/v2 counts, and feature-set summaries.
- `ldb.storageFormat`: records the overall format summary for WAL, MANIFEST, CURRENT, COLUMN-FAMILIES, backup metadata, and table formats.

Commands:

```bash
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check data/app.ldb
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool properties data/app.ldb
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool scan data/app.ldb 20
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool backup data/app.ldb backups
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool incremental-backup data/app.ldb backups
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check-backup backups/backup-000001
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool restore backups/backup-000001 restored.ldb
```

`scan` opens the default column family read-only and emits key-order JSON. The default limit is 100, an explicit limit must be positive, and key/value bytes are base64-encoded. Use it for small diagnostic samples, not as a business export replacement.

`checkpoint` creates a local consistent copy. The implementation builds in a temporary directory, publishes the target only after verification, cleans the temporary directory on failure, and records copy, hard-link, byte, and duration statistics in the success report.

```bash
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool checkpoint data/app.ldb checkpoints/app-001
```

The target directory must be absent or empty. For large databases or cross-file-system targets, SST hard links may fall back to file copying; use `Options#checkpointCopyRateLimitBytesPerSecond(long)` to limit copy bandwidth.

See the [Operations Runbook](operations.en.md) for full operational procedures.

## Check And Repair

Read-only diagnostics:

```bash
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check data/app.ldb
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool repair-plan data/app.ldb
```

Before repair:

- Stop application writes.
- Preserve a raw directory copy or existing backup.
- Archive `check`, `repair-plan`, and logs.
- Validate on a restored copy before replacing the production directory.

Run repair:

```bash
java -cp build/libs/vexra-ldb-0.8.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool repair data/app.ldb
```

## Plugins

`LdbPlugin` can hook open, write, checkpoint, and close lifecycle events for audit, observability, and internal extensions.

Recommendations:

- Treat plugins as trusted in-process extensions by default.
- Production plugins should enable capability declarations and timeout policies.
- Async plugins are useful for non-critical observability paths, but queue capacity and close timeout must be monitored.
- External plugin directories use a managed classloader for dependency isolation, not a cross-process security sandbox.

Entry points:

- [Plugin Documentation Index](ldb-plugin-docs-index.en.md)
- [Plugin Developer Guide](ldb-plugin-developer-guide.en.md)
- [Plugin Isolation And Async Design](ldb-plugin-isolation-async-design.en.md)

## Longrun And Pre-Release Validation

`ldb-longrun` is the standalone long-stress tool. Common commands:

```bash
./gradlew :ldb-longrun:productionGateLongRun -Pldb.longrun.durationMinutes=30
./gradlew :ldb-longrun:releaseSoakTest -Pldb.longrun.durationMinutes=1440
```

In a distribution:

```bash
./bin/longrun watch -c config/production-gate.properties
./bin/longrun status -c config/production-gate.properties
./bin/longrun logs -c config/production-gate.properties
```

Acceptance:

- Logs contain `SUMMARY status=PASS`.
- Logs contain `PASS production-gate`.
- Final verification emits `FINAL PROGRESS phase=verify`; do not mistake it for a stalled run.
- The report directory contains `summary.md`, `summary.properties`, `summary.json`, and `operations.csv`.

## Upgrade Guidance

Before upgrade:

1. Read `CHANGELOG.en.md` and `docs/release.en.md`.
2. Run `check` on the production database.
3. Create and verify a backup.
4. Open a copy read-only with the new version.
5. Run production-gate or a longer soak.

After upgrade:

- Keep the old jar, backups, and reports.
- Monitor `ldb.api.compatibility`, `ldb.operationStats`, `ldb.compactionStats`, and `ldb.writeStallStats`.
- If an incident occurs, stop writes first and follow the [Operations Runbook](operations.en.md).

## FAQ

**Is the process stuck at `FINAL phase=verify`?**

Not necessarily. Final active-key verification emits `FINAL PROGRESS phase=verify`. If `verified` is still increasing, verification is running. If there is no CPU, IO, or log progress for a long time, capture a thread dump.

**Why are sync writes slower than async writes?**

`sync(true)` waits for WAL sync. Group commit can help, but it must be rolled out with latency targets in mind.

**Can multiple processes write the same directory?**

No. A database directory supports one writer instance.

**Can I drop a non-empty column family directly?**

Direct drop currently supports empty column families only. Non-empty tombstone lifecycle has design and implementation boundaries; back up and validate before migration.

**Can repair run directly on production data?**

It is not recommended. Stop writes, back up the raw directory, generate a repair plan, and validate on a copy first.

## Related Documents

- [Quick Start](quick-start.en.md)
- [Operations Runbook](operations.en.md)
- [Release Process](release.en.md)
- [Storage Format Reference](storage-format.en.md)
- [0.8 Storage Format Design](storage-format-0.8-design.en.md)
- [0.8 Storage Format Acceptance Matrix](storage-format-0.8-acceptance.en.md)
- [Project Design](ldb-project-design.en.md)
- [Production Readiness Plan](ldb-production-readiness-plan.en.md)
- [RocksDB Gap And Next-Version Plan](ldb-rocksdb-gap-next-version-plan.en.md)
- [External Commitments And Acceptance Boundaries](vexra-ldb-external-commitment.en.md)
## 0.9.0-SNAPSHOT SF-06: table format policy observability

Before enabling v2 writes, applications should read `ldb.tableFormatPolicy`. The default should be `newWrites=v1,productionState=default-legacy`; after explicitly setting `Options.tableFormatVersion(2)`, it should become `newWrites=v2-properties,productionState=explicit-v2`. To stop new v2 writes, restore `tableFormatVersion=1`; existing v2 SSTs remain readable by the current version.

## Bloom Filter Random-Read Observability

For readrandom workloads with many misses, enable the full-key SST Bloom filter with `Options.filterPolicy(new BloomFilterPolicy(bitsPerKey))`. Before release, archive `ldb.sstReadStats`: `mayContainRequests` counts candidate SST filter checks, `mayContainFalse` counts Bloom decisions that the key is definitely absent, and `filterSkips` counts skipped table iterators. If no filter policy is configured or an old SST has no matching filter block, the read path conservatively falls back to may-contain=true.
