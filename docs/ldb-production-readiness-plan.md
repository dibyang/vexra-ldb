# LDB 生产级就绪计划

[English](ldb-production-readiness-plan.en.md) | 中文

## 背景

LDB 已完成插件隔离、运行时列族、group commit、增量备份对象仓库、列族 tombstone 生命周期和 longrun benchmark 报告框架等关键能力。下一阶段的主要风险不再是单点功能缺失，而是缺少可重复的生产级发布证明：旧版本数据能否升级打开、备份损坏能否被发现、长生命周期列族 tombstone 能否稳定回收、长压测结果能否作为发布门禁沉淀，以及线上故障处置是否有明确 Runbook。

本文档把“从实验室可用到生产级可信”的第十八阶段落档，作为后续代码、测试、运维文档和发布流程的共同验收基线。

## 目标

- 固化可重复执行的发布门禁，覆盖单元测试、兼容样本、longrun、备份校验和报告归档。
- 验证 `0.4.0` 及后续历史版本创建的数据目录可被 `0.5.0` 打开、检查或给出清晰迁移错误。
- 增强备份对象仓库和列族 tombstone 的损坏注入、长生命周期和物理 GC 证明。
- 为生产使用者提供清晰的运行、备份、恢复、校验、升级和故障处置 Runbook。
- 保持 JDK 8、Gradle Wrapper、UTF-8 文档和中英文文档同步。

## 非目标

- 不在本阶段实现 MergeOperator、PrefixExtractor、transactions、TTL、custom Env 或完整 RocksDB CLI 兼容。
- 不把长时间 soak 默认并入普通 `test`，避免日常开发测试不可控变慢。
- 不承诺 benchmark 数字适用于所有生产环境；报告只作为当前硬件、配置和 workload 下的可重复基线。
- 不引入新的 WAL/SST/MANIFEST 磁盘格式变更；如后续必须变更，应另起兼容性专项设计。

## 现状/已有流程

| 能力 | 当前入口 | 现状 |
| --- | --- | --- |
| 常规测试 | `.\gradlew.bat test` | 覆盖核心 API、恢复、备份、列族、工具命令和插件相关测试 |
| 本地发布产物 | `.\gradlew.bat clean publishToMavenLocal` | 生成 jar、sources、javadoc、pom 和 module |
| longrun 报告 | `:ldb-longrun:benchmarkReport`、`:ldb-longrun:longRunTest`、`:ldb-longrun:releaseSoakTest` | 已输出 `summary.json`、`operations.csv`、`failures.json` 和前后属性快照 |
| 增量备份 | `LDBFactory.createIncrementalBackup/checkBackup/planPurgeBackups` | 已有 `objects/`、`OBJECT-REFS.json` 和 dry-run 清理计划 |
| 列族生命周期 | runtime list/create/empty-drop、非空 drop tombstone、rename | 已支持稳定 cfId 和 tombstone 语义，仍需长生命周期证明 |
| 发布说明 | `docs/release.md`、`CHANGELOG.md` | 已记录 0.5.0 发布前验证项，但缺统一 production gate |

## 核心约束

- 所有变更保持 JDK 8 兼容，不引入高版本语言特性。
- 发布门禁必须显式执行，不应让普通开发测试默认承担长时间 soak。
- 兼容性测试使用只读或临时副本，不修改历史样本库。
- 损坏注入只能作用于临时目录或测试副本，不得直接破坏真实备份根目录。
- 每个可审查增量完成后本地提交，提交信息使用中文。
- 设计、README、Release、Changelog 和运维文档必须同步维护英文副本和语言切换入口。

## 接口设计

| 入口 | 类型 | 规划职责 | 输出/失败语义 |
| --- | --- | --- | --- |
| `releaseGate` | Gradle 任务 | 聚合短门禁：`test`、旧版本样本打开、备份校验、轻量 longrun profile、报告生成 | 失败返回非 0，保留 `build/reports/ldb-release-gate/` |
| `:test --tests "*Upgrade*"` | 测试过滤 | 验证历史样本库打开、读取、check、backup/restore | 失败时输出具体版本、样本名和阶段 |
| `:ldb-longrun:releaseSoakTest` | Gradle 任务 | 执行 release soak profile，验证 compaction、backup、snapshot、插件和报告稳定性 | 保留 report 目录，失败归档 `failures.json` |
| `LDBFactory.checkBackup` | API/工具底座 | 校验备份目录、对象仓库引用和 manifest 一致性 | 损坏返回 `ok=false` 或测试断言异常 |
| `LDBFactory.planPurgeBackups` | API/工具底座 | dry-run 验证备份对象仓库 GC 影响 | 不产生删除副作用 |
| `docs/operations.md` | 运维文档 | 说明生产启动、备份、恢复、升级、检查和故障处置流程 | 中英文同步维护 |

