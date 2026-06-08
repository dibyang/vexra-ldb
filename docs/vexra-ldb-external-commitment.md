# vexra-ldb 外部承诺（面向使用方与基于 LDB 插件扩展开发者）

[English](vexra-ldb-external-commitment.en.md) | 中文

## 背景

使用方当前依赖 `vexra-ldb` 作为嵌入式持久化组件。该文档用于记录使用方对 `vexra-ldb:0.3.0` 的对外可见承诺与边界，用于明确 LDB 升级、插件化、checkpoint/restore 和 longrun 验收边界。

## 目标

- 固化使用方对 column family、checkpoint/restore、DbStore 语义和版本升级的最低依赖。
- 明确哪些行为属于使用方可依赖的公开契约，哪些属于 LDB 内部实现细节。
- 给出使用方依赖 `vexra-ldb:0.3.0` 的最低验收矩阵。

## 非目标

- 不要求新增使用方功能。
- 不要求使用方直接依赖 LDB 内部 `impl` 包。
- 不把 WAL、SST、MANIFEST、CURRENT 文件格式暴露为使用方业务契约。
- 不承诺跨进程并发写同一个 LDB 目录。

## 契约范围

| 范围 | 使用方可依赖 | 使用方不应依赖 |
| --- | --- | --- |
| Java API | `LDB`、`Options`、`LdbWriteBatch`、`ReadOptions`、`WriteOptions`、`LdbColumnFamily` 等公开 API | `net.xdob.vexra.ldb.impl` 内部类 |
| 存储行为 | open、write、get、scan、checkpoint、backup、restore、repair 的公开语义 | WAL/SST 内部编码和文件编号 |
| 兼容性 | `0.3.0` 公开 API 和已有磁盘数据可打开 | 未公开 property 字段顺序和内部线程名 |
| 插件 | 可信内部插件 hook 的生命周期和失败语义 | 第三方插件安全沙箱 |

## Column family 注册能力

使用方依赖 LDB 支持稳定的列族注册能力，用于把不同业务域或索引域隔离到不同 column family。

| 契约 | 要求 |
| --- | --- |
| 静态声明 | 使用方在打开数据库前通过 `Options.addColumnFamily` 或插件 `configure(Options)` 声明所需列族 |
| 稳定 id | 同一使用方数据目录内，列族 id 必须稳定，不得在升级中复用为不同语义 |
| 稳定名称 | 列族名称应稳定、可诊断，用于 registry、日志和检查报告 |
| 默认列族 | LDB 默认列族始终可用，使用方可用于默认元数据或兼容路径 |
| 持久化 registry | LDB 负责持久化已注册列族信息，使 reopen、checkpoint、backup、restore、repair 能识别列族 |
| 未知列族 | 打开、恢复或 repair 遇到未知列族时应清晰失败，避免静默写入错误语义 |

使用方侧约束：

- 使用方不应绕过公开 API 修改列族 registry 文件。
- 使用方升级新增列族时，应保持旧列族 id 和语义不变。
- 使用方需要删除或废弃列族语义时，应通过上层迁移处理，不复用旧 id。

## checkpoint / restore 行为稳定性

使用方依赖 checkpoint、backup 和 restore 作为备份、迁移、恢复和测试验证能力。

| 契约 | 要求 |
| --- | --- |
| 一致性 | 成功返回的 checkpoint/backup 应能被 LDB 打开或检查，不暴露半成品成功 |
| 列族完整性 | checkpoint/backup 必须携带列族 registry，restore 后列族 id 和名称保持可识别 |
| 文件格式封装 | 使用方只依赖 LDB API 生成和恢复，不依赖具体文件列表或编号 |
| 失败语义 | 失败应返回异常或失败报告，不应把不可打开的数据目录标记为成功 |
| 恢复后可读 | restore 后用相同 column family 声明打开，应能读取 checkpoint/backup 时刻可见的数据 |
| 插件后置失败 | 插件 `afterCheckpoint` 失败不等价于 checkpoint 数据不存在；调用方需按异常消息和报告区分 |

使用方侧约束：

