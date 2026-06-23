# LDB 可靠性改进计划

[English](ldb-reliability-plan.en.md) | 中文

## 背景

当前 LDB 已具备基础 WAL、MemTable、SSTable、checkpoint、列族和插件化能力，但部分边界语义仍偏弱，尤其是未实现操作进入写路径、关闭阶段吞异常、手动暂停 compaction 无超时、Options 暴露可变内部状态等问题。

## 目标

- 补齐不会改变磁盘格式的可靠性护栏。
- 降低半写入、资源泄漏、调用方误用和排障困难风险。
- 为 ADB 继续基于 LDB 扩展提供更稳定的本地存储基础。

## 非目标

- 本阶段不实现真正的 range tombstone。
- 本阶段不调整 SSTable/WAL 持久化格式。
- 本阶段不重写 compaction 调度模型。

## 现状/已有流程

- 写路径当前先分配 sequence 并写 WAL，再把 batch 应用到 MemTable。
- `deleteRange` 已在 `LdbWriteBatch`、WAL、MemTable、SST、读路径、snapshot 和 compaction 中具备基线语义。
- `close()` 尝试关闭 WAL、VersionSet、TableCache、ColumnFamilyState、文件锁和插件，但多个异常路径被静默忽略。
- `suspendCompactions()` 通过单线程 compaction executor 排队暂停任务，但没有超时。

## 核心约束

- 保持 JDK8 兼容。
- 所有新增行为必须有单元测试。
- 对 ADB 使用 LDB 的兼容性优先。
- 插件 `afterWrite` 仍表示写入已提交后的通知，失败不会回滚已写入数据。
- 当前继续保持全局共享 WAL，不改为列族级 WAL；跨列族 batch 的原子性、全局 sequence 顺序和恢复路径简单性优先。列族级 WAL 仅作为后续 WAL 生命周期增强阶段的候选方案评估。

## 接口设计

- `deleteRange` 支持半开区间 range tombstone，当前采用保守 compaction 清理策略。
- `Options#getColumnFamilies` 返回只读快照，调用方必须通过 `addColumnFamily` 注册列族。
- `readOnly(true)` 支持真正只读打开，不创建 WAL/MANIFEST 并拒绝写入、compact 和 checkpoint。
- `getProperty` 返回数据库目录、最后序列号、列族、后台异常、pending output、WAL/compaction/backup/checkpoint/API 兼容等诊断信息。

## 数据结构

- 后续阶段默认不新增磁盘结构；range tombstone、列族 tombstone、备份对象仓库等已落地格式必须通过兼容性和旧版本 fail-fast 证据维护。
- 新增内存级写批次校验，用于识别未支持操作。
- 新增关闭异常聚合列表，用于保留资源关闭失败的 cause。
- 新增 compaction 暂停超时配置。

## 状态机

`OPENING -> OPEN -> CLOSING -> CLOSED`

进入 `CLOSING` 后停止接受新压缩任务，等待已有后台任务退出，再依次关闭 WAL、VersionSet、TableCache、ColumnFamilyState、文件锁和插件。

## 时序流程

1. 写入前校验 batch 是否包含未支持操作。
2. 调用插件 `beforeWrite`。
3. 获取写锁并分配 sequence。
4. 写 WAL 并按需 sync。
5. 应用 MemTable。
6. 调用插件 `afterWrite`。

## 异常处理

- 关闭阶段不再静默吞异常，记录资源名和数据库目录。
- 尝试释放所有资源后，如存在失败则抛出带 cause 的 `DBException`。
- compaction 暂停超时或 executor 已关闭时抛出 `DBException`。

## 幂等性

- `close()` 保持幂等。
- `resumeCompactions()` 在未暂停时只记录警告，不允许计数变负。

## 回滚策略

本阶段没有磁盘格式变更。若新护栏影响调用方，可回滚对应代码和测试；已产生数据仍可由旧版本读取。

## 兼容性

- `deleteRange` 原先可能在运行时半失败，现在具备 range tombstone 语义；旧版本面对新格式必须 fail-fast 或按发布说明降级。
- `Options#getColumnFamilies` 从可变列表改为只读快照。
- `readOnly(true)` 原先配置无实际效果，现在是真正只读打开，避免巡检或工具误写目录。

## 灰度/迁移

无需数据迁移。建议先在 LDB 单元测试和 ADB LdbStore 测试中验证，再进入更大范围集成测试。

## 测试方案

- 覆盖 `deleteRange` range tombstone 读写、恢复、snapshot 和 compaction 语义。
- 覆盖只读打开不写目录并拒绝写接口。
- 覆盖属性查询。
- 覆盖列族快照不可变。
- 保留已有 WAL 恢复、checkpoint、插件生命周期和列族隔离回归测试。

## 风险点

- `deleteRange` 新格式可能暴露旧版本兼容或降级边界。
- 关闭阶段开始抛出异常后，调用方需正确处理 close 失败。
- compaction 暂停超时默认值需要后续通过压力测试校准。

