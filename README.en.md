# vexra-ldb

English | [中文](README.md)

`vexra-ldb` is a pure Java local LSM/LevelDB-style key-value storage engine under the package `net.xdob.vexra.ldb`. It provides basic reads and writes, batch writes, snapshot cursors, column families, WAL recovery, SSTables, background compaction, checkpoint, offline check/repair/backup/restore, and a lightweight command-line tool.

## Features

- Basic KV API: `put`, `get`, `delete`, `write`, and `addLong`.
- Column families: register through `Options#addColumnFamily`; the default family is `LdbColumnFamily.DEFAULT`.
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
ldb backup <db> <backupRoot>
ldb restore <backupDir> <targetDir>
ldb checkpoint <db> <targetDir>
```

Commands primarily output JSON so scripts and tests can parse them. `check` and `properties` are read-only diagnostics commands. `repair`, `backup`, `restore`, and `checkpoint` create file-system side effects, so callers should confirm target directories and backup strategy before running them.

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
- `ldb.writeStallStats`
- `ldb.snapshotCursorStats`
- `ldb.api.compatibility`
- `ldb.api.supportedFeatures`
- `ldb.api.unsupportedFeatures`

## Important Boundaries

- The `deleteRange` API exists, but complete range tombstone read/write semantics remain a focused design topic. See `docs/ldb-range-delete-design.md`.
- The current implementation still uses a global WAL; cross-column-family batches rely on global sequence ordering for recovery.
- Runtime create/drop column family, MergeOperator, PrefixExtractor, and full RocksDB CLI compatibility are not current goals.
- Changes involving disk format, recovery semantics, state machines, or tool side effects must update design documents first and include compatibility and rollback notes.

## Documents

- `CONTRIBUTING.md`: contribution guide.
- `SECURITY.md`: security policy and vulnerability reporting.
- `CODE_OF_CONDUCT.md`: community code of conduct.
- `CHANGELOG.md`: release changelog.
- `docs/release.md`: release process.
- `docs/ldb-project-design.md`: overall project design in Chinese.
- `docs/ldb-project-design.en.md`: English copy of the overall project design.
- `docs/ldb-reliability-plan.md`: reliability improvement plan.
- `docs/ldb-range-delete-design.md`: range delete design.
- `docs/ldb-api-compatibility-design.md`: API compatibility and migration design.

## License

Apache License 2.0. See `LICENSE`.