## 数据结构

### 发布门禁报告

规划新增 `RELEASE-GATE-REPORT.json` 与同目录 Markdown 摘要：

| 字段 | 含义 |
| --- | --- |
| `version` | 当前项目版本 |
| `commit` | 执行门禁时的 Git commit |
| `javaVersion` | 执行环境 JDK 版本 |
| `startedAt` / `finishedAt` | 门禁开始和结束时间 |
| `gates[]` | 每个门禁项的名称、命令、耗时、结果和报告路径 |
| `artifacts[]` | longrun、备份、兼容样本和测试报告归档路径 |
| `failures[]` | 失败项、异常摘要和建议排查入口 |
| `result` | `PASSED` 或 `FAILED` |

### 旧版本升级样本

规划将历史样本固定在测试资源目录或测试生成夹具中，推荐结构：

```text
src/test/resources/upgrade/
  0.4.0/
    README.md
    basic-db.zip
    column-family-db.zip
    backup-root.zip
```

样本必须记录创建版本、创建命令、期望 key/value、列族集合和备份校验期望。

## 状态机

| 状态 | 触发 | 下一状态 |
| --- | --- | --- |
| `PLANNED` | 文档落档并确认阶段范围 | `IMPLEMENTING` |
| `IMPLEMENTING` | 某个 18.x 增量开始实现 | `GATE_RUNNING` 或 `PLANNED` |
| `GATE_RUNNING` | 执行 release gate、longrun 或损坏注入矩阵 | `PASSED` 或 `FAILED` |
| `PASSED` | 所有必需门禁通过且报告归档 | `RELEASE_ALLOWED` |
| `FAILED` | 任一必需门禁失败 | `RELEASE_BLOCKED` |
| `RELEASE_BLOCKED` | 修复并重新执行门禁 | `GATE_RUNNING` |

非法转换：未执行门禁不得从 `IMPLEMENTING` 直接进入 `RELEASE_ALLOWED`；失败报告未归档不得关闭阶段。

## 时序流程

1. 先落本文档、可靠性计划、项目设计、README、Release 和 Changelog。
2. 18.1 新增旧版本升级样本库和兼容测试。
3. 18.2 新增 `releaseGate` 聚合任务和机器可读报告。
4. 18.3 补齐备份对象仓库损坏注入矩阵。
5. 18.4 补齐列族 tombstone 长生命周期压测和物理 GC 证明。
6. 18.5 固化 production-gate longrun profile 和 benchmark 报告归档规则。
7. 18.6 补齐运维 Runbook，并把正式发布检查表指向生产级门禁。

## 异常处理

- `releaseGate` 任一子门禁失败时返回非 0，并保留已生成报告。
- 旧版本样本打开失败时，必须区分“不兼容且有清晰迁移错误”和“未知恢复错误”。
- 备份对象仓库损坏注入必须验证 check/restore 不会产生半成品目标目录。
- longrun 失败必须保留 workload 配置、属性快照、失败摘要和必要日志路径。
- 运维 Runbook 必须给出“停止继续写入、先 checkpoint/backup、再 check/repair”的保守处置顺序。

## 幂等性

- `releaseGate` 报告写入带时间戳或构建号的目录，重复执行不会覆盖历史结论。
- 旧版本样本以只读 zip 或复制到临时目录的方式参与测试。
- 损坏注入始终基于临时副本，重复运行前清理测试 workDir。
- `planPurgeBackups` 默认 dry-run，不改变对象仓库引用计数。
- longrun profile 使用明确的 workDir 配置，失败后保留目录供排查。

## 回滚策略

- 本文档本身不改变磁盘格式或运行时行为，可通过回滚文档提交撤销计划。
- 若 `releaseGate` 中某个新门禁过慢或不稳定，可从聚合任务中临时移除，但必须保留独立任务和风险记录。
- 若旧版本样本揭示真实不兼容，应阻断发布，或者在 release note 中明确迁移步骤、不可降级边界和恢复方式。
- 若备份/longrun 发现稳定性缺陷，应优先保留失败样本和报告，再进入修复阶段。

## 兼容性

- `0.5.0` 发布前至少验证 `0.4.0` 样本；后续版本应保留最近一个正式版本和一个长期样本。
- 新版本能读取旧数据时，必须验证 point get、snapshot cursor、列族注册表、check、backup/restore。
- 如未来引入新磁盘格式，旧版本打开新库必须 fail-fast，不能静默损坏。
- 所有门禁和脚本必须保持 Windows PowerShell 与常见 Linux shell 可执行路径清晰。

