# LDB 用户使用手册

[English](user-manual.en.md) | 中文

本文面向准备在应用中正式接入 `vexra-ldb` 的开发者和运维人员。第一次使用建议先阅读 [快速入门](quick-start.md)。

## 适用场景

LDB 是纯 Java 本地 LSM/LevelDB 风格 KV 存储，适合嵌入式、本地索引、边缘缓存、单机状态和测试工具持久化等场景。它不是分布式数据库，不提供跨进程事务、网络协议、SQL 查询或多副本一致性。

## 数据目录和文件

一个 LDB 数据库目录通常包含：

- `CURRENT`：当前 MANIFEST 指针。
- `MANIFEST-*`：版本编辑日志。
- `*.log`：WAL 文件。
- `*.ldb`：SSTable 文件。
- `LOCK`：进程内互斥打开保护。
- `INFO_LOG`：运行日志。
- `COLUMN-FAMILIES.json`：列族注册信息。

同一数据目录同一时刻只应由一个写实例打开。诊断命令会尽量使用只读方式，但 repair、restore、backup 和 checkpoint 都可能产生文件系统副作用。

## 基本 API

核心入口：

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

常用操作：

- `put/get/delete`：基础 KV 读写。
- `write(LdbWriteBatch, WriteOptions)`：批量写。
- `addLong`：原子计数累加。
- `newSnapshotCursor`：快照遍历。
- `compactRange`：手动压缩。
- `checkpoint`：生成数据库副本。
- `getProperty`：读取诊断属性。

`LDB`、`LdbWriteBatch` 和 `SnapshotCursor` 都应在使用后关闭。

## Options 配置

常用配置：

| 配置 | 作用 | 建议 |
| --- | --- | --- |
| `createIfMissing` | 不存在时创建数据库 | 首次启动为 `true`，生产恢复场景可显式设为 `false` |
| `errorIfExists` | 已存在时打开失败 | 初始化工具可用，普通服务不建议开启 |
| `readOnly` | 只读打开 | 诊断和巡检推荐 |
| `verifyChecksums` | 读取时校验 checksum | 生产建议开启 |
| `verifyOnOpen` | 打开时做完整校验 | 发布前、恢复后、可疑损坏时开启 |
| `writeBufferSize` | MemTable 大小 | 写入吞吐和内存之间折中 |
| `cacheBlocks` / `blockCacheSize` | 块缓存策略 | 读多场景开启并调大 |
| `forceLogOnClose` / `forceSstOnFlush` | 关闭或 flush 时强制落盘 | 对可靠性敏感时评估开启 |
| `level0*Trigger` | L0 compaction、slowdown、stop write 阈值 | 高写入场景需配合监控调优 |
| `compactionRateLimitBytesPerSecond` | compaction 限速 | 避免后台 IO 挤占业务 |
| `checkpointCopyRateLimitBytesPerSecond` | checkpoint 复制限速 | 硬链接失败或跨文件系统复制时限制带宽 |
| `groupCommitEnabled` | 同步写合并提交 | 同步写多时建议灰度开启 |

插件相关配置见 [插件文档入口](ldb-plugin-docs-index.md)。

## WriteOptions 和 ReadOptions

`WriteOptions`：

- `sync(true)`：写入返回前要求 WAL 同步落盘。
- `snapshot(true)`：写入后返回写入完成后的 `Snapshot`。

`ReadOptions`：

- `verifyChecksums(true)`：读取时校验数据块。
- `fillCache(false)`：扫描或后台任务读取时避免污染块缓存。
- `snapshot(snapshot)`：使用固定快照读。

示例：

```java
db.put(key, value, new WriteOptions().sync(true));
byte[] value = db.get(key, new ReadOptions().fillCache(false));
```

## 列族生命周期

默认列族是 `LdbColumnFamily.DEFAULT`，id 为 `1`，名称为 `default`。

打开前静态声明：

```java
Options options = new Options()
    .createIfMissing(true)
    .addColumnFamily(metrics);
```

运行时管理：

```java
List<LdbColumnFamily> families = db.listColumnFamilies();
LdbColumnFamily created = db.createColumnFamily(100, "metrics");
LdbColumnFamily renamed = db.renameColumnFamily(created, "metrics_v2");
db.dropColumnFamily(renamed);
```

约束：

- 列族 id 必须稳定，不能和已有列族冲突。
- 默认列族不能删除。
- 当前最小实现只允许直接删除空列族。
- 非空列族 drop/rename 依赖 tombstone 语义，相关边界见 [列族 tombstone 设计](ldb-column-family-tombstone-design.md)。
- 迁移前应先备份，并记录列族 id/name 映射。

## SnapshotCursor 和遍历

`SnapshotCursor` 固定打开时刻的读视图，适合一致性遍历和范围扫描。

```java
try (SnapshotCursor cursor = db.newSnapshotCursor()) {
  cursor.seekToFirst();
  while (cursor.isValid()) {
    consume(cursor.key(), cursor.value());
    cursor.next();
  }
}
```

注意事项：

- 长生命周期 cursor 会延迟旧版本资源释放，长扫描应分批执行。
- 范围扫描需要业务自行判断结束边界。
- 大范围扫描建议使用 `ReadOptions#fillCache(false)` 的读取模式，避免污染热点缓存；cursor API 当前没有单独的 fill-cache 参数。

