# LDB 插件能力增强设计

[English](ldb-plugin-design.en.md) | 中文

## 背景

LDB 当前已经提供 `LdbPlugin` 和 `LdbPluginContext`，允许上层模块在不修改核心存储实现的情况下参与列族声明、打开、写入、checkpoint 和关闭流程。现有实现更接近轻量 hook：插件按 `Options.addPlugin` 的添加顺序同步执行，`beforeWrite` 可以阻止写入，`afterWrite` 在写入提交后通知，checkpoint 和 close 也有对应回调。

随着 LDB 继续面向嵌入式持久化、诊断、备份和上层系统集成，插件机制需要补齐生产可用边界：异常语义要可解释，慢插件要可观测，插件可见上下文要尽量只读，写入 batch 的可变能力要显式受控，多插件执行顺序和失败策略要有文档和测试约束。

## 目标

- 明确插件生命周期各阶段的提交语义、失败语义和调用顺序。
- 增加插件观测面，暴露插件列表、回调次数、失败次数、耗时和最近失败。
- 收口插件上下文的可变配置暴露，避免插件在打开后误改运行时配置。
- 规划写入事件的只读视图和 batch mutation 边界，降低插件误改业务写入的风险。
- 以兼容方式分阶段增强，不改变 WAL、SST、MANIFEST、CURRENT 等磁盘格式。

## 非目标

- 不实现动态插件加载、类加载隔离或第三方插件市场。
- 不引入远程插件调用、网络回调、事务插件或跨进程插件沙箱。
- 不把插件设计成安全边界；当前插件仍假定为可信内部扩展。
- 不在本阶段改变现有插件默认执行顺序和已有回调方法签名。

## 现状/已有流程

| 能力 | 当前行为 | 风险/缺口 |
| --- | --- | --- |
| 插件注册 | `Options.addPlugin` 调用 `plugin.configure(options)` 并保存插件 | `configure` 可以修改 `Options`，但缺少插件描述信息 |
| 打开回调 | `LDbImpl` 打开完成后调用 `plugin.onOpen(context)` | `context.getOptions()` 返回可变 `Options` |
| 写前回调 | WAL 和 MemTable 写入前调用 `beforeWrite(updates, options)` | 插件可修改 batch，默认能力过强 |
| 写后回调 | WAL 和 MemTable 写入完成后调用 `afterWrite(updates, options, snapshot)` | 写入已提交，回调失败仍向调用方抛出异常，容易被误解为写入失败 |
| checkpoint 回调 | checkpoint 前后调用 `beforeCheckpoint/afterCheckpoint` | 缺少耗时、失败统计和慢回调日志 |
| 关闭回调 | close 路径调用 `beforeClose` 和 `close` | close 失败已有聚合，但缺少插件维度可观测数据 |
| 多插件顺序 | 按 `Options` 内插件列表顺序执行 | 没有 name、version、priority、failurePolicy |

## 核心约束

- 保持 JDK8 兼容。
- 不改变现有磁盘格式和恢复语义。
- 默认插件执行仍同步 inline，避免引入异步提交顺序变化。
- `beforeWrite` 失败必须继续保证不写 WAL、不推进 sequence、不应用 MemTable。
- `afterWrite` 失败必须明确标注为“提交后通知失败”，不能在文档或异常语义上暗示数据已回滚。
- 新增 property 字段允许后续扩展，调用方不得依赖固定字段顺序。

## 接口设计

### 生命周期语义

| 回调 | 阶段 | 是否允许阻止核心操作 | 失败语义 |
| --- | --- | --- | --- |
| `configure(Options)` | 插件加入 `Options` 时 | 是 | 打开前失败，数据库未开始创建 |
| `onOpen(LdbPluginContext)` | 数据库打开完成后 | 是 | 打开失败，LDB 关闭已创建资源 |
| `beforeWrite(LdbWriteBatch, WriteOptions)` | WAL 写入前 | 是 | 写入失败，数据不得落盘 |
| `afterWrite(LdbWriteBatch, WriteOptions, Snapshot)` | WAL 与 MemTable 完成后 | 否 | 数据已提交；失败记录为后置通知失败 |
| `beforeCheckpoint(File)` | checkpoint 创建前 | 是 | checkpoint 失败，源库仍可继续使用 |
| `afterCheckpoint(File)` | checkpoint 创建和校验后 | 否 | checkpoint 已生成；失败记录为后置通知失败 |
| `beforeClose()` | close 开始前 | 否 | close 继续释放资源，失败聚合记录 |
| `close()` | 插件资源释放 | 否 | close 继续释放其他资源，失败聚合记录 |