## 分阶段实施计划

1. 第一阶段：完成上述护栏和测试。
2. 第二阶段：补充关闭超时、后台任务取消和文件系统失败日志，继续保持磁盘格式不变。
3. 第三阶段：增加压力测试和故障注入。

## 第二阶段补充

- 关闭流程：新增 close 超时配置，避免停机时无限等待后台 compaction；超时后取消后台任务并记录 `DBException`。
- 文件清理：旧 WAL/SST/TEMP 和失败 compaction 输出文件删除失败时记录文件路径和原因，便于定位权限、句柄占用或磁盘异常。
- 目录落盘：checkpoint 目录 `force` 失败保留兼容行为，但输出警告日志，避免静默丢失诊断信息。
- 写入校验：写 WAL 前统一校验 batch 内容，`deleteRange` 和非法 `addLong` delta 都必须提前失败，避免 WAL 与 MemTable 状态不一致。
- 插件边界：`beforeWrite` 后再次校验 batch，避免插件误改 batch 后绕过写前校验；`LdbWriteBatch#getColumnFamilies` 返回只读快照，避免外部修改内部 touched column-family 集合。
- API 边界：`write(LdbWriteBatch, WriteOptions)` 显式拒绝未知 batch 实现和空 options，避免调用方看到不带上下文的 `ClassCastException`。
- 诊断属性：扩展 `getProperty`，暴露当前 WAL 文件号、MANIFEST 文件号、总 MemTable 估算大小、每个列族 MemTable 估算大小和 level 文件数。
- 手动压缩：接通 `compactRange(byte[], byte[])` 的最小实现，仅覆盖 default CF；执行前 flush memtable，然后按 level 触发已有 manual compaction，用于后续排障和压缩验证。
- 测试补充：覆盖 close 超时配置校验、compaction 暂停超时配置校验、重复 resume 不破坏后续 suspend/resume，以及非法 `addLong` delta 不落盘、不推进 sequence。

## 第三阶段补充

- 恢复矩阵：新增独立恢复测试，覆盖 WAL 批量重放、flush/SST 持久化、manual compaction 后重启、checkpoint 与源库分叉恢复。
- 一致性断言：每个恢复用例同时校验默认列族、自定义列族、delete、addLong、sequence/property 和重启后的读取结果。
- 边界约束：当前恢复矩阵仍以正常 close 后 reopen 为主；真正进程级 crash、半写 WAL、损坏 MANIFEST、损坏 SST 进入后续故障注入阶段。
- 故障注入：补充文件级破坏测试。WAL 尾部截断应丢弃不完整尾记录并保留已完整 sync 的记录；MANIFEST 和 SST 损坏应在 open/read 阶段明确失败并保留 cause。

## 第四阶段补充

- SST 校验：启用已有 `verifyChecksums` 配置，在读取 data/index/meta/filter block 时按 `block contents + compression type` 重新计算 masked CRC32C，并与 `BlockTrailer` 中的值比较。
- 异常语义：校验失败必须抛出带 SST 文件名、block offset、block size、期望 CRC 和实际 CRC 的异常，避免静默返回损坏数据。
- 兼容性：不改变 SST 磁盘格式；老文件已写入 trailer CRC，可直接按现有格式校验。
- 测试补充：新增 SST block 内容损坏测试，覆盖 `verifyChecksums(true)` 显式失败，以及 `verifyChecksums(false)` 不做 CRC 校验但仍可能因格式损坏在后续解析阶段失败。

## 第五阶段补充

- 进程级 crash：新增子进程故障注入测试，子进程写入后不调用 `close()`，直接 `Runtime.halt`，父进程等待退出后重新打开数据库验证恢复结果。
- WAL 恢复：覆盖同步写入的 WAL batch，在进程强退后应通过 WAL replay 恢复默认列族、自定义列族和计数器。
- SST 恢复：覆盖 flush/compact 后已写入 MANIFEST 的 SST，在进程强退后应通过 MANIFEST/SST 读取恢复数据。
- 边界约束：测试只断言已完成 sync 或已完成 flush/compact 的数据，不断言未 sync 尾部写入是否可见，避免依赖操作系统缓存行为。

## 当前安全与可靠性增量

- 表缓存句柄治理：`TableCache` 淘汰 SST table 时同步执行 table closer，并在 `evict` 后主动触发 cache cleanup，降低 Windows 等平台上旧 SST 因延迟释放文件句柄而删除失败的概率。
- 后台线程故障可观测性：compaction 线程未捕获异常不再输出到 stdout/stderr，而是写入 `backgroundException`、更新 compaction 失败统计、记录结构化日志并唤醒等待线程。
- 文件系统失败证据：新增 `ldb.fileSystemStats`、`ldb.directoryForceFailureCount`、`ldb.fileDeleteFailureCount`、`ldb.lastDirectoryForceFailure` 和 `ldb.lastFileDeleteFailure`，把目录 force best-effort 失败和旧文件删除失败纳入可查询诊断面。
- Windows 目录 force 兼容：对 Windows/JDK 不支持按文件通道打开目录导致的 `AccessDeniedException`，继续记录到 `ldb.fileSystemStats`，但默认日志降为 DEBUG，避免 benchmark 与发布门禁被已知平台噪声刷屏。
- 发布证据归档：`ldb-longrun` 的 `summary.json`、`summary.properties` 和 `properties-after.json` 已归档上述文件系统诊断字段；`ldb properties` 默认输出也包含 `ldb.fileSystemStats`。
- 兼容性边界：本增量不改变 WAL/SST/MANIFEST 磁盘格式，不改变公开 API；若同步关闭暴露 table closer 失败，只记录警告并继续释放其他资源。

