# LDB Backup Engine 引用计数设计

[English](ldb-backup-engine-design.en.md) | 中文

## 背景

LDB 当前已支持 checkpoint、全量 backup/restore、完整目录形式的 incremental backup、`BACKUP-MANIFEST.json`、`checkBackup`、旧备份清理、共享对象仓库、`OBJECT-REFS.json` 和清理 dry-run。本文记录对象仓库和引用计数的生产约束；与 RocksDB BackupEngine 相比，后续差距主要在长备份链压测、跨文件系统性能、低磁盘故障矩阵和长期仓库维护工具。

## 目标

- 固化共享对象仓库，避免每个备份目录都持有完整文件副本。
- 固化引用计数和备份链 GC，安全清理不再被任何备份引用的对象。
- 保持现有 `createBackup`、`restoreBackup` 和完整目录备份兼容。
- 为 `purgeOldBackups` 保留 dry-run 计划和可解释报告，并把长链压测作为后续验收。

## 非目标

- 不要求与 RocksDB BackupEngine 目录格式兼容。
- 不引入远程对象存储；对象仓库仍位于本地 backup root。
- 不改变数据库自身 WAL/SST/MANIFEST 格式。

## 现状/已有流程

| 能力 | 当前状态 |
| --- | --- |
| 全量备份 | 复制完整 DB 文件到 `backup-000001` |
| 增量备份 | 发布完整可恢复目录，复用共享对象仓库中的 SST/WAL/meta 对象 |
| 校验 | `checkBackup` 对备份目录执行离线 check，并在 `CheckReport.checkedFiles` 中归档备份 manifest、对象引用文件和已校验对象文件 |
| 清理 | `planPurgeBackups(root, keepLast)` 生成 dry-run，`purgeOldBackups(root, keepLast)` 执行清理 |
| 报告 | `BackupReport`、`BACKUP-MANIFEST.json`、`RESTORE-REPORT.json`、`OBJECT-REFS.json` |

## 核心约束

- 任何已发布备份必须可恢复；未完成临时目录不得被识别为备份。
- 清理必须先有 dry-run 计划，列出将删除和保留的对象。
- 对象引用计数必须能从 manifest 重建，不能只依赖单独计数文件。
- restore 必须先校验对象完整性，再创建目标 DB 目录。
- Windows 上硬链接失败要回退复制，不能影响备份语义。

## 接口设计

| API/命令 | 语义 |
| --- | --- |
| `createIncrementalBackup(source, root, options)` | 继续保留；新模式写对象仓库和备份 manifest |
| `restoreBackup(backup, target, options)` | 可从完整目录或对象仓库视图恢复 |
| `checkBackup(backup, options)` | 校验 manifest、对象引用和内容 checksum，报告中列出已校验备份元数据和对象文件 |
| `planPurgeBackups(root, keepLast)` | 新增 dry-run，输出将删除的备份和对象 |
| `purgeOldBackups(root, keepLast)` | 基于 plan 执行删除，输出实际结果 |

## 数据结构

当前目录布局：

```text
backup-root/
  objects/
    sst/000001.sst
    wal/000123.log
    meta/MANIFEST-000456
  backups/
    backup-000001/BACKUP-MANIFEST.json
    backup-000002/BACKUP-MANIFEST.json
  refs/
    OBJECT-REFS.json
```

`BACKUP-MANIFEST.json` 核心字段：

| 字段 | 含义 |
| --- | --- |
| `formatVersion` | backup manifest 格式版本 |
| `backupId` | 单调递增备份 id |
| `parentBackupId` | 逻辑父备份，可为空 |
| `objects[]` | 对象 id、类型、长度、checksum、源文件名 |
| `databaseFiles[]` | restore 时目标文件名到 object id 的映射 |
| `sourceCheck` | 源库校验摘要 |
| `published` | 发布完成标记 |

`OBJECT-REFS.json` 是缓存视图，可从所有 published manifest 重建：