### 观测属性

| Property | 含义 | 示例字段 |
| --- | --- | --- |
| `ldb.plugins` | 当前插件列表，按最终执行顺序输出 | `0:AuditPlugin:order=0:capabilities=OBSERVE_WRITE` |
| `ldb.pluginStats` | 所有插件聚合统计 | `callbacks=10,failures=1,maxMicros=1200,lastFailure=afterWrite:AuditPlugin` |
| `ldb.plugin.<index>.stats` | 单个插件统计 | `name=AuditPlugin,beforeWrite.count=3,afterWrite.failures=1,maxMicros=1200` |
| `ldb.plugin.lastFailure` | 最近插件失败摘要 | `phase=afterWrite,plugin=AuditPlugin,message=...` |
| `ldb.plugin.executionPolicy` | 当前生命周期执行策略 | `asyncEnabled=false,beforeWrite=syncFailFast` |

### 上下文只读化

短期兼容方案：

- 保留 `LdbPluginContext#getOptions()`，但文档明确返回对象不得在 `onOpen` 后修改。
- 新增测试确保插件在 `onOpen` 后修改 `Options` 不会绕过核心校验。

中期方案：

- 新增 `OptionsView` 或 `OptionsSnapshot`，只暴露只读 getter。
- 新增 `LdbPluginContext#getOptionsView()`。
- 将 `getOptions()` 标记为兼容保留接口，后续版本建议迁移。

### 写入事件边界

短期兼容方案：

- 保持 `beforeWrite(LdbWriteBatch, WriteOptions)` 可变能力。
- 增加统计和文档，标记“插件修改 batch 是高风险能力”。
- 保留写前二次校验，避免插件修改后绕过 WAL 编码和列族约束。

中期方案：

- 新增只读 `WriteEvent` 或 `WriteBatchView`：
  - `getColumnFamilies()`
  - `size()`
  - `isEmpty()`
  - `getWriteOptions()`
  - `getSnapshot()`
- 新增插件能力声明，例如 `PluginCapability.MUTATE_WRITE_BATCH`。
- 未声明 mutation 能力的插件只接收只读事件。

### 插件描述信息

`LdbPluginDescriptor` 暴露稳定插件元数据：

| 字段 | 含义 |
| --- | --- |
| `name` | 稳定插件名 |
| `version` | 插件版本 |
| `order` | 执行顺序，数值小的先执行，相同值保持注册顺序 |
| `capabilities` | 能力声明，如 observe、mutate、checkpoint |
| `failurePolicy` | 失败策略，如 fail-fast、record-and-continue |

## 数据结构

### PluginStats

| 字段 | 含义 |
| --- | --- |
| `pluginIndex` | 插件在 `Options` 中的顺序 |
| `pluginClassName` | 插件类名 |
| `phaseCounts` | 各生命周期阶段调用次数 |
| `phaseFailures` | 各生命周期阶段失败次数 |
| `totalNanos` | 累计耗时 |
| `maxNanos` | 最大单次耗时 |
| `lastFailurePhase` | 最近失败阶段 |
| `lastFailureMessage` | 最近失败摘要 |

### PluginFailure

| 字段 | 含义 |
| --- | --- |
| `phase` | 失败回调阶段 |
| `pluginClassName` | 插件类名 |
| `message` | 异常摘要 |
| `committed` | 对写入类回调标记数据是否已经提交 |

## 状态机

插件随 LDB 实例生命周期流转：

`CONFIGURED -> OPENING -> OPEN -> CLOSING -> CLOSED`

| 状态 | 触发 | 允许回调 |
| --- | --- | --- |
| `CONFIGURED` | `Options.addPlugin` 完成 | `configure` |
| `OPENING` | LDB 构造和恢复中 | 无外部回调，直到 `onOpen` |
| `OPEN` | LDB 打开成功 | `beforeWrite`、`afterWrite`、`beforeCheckpoint`、`afterCheckpoint` |
| `CLOSING` | 调用 `close()` | `beforeClose`、`close` |
| `CLOSED` | 资源释放完成 | 不再调用插件 |