## RocksDB 差距与后继开发计划

### 差距清单

| 领域 | 当前 LDB 状态 | 与 RocksDB 的主要差距 | 优先级 |
| --- | --- | --- | --- |
| 恢复与修复 | 已覆盖 WAL/SST 正常恢复、文件级损坏、进程级 crash；`repair`、`repair-plan`、结构化报告、多列族修复和坏 WAL 隔离已有最小闭环 | 缺少在线修复协调、复杂冲突自动裁决、真实介质故障样本库和更长期的恢复证据归档 | P2 |
| 只读能力 | `readOnly(true)` 已支持，不创建 WAL/MANIFEST，写接口、compact 和 checkpoint 明确拒绝 | 缺少更细的共享锁/诊断巡检模式，以及与外部备份/监控工具的并发使用手册 | P2 |
| Range Delete | `deleteRange` 已支持 range tombstone、WAL/SST 持久化、读路径遮蔽、snapshot 语义和保守 compaction | 缺少更激进的 tombstone/point key 清理策略、长时间混合 workload 报告和旧版本 fail-fast 证据归档 | P1 |
| 列族生命周期 | 支持打开时注册列族、按列族读写、列族级 compactRange/属性、运行时 list/create、非空 drop tombstone 和 rename | 缺少更激进的 dropped CF 物理 GC 策略、列族迁移策略和大规模多列族运维报告 | P2 |
| Compaction 策略 | 已具备可配置触发阈值、限速、取消清理、按列族评分和多类 soak/观测入口 | 缺少真实低磁盘、高并发和长时间生产环境压缩报告，以及压缩策略外部可视化 | P2 |
| 写入与 WAL | 支持 WAL、sync、batch、crash 恢复、半写组合测试、回收后 reopen/backup/repair，以及默认关闭的 group commit 最小实现 | 缺少 WAL 归档/保留策略、跨备份/repair 的 WAL 生命周期策略和 group commit 长期尾延迟基线 | P1 |
| Snapshot/Iterator | 已有 snapshot cursor、反向迭代、prefix/range scan 约束、资源计数和长生命周期 snapshot 跨 compaction 测试 | 缺少更大规模泄漏矩阵、极长生命周期 snapshot 下的磁盘保留上限和生产压测报告 | P2 |
| 校验与数据完整性 | 已有 SST checksum、离线 check、`verifyOnOpen`、checkpoint 报告、工具命令和损坏注入矩阵 | 缺少更完整的介质故障、权限、低磁盘和跨文件系统损坏注入生态，以及 repair/check 运维组合验证 | P2 |
| 备份/Checkpoint | 支持 checkpoint、全量/增量备份、对象仓库、`OBJECT-REFS.json`、dry-run 清理、校验、恢复报告、CLI 和发布门禁 | 缺少长备份链压测、跨文件系统 checkpoint/backup 性能基线、低磁盘失败矩阵和备份仓库长期维护工具 | P1 |
| 性能与观测 | 已有 benchmark、operation histogram、block cache stats、IO/compaction/write stall 指标、慢操作日志和容量水位属性 | 缺少外部可视化、长期趋势存储和真实低磁盘/高并发环境压测报告 | P2 |
| API 兼容与生态 | 提供 DBFactory/LDB API、兼容性自描述、迁移文档和 LDB 工具命令最小入口 | 缺少 MergeOperator、PrefixExtractor、transactions、TTL、custom Env、完整 RocksDB CLI 兼容和成熟插件生态 | P3 |

### 后继实施顺序

