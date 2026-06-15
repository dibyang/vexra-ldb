# LDB Quick Start

English | [中文](quick-start.md)

This guide helps first-time users run local KV reads and writes, column families, snapshot iteration, backup, and basic diagnostics in 5-10 minutes. For full configuration and operations guidance, see the [User Manual](user-manual.en.md) and [Operations Runbook](operations.en.md).

## Requirements

- JDK 8 or a compatible runtime.
- Use the included Gradle Wrapper when building from source.
- Place database directories on reliable local disks; do not mix them with temporary directories or build output directories.

## Dependency

Current project coordinates:

```groovy
dependencies {
  implementation "net.xdob.vexra:vexra-ldb:0.5.0-SNAPSHOT"
}
```

For local source validation, publish the artifact to your local Maven repository first:

```bash
./gradlew publishToMavenLocal
```

Windows PowerShell:

```powershell
.\gradlew.bat publishToMavenLocal
```

## Minimal Read And Write

```java
import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.LDBFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class LdbQuickStart {
  public static void main(String[] args) throws Exception {
    Options options = new Options()
        .createIfMissing(true)
        .verifyChecksums(true);

    try (LDB db = LDBFactory.factory.open(new File("data/quick-start.ldb"), options)) {
      byte[] key = "hello".getBytes(StandardCharsets.UTF_8);
      db.put(key, "world".getBytes(StandardCharsets.UTF_8));

      byte[] value = db.get(key);
      System.out.println(new String(value, StandardCharsets.UTF_8));

      db.delete(key);
    }
  }
}
```

Always close `LDB`; `try-with-resources` is recommended. Writes enter WAL first and are then applied to MemTable. After restart, recovery uses MANIFEST, SST, and WAL files.

## Sync Writes And Group Commit

Use `WriteOptions#sync(true)` when a write must be forced to durable storage:

```java
import net.xdob.vexra.ldb.WriteOptions;

db.put("order:1".getBytes(StandardCharsets.UTF_8),
    "created".getBytes(StandardCharsets.UTF_8),
    new WriteOptions().sync(true));
```

Sync writes have lower throughput. Production workloads can enable group commit to merge multiple sync writes within a very short window:

```java
Options options = new Options()
    .createIfMissing(true)
    .groupCommitEnabled(true)
    .groupCommitMaxDelayNanos(200_000L)
    .groupCommitMaxBatchBytes(1 << 20);
```

## Batch Writes

```java
import net.xdob.vexra.ldb.LdbWriteBatch;
import net.xdob.vexra.ldb.WriteOptions;

try (LdbWriteBatch batch = db.createWriteBatch()) {
  batch.put("k1".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8));
  batch.put("k2".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8));
  batch.delete("old".getBytes(StandardCharsets.UTF_8));
  db.write(batch, new WriteOptions().sync(true));
}
```

## Column Families

Column families can be declared before open or created at runtime.

```java
import net.xdob.vexra.ldb.LdbColumnFamily;

LdbColumnFamily metrics = new LdbColumnFamily() {
  @Override
  public int getId() {
    return 100;
  }

  @Override
  public String getName() {
    return "metrics";
  }
};

Options options = new Options()
    .createIfMissing(true)
    .addColumnFamily(metrics);

try (LDB db = LDBFactory.factory.open(new File("data/cf.ldb"), options)) {
  db.put(metrics,
      "counter".getBytes(StandardCharsets.UTF_8),
      "1".getBytes(StandardCharsets.UTF_8));
}
```

Runtime creation:

```java
LdbColumnFamily events = db.createColumnFamily(101, "events");
System.out.println(db.listColumnFamilies());
db.dropColumnFamily(events); // only empty column families can be dropped directly
```

## Snapshot Iteration

`SnapshotCursor` pins a read view at cursor creation time and must be closed after use.

```java
import net.xdob.vexra.ldb.SnapshotCursor;

try (SnapshotCursor cursor = db.newSnapshotCursor()) {
  cursor.seekToFirst();
  while (cursor.isValid()) {
    System.out.println(new String(cursor.key(), StandardCharsets.UTF_8));
    cursor.next();
  }
}
```

Range scan:

```java
byte[] begin = "user:".getBytes(StandardCharsets.UTF_8);
byte[] end = "user;".getBytes(StandardCharsets.UTF_8);

try (SnapshotCursor cursor = db.newSnapshotCursor()) {
  cursor.seek(begin);
  while (cursor.isValid() && compareUnsigned(cursor.key(), end) < 0) {
    cursor.next();
  }
}
```

The example `compareUnsigned` should use your application byte-order comparison and must match the key ordering used by writes.

## Checkpoint

```java
db.checkpoint("backup/checkpoint-001");
```

A checkpoint creates an independently checkable database copy, useful for local diagnostics, cold backup, or release evidence.

## CLI Check, Backup, And Restore

The tool entry point is `net.xdob.vexra.ldb.tool.LdbTool`. Build the jar first when using source:

```bash
./gradlew jar
```

Common commands:

```bash
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check data/quick-start.ldb
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool properties data/quick-start.ldb
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool backup data/quick-start.ldb backups
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check-backup backups/backup-000001
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool restore backups/backup-000001 restored.ldb
```

Backup, restore, repair, and checkpoint create file-system side effects. In formal environments, run `check`, `properties`, or `repair-plan` first, then confirm target directories and archival policy before running write-side commands.

## Quick Longrun Validation

Run a short release gate before release or integration:

```bash
./gradlew :ldb-longrun:productionGateLongRun -Pldb.longrun.duration=5s
```

The normal production-gate duration is 30 minutes:

```bash
./gradlew :ldb-longrun:productionGateLongRun -Pldb.longrun.durationMinutes=30
```

The run is successful when output contains `SUMMARY status=PASS` and `PASS production-gate`.

## Next Steps

- Read the [User Manual](user-manual.en.md) for configuration, column-family lifecycle, backup/restore, and plugins.
- Read the [Operations Runbook](operations.en.md) to define pre-release checks, upgrade, backup, and incident procedures.
- Read the [Plugin Developer Guide](ldb-plugin-developer-guide.en.md) for extension points.
