# LDB API 兼容与迁移说明

[English](ldb-api-compatibility-design.en.md) | 中文

## 背景

LDB 已经具备 WAL、MemTable、SSTable、checkpoint、repair、verify/check、backup、列族、range delete、只读打开、观测属性和插件能力。第十六阶段的目标不是把 RocksDB API 原样搬进来，而是先把 LDB 已支持、部分支持和明确不支持的边界写清楚，避免调用方把 RocksDB 习惯配置误认为已经生效。

## 目标

- 给出 RocksDB/LevelDB 风格 Options 到 LDB `Options` 的映射口径。
- 固化 `getProperty` 的兼容性与统计入口，便于 ADB 和运维工具做运行时探测。
- 明确 MergeOperator、PrefixExtractor、工具命令等能力的当前状态和后续评审边界。
- 给出从现有 RocksDB/LevelDB 使用方式迁移到 LDB 的检查清单。

## 非目标

- 不实现 RocksDB JNI 或 RocksJava 兼容层。
- 不在本阶段引入 MergeOperator、PrefixExtractor、事务、TTL、二级索引或 runtime create/drop column family。
- 不改变 WAL、SST、MANIFEST、CURRENT 的磁盘格式。
- 不承诺 RocksDB 所有 property 名称和返回格式兼容。

## 现状/已有流程

| 能力 | 当前 LDB 行为 | 迁移提示 |
| --- | --- | --- |
| 基础读写 | `put`、`delete`、`get`、`write` 已支持 | 写入失败以 `DBException` 或参数异常暴露 |
| 列族 | 打开前通过 `Options.addColumnFamily` 声明 | 不支持运行时 create/drop |
| Range Delete | `LdbWriteBatch.deleteRange` 已支持 | 涉及磁盘语义，旧版本需确认是否能读取对应数据 |
| 只读打开 | `Options.readOnly(true)` 已支持 | 只读实例不创建新 WAL，不允许写接口和 compaction/checkpoint |
| 统计 | 使用 `LDB.getProperty` | 不提供 RocksDB native statistics 对象 |
| Checkpoint/Backup | checkpoint 与 LDB backup API 已支持 | checkpoint 目标目录必须为空 |
| Repair/Verify | factory repair/check 已支持 | repair 会生成结构化报告 |

## 核心约束

- JDK8 兼容优先，不能引入只在更高 JDK 可用的 API。
- 兼容文档优先描述事实，不把未实现能力写成“自动忽略”。
- 对未支持能力采用显式 unsupported，而不是静默忽略。
- 涉及磁盘格式、WAL 语义、读路径遮蔽、compaction 合并或快照可见性的能力，必须单独设计评审。

## 接口设计

### 运行时自描述 property

| Property | 含义 | 稳定性 |
| --- | --- | --- |
| `ldb.api.compatibility` | 总览兼容策略，例如 Options 部分兼容、unsupported 配置拒绝、统计走 property | 稳定 |
| `ldb.api.optionsMapping` | RocksDB 风格 Options 到 LDB 行为的映射清单 | 稳定新增，字段含义不反转 |
| `ldb.api.optionValues` | 当前实例的关键有效配置值 | 可新增字段 |
| `ldb.api.supportedFeatures` | 当前显式支持的能力清单 | 可新增字段 |
| `ldb.api.unsupportedFeatures` | 当前显式不支持的能力清单 | 能力实现后可迁出 |

### Options 映射策略

| RocksDB/LevelDB 心智 | LDB 映射 | 策略 |
| --- | --- | --- |
| `create_if_missing` | `Options.createIfMissing` | supported |
| `error_if_exists` | `Options.errorIfExists` | supported |
| `write_buffer_size` | `Options.writeBufferSize` | supported |
| L0 slowdown delay | `Options.writeSlowdownDelayNanos` | supported |
| `max_open_files` | `Options.maxOpenFiles` | supported |
| `block_size` | `Options.blockSize` | supported |
| `block_restart_interval` | `Options.blockRestartInterval` | supported |
| `compression` | `Options.compressionType` | supported，当前枚举以 LDB 实现为准 |
| `verify_checksums` | `Options.verifyChecksums` | supported |
| `comparator` | `Options.comparator` | supported，调用方负责排序一致性 |
| `filter_policy` | `Options.filterPolicy` | supported |
| `statistics` | `LDB.getProperty` | properties |
| `merge_operator` | 无 | unsupported |
| `prefix_extractor` | 无 | unsupported |
| `env` | 无 | unsupported |
| `ttl` | 无 | unsupported |
| LDB 工具命令 | `LdbTool check/properties/repair/backup/restore/checkpoint` | partial |
| RocksDB 工具命令 | 无 RocksDB CLI 兼容层 | unsupported |

