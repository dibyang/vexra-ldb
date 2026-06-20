# LDB 运维 Runbook

[English](operations.en.md) | 中文

本文档面向 `vexra-ldb` 的生产前验证和日常运维。所有会写文件的命令都应先在副本、checkpoint 或备份上演练；真实故障场景优先保留现场，再做 repair。

## 基本原则

- 先停止继续写入，再做 checkpoint、backup、check 或 repair。
- `check`、`properties`、`check-backup` 和 `repair-plan` 是只读诊断入口。
- `repair`、`backup`、`incremental-backup`、`restore` 和 `checkpoint` 会产生文件副作用，执行前确认目录、权限和剩余磁盘空间。
- 备份和恢复必须保留 `BACKUP-REPORT.json`、`RESTORE-REPORT.json`、`BACKUP-MANIFEST.json`、`OBJECT-REFS.json` 和 longrun/release gate 报告。
- 涉及旧版本升级、列族 tombstone、对象仓库损坏或 longrun 失败时，不要删除失败样本。

## 发布前门禁

正式发布前至少执行：

```powershell
.\gradlew.bat releaseGate
.\gradlew.bat clean publishToMavenLocal
```

`releaseGate` 会聚合：

- 常规单元测试。
- `0.4.0` 旧版本升级兼容样本。
- production-gate longrun profile。
- `storageFormatGates` 文件格式专项门禁，覆盖 SST/table properties、兼容策略、future/malformed 版本 fail-fast、check/repair/backup/plugin 证据和默认 v1 写入策略。
- `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json` 和 Markdown 摘要。

正式候选版本还应补充更长时间的 production-gate 或 soak：

```powershell
.\gradlew.bat :ldb-longrun:productionGateLongRun "-Pldb.longrun.durationMinutes=30"
.\gradlew.bat :ldb-longrun:releaseSoakTest "-Pldb.longrun.durationMinutes=1440"
```

## 升级流程

1. 在旧版本停止写入后创建备份或 checkpoint。
2. 用新版本对副本执行 `check`。
3. 用新版本打开副本并验证关键 key、列族、snapshot cursor 和业务读路径。
4. 对副本执行 backup/restore 闭环。
5. 归档 `ldb.tableFormat`、`ldb.storageFormat`、`CheckReport.storageFormat` 和 `RepairReport.storageFormat`，确认旧 SST 被识别为 v1 legacy，v2 SST 只来自显式 opt-in。
6. 通过后再切换真实库；失败时保留旧库副本、`RELEASE-GATE-REPORT.json` 和 check 报告。

旧版本兼容硬门禁由 `LdbUpgradeCompatibilityTest` 覆盖。若本地缺少 `vexra-ldb-0.4.0.jar`，先把正式 0.4.0 发布产物安装到本地 Maven 缓存。

## 备份

全量备份：

```text
ldb backup <db> <backupRoot>
```

增量备份：

```text
ldb incremental-backup <db> <backupRoot>
```

备份后必须执行：

```text
ldb check-backup <backupDir>
```

检查点：

```text
ldb checkpoint <db> <targetDir>
```

注意事项：

- `backupRoot` 应位于独立磁盘或独立挂载点，避免和源库同时损坏。
- 增量备份会维护 `objects/` 和 `OBJECT-REFS.json`；清理前先执行 `planPurgeBackups` 或对应 dry-run 流程。
- 对象仓库出现缺失对象、错误引用计数、孤儿对象或坏 manifest 时，`check-backup` 必须失败，不能继续 restore。

## 恢复

恢复到空目录：

```text
ldb restore <backupDir> <targetDir>
```

恢复后验证：

```text
ldb check <targetDir>
ldb properties <targetDir>
```

恢复失败时：

- 不要手动补写半成品目录。
- 保留 `RESTORE-REPORT.json`、备份目录和目标目录状态。
- 如果目标目录未创建，说明 restore 在校验阶段 fail-fast，优先修复备份源。

## 检查与修复

只读检查：

