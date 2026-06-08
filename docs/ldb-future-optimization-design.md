# LDB 后续性能与可靠性专项评估

[English](ldb-future-optimization-design.en.md) | 中文

## 背景

当前 LDB 已完成 benchmark/soak 入口、compaction 压力回归、range tombstone 读取路径优化、write stall 降速配置，以及 checkpoint、backup/restore、repair 的恢复闭环测试。下一批高收益方向集中在写入聚合、备份增量化和 range delete 完整语义三个区域。这些能力都会影响 WAL、SST、MANIFEST、恢复、压缩或运维报告，必须先明确边界，再分阶段实现。

## 目标

- 给出 group commit、增量备份、range delete 后续落地的接口、数据结构、状态和测试门禁。
- 明确哪些能力可以在不改变磁盘格式的情况下先推进，哪些必须作为格式变更单独评审。
- 保护现有调用方：默认行为保持兼容，新能力必须可观测、可关闭、可回滚。

## 非目标

- 本文档记录 group commit、增量备份和 range delete 后续工作的设计边界；其中 group commit 最小实现、增量备份最小实现、compaction 长压测入口和 benchmark 报告输出已在 `0.4.0` 落地。
- 不引入 RocksDB JNI、RocksJava 或外部存储服务。
- 不改变现有 `LDBFactory.createBackup/restoreBackup`、`LDB#checkpoint` 和 `LdbWriteBatch.deleteRange` 的当前兼容行为。

## 现状/已有流程

| 方向 | 当前能力 | 主要缺口 |
| --- | --- | --- |
| 写入路径 | 单 writer 顺序写 WAL，再应用 MemTable；已有 write stall 计数、可配置 slowdown delay 和默认关闭的 group commit 最小实现 | Group commit 仍按请求分别写 WAL record，尚未把多个请求编码成单条 WAL record，也未形成长期吞吐/尾延迟基线 |
| 备份 | 离线全量 backup/restore，临时目录发布，`BACKUP-REPORT.json`、`RESTORE-REPORT.json`、清理旧版本、`BACKUP-MANIFEST.json` 和可独立恢复的增量备份目录 | 增量备份当前只复用同名同长度 SST 文件，硬链接失败会回退复制；尚未实现共享对象仓库、引用计数和增量链清理 |
| Range Delete | API、WAL/SST/MemTable/read/compaction 已具备基础语义，读路径已避免无谓全表 tombstone 扫描 | 仍缺少格式版本边界、旧版本兼容策略、更长 snapshot/compaction/repair 矩阵和降级规则 |

## 核心约束

- 保持 JDK8 兼容。
- 默认配置必须保持现有行为，不让老调用方在升级后自动承担新的延迟、格式或清理风险。
- 任何磁盘格式变更都必须新增兼容性测试：新版本读旧库、旧版本面对新格式的明确失败或只读降级、repair/check/backup 报告可解释。
- 写入提交语义必须保持：WAL 成功并应用 MemTable 后才对调用方返回成功；`WriteOptions.sync(true)` 不能被静默降级。
- 备份发布必须继续使用临时目录，未完成版本不得被 `purgeOldBackups` 识别为可恢复版本。

## 接口设计

### Group Commit

| 接口/属性 | 建议 | 说明 |
| --- | --- | --- |
| `Options.groupCommitEnabled(boolean)` | 默认 `false` | 灰度开关，保持现有单 writer 行为 |
| `Options.groupCommitMaxBatchBytes(long)` | 默认 1 MiB | 限制单次聚合大小，避免过大的组提交窗口 |
| `Options.groupCommitMaxDelayNanos(long)` | 默认 200 微秒 | 控制等待窗口，超过后立即提交 |
| `ldb.groupCommitStats` | 已新增 property | 暴露 enabled、maxDelayNanos、maxBatchBytes、groups、requests、syncGroups 和 waitNanos |

### 增量备份

| 接口/属性 | 建议 | 说明 |
| --- | --- | --- |
| `LDBFactory.createIncrementalBackup(File sourceDir, File backupRoot, Options options)` | 已新增可选 API | 不改变现有全量备份入口；当前发布完整可恢复目录 |
| `LDBFactory.restoreBackup(File backupDir, File targetDir, Options options)` | 复用 | restore 可直接恢复全量或增量发布目录 |
| `LDBFactory.checkBackup(File backupDir, Options options)` | 已新增校验入口 | 独立校验备份目录，避免 restore 时才发现损坏 |
| `BACKUP-MANIFEST.json` | 已新增备份元数据 | 记录版本、备份 id、父备份、复制文件、复用文件和发布状态 |