## 数据结构

本阶段不新增持久化数据结构。新增内容仅为文档和只读 property 字符串；property 输出不写入 WAL、MANIFEST、SST 或 repair/backup 报告。

## 状态机

| 状态 | 触发 | 行为 |
| --- | --- | --- |
| 支持 | LDB 已有等价配置或能力 | property 标记为 `supported`，调用方可使用 |
| 部分支持 | LDB 有相近能力但非 RocksDB 完整语义 | property 使用说明性字段，例如 `rocksdbOptions=partial` |
| 显式不支持 | 未实现或语义风险较高 | property 标记为 `unsupported`，后续实现必须更新文档和测试 |

## 时序流程

1. 调用方构造 `Options` 并打开 LDB。
2. 打开成功后调用 `getProperty("ldb.api.compatibility")` 判断兼容策略。
3. 调用方读取 `ldb.api.optionsMapping` 和 `ldb.api.unsupportedFeatures` 做启动前诊断。
4. 运维或测试读取 `ldb.operationStats`、`ldb.compactionStats`、`ldb.walPolicy`、`ldb.snapshotCursorStats` 等统计入口。

## 异常处理

- LDB Java API 中已有 setter 会对非法值抛出 `IllegalArgumentException`，例如非法 block cache、压缩类型、L0 阈值和限速值。
- 不存在的 property 返回 `null`，调用方不能把 `null` 当成 false 或 0。
- 当前没有 MergeOperator/PrefixExtractor setter，因此迁移层不得把这些配置静默丢弃；应在适配层或启动校验中拒绝。

## 幂等性

读取 property 是无副作用操作，可重复调用。`ldb.api.optionValues` 只反映实例打开时或运行时 Options 的当前有效值；本阶段不引入会修改 Options 的运行时接口。

## 回滚策略

- 如果新增 property 影响调用方，可回滚调用方对这些 property 的依赖；数据库文件无需迁移。
- 由于不改变磁盘格式，回滚代码后旧库仍可按原逻辑打开。
- 后续若实现 MergeOperator、PrefixExtractor 或工具命令，必须单独给出关闭开关和数据回滚策略。

## 兼容性

- 旧数据文件兼容：本阶段不写入新格式。
- 旧客户端兼容：未读取新 property 的客户端行为不变。
- 新客户端兼容：读取不到新 property 时应降级为“能力未知”，不能假定 supported。
- ADB 约束：ADB 侧应优先使用 LDB property 判断能力，而不是硬编码 RocksDB 能力矩阵。

## 灰度/迁移

1. 在测试环境读取 `ldb.api.compatibility`，确认返回非空。
2. 对迁移配置做白名单校验，只允许 `supported` 或 `properties` 项进入 LDB。
3. 对 `unsupported` 项启动失败，提示调用方移除配置或等待后续设计。
4. 上线后采集 `ldb.operationStats` 和 `ldb.compactionStats` 验证运行状态。

## 测试方案

- `LdbApiCompatibilityTest` 覆盖 Options 映射、有效配置值、supported/unsupported 能力和未知 property 返回 `null`。
- `LdbCoreBehaviorTest` 继续覆盖基础 API、只读、插件、列族和非法配置。
- `LdbObservabilityTest`、`LdbWalLifecycleTest`、`LdbSnapshotIteratorTest` 覆盖统计 property 的稳定性。
- 后续工具命令实现时需新增独立命令测试，覆盖参数错误、损坏库、只读库和权限失败。