1. 第六阶段：实现 `repair` 最小闭环。
   - 扫描 MANIFEST、WAL、SST，校验可读取文件。
   - 从可用 SST/WAL 重建 MANIFEST 和 CURRENT。
   - 将损坏文件移动到 `lost/` 或 `corrupt/` 目录，并输出修复报告。
   - 测试：MANIFEST 丢失、SST 部分损坏、WAL 尾部损坏、repair 后 reopen。
   - 6.1 增量：先从可读 SST 重建 default CF 的 MANIFEST/CURRENT，并隔离损坏 SST/旧 MANIFEST；暂不把 WAL 内容重写成 SST。
   - 6.2 增量：再补 WAL replay 到修复输出的能力，覆盖只有 WAL、无可用 MANIFEST 的恢复场景。
   - 6.3 增量：输出结构化 `REPAIR-REPORT.json`，记录可恢复 SST/WAL、隔离文件、丢弃尾部 WAL、重建 MANIFEST/CURRENT 文件号和最后序列号。
   - 6.4 增量：覆盖多列族 repair、坏 WAL 隔离、SST+WAL 混合恢复；未知列族按调用方 `Options` 是否注册决定恢复或隔离。
   - 6.5 增量：新增 `LDBFactory.planRepair` 和 `ldb repair-plan <db>` dry-run 入口，只读扫描注册表、SST 和 WAL，输出 JSON 格式的恢复、重放和隔离计划，不写 MANIFEST/CURRENT/SST/报告文件。
   - repair 报告字段：`databaseDir`、`recoveredSstFiles`、`replayedWalFiles`、`quarantinedFiles`、`discardedWalBytes`、`manifestFileNumber`、`currentFile`、`lastSequence`、`nextFileNumber`；报告是诊断产物，不参与数据库恢复语义。

2. 第七阶段：实现真正只读打开。
   - `Options.readOnly(true)` 不创建新 WAL，不写 MANIFEST，不删除旧文件。
   - 锁策略：只读打开不获取 `LOCK` 独占锁，允许已有写库进程/实例继续持有写锁；只读实例只读取现有 CURRENT/MANIFEST/SST/WAL，并在打开后形成自身内存视图。
   - 禁止 put/delete/write/compact/checkpoint 等写操作。
   - 暴露只读诊断属性，至少包含只读状态、当前 MANIFEST、当前 WAL 引用和是否跳过后台 compaction。
   - 测试：写库存在时只读打开、损坏库只读失败、只读接口拒绝写入、只读打开不产生新 WAL/MANIFEST。

3. 第八阶段：补齐列族级运维能力。
   - 新增列族级 `compactRange(cf, begin, end)`，复用现有手动 compaction 调度；由于当前 WAL 为全局共享，执行前 flush 所有列族以避免旧 WAL 被清理时丢失非目标列族 MemTable 数据，随后只对目标列族触发 manual compaction。
   - 暴露列族级 `getProperty` 诊断项：`ldb.columnFamily.<cfId>.memTableBytes` 和 `ldb.columnFamily.<cfId>.levelFiles`。
   - 不在本阶段引入列族级 WAL；如需优化 WAL 回收粒度，进入第十四阶段统一设计。
   - 8.3 增量：新增 `docs/ldb-column-family-lifecycle-design.md` 及英文副本，落地 `COLUMN-FAMILIES` 注册表和运行时 `list/create/drop-empty` 最小实现；backup、checkpoint、check 和 repair 已识别 runtime CF 注册表。
   - 8.4 增量：补充损坏注入矩阵，覆盖坏注册表、缺失注册表导致 runtime CF WAL 无法解释、CURRENT 指向缺失 MANIFEST、坏备份注册表拒绝 restore，以及 runtime CF WAL-only repair。
   - 8.5 增量：新增 `docs/ldb-column-family-tombstone-design.md` 及英文副本，并完成非空 drop tombstone 与 rename 最小实现；注册表保留 dropped 记录，列族 id 不复用，API 自描述标记对应能力。
   - 列族迁移 tombstone 和更激进的物理 GC 仍需后续单独设计。
   - 测试：多列族 compact、未知列族失败、列族目录/元数据恢复。

4. 第九阶段：Range Delete 设计与落地。
   - 设计文档：`docs/ldb-range-delete-design.md`；英文副本：`docs/ldb-range-delete-design.en.md`。
   - 先按设计文档评审 range tombstone 的 WAL/SST 编码、读路径遮蔽规则、snapshot 语义、compaction 合并和降级限制。
   - 实现 MemTable range tombstone、SST 持久化、snapshot 语义和 compaction 合并。
   - 这是磁盘格式变更，必须先做兼容性和回滚设计。
   - 测试：跨 SST/level 删除、snapshot 旧视图、crash recovery、老版本数据兼容。