### Range Delete 完整落地

| 接口/属性 | 建议 | 说明 |
| --- | --- | --- |
| `LdbWriteBatch.deleteRange` | 保持现有入口 | 后续只强化语义和测试，不新增重复 API |
| `ldb.rangeDeleteStats` | 新 property | 暴露 MemTable/SST tombstone 数、读路径过滤次数、compaction 合并次数 |
| `ldb.api.optionValues` | 扩展字段 | 暴露是否启用格式版本、range tombstone 兼容策略 |

## 数据结构

### Group Commit 内存结构

| 字段 | 含义 |
| --- | --- |
| `WriterRequest` | 单个调用方提交的 batch、write options、完成状态和异常 |
| `CommitGroup` | 同一轮 WAL append/sync 的请求集合 |
| `firstSequence`/`lastSequence` | 聚合后全局 sequence 区间 |
| `requiresSync` | 只要组内任一请求要求 sync，则本轮 WAL 必须 sync |

### 增量备份元数据

| 字段 | 含义 |
| --- | --- |
| `formatVersion` | 备份元数据版本 |
| `backupId` | 单调递增备份编号 |
| `parentBackupId` | 增量备份基线；全量备份为空 |
| `files[]` | 文件名、类型、长度、校验值、引用计数、来源备份 |
| `sourceCheck` | 备份源库 check 摘要 |
| `published` | 只有发布成功后才置为 `true` |

### Range Delete 元数据

| 字段 | 含义 |
| --- | --- |
| `beginUserKey`/`endUserKey` | 左闭右开的删除范围 |
| `sequence` | tombstone 可见性序列号 |
| `cfId` | 列族 id |
| `formatVersion` | SST/WAL 持久化版本 |

## 状态机

### Group Commit

`IDLE -> COLLECTING -> WRITING_WAL -> SYNCING -> APPLYING_MEMTABLE -> COMPLETING -> IDLE`

- `COLLECTING` 只在持有 writer 队列所有权时等待短窗口。
- `WRITING_WAL` 后失败时，整组失败且不能应用 MemTable。
- `SYNCING` 失败时，整组失败；后续恢复必须依赖 WAL 实际落盘结果。
- `APPLYING_MEMTABLE` 完成后才能逐个唤醒调用方。

### 增量备份

`PLANNING -> COPYING_SHARED_FILES -> WRITING_METADATA -> VERIFYING -> PUBLISHING -> PUBLISHED`

失败状态统一进入 `FAILED_TEMP`，保留临时目录诊断但不发布为 `backup-000001` 风格目录。

### Range Delete

`BATCHED -> WAL_DURABLE -> MEMTABLE_VISIBLE -> SST_DURABLE -> COMPACTION_MERGED -> OBSOLETE`

任一阶段都必须保持 snapshot sequence 可解释，不能让新 tombstone 覆盖旧 snapshot 应看到的数据。

## 时序流程

### Group Commit 写入

1. 调用方构造 batch 并进入 writer 队列。
2. 队首 writer 在 `groupCommitMaxDelayNanos` 或大小阈值内收集后续请求。
3. 为整组分配连续 sequence 区间。
4. 以请求顺序写入 WAL；若任一请求需要 sync，则本轮执行 sync。
5. 按相同顺序应用 MemTable。
6. 分别完成每个请求的 future/等待状态，并记录统计。

### 增量备份

1. 对源库执行 `check`，失败则不创建新备份。
2. 读取上一发布备份的 `BACKUP-MANIFEST.json`。
3. 对仍可复用的同名同长度 SST 文件优先建立硬链接；硬链接失败或非 SST 文件则复制到临时目录。
4. 写入本次备份 manifest 和报告。
5. 对完整备份视图执行 `checkBackup`/离线 check 校验。
6. 原子发布备份目录，更新备份根索引。

### Range Delete

1. `deleteRange` 写入 WAL，携带列族、begin/end 和 sequence。
2. MemTable 保存 tombstone 索引，point get 和 iterator 按 snapshot sequence 判断遮蔽。
3. flush 写出 SST range tombstone block。
4. compaction 合并 tombstone 和被覆盖 key，保留仍可能被 snapshot 读取的边界。
5. check/repair/backup 识别并报告 range tombstone 文件统计。

## 异常处理