## 风险点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 调用方误以为 LDB 完整兼容 RocksDB | 配置被误用或语义不一致 | property 和文档明确 `partial` |
| 未支持能力被适配层静默忽略 | 数据语义错误 | unsupported 必须启动失败 |
| property 字段被外部强解析 | 后续新增字段造成兼容问题 | 文档声明可新增字段，调用方按键值片段识别 |
| MergeOperator/PrefixExtractor 直接实现 | 破坏读写和 compaction 语义 | 单独设计评审，不能混入兼容文档阶段 |

## 工具命令评审

当前仓库已有 `net.xdob.vexra.ldb.tool.LdbTool` 最小 CLI 入口，实现只读 `check`、`properties` 和显式副作用 `repair`、`backup`、`restore`、`checkpoint`。实例级 `compactRange` 暂不进入 CLI。因此 `ldbToolCommands` 为 partial，`rocksdbToolCommands` 仍保持 unsupported。

### 候选命令

| 命令 | 底层能力 | 是否写库 | 默认锁策略 | 主要输出 |
| --- | --- | --- | --- | --- |
| `ldb check <db>` | `LDBFactory.check` | 否 | 不创建写锁 | 结构化校验报告，退出码反映是否健康 |
| `ldb repair <db>` | `LDBFactory.repair` | 是 | 独占写锁 | `REPAIR-REPORT.json`、隔离文件、重建 MANIFEST/CURRENT 信息 |
| `ldb checkpoint <db> <target>` | `LDB.checkpoint` | 正常打开源库、写目标目录 | 目标目录必须不存在或为空 | `CHECKPOINT-REPORT.json` |
| `ldb backup <db> <backup-root>` | `LDBFactory.createBackup` | 读源库、写备份目录 | 离线校验源库 | `BackupReport` JSON 和校验结果 |
| `ldb restore <backup> <target>` | `LDBFactory.restoreBackup` | 写目标库 | 目标目录必须可创建或为空 | `BackupReport` JSON 和校验结果 |
| `ldb properties <db> [property...]` | `LDB.getProperty` | 否 | 默认只读打开 | property 键值输出 |
| `ldb compact <db> [begin] [end]` | `LDB.compactRange` | 是 | 正常写打开 | compaction 统计和错误信息 |

### 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功，且校验类命令报告健康 |
| `1` | 参数错误、未知命令或未知 property |
| `2` | 数据库校验失败、repair 部分失败或发现不可恢复文件 |
| `3` | 文件系统权限、锁冲突、目录非空或路径不可用 |
| `4` | 内部异常，包含未分类 `DBException` 或 IO 异常 |

### 错误语义

- `check` 和 `properties` 必须默认只读，不创建新 WAL，不修改 MANIFEST。
- `repair`、`compact`、`restore` 属于写命令，必须在帮助和日志中明确有副作用；当前已开放的 `repair` 成功后直接输出 `REPAIR-REPORT.json`。
- `checkpoint` 和 `backup` 不应修改源库语义，但会写目标目录；目标目录非空应失败。当前已开放的 `backup`/`restore` 直接复用离线备份报告，报告失败返回退出码 `2`；`checkpoint` 输出目标目录内的 `CHECKPOINT-REPORT.json`。
- 所有命令应支持机器可读输出，优先 JSON；面向人的文本输出只能作为附加视图。
- 当前 `check` 和 `properties` 已有最小入口；实现更多命令前，`ldb.api.unsupportedFeatures` 中仍保留 `rocksdbToolCommands`。

### 实现前置条件

1. 先新增 CLI 入口类和命令解析测试，不复用测试里的临时 `main`。
2. 所有写命令必须覆盖锁冲突、路径不存在、目标目录非空和权限失败。
3. `properties` 命令必须覆盖 `ldb.api.*`、统计 property 和未知 property。
4. 命令输出字段必须与 repair/check/backup 报告兼容，不能另起一套含义相近但字段不同的格式。

## MergeOperator/PrefixExtractor 评审

当前 LDB 不暴露 MergeOperator 或 PrefixExtractor 配置，运行时 property 也明确标记为 unsupported。它们不能作为普通 Options setter 直接加入，因为二者会改变写入、读取、恢复和 compaction 的核心语义。

### MergeOperator 影响面

