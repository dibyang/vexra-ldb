# vexra-ldb

English | [中文](README.md)

`vexra-ldb` is a pure Java local LSM/LevelDB-style key-value storage engine under the package `net.xdob.vexra.ldb`. It provides basic reads and writes, batch writes, snapshot cursors, column families, WAL recovery, SSTables, background compaction, checkpoint, offline check/repair/backup/restore, and a lightweight command-line tool.

## Features

- Basic KV API: `put`, `get`, `delete`, `write`, and `addLong`.
- Column families: register through `Options#addColumnFamily` or the runtime `listColumnFamilies`/`createColumnFamily`/empty `dropColumnFamily` APIs; the default family is `LdbColumnFamily.DEFAULT`.
- Write reliability: writes go to WAL first and are then applied to MemTable; restart recovery uses MANIFEST, SST, and WAL.
- Read path: lookup checks MemTable, immutable MemTable, and Version/SSTable in order, with snapshot cursor support.
- Storage files: supports LevelDB-style WAL, MANIFEST/CURRENT, SSTable, LOCK, INFO_LOG, and related files.
- Compaction: supports background compaction, manual `compactRange`, suspend/resume, rate limiting, and diagnostics.
- Data maintenance: supports `checkpoint`, offline `check`, `repair`, full `backup`, `restore`, and old-backup cleanup.
- Observability: `getProperty` exposes WAL, file size, compaction, write stall, operation latency, snapshot cursor, and other diagnostics.
- Extension points: `LdbPlugin` can hook into open, write, checkpoint, and close lifecycle events.

## Requirements

- JDK 8
- Gradle Wrapper

The project uses UTF-8 by default. See `gradle.properties` and `build.gradle` for Gradle configuration.

## Build And Test

```bash
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew.bat test
```

## Basic Usage

```java
import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.LDBFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class Example {
  public static void main(String[] args) throws Exception {
    Options options = new Options()
        .createIfMissing(true)
        .verifyChecksums(true);

    try (LDB db = LDBFactory.factory.open(new File("data/example.ldb"), options)) {
      byte[] key = "hello".getBytes(StandardCharsets.UTF_8);
      byte[] value = "world".getBytes(StandardCharsets.UTF_8);

      db.put(key, value);
      byte[] loaded = db.get(key);
      System.out.println(new String(loaded, StandardCharsets.UTF_8));
    }
  }
}
```

## Column Family Example

```java
import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.LdbColumnFamily;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.LDBFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;

LdbColumnFamily metrics = new LdbColumnFamily(100, "metrics");
Options options = new Options()
    .createIfMissing(true)
    .addColumnFamily(metrics);

try (LDB db = LDBFactory.factory.open(new File("data/cf.ldb"), options)) {
  db.put(metrics,
      "counter".getBytes(StandardCharsets.UTF_8),
      "1".getBytes(StandardCharsets.UTF_8));
}
```

## Operational Tool

The command-line entry point is `net.xdob.vexra.ldb.tool.LdbTool`. Current commands are:

```text
ldb check <db>
ldb properties <db> [property...]
ldb repair <db>
ldb repair-plan <db>
ldb backup <db> <backupRoot>
ldb incremental-backup <db> <backupRoot>
ldb check-backup <backupDir>
ldb restore <backupDir> <targetDir>
ldb checkpoint <db> <targetDir>
```

Commands primarily output JSON so scripts and tests can parse them. `check`, `properties`, `repair-plan`, and `check-backup` are read-only diagnostics commands. `repair`, `backup`, `incremental-backup`, `restore`, and `checkpoint` create file-system side effects, so callers should confirm target directories and backup strategy before running them.

## Common Diagnostic Properties

- `ldb.databaseDir`
- `ldb.readOnly`
- `ldb.lastSequence`
- `ldb.currentLogNumber`
- `ldb.walPolicy`
- `ldb.fileCounts`
- `ldb.fileBytes`
- `ldb.totalBytes`
- `ldb.compactionStats`
- `ldb.checkpointStats`
- `ldb.writeStallStats`
- `ldb.groupCommitStats`
- `ldb.plugins`
- `ldb.pluginStats`
- `ldb.plugin.executionPolicy`
- `ldb.plugin.asyncStats`
- `ldb.snapshotCursorStats`
- `ldb.api.compatibility`
- `ldb.api.supportedFeatures`
- `ldb.api.unsupportedFeatures`

## Important Boundaries

- `deleteRange` supports range tombstone read/write, recovery, snapshot, and conservative compaction semantics. Longer mixed-workload evidence and more aggressive cleanup remain future work. See `docs/ldb-range-delete-design.md`.
- The current implementation still uses a global WAL; cross-column-family batches rely on global sequence ordering for recovery.
- Runtime column-family list/create/drop, non-empty drop tombstones, and rename are supported; MergeOperator, PrefixExtractor, transactions, TTL, custom Env, and full RocksDB CLI compatibility remain explicit non-goals or ecosystem gaps.
- Plugins are trusted in-process extensions. External longrun plugin directories use a managed classloader for dependency isolation, but this is not a cross-process security sandbox.
- Changes involving disk format, recovery semantics, state machines, or tool side effects must update design documents first and include compatibility and rollback notes.

## Documents

- `CONTRIBUTING.md`: contribution guide, with English copy and language switch.
- `SECURITY.md`: security policy and vulnerability reporting, with English copy and language switch.
- `CODE_OF_CONDUCT.md`: community code of conduct, with English copy and language switch.
- `CHANGELOG.md`: release changelog, with English copy and language switch.
- `docs/release.md`: release process, with English copy and language switch.
- `docs/quick-start.md`: quick start for first-time users, with English copy and language switch.
- `docs/user-manual.md`: user manual for application integration, operations, and troubleshooting, with English copy and language switch.
- `docs/operations.md`: runbook for production-readiness validation, backup/restore, upgrade, check/repair, and incident handling.
- `docs/ldb-project-design.md`: overall project design in Chinese.
- `docs/ldb-project-design.en.md`: English copy of the overall project design.
- `docs/ldb-reliability-plan.md`: reliability improvement plan.
- `docs/ldb-range-delete-design.md`: range delete design.
- `docs/ldb-api-compatibility-design.md`: API compatibility and migration design.
- `docs/ldb-plugin-design.md`: plugin capability enhancement design.
- `docs/ldb-plugin-docs-index.md`: plugin documentation entry point.
- `docs/ldb-column-family-tombstone-design.md`: current non-empty column-family drop/rename/tombstone semantics and future GC plan.
- `docs/ldb-backup-engine-design.md`: shared object store and reference-counted backup engine design.
- `docs/ldb-longrun-benchmark-design.md`: long-run stress and benchmark report framework design.
- `docs/ldb-production-readiness-plan.md`: production release gates, upgrade fixtures, long stress, and operations runbook plan.
- `docs/vexra-ldb-external-commitment.md`: external commitments and release acceptance boundaries.
- `docs/ldb-future-optimization-design.md`: future performance and reliability evaluation.

## License

Apache License 2.0. See `LICENSE`.