- restore 目标目录的覆盖、清理和保留策略由使用方明确控制。
- 使用方不应在 checkpoint/restore 过程中并发修改目标目录。
- 使用方验收时应至少执行一次 restore 后 open 和关键 key 读取。

## DbStore 语义契约

使用方的 `DbStore` 层通常会把 LDB 映射为 key-value、计数器、scan、batch 和事务边界能力。以下契约固化使用方对 LDB 的最低语义依赖。

### 计数器

| 契约 | 要求 |
| --- | --- |
| 原子增量 | `addLong` 或等价 batch 增量在单次 LDB write 内原子应用 |
| 恢复一致 | write 成功返回后，reopen/recovery 不应丢失已提交计数器更新 |
| 类型边界 | 计数器编码由使用方和 LDB API 约定，使用方 不应混用普通 value 和 counter value 语义 |
| 并发可见性 | 同一 LDB 实例内提交后的读取应按 LDB 当前快照/读选项语义可见 |

### scan

| 契约 | 要求 |
| --- | --- |
| 有序遍历 | scan/cursor 结果按 LDB comparator 定义的 key 顺序返回 |
| 范围边界 | start/limit 或等价范围语义必须稳定，避免越界返回 |
| 快照一致性 | 基于 snapshot cursor 的 scan 在 cursor 生命周期内保持一致视图 |
| 资源释放 | cursor 必须由使用方关闭；LDB 保证关闭后释放相关资源 |

### batch

| 契约 | 要求 |
| --- | --- |
| 原子提交 | 单个 `LdbWriteBatch` 内跨 key、跨 column family 的变更作为一次 write 应用 |
| 顺序语义 | 同一 batch 内操作按 batch 记录顺序编码和应用，后续操作可覆盖前序同 key 结果 |
| 空 batch | 空 batch 行为应明确：要么无副作用成功，要么由上层或插件拒绝，不能产生部分提交 |
| 失败无部分提交 | `beforeWrite` 或写入前校验失败时，不写 WAL、不推进 sequence、不应用 MemTable |

### commit / rollback

| 契约 | 要求 |
| --- | --- |
| commit 边界 | 使用方的 commit 应映射为一次或一组明确的 LDB write 成功返回 |
| rollback 边界 | 未提交 batch 可以由使用方丢弃；LDB 不提供已成功 write 的事务级回滚 |
| 异常解释 | `afterWrite` 插件失败属于提交后通知失败，数据可能已经提交，使用方 重试前必须按幂等策略处理 |
| 崩溃恢复 | write 成功返回且符合写选项持久化语义的数据，应在 reopen/recovery 后满足 LDB 恢复保证 |

## LDB 版本升级兼容边界

使用方依赖 LDB 升级时保持以下边界稳定。

| 类型 | 兼容要求 |
| --- | --- |
| 源码兼容 | 不删除使用方使用的公开 API；必要变更先提供迁移期 |
| 二进制兼容 | 小版本升级尽量保持方法签名和 public 类型兼容 |
| 数据兼容 | 新版本应能打开 `0.3.0` 创建的数据目录，除非发布说明明确标记破坏性迁移 |
| 行为兼容 | get、write、batch、scan、column family、checkpoint/restore 的公开语义不做静默破坏 |
| 配置兼容 | 使用方已使用的 `Options` 默认值和含义如需改变，必须在 release note 中说明 |
| 插件兼容 | 旧插件 default 方法应继续可运行；新增能力声明不应立即破坏旧插件 |

使用方 不应依赖：

- 内部 compaction 文件选择策略。
- SST 层级编号和文件命名细节。
- property 字符串字段顺序。
- 内部线程池名称、日志文案和 warning 精确文本。

## `vexra-ldb:0.3.0` 最低验收矩阵