## 灰度/迁移

| 顺序 | 内容 | 发布影响 |
| --- | --- | --- |
| 18.1 | 旧版本升级样本库 | 作为 0.5.0 发布前硬门禁 |
| 18.2 | `releaseGate` 聚合任务 | 作为发布前统一入口 |
| 18.3 | 备份对象仓库损坏注入 | 提升备份/恢复可信度 |
| 18.4 | 列族 tombstone 长压测 | 证明 drop/rename 后长期清理安全 |
| 18.5 | production-gate longrun profile | 固化性能和可靠性回归报告 |
| 18.6 | 运维 Runbook | 面向生产使用者补齐操作手册 |

## 测试方案

- 单元测试：报告 JSON 序列化、门禁结果聚合、失败项摘要。
- 兼容测试：旧版本样本打开、读取、check、backup/restore。
- 故障注入：缺失对象文件、错误引用计数、损坏 manifest、孤儿对象、restore 目标回滚。
- 长生命周期测试：列族 drop/rename 后跨 reopen、compaction、backup、repair 和 snapshot cursor。
- longrun：短门禁 profile、release soak profile 和失败报告归档。
- 发布验证：`.\gradlew.bat test`、`.\gradlew.bat clean publishToMavenLocal`、`.\gradlew.bat releaseGate`。

## 风险点

| 风险 | 严重性 | 缓解 |
| --- | --- | --- |
| release gate 过慢影响开发效率 | 中 | 分为 short CI gate、release gate 和 nightly soak |
| 历史样本漂移或创建过程不可复现 | 高 | 固化样本说明、创建版本、期望数据和校验命令 |
| benchmark 阈值受硬件波动影响 | 中 | 记录环境信息，先以稳定性和错误率为硬门禁，性能阈值保守设定 |
| 损坏注入误作用到真实数据 | 高 | 只允许临时副本和测试 workDir，文档中明确禁止真实目录注入 |
| Runbook 与工具行为不同步 | 中 | Release checklist 要求同步检查运维文档 |

## 分阶段实施计划

| 阶段 | 标题 | 交付物 | 验收 |
| --- | --- | --- | --- |
| 18.1 | 旧版本升级兼容样本库 | `0.4.0` 样本、兼容测试、样本 README | 已完成：新版本可打开并校验样本，或输出清晰迁移错误 |
| 18.2 | 生产级 `releaseGate` 聚合任务 | Gradle 任务、`RELEASE-GATE-REPORT.json`、Markdown 摘要 | 已完成：任一门禁失败时整体失败并保留报告 |
| 18.3 | 备份对象仓库损坏注入矩阵 | 缺失对象、坏引用、孤儿对象、坏 manifest、restore 回滚测试 | 已完成：`checkBackup`/restore 对损坏输入 fail-fast 且不污染目标 |
| 18.4 | 列族 tombstone 长压测与物理 GC 证明 | 长生命周期 drop/rename/reopen/compact/backup/repair 测试 | 已完成：tombstone 不破坏旧快照，cfId 不复用；更激进物理 GC 留给后续硬化 |
| 18.5 | production-gate longrun profile | 发布门禁 profile、benchmark 报告归档规则 | 已完成：输出可复查报告，失败保留 workload 和属性快照 |
| 18.6 | 运维手册与故障处置 Runbook | `docs/operations.md` 和英文副本 | 已完成：覆盖备份、恢复、升级、check、repair、发布前门禁 |

## 阶段状态与下一阶段

第十八阶段已经形成 `0.5.0` 发布前最小闭环。后续不再把 18.1-18.6 作为待补计划，而是作为发布门禁持续复跑和归档。

下一阶段转入 `docs/ldb-reliability-plan.md` 的第十九至第二十二阶段：

| 阶段 | 标题 | 生产价值 |
| --- | --- | --- |
| 19 | checkpoint/backup 生产证据固化 | 证明跨文件系统、低磁盘、权限失败、长备份链和报告归档可控 |
| 20 | WAL 生命周期与写入策略生产化 | 明确 WAL 归档/保留/清理策略，并沉淀 group commit 长基线 |
| 21 | 运维生态与外部观测 | 统一 CLI/报告索引，接入外部指标或趋势分析 |
| 22 | RocksDB 高级 API 兼容评审 | 独立评审 MergeOperator、PrefixExtractor、transactions、TTL、custom Env 等高级能力 |
