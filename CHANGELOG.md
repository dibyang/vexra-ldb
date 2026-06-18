# Changelog

[English](CHANGELOG.en.md) | 中文

本文档记录 `vexra-ldb` 的重要变更。格式遵循 Keep a Changelog 的精神，版本号遵循语义化版本约定。

## [Unreleased]

## [0.6.0] - 2026-06-18

### Added

- 新增 RocksDB 差距与下一版本规划中英文设计文档，确认 `0.6.0` 下一开发线、`11.1.1` 对标 baseline、`MultiGet` 低风险实现项和高级 API unsupported 边界。
- 新增 `LDB#get(List<byte[]>)` 及列族重载的批量点查能力，按输入顺序返回结果，未命中 key 返回 null。
- 新增 `ldb.recoveryEvidence` 与 `ldb.backupEvidence` 诊断属性，用于归档 WAL/Manifest、check/repair、checkpoint、backup/restore、对象仓库和清理 dry-run 证据约定。
- 新增 `ldb.columnFamilyEvidence` 诊断属性，用于归档列族注册表、active/dropped 数量、MemTable、Level 文件和 drop/rename 策略。
- 新增 `ldb.prefixReadiness` 诊断属性，用于归档 PrefixExtractor、prefix bloom、cache warmup 的启用前置条件和当前 cache/filter 配置；本阶段只观测，不改变读路径。
- 收敛 RocksDB 差距规划开放问题，补充性能门禁、property 契约、旧版本打开新库、外部观测和备份存储后端的默认决策。
- 新增 `LdbTool scan <db> [limit]` 只读诊断命令，按默认列族 key 顺序输出 base64 JSON 样本，默认最多 100 条，不创建 WAL 或修改 MANIFEST。
- 新增 `docs/release.md` 的 `0.6.0` 发布前验证记录，要求归档 releaseGate、MultiGet、恢复/备份/列族 evidence、`ldb.prefixReadiness`、`scan` 和开放问题默认决策。
- 增强 CURRENT/MANIFEST 恢复校验，`check` 与 `open` 均拒绝非法 Manifest 文件名或包含路径分隔符的 CURRENT 内容。
- 增强 `checkBackup` 证据报告，`CheckReport.checkedFiles` 现在会记录 `BACKUP-MANIFEST.json`、`OBJECT-REFS.json` 和已校验对象文件名。
- 增强 `releaseGate` 报告，新增 `rocksdbGapPlan` 和 `rocksdbGapGates` 分组，用于归档 RocksDB baseline、下一版本目标、MultiGet 验收和高级 API unsupported 策略。
- 新增非空列族 drop/rename/tombstone 中英文设计文档，明确列族逻辑删除、稳定 cfId、MANIFEST/注册表历史、回滚和兼容边界。
- 新增 Backup Engine 共享对象仓库与引用计数中英文设计文档，规划对象复用、备份链 GC、dry-run 清理和发布状态机。
- 新增长期压测与 benchmark 报告框架中英文设计文档，规划 workload 矩阵、机器可读报告、发布阈值和失败保留策略。
- 新增生产级就绪计划中英文文档，规划旧版本升级样本、`releaseGate`、备份对象仓库损坏注入、列族 tombstone 长压测、production-gate longrun 和运维 Runbook。
- 新增运维 Runbook 中英文文档，覆盖发布门禁、升级、备份、恢复、check/repair、列族 tombstone 和故障处置顺序。
- 实现运行时列族非空 drop tombstone 与 rename 最小闭环，`ldb.api.supportedFeatures` 已标记对应能力。
- 增强增量备份对象仓库，新增 `objects/`、`OBJECT-REFS.json` 和 `planPurgeBackups` dry-run 清理计划。
- 增强 longrun benchmark 报告框架，新增 `summary.json`、`operations.csv`、`failures.json`、`properties-before.json`、`properties-after.json` 和显式 Gradle 任务入口。

### Changed

- 将构建版本切换为正式发布版本 `0.6.0`，用于本地发布产物验证。

## [0.4.0] - 2026-06-08

### Added

