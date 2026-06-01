# vexra-ldb

[English](README.en.md) | 中文

`vexra-ldb` 是一个纯 Java 的本地 LSM/LevelDB 风格 KV 存储实现，包名为 `net.xdob.vexra.ldb`。项目提供基础读写、批量写、快照游标、列族、WAL 恢复、SSTable、后台 compaction、checkpoint、离线 check/repair/backup/restore 以及轻量命令行工具。

## 主要能力

- 基础 KV API：`put`、`get`、`delete`、`write`、`addLong`。
- 列族：通过 `Options#addColumnFamily` 注册，默认列族为 `LdbColumnFamily.DEFAULT`。
- 写入可靠性：写入先进入 WAL，再应用到 MemTable；重启时通过 MANIFEST、SST 和 WAL 恢复。
- 读路径：按 MemTable、immutable MemTable、Version/SSTable 的顺序查询，并支持 snapshot cursor。
- 存储文件：支持 WAL、MANIFEST/CURRENT、SSTable、LOCK、INFO_LOG 等 LevelDB 风格文件。
- Compaction：支持后台 compaction、手动 `compactRange`、暂停/恢复 compaction、限速和观测属性。
- 数据维护：支持 `checkpoint`、离线 `check`、`repair`、全量 `backup`、`restore` 和旧备份清理。
- 可观测性：通过 `getProperty` 暴露 WAL、文件大小、compaction、write stall、操作耗时、snapshot cursor 等诊断信息。
- 扩展点：`LdbPlugin` 可挂接打开、写入、checkpoint 和关闭生命周期。

## 环境要求

- JDK 8
- Gradle Wrapper

项目默认使用 UTF-8 编码，Gradle 配置见 `gradle.properties` 和 `build.gradle`。

## 构建与测试

```bash
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew.bat test
```

## 基本用法

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

## 列族示例

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

## 运维工具

工具入口为 `net.xdob.vexra.ldb.tool.LdbTool`，当前命令包括：

```text
ldb check <db>
ldb properties <db> [property...]
ldb repair <db>
ldb backup <db> <backupRoot>
ldb restore <backupDir> <targetDir>
ldb checkpoint <db> <targetDir>
```

命令输出以 JSON 为主，便于脚本和测试解析。`check`、`properties` 是只读诊断命令；`repair`、`backup`、`restore`、`checkpoint` 会产生文件副作用，使用前应确认目标目录和备份策略。

## 常用诊断属性

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
- `ldb.plugins`
- `ldb.pluginStats`
- `ldb.snapshotCursorStats`
- `ldb.api.compatibility`
- `ldb.api.supportedFeatures`
- `ldb.api.unsupportedFeatures`

## 重要边界

- `deleteRange` 接口存在，但 range tombstone 的完整读写语义仍是独立设计主题，详见 `docs/ldb-range-delete-design.md`。
- 当前仍采用全局 WAL，跨列族 batch 依赖全局 sequence 保持恢复顺序。
- 运行时 create/drop column family、MergeOperator、PrefixExtractor、完整 RocksDB CLI 兼容不是当前实现目标。
- 涉及磁盘格式、恢复语义、状态机或工具副作用的改动，需要先更新设计文档并补充兼容性和回滚说明。

## 文档

- `CONTRIBUTING.md`：贡献指南。
- `SECURITY.md`：安全政策和漏洞报告方式。
- `CODE_OF_CONDUCT.md`：社区行为准则。
- `CHANGELOG.md`：版本变更记录。
- `docs/release.md`：发布流程说明。
- `docs/ldb-project-design.md`：项目整体设计文档。
- `docs/ldb-project-design.en.md`：整体设计文档英文副本。
- `docs/ldb-reliability-plan.md`：可靠性增强计划。
- `docs/ldb-range-delete-design.md`：range delete 设计。
- `docs/ldb-api-compatibility-design.md`：API 兼容与迁移设计。
- `docs/ldb-plugin-design.md`：插件能力增强设计。
- `docs/ldb-future-optimization-design.md`：后续性能与可靠性专项评估。

## License

Apache License 2.0，见 `LICENSE`。