| 编号 | 领域 | 验收项 | 最低通过标准 |
| --- | --- | --- | --- |
| A1 | open/reopen | 新库创建、关闭、重新打开 | 数据可读，列族 registry 可加载 |
| A2 | column family | 多列族写入、读取、reopen | 各列族数据隔离，id/name 稳定 |
| A3 | counter | 计数器增量和 reopen | 提交后的计数器值恢复一致 |
| A4 | scan | 范围 scan 和 snapshot scan | 顺序、边界和快照视图符合预期 |
| A5 | batch | 跨 key、跨列族 batch | 成功时原子可见，失败时无部分提交 |
| A6 | commit/rollback | 未提交 batch 丢弃、已提交 write 可恢复 | rollback 不影响已提交数据；commit 成功后可恢复 |
| A7 | checkpoint | 创建 checkpoint 并打开检查 | checkpoint 可打开，关键数据和列族可读 |
| A8 | restore | backup/restore 后打开 | restore 目录可打开，关键数据可读 |
| A9 | crash/recovery | 写入过程中异常退出后恢复 | 不出现 checksum mismatch；已提交数据满足恢复语义 |
| A10 | compatibility | 使用 `0.3.0` 旧数据目录升级打开 | 无需人工改文件即可打开或给出明确迁移错误 |
| A11 | plugin boundary | 插件 `beforeWrite` 拒绝和 `afterWrite` 失败 | 写前拒绝无部分提交；写后失败明确标记 post-commit |
| A12 | longrun | smoke、crash、performance profile | smoke/crash PASS；performance 输出关键指标和版本 |

### 验收矩阵执行映射

| 编号 | 自动化入口 | longrun/profile | 关键日志或报告字段 |
| --- | --- | --- | --- |
| A1 | `LdbCoreBehaviorTest.shouldPersistDefaultAndCustomColumnFamiliesAcrossReopen` | `smoke.properties` | `PASS smoke`、`reopenChecks` |
| A2 | `LdbCoreBehaviorTest.shouldPersistDefaultAndCustomColumnFamiliesAcrossReopen`、`LdbColumnFamilyLifecycleTest` | `smoke.properties` | `CONFIG ldb.writeBufferSizeMb`、`ldb.columnFamilies` |
| A3 | `LdbCoreBehaviorTest.shouldPreserveCounterAddLongBatchAcrossColumnFamiliesAndReopen` | `smoke.properties` | `SUMMARY status=PASS` |
| A4 | `LdbSnapshotIteratorTest`、`LdbCoreBehaviorTest.shouldReadStableValueFromSnapshotAfterOverwrite` | `reopen.properties` | `PROGRESS ... reopenChecks=` |
| A5 | `LdbCoreBehaviorTest.shouldReplayMultiColumnFamilyBatchAfterReopen`、`shouldRejectInvalidAddLongDeltaBeforePersistingBatch` | `smoke.properties` | `RESULT phase=workload` |
| A6 | `LdbCoreBehaviorTest.shouldAbortWriteWhenPluginRejectsBeforeWrite` | `crash.properties` | `RECOVERY reconciled`、`recoveryChecks` |
| A7 | `LdbCoreBehaviorTest.shouldOpenCheckpointWithExistingData`、`LdbBackupTest` | `smoke.properties` | `FINAL phase=verify` |
| A8 | `LdbBackupTest` | `smoke.properties` | `SUMMARY status=PASS` |
| A9 | `LdbCrashRecoveryTest`、`LdbRecoveryMatrixTest` | `crash.properties` | `PASS crash recovery cycles=` |
| A10 | `LdbApiCompatibilityTest` | 发布前旧库打开门禁 | `release note` 兼容说明 |
| A11 | `LdbPluginTest`、`SmokeRunnerTest.runsWithDiagnosticPluginAndReportsPluginState` | `plugin-sample.properties` | `PLUGIN stats=`、`pluginLastFailure` |
| A12 | `SmokeRunnerTest`、`ReportAnalyzerTest` | `smoke.properties`、`crash.properties`、`performance*.properties` | `COMPONENT`、`RESULT`、`SUMMARY`、`PASS` |

## 发布和升级检查

每次 LDB 升级给使用方使用前，应至少确认：

- 使用方使用的公开 API 编译通过。
- 使用方现有数据目录可由新 LDB 打开。
- column family registry 未发生不兼容变化。
- checkpoint/restore 后关键数据可读。
- counter、scan、batch、commit/rollback 语义测试通过。
- longrun smoke/crash/performance 基线没有明显回退。
- release note 标明影响使用方的配置默认值、恢复语义或插件语义变化。

