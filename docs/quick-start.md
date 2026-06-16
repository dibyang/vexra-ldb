# LDB 快速入门

[English](quick-start.en.md) | 中文

本文帮助第一次接入 `vexra-ldb` 的用户在 5-10 分钟内跑通本地 KV 读写、列族、快照遍历、备份和基础诊断。更完整的配置和运维说明见 [用户使用手册](user-manual.md) 与 [运维 Runbook](operations.md)。

## 环境要求

- JDK 8 或兼容运行环境。
- 构建源码时使用项目自带 Gradle Wrapper。
- 数据目录建议放在本地可靠磁盘，不要和临时目录、构建产物目录混用。

## 获取依赖

当前项目坐标：

```groovy
dependencies {
  implementation "net.xdob.vexra:vexra-ldb:0.5.0-SNAPSHOT"
}
```

本地源码验证可以先发布到本机 Maven 仓库：

```bash
./gradlew publishToMavenLocal
```

Windows PowerShell：

```powershell
.\gradlew.bat publishToMavenLocal
```

## 最小读写

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

`LDB` 必须关闭，推荐使用 `try-with-resources`。写入会先进入 WAL，再应用到 MemTable；进程重启时会按 MANIFEST、SST 和 WAL 恢复。

## 同步写和 group commit

需要强落盘时使用 `WriteOptions#sync(true)`：

```java
import net.xdob.vexra.ldb.WriteOptions;

db.put("order:1".getBytes(StandardCharsets.UTF_8),
    "created".getBytes(StandardCharsets.UTF_8),
    new WriteOptions().sync(true));
```

同步写吞吐较低。生产场景可以开启 group commit，把多个同步写在极短窗口内合并：

```java
Options options = new Options()
    .createIfMissing(true)
    .groupCommitEnabled(true)
    .groupCommitMaxDelayNanos(200_000L)
    .groupCommitMaxBatchBytes(1 << 20);
```

## 批量写

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

## 列族

打开数据库前可以静态声明列族，也可以运行时创建列族。

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

运行时创建：

```java
LdbColumnFamily events = db.createColumnFamily(101, "events");
System.out.println(db.listColumnFamilies());
db.dropColumnFamily(events); // 非 default 列族会被逻辑删除，cfId 不可复用
```

## 快照遍历

`SnapshotCursor` 会固定打开时刻的读取视图，使用后必须关闭。

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

范围扫描：

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

示例中的 `compareUnsigned` 应使用业务自己的字节序比较逻辑，并保持和写入 key 的排序规则一致。

## Checkpoint

```java
db.checkpoint("backup/checkpoint-001");
```

checkpoint 会生成可独立检查的数据库副本，适合本机诊断、冷备份或发布前归档。

## 命令行检查、备份和恢复

工具入口是 `net.xdob.vexra.ldb.tool.LdbTool`。如果使用源码构建，可以先生成 jar：

```bash
./gradlew jar
```

常用命令：

```bash
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check data/quick-start.ldb
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool properties data/quick-start.ldb
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool backup data/quick-start.ldb backups
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check-backup backups/backup-000001
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool restore backups/backup-000001 restored.ldb
```

备份、恢复、repair 和 checkpoint 都有文件系统副作用。正式环境建议先执行 `check`、`properties` 或 `repair-plan`，确认目标目录和归档策略后再执行写入型命令。

## 长压测快速验证

发布前或接入前可以运行短门禁：

```bash
./gradlew :ldb-longrun:productionGateLongRun -Pldb.longrun.duration=5s
```

完整 production-gate 默认建议 30 分钟：

```bash
./gradlew :ldb-longrun:productionGateLongRun -Pldb.longrun.durationMinutes=30
```

最终看到 `SUMMARY status=PASS` 和 `PASS production-gate` 即表示长压测通过。

## 下一步

- 阅读 [用户使用手册](user-manual.md) 了解配置、列族生命周期、备份恢复和插件。
- 阅读 [运维 Runbook](operations.md) 建立发布前检查、升级、备份和故障处理流程。
- 阅读 [插件开发指南](ldb-plugin-developer-guide.md) 了解扩展点。