5. 第十阶段：压力测试与观测。
   - 新增轻量 benchmark：写入吞吐、随机读、scan、flush、compaction、checkpoint。
   - 增加长时间 soak 测试：多线程写入、读写混合、反复 reopen/crash。
   - 扩展属性和日志：write stall、compaction backlog、block cache 命中、WAL/SST 文件数量和大小、容量水位和慢操作日志。
   - 10.1 增量：已新增轻量 benchmark 工作流测试，覆盖写入、随机读、snapshot scan、manual compaction 和 checkpoint；已扩展 `getProperty` 暴露 `ldb.fileCounts`、`ldb.fileBytes`、`ldb.totalBytes`、`ldb.walBytes`、`ldb.sstBytes`、`ldb.compactionBacklog`、`ldb.compactionScore` 和 `ldb.compactionLevel`。
   - 10.2 增量：先把慢操作日志和基础延迟直方图接入观测面，提供可配置慢操作阈值，并通过 `getProperty` 暴露 `get/write/compact/checkpoint` 的次数、平均耗时、最大耗时和慢操作次数；更长时间的 soak、反复 reopen 和 write stall 行为定义继续留到 10.3。
   - 10.2 修复：命中读压测暴露 MemTable 在无 range tombstone 时仍执行全表 tombstone 扫描，导致纯 MemTable 读随条目数线性退化；已改为单独维护 range tombstone 索引，普通 point get 直接走 skip-list seek。
   - 10.3 增量：补轻量反复 reopen/scan soak 回归，并把 write stall 语义接入观测面：Level-0 soft trigger 记录 slowdown delay，immutable MemTable 或 Level-0 stop trigger 记录 wait，暴露次数、累计耗时和触发阈值。
   - 10.4 增量：补充 operation latency histogram 和 `ldb.blockCacheStats`，暴露 BlockCache enabled/maxEntries/size/hits/misses/puts/evictions，并让 `Options.cacheBlocks(false)` 真正关闭缓存。
   - 10.5 增量：新增 `docs/ldb-longrun-benchmark-design.md` 及英文副本，并完成 longrun 机器可读报告最小实现：`summary.json`、`operations.csv`、`failures.json`、`properties-before.json`、`properties-after.json`；新增 `benchmarkReport`、`longRunTest`、`releaseSoakTest` 显式 Gradle 任务。

6. 第十一阶段：全库 verify/check。
   - 新增离线 verify/check 能力，扫描 MANIFEST、SST 和 WAL，输出文件级、block 级和序列号级校验报告。
   - 支持可选启动时全量校验配置；默认关闭，避免影响正常打开延迟。
   - checkpoint 增加校验报告，记录纳入快照的 CURRENT、MANIFEST、SST、WAL 及校验结果。
   - 测试：完整库校验成功、坏 SST/WAL/MANIFEST 明确失败、启动时全量校验可开关、checkpoint 报告可复核。
   - 11.1 增量：先新增离线 `LDBFactory.check(File, Options)`，扫描 CURRENT、MANIFEST、SST 和 WAL，返回结构化报告；启动时校验和 checkpoint 报告留到 11.2。
   - 11.2 增量：新增 `Options.verifyOnOpen(true)` 启动时全库校验；checkpoint 完成后写入 `CHECKPOINT-REPORT.json`，报告包含校验文件、失败列表和 MANIFEST/WAL/SST 计数。

7. 第十二阶段：备份与 checkpoint 增强。
   - 设计并实现类似 RocksDB backup engine 的增量备份目录结构，支持按备份 id 引用 SST/WAL。
   - 增加备份校验、版本清理、restore 报告和失败回滚，避免半成品备份污染可恢复集合。
   - 保持现有 checkpoint API 兼容，新增能力优先通过独立备份接口或工具入口暴露。
   - 测试：首次全量备份、增量备份、清理旧版本、损坏备份校验失败、restore 后 reopen。
   - 12.1 增量：先新增离线全量备份/恢复入口 `LDBFactory.createBackup/restoreBackup`，采用临时目录写入、校验通过后发布，输出 `BACKUP-REPORT.json` 和 `RESTORE-REPORT.json`；增量备份、版本清理和共享 SST 引用计数留到 12.2。
   - 12.2 增量：新增 `LDBFactory.purgeOldBackups(root, keepLast)`，只清理已发布的 `backup-000001` 风格目录，保留最新 N 个版本并输出清理报告；共享文件引用计数仍留给后续增量。
   - 12.3 增量：新增 `LDBFactory.createIncrementalBackup/checkBackup`，发布完整可恢复目录，写入 `BACKUP-MANIFEST.json`，并优先通过硬链接复用上一备份中的同名同长度 SST 文件；共享对象仓库和引用计数清理随后在 12.5 落地。
   - 12.4 增量：将增量备份和备份校验暴露到 `LdbTool incremental-backup` 与 `LdbTool check-backup`，复用 `BackupReport`/`CheckReport` JSON 输出和退出码语义。
   - 12.5 增量：新增 `docs/ldb-backup-engine-design.md` 及英文副本，并完成共享对象仓库与引用计数最小实现：备份根目录维护 `objects/` 和 `OBJECT-REFS.json`，`planPurgeBackups` 支持 dry-run 清理影响审计。