### 升级兼容门禁

| 门禁 | 执行要求 | 通过证据 |
| --- | --- | --- |
| 旧库打开 | 使用升级后版本打开 `0.3.0` 创建的数据目录 | 成功打开，或 release note 给出明确迁移错误与处理方式 |
| checkpoint / restore | 对旧库创建 checkpoint/backup，并 restore 到新目录后重新打开 | 关键 key 和 column family 可读，报告为 PASS |
| 插件默认方法 | 使用旧插件或仅实现默认方法的测试插件启动 | 不因新增 hook 或 capability 字段破坏启动 |
| longrun 基线 | 执行 smoke、crash、performance profile | `COMPONENT`、`RESULT`、`SUMMARY`、`PASS` 字段齐全 |
| 写入策略记录 | 记录 `syncWrites`、group commit、插件 async 配置 | 报告可直接看出性能数据口径 |
| 破坏性变化 | 如果存在磁盘格式、公开 API 或默认值破坏性变化 | release note 必须包含迁移步骤、回滚边界和最低验收结果 |

## 承诺状态（当前版本）

以下为当前对外承诺的实现状态，仅表示对外承诺是否在当前版本内可被验证：

| 承诺项 | 状态 | 说明 |
| --- | --- | --- |
| Column family 注册稳定性与持久化 | 已落实 | 接口与恢复链路可复用，checkpoint/restore/reopen 场景下已有行为验证。 |
| checkpoint / restore 行为 | 已落实 | 成功路径可打开验证；损坏备份或不可恢复目录不发布为成功结果，失败原因通过报告或异常保留。 |
| DbStore 计数器语义 | 已落实 | counter 写入、恢复一致性与可见性在当前验收中可覆盖。 |
| DbStore scan 语义 | 已落实 | 有序范围与快照一致性在基线验证中可验证。 |
| DbStore batch 原子性与失败行为 | 已落实 | 跨 key、跨列族 batch 按一次 write 提交；beforeWrite 拒绝、非法 addLong 等写前失败不写 WAL、不推进 sequence、不应用 MemTable。 |
| commit / rollback 语义边界 | 已落实 | LDB 明确只承诺 write 成功后的提交语义；未提交 batch 由调用方丢弃即 rollback，已成功 write 不提供事务级回滚。 |
| 版本升级与配置兼容边界 | 已落实 | 公开 API、磁盘数据打开、配置默认值和插件 default 方法按本文边界保持兼容；破坏性变化必须进入 release note 和验收矩阵。 |
| `vexra-ldb:0.3.0` 最低验收矩阵 | 已落实 | smoke/crash/performance 与基础语义链路已有可复现实测。 |
| 长期插件生命周期约束 | 已落实 | 插件 configure、beforeWrite、afterWrite、beforeCheckpoint、afterCheckpoint、close 的顺序、失败策略、同步/异步边界和统计属性已纳入公开约束。 |

## 已处理决议

以下事项在当前版本内已经形成决议，并按以下结论执行：