| 场景 | 行为 |
| --- | --- |
| Group commit WAL 写失败 | 整组失败，不应用 MemTable，调用方收到同一 cause |
| Group commit sync 失败 | 整组失败，记录 `syncFailures`，重开后按 WAL 实际内容恢复 |
| 增量备份源库 check 失败 | 不发布版本，报告 `ok=false` |
| 增量备份共享文件复用失败 | 禁止复用并降级复制；仍失败则本次备份失败 |
| Restore 缺少父备份 | 当前发布目录是完整视图，可独立恢复；未来共享对象仓库模式需失败并报告缺失链路 |
| Range tombstone 格式不认识 | 新版本按兼容策略处理；旧版本必须明确失败或只读拒绝，不能静默忽略 |

## 幂等性

- Group commit 对调用方不可重试内部 WAL append；失败后由调用方基于业务幂等重试。
- 增量备份以 `backupId` 和临时目录名隔离，重复执行会生成新备份号，不覆盖已发布版本。
- Restore 目标目录必须不存在或为空，失败后不留下可打开的半成品库。
- Range delete 以 sequence 决定可见性，重复恢复同一 WAL record 不允许产生额外 sequence。

## 回滚策略

- Group commit 默认关闭；如果指标异常，回滚到单 writer 路径即可，不涉及磁盘迁移。
- 增量备份必须保留全量备份入口；如共享文件元数据异常，可停止创建增量版本并继续使用已有全量备份恢复。
- Range delete 的格式变更必须有独立 `formatVersion`。一旦上线后需要回滚，应先停止写入新 tombstone，保留新版本 repair/check 工具处理已生成文件。

## 兼容性

| 能力 | 旧数据 | 新数据 | 旧版本行为 |
| --- | --- | --- | --- |
| Group commit | 兼容 | WAL record 仍沿用现有格式 | 可读 |
| 增量备份 | 全量备份仍可恢复 | 新增备份元数据，不改变 DB 数据文件格式；发布目录保持完整可恢复 | 旧版本备份工具可按普通目录恢复数据，但不理解增量复用元数据 |
| Range delete | 旧库可读 | 可能引入新 SST/WAL range tombstone 格式 | 必须明确失败或拒绝打开 |

## 灰度/迁移

1. 先上线观测属性和压力测试，不启用新行为。
2. Group commit 在测试环境以小等待窗口灰度，观察 p99 写延迟、sync 次数和吞吐。
3. 增量备份已支持“完整目录 + manifest + SST 硬链接复用”，后续再启用共享对象仓库和引用计数清理。
4. Range delete 先补格式兼容测试和 repair/check 报告，再启用写入新格式。

## 测试方案

| 方向 | 必测项 |
| --- | --- |
| Group commit | 已覆盖并发写、sync/non-sync 组合、reopen 后可恢复和统计属性；后续补 WAL 注入失败、MemTable 应用失败和更长尾延迟报告 |
| 增量备份 | 已覆盖首次增量、连续增量、SST 复用、restore 后 reopen、checkBackup；后续补父备份缺失、共享文件损坏和引用清理 |
| Range delete | 跨 MemTable/SST/level 删除、snapshot 旧视图、crash recovery、repair/check/backup、旧格式兼容、compaction 合并 |

## 风险点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Group commit 增加尾延迟 | 写入 p99 变差 | 默认关闭，限制等待窗口，暴露统计 |
| sync 语义被误合并 | 数据可靠性下降 | 组内任一 sync 都触发整组 sync，测试覆盖 |
| 增量备份引用计数错误 | restore 缺文件或误删 | manifest 校验、清理前 dry-run 报告、失败不发布 |
| Range tombstone 旧版本静默忽略 | 数据被错误读出 | 格式版本和启动校验必须明确失败 |
| compaction 过早丢弃 tombstone | snapshot 或恢复读错 | snapshot 矩阵和长生命周期测试作为合入门禁 |

## 分阶段实施计划

| 阶段 | 交付物 | 验收 |
| --- | --- | --- |
| 1 | Group commit 观测属性和开关，默认关闭 | 已完成：单 writer 行为不变，新增 property 可观测 |
| 2 | Group commit 最小实现 | 已完成：并发写、sync、reopen 和统计测试通过 |
| 3 | 增量备份 manifest 与 checkBackup | 已完成：全量备份兼容，manifest 校验可解释 |
| 4 | 共享 SST 增量备份最小实现 | 已完成：连续增量、SST 复用、restore 和 checkBackup 通过；引用计数清理仍是后续工作 |
| 5 | Range delete 格式兼容评审与测试矩阵 | 新旧格式边界、repair/check/backup 文档和测试齐备 |
| 6 | Range delete 完整压缩与恢复强化 | snapshot、compaction、crash、repair 全矩阵通过 |