8. 第十三阶段：Compaction 策略增强。
   - 增加可配置触发阈值、限速、取消和按列族评分能力，保留默认行为兼容现有调用方。
   - 明确 manual/background compaction 的取消边界和输出文件清理规则。
   - 增加长时间压力测试，覆盖多列族、写入高峰、暂停/恢复、关闭超时和磁盘空间紧张场景。
   - 测试：阈值触发、限速生效、取消可恢复、按列族评分选择、长时间压缩不破坏读取一致性。
   - 13.1 增量：先把 Level-0 compaction/slowdown/stop 触发阈值提升为 `Options` 配置，默认仍为 4/8/12，并通过 write-stall 属性暴露实际阈值；限速、取消和 pending bytes 留到后续增量。
   - 13.2 增量：补充 compaction 运行统计属性，暴露后台 compaction 是否运行、执行/成功/失败次数、最近失败原因和 SST 输出字节数，为后续取消、限速和 backlog 压测提供观测基线。
   - 13.3 增量：记录全局最佳 compaction 候选的列族，避免多列族按全局分数调度时误选；新增 pending bytes、候选信息和列族级 compaction score/pending 属性。
   - 13.4 增量：新增可选 compaction 输出限速，默认关闭；开启后按 SST 输出字节进行轻量延迟，并暴露限速配置、等待次数和等待耗时属性。
   - 13.5 增量：收紧 compaction 取消/失败清理边界，确保异常、中断或关闭取消时清理未安装输出文件和 pending outputs，并暴露取消次数与清理文件数属性。
   - 13.6 增量：补充多列族 compaction 一致性 soak，用 default 与自定义列族交错写入、分别压缩、重启复查，验证按列族评分/属性不会破坏跨列族读取隔离。
   - 13.7 增量：补充限速 compaction 期间读取一致性 soak，在后台 compact 进行时反复执行 point get 与 snapshot cursor scan，验证长时间压缩不破坏前台读取。
   - 13.8 增量：补充写入高峰观测 soak，用小写缓冲和多轮批量写入验证 write-stall、operation、backlog 与 compaction 属性在高频写入后仍稳定可读，且数据完整。
   - 13.9 增量：补充 suspend/resume 与写入压力交互测试，验证暂停 compaction 时前台写入会受 immutable memtable 背压，恢复后写入继续完成且数据可读。
   - 13.10 增量：补充文件容量水位 soak，覆盖写入、压缩和重启后的 WAL/SST/total bytes、fileBytes、pending bytes 与 compaction 输出统计，作为磁盘空间紧张场景的前置观测入口。
   - 13.11 增量：补充关闭超时恢复测试，验证 compaction executor 被暂停占用时 `closeTimeoutMillis` 会显式失败，并且关闭路径释放锁后可重新打开数据库读取已提交数据。
   - 第十三阶段完成判定：阈值配置、限速、取消清理、按列族评分、写入高峰、多列族、暂停/恢复、关闭超时、容量水位和长时间读取一致性均已有实现或独立测试入口；真实低磁盘注入留给后续环境级压测。

9. 第十四阶段：WAL 生命周期增强。
   - 设计 WAL 归档/回收策略，明确哪些 WAL 可删除、归档或保留用于 repair/backup。
   - 评估全局 WAL、列族级 WAL、WAL 分片/归档三种方案；默认保持全局 WAL，除非能同时保证跨列族 batch 原子性、全局 sequence 恢复顺序、MANIFEST 引用和回滚兼容。
   - 补齐系统化半写 WAL 组合测试，包括 header 截断、record 截断、checksum 错误和多 log 文件边界。
   - 评估写入限流和 group commit 行为定义，先明确兼容性和延迟/吞吐权衡后实现。
   - 测试：WAL 回收不影响 reopen/repair/backup、半写组合按预期恢复或失败、限流和 group commit 指标可观测。
   - 14.1 增量：先保持全局 WAL 方案不变，新增 WAL 生命周期观测属性，暴露现存 WAL、引用 WAL、可回收 WAL 和当前 WAL，作为后续归档/回收策略的验收入口。
   - 14.2 增量：补齐 WAL 半写组合测试，覆盖 header 截断、checksum 错误和多 WAL 边界尾部截断；当前策略为跳过损坏或不完整 record，保留此前完整记录。
   - 14.3 增量：补齐 WAL 清理后 reopen/backup/repair 验收，验证已落 SST 且旧 WAL 被清理后，常规打开、离线备份恢复和 repair 均能读取完整数据。
   - 14.4 增量：显式暴露当前 WAL 策略属性：全局 WAL、归档关闭、obsolete WAL 删除回收、group commit 默认关闭、写入限流沿用 write-stall；后续若改变策略必须先更新兼容性文档和测试。
   - 14.5 增量：新增 group commit 最小实现和统计属性，开启后并发写请求进入短窗口聚合队列，组内任一 sync 请求会触发本轮 sync；WAL record 仍按请求分别编码，保持磁盘格式兼容。
   - 第十四阶段完成判定：全局 WAL 方案已确认，WAL 生命周期观测、半写组合、回收后 reopen/backup/repair，以及写入限流/group commit 当前策略均已有文档和测试入口。