| 主题 | 决议 | 对外承诺 |
| --- | --- | --- |
| column family id 分配 | LDB 不维护全局 id 分配表；每个使用方或插件在自身命名空间内固定 id/name，并通过 `Options.addColumnFamily`、插件 `configure(Options)` 或运行时 `createColumnFamily(int, String)` 注册。 | LDB 持久化 registry，reopen、checkpoint、backup、restore、repair 保持 id/name 可识别；同一库内 id/name 冲突清晰失败，废弃列族不得复用旧 id。 |
| counter 编码 | LDB 公开承诺的 counter 语义仅覆盖 `addLong` 及等价 batch `addLong`，编码为 LDB 内部定义的 8 字节 long delta/value 语义。 | 调用方自定义 counter/value 编码属于上层业务语义，不纳入 LDB counter 兼容承诺；混用普通 value 和 counter value 由调用方自行迁移或隔离。 |
| 写入持久化默认策略 | `WriteOptions.sync` 默认 `false`；调用方需要同步落盘时必须显式设置 `new WriteOptions().sync(true)`。 | LDB 按 write option 执行持久化语义；验收与性能报告必须打印或记录实际写选项，避免把异步写结果误认为同步写结果。 |
| group commit 默认策略 | `Options.groupCommitEnabled` 默认 `false`；默认等待阈值为 `200us`，默认聚合上限为 `1MB`，仅在显式启用后生效。 | 启用 group commit 不改变单个 write 的原子性和恢复语义；含同步请求的 group 必须按同步写路径处理，并通过 `ldb.groupCommitStats` 可观测。 |
| 插件异步默认策略 | `Options.pluginAsyncEnabled` 默认 `false`；启用后仅提交后通知类回调可异步，`beforeWrite` 仍同步执行。 | `beforeWrite` 拒绝仍阻止提交且不产生部分写；`afterWrite` 异步失败属于 post-commit 通知失败，通过插件统计和最后失败属性暴露。 |

## 完善规划

以下事项不是对当前承诺的否定，而是记录本轮已完成的工程化固化项、证据链和开发者约束。执行细则见 [LDB 插件化完善规划执行手册](ldb-plugin-improvement-execution-plan.md)。

| 编号 | 需要完善的点 | 当前缺口 | 交付物 | 验收标准 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| G1 | 承诺到测试的追踪矩阵 | A1-A12 已有测试和 longrun 覆盖，但证据分散在多个测试类、profile 和日志约定中。 | 在本文补充验收矩阵到测试类、longrun profile、关键日志字段的映射。 | 每个验收项至少能定位到一个测试类或 longrun profile；无法自动化的项必须写明人工验收命令和通过日志。 | 已验收 |
| G2 | 插件组合场景回归 | 已覆盖 beforeWrite 拒绝、afterWrite/afterCheckpoint 失败、排序、异步通知、timeout、provider 发现、外部目录隔离、versionRange、capability enforcement、兼容性 testkit 与 async 组合场景。 | 持续保留插件回归测试或 longrun profile，覆盖加载失败、版本不匹配、未授权 mutation、异步队列关闭等待、托管 classloader 释放和统计输出。 | 插件化回归能证明失败清晰、不会产生部分提交、统计属性可定位原因、兼容性检查可运行，且默认关闭外部发现。 | 已验收 |
| G3 | column family id/name 命名约束 | LDB 不维护全局 id 分配表，但插件和使用方仍需要统一的本地命名规范，避免不同插件复用同一 id 或语义漂移。 | 在插件开发手册新增 column family id/name 约束模板，包括推荐 id 范围、命名格式、废弃策略和冲突处理示例。 | 新插件接入文档中必须声明所需 id/name；冲突时打开或创建失败，不能静默复用。 | 已验收 |
| G4 | counter 兼容证据固化 | LDB 已承诺 `addLong` 语义，但还需要把 8 字节 long 编码、非法 delta、reopen/recovery 的证据固定到专门验收项。 | 增补 counter 兼容测试说明或测试用例，覆盖正常增量、非法 delta、跨列族 batch 和 reopen。 | counter 验收能证明普通 value 与 counter value 不混用；非法 delta 写前失败且无部分提交。 | 已验收 |
| G5 | 写入策略与性能报告联动 | `sync=false`、group commit 默认关闭、插件异步默认关闭已经明确，但性能日志和 release note 需要持续展示这些影响指标可比性的选项。 | longrun/performance 报告和 release note 检查表固定输出 `syncWrites`、group commit、插件异步和关键写入配置。 | 性能报告中能直接看出写入持久化策略和 group commit 是否启用；配置变化必须进入 release note。 | 已验收 |
| G6 | 版本升级兼容门禁 | 文档已定义升级边界，但发布前的兼容检查仍需要固定为可重复执行的门禁清单。 | 在 release 文档或本文补充升级前检查命令、旧库打开、backup/restore、插件 default 方法和 longrun 基线。 | 每次升级前能按清单完成验证；破坏性变更必须有迁移说明、回滚边界和最低验收结果。 | 已验收 |