非法状态：

- `CLOSED` 后不得再触发插件回调。
- `beforeWrite` 失败后不得触发同一写入的 `afterWrite`。
- `afterWrite` 失败后不得回滚已经写入 WAL 和 MemTable 的数据。

## 时序流程

### 打开流程

1. 调用方创建 `Options` 并通过 `addPlugin` 注册插件。
2. 每个插件执行 `configure(options)`，可声明列族。
3. LDB 根据最终 `Options` 创建列族状态、恢复版本和 WAL。
4. 打开成功后按顺序调用 `onOpen(context)`。
5. 任一 `onOpen` 失败，打开失败并关闭已经创建的资源。

### 写入流程

1. 调用方创建 batch。
2. LDB 执行写前 batch 校验。
3. 按顺序调用 `beforeWrite`。
4. 再次执行 batch 校验，防止插件 mutation 绕过约束。
5. 分配 sequence、写 WAL、按需 sync、应用 MemTable。
6. 按顺序调用 `afterWrite`，并记录耗时与失败。
7. 如果 `afterWrite` 失败，返回后置通知异常，但文档和统计必须标记数据已提交。

### checkpoint 流程

1. 调用 `beforeCheckpoint(targetDir)`。
2. flush、暂停 compaction、复制或硬链接文件、写 checkpoint 报告。
3. 调用 `afterCheckpoint(targetDir)`。
4. 如果 `afterCheckpoint` 失败，checkpoint 已生成，失败记录为后置通知失败。

## 异常处理

| 场景 | 调用方结果 | 数据状态 | 统计 |
| --- | --- | --- | --- |
| `configure` 失败 | 打开前失败 | 无新 LDB 实例 | 记录到打开异常 |
| `onOpen` 失败 | 打开失败 | 已创建资源被关闭 | 记录打开失败 |
| `beforeWrite` 失败 | 写入失败 | 未写 WAL，未应用 MemTable | `committed=false` |
| WAL/sync/MemTable 失败 | 写入失败 | 按现有恢复语义处理 | 非插件失败 |
| `afterWrite` 失败 | 后置通知失败 | 已提交 | `committed=true` |
| `beforeCheckpoint` 失败 | checkpoint 失败 | 源库不变 | `committed=false` |
| `afterCheckpoint` 失败 | 后置通知失败 | checkpoint 已生成 | `committed=true` |
| `beforeClose/close` 失败 | close 聚合失败 | 尽量释放所有资源 | close failure 记录插件阶段 |

## 幂等性

- 插件回调可能在调用方重试时被重复触发，插件实现必须自行保证外部副作用幂等。
- `afterWrite` 的外部通知不得作为 LDB 数据提交依据；如果通知失败，数据仍以 LDB 返回前的 WAL/MemTable 状态为准。
- 插件统计是诊断信息，不参与恢复和数据可见性判断。

## 回滚策略

- 本设计不改变磁盘格式，回滚代码后旧库仍可打开。
- 插件统计 property 属于只读观测面，回滚只会导致 property 不存在。
- `OptionsView`、`WriteEvent` 等新增接口应以兼容方式引入，旧插件继续运行。
- 如果后续默认禁止 batch mutation，应先提供兼容开关或迁移期警告。

## 兼容性

| 维度 | 策略 |
| --- | --- |
| 源码兼容 | 不删除现有 `LdbPlugin` 方法，不改变参数类型 |
| 二进制兼容 | 新增 default 方法优先，避免破坏已有插件实现 |
| 数据兼容 | 不写入新的 WAL/SST/MANIFEST/CURRENT 格式 |
| 行为兼容 | 默认同步执行顺序不变；新增统计不影响核心写入语义 |
| 运维兼容 | 新 property 可新增字段，调用方按 key 解析 |

## 灰度/迁移

1. 先新增文档和测试，固定当前事实语义。
2. 增加插件统计和慢回调日志，默认启用但只读。
3. 增加只读 `OptionsView`，文档建议新插件使用。
4. 增加 `WriteEvent` 只读事件，保留旧可变 batch 回调。
5. 评估是否引入 `LdbPluginDescriptor` 和 failure policy。
6. 最后再决定是否把默认插件能力从可变 batch 收紧为只读观察。

## 测试方案