10. 第十五阶段：Snapshot/Iterator 完整性。
    - 补充 iterator 资源泄漏矩阵，覆盖未关闭 cursor、异常中断、跨 compaction 持有 snapshot 等场景。
    - 设计并实现反向迭代、prefix/range scan 行为约束，明确与 snapshot sequence 的关系。
    - 增加长生命周期 snapshot 压测，观察 SST 引用保留、obsolete 文件清理和内存占用。
    - 测试：资源释放、反向迭代顺序、prefix/range 边界、长 snapshot 下 compaction 与清理安全。
    - 15.1 增量：补齐 `SnapshotCursor` 反向迭代，支持 `seekToLast`、`seekForPrev` 和 `prev`；当前实现按 snapshot 可见视图物化后反向遍历，优先保证语义正确。
    - 15.2 增量：新增 snapshot cursor 资源计数属性，暴露打开、关闭和活跃 cursor 数，作为泄漏矩阵测试入口。
    - 15.3 增量：补充 prefix/range scan 约束测试，明确 scan 边界由调用方停止条件控制，cursor 始终按 snapshot sequence 返回可见 user key。
    - 15.4 增量：补充长生命周期 snapshot 跨 compaction 测试，验证持有 cursor 时压缩和清理不破坏旧视图，关闭后资源计数恢复。
    - 第十五阶段完成判定：资源释放、反向迭代、prefix/range scan 边界和长 snapshot 跨 compaction 均已有实现或独立测试入口。

11. 第十六阶段：API 兼容与生态。
    - 评估 RocksDB 常见 Options 映射，明确支持、忽略、拒绝三类策略和调用方可见错误。
    - 设计 MergeOperator、PrefixExtractor、统计接口和工具命令；涉及磁盘或读写语义变化的能力必须单独评审。
    - 提供迁移/兼容文档，说明 LDB 与 RocksDB API 行为差异及 ADB 使用约束。
    - 测试：Options 映射兼容、未知配置拒绝、统计接口稳定、工具命令错误语义清晰。
    - 16.1 增量：先提供只读兼容性自描述属性，暴露 RocksDB 风格 Options 映射、当前有效配置值、已支持能力和明确不支持能力；该增量不改变磁盘格式和读写语义，MergeOperator、PrefixExtractor、工具命令等仍保持显式 unsupported，后续若实现必须单独设计评审。
    - 16.2 增量：新增 LDB/RocksDB API 兼容与迁移说明文档，固化 Options 映射、property 统计入口、显式 unsupported 能力、回滚策略和后续 MergeOperator/PrefixExtractor/工具命令评审边界；文档不改变运行时行为，但作为后续生态兼容实现的验收基线。
    - 16.3 增量：补充 LDB 工具命令设计评审，先定义 `check`、`repair`、`checkpoint`、`backup`、`restore`、`properties`、`compact` 的参数、只读/写入边界、退出码和错误语义；当前仍不提供 CLI 实现，`rocksdbToolCommands` 继续标记为 unsupported，待命令入口实现后再迁出。
    - 16.4 增量：补充 MergeOperator/PrefixExtractor 专项评审边界，明确二者会触及 WAL/SST 格式、MemTable 合并、读路径可见性、compaction 合并、snapshot 语义、repair/check/backup 兼容和降级策略；当前继续保持 `mergeOperator`、`prefixExtractor` unsupported，后续必须以独立设计和迁移方案推进。
    - 16.5 增量：新增 `ldb.api.ecosystemGaps`，把 MergeOperator、PrefixExtractor、transactions、TTL、custom Env、列族 drop/rename 的实现状态、RocksDB 工具兼容和二级索引的生态状态暴露给迁移层。
    - 第十六阶段完成判定：Options 映射、运行时兼容性自描述、迁移说明、工具命令语义和 MergeOperator/PrefixExtractor 评审边界均已文档化并有 `LdbApiCompatibilityTest` 验证核心 property；真正 CLI 或 Merge/Prefix 实现作为后续独立阶段，不阻塞本阶段关闭。

12. 第十七阶段：LDB 工具命令最小入口。
    - 先实现无破坏性命令入口，覆盖 `check` 和 `properties` 两个只读命令，复用第十六阶段定义的退出码和 JSON 输出约束。
    - `check <db>` 调用离线 `LDBFactory.check(File, Options)`，不获取写锁、不创建 WAL、不写 MANIFEST；校验失败返回退出码 `2`。
    - `properties <db> [property...]` 默认以 `readOnly(true)` 打开数据库并输出指定 property；未指定时输出 `ldb.api.*` 和关键统计属性；未知 property 返回退出码 `1`。
    - 写命令 `repair`、`checkpoint`、`backup`、`restore`、`compact` 暂不在 17.1 实现，仍按第十六阶段工具命令评审推进，避免副作用命令过早暴露。
    - 测试：命令参数错误、健康库 check、坏库 check、properties 默认输出、指定 property 输出、未知 property 错误语义。
    - 17.1 增量：新增 `net.xdob.vexra.ldb.tool.LdbTool`，实现 `check` 和 `properties` 两个只读命令；运行时兼容属性增加 `ldbToolCommands=partial`，但 RocksDB 工具命令仍保持 `rocksdbToolCommands=unsupported`。
    - 17.2 增量：新增 `repair <db>` 命令，作为第一个显式副作用工具命令；命令调用 `LDBFactory.repair`，成功后输出 `REPAIR-REPORT.json` 内容，异常返回退出码 `4`，参数错误返回退出码 `1`。
    - 17.3 增量：新增 `backup <db> <backupRoot>` 和 `restore <backupDir> <targetDir>` 命令，复用离线备份/恢复引擎并输出 `BackupReport` JSON；报告 `ok=false` 返回退出码 `2`，成功返回 `0`。
    - 17.4 增量：新增 `checkpoint <db> <targetDir>` 命令，正常打开源库并调用实例级 `checkpoint`，成功后输出 `CHECKPOINT-REPORT.json`；目标目录非空或 checkpoint 校验失败返回内部错误退出码 `4`。
    - 17.5 增量：新增 `incremental-backup <db> <backupRoot>` 和 `check-backup <backupDir>` 命令，覆盖完整目录形式的增量备份发布和只读备份校验。