| 字段 | 含义 |
| --- | --- |
| `objectId` | 内容地址或稳定文件号派生 id |
| `refCount` | 被多少 published backup 引用 |
| `backups[]` | 引用该对象的 backup id |
| `bytes` | 对象大小 |

## 状态机

| 状态 | 触发 | 行为 |
| --- | --- | --- |
| `PLANNING` | 开始备份 | 扫描源库 live 文件和上一备份 manifest |
| `STAGING_OBJECTS` | 复制/硬链接对象 | 写到临时对象名，完成后 fsync |
| `WRITING_MANIFEST` | 对象准备完成 | 写临时 manifest |
| `VERIFYING` | manifest 完成 | 校验对象和可恢复视图 |
| `PUBLISHING` | 校验通过 | 原子发布 backup manifest |
| `PUBLISHED` | 发布完成 | 纳入 refs 重建 |
| `FAILED_TEMP` | 任意失败 | 临时文件保留诊断但不参与 restore |

## 时序流程

### 创建增量备份

1. 对源库执行 `check`，失败则不创建备份。
2. 冻结源文件集合，计算 object id 与 checksum。
3. 已存在对象直接复用；不存在对象复制或硬链接到临时对象。
4. 写 `BACKUP-MANIFEST.json.tmp`，记录 database file 到 object 的映射。
5. 构造临时可恢复视图并执行 `checkBackup`。
6. 发布 manifest，更新 refs 缓存。

### 清理旧备份

1. 枚举 published backup manifests。
2. 根据 `keepLast` 计算待删除 backup。
3. 从剩余 manifest 重建对象引用图。
4. dry-run 输出：待删 backup、待删 object、保留 object、预计释放字节。
5. 执行删除时先删除 backup manifest，再删除 refCount=0 对象。

## 异常处理

- manifest 损坏：该备份不可恢复，`checkBackup` 失败。
- 对象缺失：restore/checkBackup 失败，报告缺失 object id。
- refs 缓存损坏：从 published manifest 重建，不直接信任缓存。
- 删除对象失败：报告失败并保留 refs，下次清理可重试。

## 幂等性

- 相同源库重复创建备份会生成新 backup id，但对象可复用。
- `planPurgeBackups` 无副作用。
- `purgeOldBackups` 可重复执行；已删除对象不应导致失败，只记录 skipped。

## 回滚策略

- 继续保留当前完整目录备份格式读取能力。
- 新对象仓库模式通过 `formatVersion` 区分。
- 若对象仓库出现问题，可关闭新模式并退回完整目录备份。

## 兼容性

- 旧备份目录：按当前逻辑 restore/check。
- 新备份目录：需要新版本工具理解 object mapping。
- 旧工具面对新 manifest 应明确失败，不能把 object 仓库目录当成完整 DB。

## 测试方案

- 首次对象仓库备份、连续增量、对象复用。
- restore/checkBackup 校验缺失对象、损坏对象、坏 manifest。
- 清理 dry-run 与实际 purge 一致。
- refs 缓存删除后可重建。
- Windows 硬链接失败回退复制。

## 风险点

| 风险 | 缓解 |
| --- | --- |
| 引用计数错误导致误删 | refs 从 manifest 重建，删除前 dry-run |
| restore 依赖对象缺失 | restore 前强制 checkBackup |
| 对象 id 冲突 | 使用 checksum + length + type，必要时内容地址化 |

## 分阶段实施计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 1 | 增加对象仓库格式和 manifest 设计测试 | 已完成：新旧格式识别清晰 |
| 2 | 实现对象复用备份但不清理 | 已完成：连续增量 restore/check 通过 |
| 3 | 实现 refs 重建和 purge dry-run | 已完成：dry-run 报告可解释 |
| 4 | 实现安全 purge | 已完成：删除后剩余备份全部可恢复 |
| 5 | 压测大备份链 | 后续：长链 check/restore/purge 报告稳定 |
| 6 | 跨文件系统、低磁盘和权限故障矩阵 | 后续：失败不污染对象仓库，报告可定位 |