| 影响面 | 需要设计的问题 | 未评审前策略 |
| --- | --- | --- |
| WAL | merge 操作是否新增 `ValueType`，如何记录 operator 名称和操作数 | 不写入 merge 记录 |
| MemTable | point get 是否在内存中实时合并，失败如何暴露 | 不支持 merge 写入 |
| SST | merge operand 如何编码，旧版本能否跳过或拒绝 | 不新增 SST 编码 |
| 读路径 | 多版本 operand、put、delete、range tombstone 的优先级 | 保持当前 point/range delete 规则 |
| Compaction | 何时做 full merge，operator 异常是否中断 compaction | 不引入 compaction merge |
| Snapshot | snapshot sequence 下 operand 可见性和重复读取幂等性 | 保持现有 snapshot 语义 |
| Repair/Check | unknown operator 或损坏 operand 如何报告 | 继续按现有格式校验 |
| Backup/Restore | 备份是否需要 operator 元数据 | 不新增元数据 |

MergeOperator 若后续实现，必须先给出 operator 注册模型、确定性要求、异常处理、磁盘元数据、旧版本拒绝策略和禁用/回滚方案。默认要求 operator 纯函数、确定性、无外部 IO、线程安全，并且 operator 名称必须持久化到 MANIFEST 或等价元数据中。

### PrefixExtractor 影响面

| 影响面 | 需要设计的问题 | 未评审前策略 |
| --- | --- | --- |
| Comparator | prefix 顺序是否与 user comparator 兼容 | 不暴露 prefix 配置 |
| Bloom/Filter | prefix bloom 与现有 filter policy 如何共存 | 保持现有 filter policy |
| Iterator | prefix seek 的边界、反向迭代和 range scan 停止条件 | 由调用方按 key 边界控制 |
| Range Delete | prefix scan 与 range tombstone 遮蔽如何组合 | 保持全 key 范围规则 |
| Compaction | prefix 分区是否影响文件边界和候选选择 | 不改变 compaction picker |
| Check/Repair | prefix 元数据损坏或缺失如何校验 | 不新增校验项 |

PrefixExtractor 若后续实现，必须先证明与当前 `DBComparator`、`FilterPolicy`、`SnapshotCursor`、range delete 和列族级 compaction 的组合语义。任何 prefix bloom 或 prefix seek 优化都必须有退化路径，不能让错误 prefix 配置导致漏读。

### 评审准入清单

- 新增 public API 前必须更新本设计文档及英文副本。
- 涉及新 `ValueType`、MANIFEST 字段、SST entry 或 filter block 时，必须提供旧版本打开行为和回滚限制。
- 必须新增 fault injection：WAL 半写、unknown operator、operator 抛异常、prefix 配置不一致、compaction 中断。
- 必须新增兼容测试：旧库新版本读取、新库旧版本拒绝、backup/restore 保留语义、repair/check 报告清晰。
- 未完成上述评审前，`ldb.api.optionsMapping` 继续返回 `mergeOperator=unsupported`、`prefixExtractor=unsupported`。

## 分阶段实施计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 16.1 | 运行时兼容性自描述 property | `LdbApiCompatibilityTest` 通过 |
| 16.2 | API 兼容与迁移说明文档 | 中英文文档同步，计划文档记录增量 |
| 16.3 | 工具命令设计评审 | 明确命令、错误语义、退出码和只读/写入边界 |
| 16.4 | MergeOperator/PrefixExtractor 评审 | 明确 WAL/SST/compaction/snapshot/repair/backup 影响，继续保持 unsupported |
| 17.1 | LDB 只读工具命令入口 | `check` 和 `properties` 命令通过 `LdbToolTest` 覆盖 |
| 17.2 | LDB repair 工具命令 | `repair` 命令通过 CURRENT 丢失后修复并 reopen 的测试覆盖 |
| 17.3 | LDB backup/restore 工具命令 | `backup`/`restore` 命令通过备份发布、恢复重开和坏源库失败测试覆盖 |
| 17.4 | LDB checkpoint 工具命令 | `checkpoint` 命令通过报告输出、目标库重开和非空目录失败测试覆盖 |