13. 第十八阶段：生产级发布门禁与运维硬化。（已完成最小闭环，后续按发布证据持续维护）
    - 设计并实现面向正式发布的统一 `releaseGate`，聚合常规测试、旧版本升级样本、备份对象仓库校验、轻量 longrun profile 和报告归档。
    - 固化 `0.4.0` 及后续历史版本样本库，验证新版本可以打开、读取、check、backup/restore，或在不兼容时给出清晰迁移错误。
    - 补齐备份对象仓库损坏注入矩阵，覆盖缺失对象、错误引用计数、孤儿对象、损坏 manifest 和 restore 回滚。
    - 补齐列族 tombstone 长生命周期压测，覆盖 drop/rename 后跨 reopen、compaction、backup、repair、snapshot cursor 与物理 GC 证明。
    - 固化 production-gate longrun profile 和 benchmark 报告归档规则，把 `summary.json`、`operations.csv`、`failures.json` 和属性快照纳入发布证据。
    - 补齐运维 Runbook，覆盖生产启动、备份、恢复、升级、check、repair、发布前门禁和故障处置顺序。
    - 18.1 增量：旧版本升级兼容样本库。
    - 18.2 增量：生产级 `releaseGate` 聚合任务。
    - 18.3 增量：备份对象仓库损坏注入矩阵。
    - 18.4 增量：列族 tombstone 长压测与物理 GC 证明。
    - 18.5 增量：production-gate longrun profile。
    - 18.6 增量：运维手册与故障处置 Runbook。
    - 设计基线见 `docs/ldb-production-readiness-plan.md` 及英文副本。

14. 第十九阶段：checkpoint/backup 生产证据固化。
    - 补充跨文件系统 checkpoint/backup 基线，验证硬链接失败回退复制、复制限速、临时目录发布和失败清理。
    - 增加 checkpoint/backup 长链压测，覆盖大库、多 SST、多列族、连续 checkpoint、连续增量备份和 purge dry-run。
    - 补齐低磁盘、权限拒绝、目标目录被占用、中断和插件后置失败的故障注入矩阵。
    - 将 `CHECKPOINT-REPORT.json`、`BACKUP-MANIFEST.json`、`OBJECT-REFS.json`、release gate 和 longrun 报告纳入统一发布证据索引。

15. 第二十阶段：WAL 生命周期与写入策略生产化。
    - 设计 WAL 归档、保留、清理和 repair/backup 依赖关系，明确哪些 WAL 可删除、保留或纳入备份证据。
    - 补充 group commit 长时间尾延迟、sync 次数、吞吐和 crash/reopen 基线，形成默认关闭到灰度开启的判定标准。
    - 评估写入限流与 write-stall 的生产阈值，并在 longrun 中输出可复查报告。

16. 第二十一阶段：运维生态与外部观测。
    - 增强 CLI/报告索引，统一 check、repair、backup、restore、checkpoint、properties、longrun 和 release gate 的输出位置与退出码说明。
    - 设计外部指标导出或报告转换入口，便于接入 Prometheus、日志平台或离线趋势分析。
    - 建立可复现故障样本库，沉淀坏 WAL、坏 SST、坏 manifest、坏备份对象和权限/低磁盘样本。

17. 第二十二阶段：RocksDB 高级 API 兼容评审。
    - 对 MergeOperator、PrefixExtractor、transactions、TTL、custom Env 和完整 RocksDB CLI 兼容分别做独立设计评审。
    - 明确哪些能力会改变 WAL/SST/MANIFEST 或读写语义，哪些只能作为迁移层显式 unsupported。
    - 不把这些高级能力作为 `0.5.0` 生产发布阻塞项。

### 近期优先级

第十八阶段 18.1-18.6 已形成最小闭环，当前文档计划不再把旧版本样本、`releaseGate`、备份对象仓库损坏注入、列族 tombstone 长压测、production-gate longrun 和 Runbook 作为待补阶段。下一优先级进入第十九阶段：先固化 checkpoint/backup 的跨文件系统、低磁盘、权限和长链证据，再推进第二十阶段 WAL 生命周期与 group commit 长基线。MergeOperator、PrefixExtractor、transactions、TTL、custom Env 和完整 RocksDB CLI 兼容仍作为第二十二阶段独立评审。