- 新增默认关闭的最小 group commit 实现，提供 `Options.groupCommitEnabled`、`groupCommitMaxDelayNanos`、`groupCommitMaxBatchBytes` 和 `ldb.groupCommitStats`。
- 新增 longrun 写入策略可比性报告：`workloadSyncWrites`、group commit 配置、插件 async 配置和插件回调预算配置会写入 `state/run.properties` 并汇总到 `report/summary.properties`。
- 新增 `LDBFactory.createIncrementalBackup` 和 `checkBackup`，通过 `BACKUP-MANIFEST.json` 记录复制文件和复用文件，并优先复用上一备份中的 SST 文件。
- 新增 `LDBFactory.planRepair` 和 `ldb repair-plan <db>` dry-run 入口，输出 repair 计划但不修改数据库目录。
- 新增 operation latency histogram property、`ldb.blockCacheStats` 以及 `incremental-backup` / `check-backup` 工具命令。
- 新增运行时列族 `list/create/drop-empty` 最小实现和 `COLUMN-FAMILIES` 注册表，backup、checkpoint、check、repair 会携带并校验该注册表。
- 新增列族生命周期中英文设计文档和损坏矩阵，覆盖坏注册表、缺失注册表、坏 CURRENT、坏备份注册表和 runtime CF WAL-only repair 场景。
- 新增 `LdbPluginCompatibility` 轻量 provider/plugin 兼容性 testkit。
- 新增 `ldb.plugin.maxTotalCallbackMillis`，按单插件累计回调耗时做资源治理。
- 新增 `LdbPlugin.unwrap()` 默认方法，作为托管插件包装器协作点。
- 新增 longrun 插件配置加载、provider 发现、版本范围检查、外部插件目录发现、diagnostic 插件以及 `sample-audit` 示例插件/profile。

### Changed

- longrun 外部插件 provider 改为受托管的 child-first 独立 classloader，并在插件关闭时释放；LDB API 包仍由父加载器提供。
- 插件 capability enforcement 现在同时约束 metadata 读取、插件上下文创建写批次和 checkpoint hook。
- `Options.cacheBlocks(false)` 现在会真正关闭 BlockCache，同时保留可观测的 disabled 状态。
- `ldb.api.supportedFeatures` 标记 runtime column family list/create/drop-empty，`ldb.api.unsupportedFeatures` 保留 non-empty drop 与 rename。
- 新增 `ldb.api.ecosystemGaps`，解释 MergeOperator、PrefixExtractor、transactions、TTL、custom Env、非空列族 drop/rename 等生态差距的阻塞原因。
- 更新性能/可靠性、项目设计、API 兼容、插件和外部承诺的中英文文档，使其对齐 0.4.0 能力集合。

### Verification

- 插件相关聚焦发布验证已通过：`./gradlew.bat test --tests "*LdbPluginTest" --tests "*LongRunPluginResolverTest" --tests "*LongRunConfigTest" --tests "*SmokeRunnerTest" --tests "*ReportAnalyzerTest"`。
- 正式发布前仍需执行完整发布验证：`./gradlew.bat clean test` 和 `./gradlew.bat clean publishToMavenLocal`。

## [0.2.0] - 2026-06-01

### Added

- 新增中文 README 和英文 README。
- 新增项目整体设计文档及英文副本。
- 新增贡献指南、安全政策、NOTICE、发布说明和 CI 配置。
- 新增 benchmark/soak 回归入口，覆盖写入、随机读、snapshot scan、manual compaction、checkpoint 和 reopen 工作流。
- 新增 compaction 高压可靠性回归，覆盖手动压缩压力和写入突增后的反复压缩恢复。
- 新增 checkpoint、backup/restore、repair 和 check 串联的恢复闭环回归测试。
- 新增后续性能可靠性专项评估文档及英文副本，规划 group commit、增量备份和 range delete 强化路径。
- 新增插件能力增强设计文档及英文副本。
- 新增插件增强 API：`LdbPluginDescriptor`、`LdbPluginFailurePolicy`、`OptionsView`、`WriteEvent` 和 `WriteBatchView`。
- 新增插件观测属性：`ldb.plugins`、`ldb.pluginStats`、`ldb.plugin.<index>.stats` 和 `ldb.plugin.lastFailure`。
- 新增 Windows + Ubuntu JDK 8 CI，以及本地 Maven 发布产物校验任务。
- 新增 `CHANGELOG`、贡献指南、安全政策、行为准则和发布说明的英文副本，并为用户可见文档补充中英切换入口。

### Changed

- 修正 Maven POM 元数据，使项目名称、描述和主页指向 `vexra-ldb`。
- 优化 range tombstone 读取路径，避免普通 point get 在无关 tombstone 上做不必要扫描。
- 新增 `Options.writeSlowdownDelayNanos`，使 Level-0 slowdown 延迟可配置并可通过诊断属性观测。
- 明确插件 `afterWrite` 和 `afterCheckpoint` 的提交后通知失败语义，避免调用方误以为已提交数据会回滚。
- 将版本号切换为正式发布版本 `0.2.0`。

### Fixed

- 补齐插件提交后失败、checkpoint 后置失败、只读配置视图和只读写入事件的回归覆盖。
- 补齐本地发布产物验证，确认主 jar、sources jar、javadoc jar、POM、module 和对应签名文件可生成。

## [0.1.0]

### Added

- 新增基础 LDB API、WAL、MemTable、SSTable、MANIFEST/CURRENT、VersionSet 和 compaction。
- 新增列族、snapshot cursor、checkpoint、离线 check/repair/backup/restore 和工具命令。
- 新增可靠性、range delete、API 兼容和项目整体设计文档。
