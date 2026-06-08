# Changelog

[English](CHANGELOG.en.md) | 中文

本文档记录 `vexra-ldb` 的重要变更。格式遵循 Keep a Changelog 的精神，版本号遵循语义化版本约定。

## [Unreleased]

暂无未发布变更。

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