| 测试 | 目标 |
| --- | --- |
| `afterWrite` 失败后 reopen | 验证数据已提交且可恢复 |
| `beforeWrite` 失败 | 验证不写 WAL、不推进 sequence、不触发 `afterWrite` |
| 多插件顺序 | 验证按注册顺序调用 |
| 某插件失败 | 验证失败阶段、后续插件行为和统计 |
| checkpoint 后置失败 | 验证 checkpoint 已生成且报告存在 |
| close 插件失败 | 验证继续释放其他资源并聚合 close failure |
| 插件统计 property | 验证回调次数、失败次数、耗时、最近失败可解析 |
| `OptionsView` | 验证插件不能通过上下文误改运行时配置 |
| batch mutation 兼容 | 验证旧插件仍可运行，且二次校验兜底 |

## 风险点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `afterWrite` 失败被调用方当成提交失败 | 业务重试导致重复写或重复外部副作用 | 文档、异常消息和统计标记 `committed=true` |
| 插件慢回调拖慢写入 | 写入 p99 变差 | 慢日志、耗时统计、后续异步评估 |
| 插件修改 batch 导致语义不透明 | 调用方写入被隐式改写 | 只读事件、能力声明、二次校验 |
| 上下文暴露可变 Options | 打开后配置被误改 | `OptionsView`、快照和测试 |
| 多插件失败策略不清 | 排障困难或副作用不一致 | descriptor 和 failure policy 评估 |

## 分阶段实施计划

| 阶段 | 交付物 | 验收 |
| --- | --- | --- |
| 1 | 本设计文档和英文副本 | 已完成：文档合入，明确插件事实语义和后续计划 |
| 2 | 插件失败语义测试 | 已完成：`beforeWrite`、`afterWrite`、checkpoint 后置失败语义有回归 |
| 3 | 插件统计 property | 已完成：`ldb.plugins`、`ldb.pluginStats`、单插件 stats 测试通过 |
| 4 | 只读配置视图 | 已完成：新增 `OptionsView` 快照接口，旧 `getOptions()` 兼容 |
| 5 | 写入只读事件 | 已完成：新增 `WriteEvent` 和 `WriteBatchView`，保留旧回调 |
| 6 | 插件描述与失败策略评估 | 已完成：新增最小 descriptor 和 `failurePolicy`，当前仅提交后通知阶段支持 record-and-continue |

## 已落地实现

- 新增 `LdbPluginDescriptor`、`LdbPluginCapability`、`LdbPluginFailurePolicy`，用于暴露插件名称、版本、顺序、能力和失败策略。
- 新增 `OptionsView`，`LdbPluginContext#getOptionsView()` 返回打开时配置快照；`getOptions()` 保持兼容。
- 新增 `WriteEvent`、`WriteBatchView`，新插件可通过只读事件观察写入；旧的可变 batch 回调继续保留。
- 新增插件观测属性：`ldb.plugins`、`ldb.pluginStats`、`ldb.plugin.<index>.stats`、`ldb.plugin.lastFailure`。
- 新增按 `descriptor.order()` 的稳定插件调度，相同 order 保持注册顺序。
- 新增 provider 发现 API（`LdbPluginProvider`、`LdbPluginLoader`）以及 longrun 配置加载，默认不启用 provider 名称解析。
- 新增 `ldb.plugin.executionPolicy`，用于暴露当前同步执行策略和异步候选阶段。
- `afterWrite` 和 `afterCheckpoint` 失败时，异常消息明确标记为 post-commit notification failure；数据或 checkpoint 不回滚。
- `RECORD_AND_CONTINUE` 只作用于提交后通知阶段，`beforeWrite`、`beforeCheckpoint`、`onOpen` 等影响正确性的阶段仍保持 fail-fast。

## 相关文档

插件化当前能力、开发手册和契约文档统一从文档索引进入：

- [LDB 插件化文档索引](ldb-plugin-docs-index.md)
- [LDB Plugin Documentation Index](ldb-plugin-docs-index.en.md)

## 开放问题

- `afterWrite` 失败是否继续向调用方抛出 `DBException`，还是只记录统计并返回成功？建议短期保持抛出，但异常消息必须明确“写入已提交，后置通知失败”。
- batch mutation 是否应该长期保留为默认能力？建议短期兼容，长期迁移到显式 capability。
- 插件统计是否需要纳入 JSON 报告或只通过 `getProperty` 暴露？建议先只通过 property 暴露。