## Compaction 和写入背压

LDB 会自动后台 compaction，也支持手动触发：

```java
db.compactRange(null, null);
db.compactRange(cf, beginKey, endKey);
```

诊断属性：

- `ldb.compactionStats`
- `ldb.writeStallStats`
- `ldb.fileCounts`
- `ldb.fileBytes`
- `ldb.totalBytes`

如果出现写入抖动，先查看 `ldb.writeStallStats` 和 L0 文件数量，再考虑调大 `writeBufferSize`、调整 L0 阈值或设置 compaction 限速。

## 备份、恢复和 checkpoint

推荐策略：

1. 先执行只读 `check` 或 `properties`。
2. 创建全量或增量备份。
3. 对备份执行 `check-backup`。
4. 定期做恢复演练。

命令：

```bash
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check data/app.ldb
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool backup data/app.ldb backups
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool incremental-backup data/app.ldb backups
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check-backup backups/backup-000001
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool restore backups/backup-000001 restored.ldb
```

`checkpoint` 适合生成本地一致性副本。实现会在临时目录中构建，校验通过后再发布到目标目录；失败时会清理临时目录，成功报告会包含复制、硬链接、字节数和耗时统计。

```bash
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool checkpoint data/app.ldb checkpoints/app-001
```

目标目录必须不存在或为空。大库或跨文件系统场景下，如果 SST 硬链接失败会退化为文件复制，可通过 `Options#checkpointCopyRateLimitBytesPerSecond(long)` 限制复制带宽。

完整运维流程见 [运维 Runbook](operations.md)。

## Check 和 Repair

只读诊断：

```bash
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool check data/app.ldb
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool repair-plan data/app.ldb
```

执行 repair 前必须：

- 停止业务写入。
- 保存原始目录副本或已有备份。
- 归档 `check`、`repair-plan` 输出和日志。
- 在恢复副本上验证结果，再决定是否替换生产目录。

执行 repair：

```bash
java -cp build/libs/vexra-ldb-0.5.0-SNAPSHOT.jar net.xdob.vexra.ldb.tool.LdbTool repair data/app.ldb
```

## 插件

`LdbPlugin` 可以挂接 open、write、checkpoint、close 等生命周期，用于审计、观测和内部扩展。

常用建议：

- 插件默认视为可信进程内扩展。
- 生产插件应开启能力声明和超时策略。
- 异步插件适合非关键路径观测，但需要关注队列容量和关闭超时。
- 外部插件目录使用托管 classloader 隔离依赖，不等同于跨进程安全沙箱。

入口文档：

- [插件文档索引](ldb-plugin-docs-index.md)
- [插件开发指南](ldb-plugin-developer-guide.md)
- [插件隔离和异步设计](ldb-plugin-isolation-async-design.md)

## Longrun 和发布前验证

`ldb-longrun` 是独立长压测工具。常用命令：

```bash
./gradlew :ldb-longrun:productionGateLongRun -Pldb.longrun.durationMinutes=30
./gradlew :ldb-longrun:releaseSoakTest -Pldb.longrun.durationMinutes=1440
```

发行包内：

```bash
./bin/longrun watch -c config/production-gate.properties
./bin/longrun status -c config/production-gate.properties
./bin/longrun logs -c config/production-gate.properties
```

通过标准：

- 日志包含 `SUMMARY status=PASS`。
- 日志包含 `PASS production-gate`。
- 最终校验阶段有 `FINAL PROGRESS phase=verify` 进度，不应误判为卡死。
- 报告目录包含 `summary.md`、`summary.properties`、`summary.json` 和 `operations.csv`。

## 升级建议

升级前：

1. 阅读 `CHANGELOG.md` 和 `docs/release.md`。
2. 对生产库执行 `check`。
3. 创建并校验备份。
4. 用新版本只读打开副本。
5. 运行 production-gate 或更长 soak。

升级后：

- 保留旧版本 jar、备份和报告。
- 观察 `ldb.api.compatibility`、`ldb.operationStats`、`ldb.compactionStats` 和 `ldb.writeStallStats`。
- 出现异常时先停止写入，再按 [运维 Runbook](operations.md) 处理。

## 常见问题

**进程停在 `FINAL phase=verify` 是否卡死？**

不一定。最终 active key 校验会输出 `FINAL PROGRESS phase=verify`。如果 `verified` 仍在增长，说明正在校验；如果长时间没有 CPU、IO 和日志变化，再抓线程栈排查。

**为什么同步写吞吐比异步写低？**

`sync(true)` 会等待 WAL 同步落盘。可以评估 group commit，但必须结合延迟目标灰度。

**能否多个进程同时写一个目录？**

不能。同一个数据库目录只支持单写实例。

**能否直接删除非空列族？**

当前直接 drop 只支持空列族。非空列族的 tombstone 生命周期已有设计和部分实现边界，正式迁移前应先备份并验证。

**repair 可以直接在生产目录执行吗？**

不建议。先停止写入、备份原目录、生成 repair-plan，并优先在副本上验证。

## 相关文档

- [快速入门](quick-start.md)
- [运维 Runbook](operations.md)
- [发布流程](release.md)
- [项目设计](ldb-project-design.md)
- [生产级发布规划](ldb-production-readiness-plan.md)
- [对外承诺和验收边界](vexra-ldb-external-commitment.md)