```text
ldb check <db>
ldb properties <db> ldb.tableFormat ldb.storageFormat
ldb properties <db> [property...]
ldb scan <db> [limit]
ldb repair-plan <db>
```

`scan` 只读取默认列族，输出按 key 排序的 JSON 样本；默认 limit 为 100，key/value 使用 base64，适合事故排查时确认少量数据形态。

修复：

```text
ldb repair <db>
```

建议流程：

1. 停止写入并保留原目录快照。
2. 执行 `check` 和 `repair-plan`。
3. 归档 `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables`，用于解释 mixed-format 或未来格式 fail-fast。
4. 复制原库目录到隔离目录。
5. 在隔离目录执行 `repair`。
6. 打开修复目录验证业务关键数据。
7. 验证通过后再替换真实库或通过业务迁移切换。

## 列族 tombstone

- drop 后的 cfId 不能复用，即使该列族数据已物理清理。
- `COLUMN-FAMILIES` 中的 dropped 记录是恢复历史语义的一部分，不能手工删除。
- backup、restore、check 和 repair 必须保留并理解 dropped 记录，避免历史 MANIFEST/WAL 被误判为未知列族。
- 如果看到 `Unknown column family id`，先确认 `COLUMN-FAMILIES` 是否丢失或被编辑。

## 故障处置顺序

| 场景 | 优先动作 | 禁止动作 |
| --- | --- | --- |
| 打开失败 | 停写，复制目录，执行 `check` | 直接在原目录反复 repair |
| 备份校验失败 | 保留备份根，执行 `check-backup`，核对 `OBJECT-REFS.json` | 删除 `objects/` 或手工改引用计数 |
| 恢复失败 | 保留 `RESTORE-REPORT.json`，换空目录重试 | 覆盖已有目标目录 |
| 文件格式异常 | 归档 `ldb.tableFormat`、`ldb.storageFormat`、check/repair storage format 字段 | 关闭 `failOnUnknownTableFeature` 后直接生产写入 |
| longrun 失败 | 归档 workDir、`report/` 和日志 | 清理失败 workDir 后再排查 |
| 旧版本升级失败 | 保留旧库副本和 release gate 报告 | 直接用新版本写入旧库 |

## 需要归档的证据

- `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json`
- `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.md`
- `ldb-longrun/build/reports/ldb-longrun/*/report/`
- `BACKUP-REPORT.json`
- `RESTORE-REPORT.json`
- `BACKUP-MANIFEST.json`
- `OBJECT-REFS.json`
- `REPAIR-REPORT.json` 或 `repair-plan` 输出
- `ldb.tableFormat` 和 `ldb.storageFormat`
- `CheckReport.storageFormat`、`tableFormats`、`legacyTables`、`v2Tables`、`incompatibleTables`
- `RepairReport.storageFormat`、`tableFormats`、`legacyTables`、`v2Tables`、`incompatibleTables`
## 0.9.0-SNAPSHOT SF-06：v2 文件格式生产化检查

生产启用 v2 写入前，必须归档 `ldb.tableFormatPolicy`、`ldb.tableFormat` 和 `ldb.storageFormat`。启用前应看到 `productionState=default-legacy`；启用后应看到 `productionState=explicit-v2` 与 `newWrites=v2-properties`。回滚新写入时恢复 `Options.tableFormatVersion(1)`，并再次归档 `newWrites=v1`。`failOnUnknownTableFeature=false` 只允许 diagnostic-only 排查，不允许作为生产回滚策略。

## readrandom / Bloom filter 发布前检查

如果本次发布面向随机读 miss 优化，必须在启用 `BloomFilterPolicy` 的压测或门禁用例后归档 `ldb.sstReadStats`。期望至少看到 `mayContainRequests>0`；对范围内缺失 key 的测试应看到 `mayContainFalse>0` 与 `filterSkips>0`。若 `filterSkips` 长期为 0，需要确认数据是否没有落入 SST 范围、是否未配置 filter policy、或 SST 是否由旧配置写入。
