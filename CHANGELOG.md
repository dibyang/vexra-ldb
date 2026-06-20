# Changelog

[English](CHANGELOG.en.md) | 中文

本文档记录 `vexra ldb` 的重要变更。格式遵循 Keep a Changelog 的精神，版本号遵循语义化版本约定。

## [Unreleased]

## 0.9.0-SNAPSHOT SF-06 v2 文件格式生产化观测

- 新增 `ldb.tableFormatPolicy` 运行时属性，集中暴露新写入格式、v2 properties 开关、legacy 读取策略、unknown/future fail-fast 策略、回滚动作和生产状态。
- `LdbObservabilityTest` 覆盖默认 v1、显式 v2、回滚说明和 fail-fast 策略证据。
- `storageFormatGates` 新增 `tableFormatPolicyCoverage`，并要求中英文格式参考、设计、验收、README、用户手册和运维文档包含 `ldb.tableFormatPolicy` 生产化说明。
## 0.9.0-SNAPSHOT RR-01 Bloom/filter block 随机读优化

- 启用 `BloomFilterPolicy` 时，SST 写入 `filter.<policyName>` metaindex entry，并在读侧用同名策略执行 full-key `mayContain`。
- Level0、LevelN 和 MultiGet 候选 SST 会在打开 table iterator 前执行 filter 判断；Bloom 返回 false 时记录 `filterSkips` 并跳过该 SST。
- `LdbObservabilityTest` 增加范围内缺失 key 场景，断言 `filterSkips>0`、`mayContainRequests>0`、`mayContainFalse>0`。
- `storageFormatGates` 新增 `filterBlockCoverage`，并要求中英文格式、设计、验收、README、用户手册和运维文档记录 Bloom/filter block 发布前证据。
- 200k warm_readrandom 发布准备对比：LDB ead_optimized 为 247,361.396 ops/s，RocksDB JNI 为 444,235.456 ops/s，比例 55.68%，达到至少 50% 的 P0 目标。
## 0.9.0-SNAPSHOT REL-01 发布链路修正

- 新增 `verifyUserManagedReleaseConfig` 与 `publishUserManagedRelease` 发布入口，后者要求正式版本、显式远程 release 仓库以及 USER_MANAGED/user-managed 或 staging 待审核模式。
- `releaseGate` 新增 `userManagedReleaseConfig` 与 `gitReleaseTraceability` 门禁，发布仓库若出现 AUTOMATIC、无法证明待审核模式，或正式版本上传前无法证明 commit/tag 已推送，则失败。
- 普通 release 仓库选择路径继续 fail-fast，避免再次把普通 `publish` 成功误判为 user-managed 发布；`publishUserManagedRelease` 会在中央仓库上传前强制检查干净工作区、upstream 已推送和 `v${version}` tag 已推送。
## [0.8.0]   2026 06 20

### Added

  新增 SST/table v2 properties block opt-in 写入与读侧解析，记录 table format version、feature set、entry/block/filter/compression/key/checksum 等自描述字段。
  新增 `Options.tableFormatVersion`、`Options.writeTableProperties`、`Options.allowLegacyTableFormat`、`Options.failOnUnknownTableFeature` 与 `OptionsView` 只读快照能力，默认仍写 v1，v2 需要显式 opt-in。
  新增 `ldb.tableFormat` 与 `ldb.storageFormat` 诊断属性，以及 check/repair 报告中的 `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables` 证据字段。
  新增备份元数据 schema、chainId、generation 与 object store schema 证据，增强备份链诊断和未来迁移锚点。
  新增文件格式参考、0.8 设计文档和发布验收矩阵中英文副本，并将 README、用户手册、运维 Runbook、Options API 契约纳入 `storageFormatDocs` 发布门禁。

### Changed

  默认磁盘写入格式保持 v1，旧 SST/table 默认可读；未来格式版本、未知 incompatible feature 和 malformed table format version 默认 fail-fast，避免静默误读。
  `releaseGate` 新增并强化 `storageFormatGates`，覆盖文档、TableProperties、future/malformed 版本保护、mixed-format check、repair、backup、plugin OptionsView 和默认 legacy write policy。
  将构建版本切换为正式发布候选版本 `0.8.0`，用于发布前门禁和本地发布产物验证。

### Verification

  发布前必须执行 `.\gradlew.bat test`、`.\gradlew.bat releaseGate` 和 `.\gradlew.bat publishToMavenLocal`。最终结果记录在 `docs/release.md` 的 `0.8.0 发布前验证记录`。
## [0.6.0]   2026 06 18

### Added

  新增 LDB/RocksDB 性能基线中英文文档、`ldbDbBenchReport` 任务、native `db_bench` 对比脚本和 RocksDB JNI 对比脚本，覆盖 `fillseq`、`readrandom`、`overwrite`、`readwhilewriting` 四类场景。
  新增 RocksDB 差距与下一版本规划中英文设计文档，确认 `0.6.0` 下一开发线、`11.1.1` 对标 baseline、`MultiGet` 低风险实现项和高级 API unsupported 边界。
  新增 `LDB#get(List<byte[]>)` 及列族重载的批量点查能力，按输入顺序返回结果，未命中 key 返回 null。
  新增 `ldb.recoveryEvidence` 与 `ldb.backupEvidence` 诊断属性，用于归档 WAL/Manifest、check/repair、checkpoint、backup/restore、对象仓库和清理 dry run 证据约定。
  新增 `ldb.columnFamilyEvidence` 诊断属性，用于归档列族注册表、active/dropped 数量、MemTable、Level 文件和 drop/rename 策略。
  新增 `ldb.prefixReadiness` 诊断属性，用于归档 PrefixExtractor、prefix bloom、cache warmup 的启用前置条件和当前 cache/filter 配置；本阶段只观测，不改变读路径。
  收敛 RocksDB 差距规划开放问题，补充性能门禁、property 契约、旧版本打开新库、外部观测和备份存储后端的默认决策。
  新增 `LdbTool scan <db> [limit]` 只读诊断命令，按默认列族 key 顺序输出 base64 JSON 样本，默认最多 100 条，不创建 WAL 或修改 MANIFEST。
  新增 `docs/release.md` 的 `0.6.0` 发布前验证记录，要求归档 releaseGate、MultiGet、恢复/备份/列族 evidence、`ldb.prefixReadiness`、`scan` 和开放问题默认决策。
  增强 CURRENT/MANIFEST 恢复校验，`check` 与 `open` 均拒绝非法 Manifest 文件名或包含路径分隔符的 CURRENT 内容。
  增强 `checkBackup` 证据报告，`CheckReport.checkedFiles` 现在会记录 `BACKUP-MANIFEST.json`、`OBJECT-REFS.json` 和已校验对象文件名。
  增强 `releaseGate` 报告，新增 `rocksdbGapPlan` 和 `rocksdbGapGates` 分组，用于归档 RocksDB baseline、下一版本目标、MultiGet 验收和高级 API unsupported 策略。
  新增非空列族 drop/rename/tombstone 中英文设计文档，明确列族逻辑删除、稳定 cfId、MANIFEST/注册表历史、回滚和兼容边界。
  新增 Backup Engine 共享对象仓库与引用计数中英文设计文档，规划对象复用、备份链 GC、dry run 清理和发布状态机。
  新增长期压测与 benchmark 报告框架中英文设计文档，规划 workload 矩阵、机器可读报告、发布阈值和失败保留策略。
  新增生产级就绪计划中英文文档，规划旧版本升级样本、`releaseGate`、备份对象仓库损坏注入、列族 tombstone 长压测、production gate longrun 和运维 Runbook。
  新增运维 Runbook 中英文文档，覆盖发布门禁、升级、备份、恢复、check/repair、列族 tombstone 和故障处置顺序。
  实现运行时列族非空 drop tombstone 与 rename 最小闭环，`ldb.api.supportedFeatures` 已标记对应能力。
  增强增量备份对象仓库，新增 `objects/`、`OBJECT-REFS.json` 和 `planPurgeBackups` dry run 清理计划。
  增强 longrun benchmark 报告框架，新增 `summary.json`、`operations.csv`、`failures.json`、`properties before.json`、`properties after.json` 和显式 Gradle 任务入口。

### Changed

  将构建版本切换为正式发布版本 `0.6.0`，用于本地发布产物验证。

## [0.4.0]   2026 06 08

### Added

  新增默认关闭的最小 group commit 实现，提供 `Options.groupCommitEnabled`、`groupCommitMaxDelayNanos`、`groupCommitMaxBatchBytes` 和 `ldb.groupCommitStats`。
  新增 longrun 写入策略可比性报告：`workloadSyncWrites`、group commit 配置、插件 async 配置和插件回调预算配置会写入 `state/run.properties` 并汇总到 `report/summary.properties`。
  新增 `LDBFactory.createIncrementalBackup` 和 `checkBackup`，通过 `BACKUP-MANIFEST.json` 记录复制文件和复用文件，并优先复用上一备份中的 SST 文件。
  新增 `LDBFactory.planRepair` 和 `ldb repair plan <db>` dry run 入口，输出 repair 计划但不修改数据库目录。
  新增 operation latency histogram property、`ldb.blockCacheStats` 以及 `incremental backup` / `check backup` 工具命令。
  新增运行时列族 `list/create/drop empty` 最小实现和 `COLUMN FAMILIES` 注册表，backup、checkpoint、check、repair 会携带并校验该注册表。
  新增列族生命周期中英文设计文档和损坏矩阵，覆盖坏注册表、缺失注册表、坏 CURRENT、坏备份注册表和 runtime CF WAL only repair 场景。
  新增 `LdbPluginCompatibility` 轻量 provider/plugin 兼容性 testkit。
  新增 `ldb.plugin.maxTotalCallbackMillis`，按单插件累计回调耗时做资源治理。
  新增 `LdbPlugin.unwrap()` 默认方法，作为托管插件包装器协作点。
  新增 longrun 插件配置加载、provider 发现、版本范围检查、外部插件目录发现、diagnostic 插件以及 `sample audit` 示例插件/profile。

### Changed

  longrun 外部插件 provider 改为受托管的 child first 独立 classloader，并在插件关闭时释放；LDB API 包仍由父加载器提供。
  插件 capability enforcement 现在同时约束 metadata 读取、插件上下文创建写批次和 checkpoint hook。
  `Options.cacheBlocks(false)` 现在会真正关闭 BlockCache，同时保留可观测的 disabled 状态。
  `ldb.api.supportedFeatures` 标记 runtime column family list/create/drop empty，`ldb.api.unsupportedFeatures` 保留 non empty drop 与 rename。
  新增 `ldb.api.ecosystemGaps`，解释 MergeOperator、PrefixExtractor、transactions、TTL、custom Env、非空列族 drop/rename 等生态差距的阻塞原因。
  更新性能/可靠性、项目设计、API 兼容、插件和外部承诺的中英文文档，使其对齐 0.4.0 能力集合。

### Verification

  插件相关聚焦发布验证已通过：`./gradlew.bat test   tests "*LdbPluginTest"   tests "*LongRunPluginResolverTest"   tests "*LongRunConfigTest"   tests "*SmokeRunnerTest"   tests "*ReportAnalyzerTest"`。
  正式发布前仍需执行完整发布验证：`./gradlew.bat clean test` 和 `./gradlew.bat clean publishToMavenLocal`。

## [0.2.0]   2026 06 01

### Added

  新增中文 README 和英文 README。
  新增项目整体设计文档及英文副本。
  新增贡献指南、安全政策、NOTICE、发布说明和 CI 配置。
  新增 benchmark/soak 回归入口，覆盖写入、随机读、snapshot scan、manual compaction、checkpoint 和 reopen 工作流。
  新增 compaction 高压可靠性回归，覆盖手动压缩压力和写入突增后的反复压缩恢复。
  新增 checkpoint、backup/restore、repair 和 check 串联的恢复闭环回归测试。
  新增后续性能可靠性专项评估文档及英文副本，规划 group commit、增量备份和 range delete 强化路径。
  新增插件能力增强设计文档及英文副本。
  新增插件增强 API：`LdbPluginDescriptor`、`LdbPluginFailurePolicy`、`OptionsView`、`WriteEvent` 和 `WriteBatchView`。
  新增插件观测属性：`ldb.plugins`、`ldb.pluginStats`、`ldb.plugin.<index>.stats` 和 `ldb.plugin.lastFailure`。
  新增 Windows + Ubuntu JDK 8 CI，以及本地 Maven 发布产物校验任务。
  新增 `CHANGELOG`、贡献指南、安全政策、行为准则和发布说明的英文副本，并为用户可见文档补充中英切换入口。

### Changed

  修正 Maven POM 元数据，使项目名称、描述和主页指向 `vexra ldb`。
  优化 range tombstone 读取路径，避免普通 point get 在无关 tombstone 上做不必要扫描。
  新增 `Options.writeSlowdownDelayNanos`，使 Level 0 slowdown 延迟可配置并可通过诊断属性观测。
  明确插件 `afterWrite` 和 `afterCheckpoint` 的提交后通知失败语义，避免调用方误以为已提交数据会回滚。
  将版本号切换为正式发布版本 `0.2.0`。

### Fixed

  补齐插件提交后失败、checkpoint 后置失败、只读配置视图和只读写入事件的回归覆盖。
  补齐本地发布产物验证，确认主 jar、sources jar、javadoc jar、POM、module 和对应签名文件可生成。

## [0.1.0]

### Added

  新增基础 LDB API、WAL、MemTable、SSTable、MANIFEST/CURRENT、VersionSet 和 compaction。
  新增列族、snapshot cursor、checkpoint、离线 check/repair/backup/restore 和工具命令。
  新增可靠性、range delete、API 兼容和项目整体设计文档。

## 追加：0.6.0 发布前 MultiGet 深层优化

### 本版本已完成
  完成 MultiGet SST 批量读取深层优化：Level0 按新到旧文件顺序批量处理未命中 key，非 L0 level 按 SST 文件分组 key，并在同一 SST 内复用一个 table iterator，减少 MultiGet miss path 的重复 table iterator 创建与 table read 计数。
  修正 SST candidateFiles profiling 口径：空 level 不再继承上一次读取统计，candidateFiles/tableReads/iteratorRequests 更能反映当前读取路径。
  保持当前磁盘文件格式不变，本版本只做读路径、统计口径和 benchmark 可追踪性修复。

### 发布前证据
  LDB read_optimized multiget_random 200k：200,302.818 ops/s。
  RocksDB JNI multiget_random 200k：243,015.078 ops/s。
  LDB/RocksDB JNI 比例：82.42%。
  LDB MultiGet 深层优化后 tableReads=34,315，iteratorRequests=34,394，candidateFiles=200,000。
  报告路径：`ldb longrun/build/reports/ldb multiget optimized 200k/ldb db bench summary.json`，`build/reports/rocksdbjni comparison multiget optimized 200k/comparison.csv`。

### 下一版本保留
  文件格式层能力仍保留到下一版本：更严格的 key 前缀压缩元信息、restart points 元信息、compression block 扩展、partitioned index/filter、range tombstone/merge operand 格式能力。
## 追加：0.6.0 发布前完整 gate

### 发布校验
  已完成发布前完整 gate：`clean test releaseGate publishToMavenLocal`。
  Gradle 构建结果：`BUILD SUCCESSFUL`。
  覆盖项：主模块测试、`ldb longrun:test`、`releaseGateUnitTest`、`upgradeCompatibilityTest`、`productionGateLongRun`、`releaseGate`、`publishToMavenLocal`。
  `productionGateLongRun` 结果：`SUMMARY status=PASS`。

### 已知非阻塞项
  Gradle 仍提示 deprecated features，后续需要处理 Gradle 8 兼容性。
  Java 编译仍提示 deprecated/unchecked API。
  Windows 下目录 force 仍可能出现 `AccessDeniedException` warning，但本次 release gate 最终 PASS，不阻断当前版本发布。
## 追加：0.7.0 正式发布上传

### 发布状态
  已将版本切换为 `0.7.0` 并执行正式发布上传：`clean releaseGate publish`。
  Gradle 构建结果：`BUILD SUCCESSFUL`。
  `releaseGate` 在正式版本号 `0.7.0` 下通过。
  `productionGateLongRun` 结果：`SUMMARY status=PASS`。
  `publishMavenPublicationToMavenRepository` 与 `publish` 已执行成功，发布包已上传到中央仓库发布入口，等待人工审核/后续发布确认。

### 下一开发版本
  发布上传完成后，工作区版本号已升级为 `0.8.0-SNAPSHOT`。
## 追加：0.8.0 开发线目标

### 规划
  `0.8.0-SNAPSHOT` 版本目标确定为文件格式完善与改进。
  新增设计文档：`docs/storage-format-0.8-design.md` 与 `docs/storage-format-0.8-design.en.md`。
  首批方向：SST/table format version、feature set、properties block、旧库兼容、未知不兼容 feature fail fast、check/repair/report 格式证据链。
## 追加：0.8.0 SF 01 文件格式参考

### 文档
  新增 `docs/storage-format.md` 与 `docs/storage-format.en.md`，记录当前 WAL、SST/table、MANIFEST、CURRENT、COLUMN FAMILIES、backup metadata 和 check/repair 文件格式事实。
  SF 01 已完成，后续进入 SF 02 table properties block reader 与 SF 03 format version/feature set。
## 追加：0.8.0 SF 02/SF 03 读侧骨架

### 变更
  新增 `TableProperties`，作为 SST/table properties block 的读侧模型。
  `Table` 打开 SST 时尝试解析 metaindex `properties` entry；缺失时识别为 v1 legacy。
  默认对 unknown incompatible table feature fail fast。
  新增 `Options.allowLegacyTableFormat` 与 `Options.failOnUnknownTableFeature` 读侧保护开关。
  新增 `TableCache#getTableProperties(long)`，供后续 check/repair/report 集成。

### 边界
- 该 reader 增量不改变默认 SST/table 写入格式；后续 0.8 增量已补齐 v2 opt-in properties 写入、check/repair/report 证据和 `storageFormatGates`。
## 追加：0.8.0 SF 04 v2 properties opt in 写入

### 变更
  新增 `Options.tableFormatVersion(int)`，默认 `1`，可显式设置为 `2`。
  新增 `Options.writeTableProperties(boolean)`，仅在 `tableFormatVersion=2` 时影响写入。
  `TableBuilder` 支持 opt in 写入 v2 properties block，并在 metaindex 中记录 `properties` block handle。
  properties block 当前记录 format/version、feature set、entry/block/filter/compression/key/checksum 等自描述字段。

### 边界
  默认仍写 v1 SST/table，不改变旧库和默认写入路径。
## 追加：0.8.0 SF 04 专项测试

### 测试
  新增 `TablePropertiesTest`，覆盖默认 v1 legacy、显式 v2 properties block、unknown incompatible feature fail fast 和 `tableFormatVersion` 参数校验。
## 追加：0.8.0 SF 05 诊断属性

### 变更
  新增 `ldb.tableFormat`，汇总当前 SST/table format version、legacy/v2 计数和 feature set。
  新增 `ldb.storageFormat`，汇总 table/WAL/MANIFEST/CURRENT/COLUMN FAMILIES/backup metadata 格式策略。
  `LdbObservabilityTest` 增加 storage format property 覆盖。
## 追加：0.8.0 SF 05 check 报告字段

### 变更
  `CheckReport` 新增 storage format 证据字段：`storageFormat`、`tableFormats`、`legacyTables`、`v2Tables`、`incompatibleTables`。
  `LdbTool check` 输出自动包含上述字段。
  `LdbVerifyCheckTest` 增加 v2 opt in SST 的 check report 覆盖。
## 追加：0.8.0 SF 05 release gate

### 变更
  `releaseGate` 新增 `storageFormatGates` 分组。
  release gate 总体结果已纳入 storage format 文档、TableProperties 单测、CheckReport storage format 证据和默认 v1 写入策略检查。
## 0.8.0-SNAPSHOT 文件格式改进追加

  新增备份元数据 schema 字段：`BACKUP-MANIFEST.json` 记录 `schemaVersion/chainId/generation`，`OBJECT-REFS.json` 记录 `schemaVersion/objectStoreVersion/generatedBy`。
  改进增量备份链标识：后续增量备份继承父清单 `chainId`，提升链路诊断和未来迁移的稳定性。
## 0.8.0-SNAPSHOT repair 报告格式证据

  `REPAIR-REPORT.json` 与 `repair plan` 输出新增 storage format 结构化字段，覆盖 table 格式摘要和 v1/v2/incompatible 计数。
  release gate 的 `storageFormatGates` 新增 repair 报告证据项。
## 0.8.0-SNAPSHOT v2 repair 格式保持

  repair 新增 v2 SST 格式保持回归覆盖，`REPAIR-REPORT.json` 可展示 `formatVersion=2`、`table.properties` 和 `v2Tables`。
## 0.8.0-SNAPSHOT mixed format check 覆盖

  新增同一数据库目录内 v1/v2 SST 混合格式 check 报告覆盖，release gate 增加 `mixedFormatCheckCoverage`。

## 0.8.0-SNAPSHOT 文件格式验收矩阵

  新增中英文 `storage-format-0.8-acceptance` 验收矩阵文档，集中索引文件格式目标、实现证据、测试证据和 release gate。
  `storageFormatDocs` 门禁扩展为检查验收矩阵关键字。
  验收矩阵新增 storage format gate 阻断项清单，release gate 文档检查覆盖所有 storage format gate 名称。
  `OptionsView` 新增 table format 只读 getter，插件侧可以观测文件格式策略；新增 getter 使用 Java 8 default methods 保持接口兼容。
  `storageFormatDocs` 门禁补充 `OptionsView` / `failOnUnknownTableFeature` 验收矩阵检查。

## 0.8.0-SNAPSHOT OptionsView 文件格式快照修复

- OptionsSnapshot 补齐 table format 四个只读配置字段，插件现在能通过 OptionsView 看到真实的 `tableFormatVersion/writeTableProperties/allowLegacyTableFormat/failOnUnknownTableFeature`。

## 0.8.0-SNAPSHOT pluginOptionsViewCoverage 门禁

- `storageFormatGates` 新增 `pluginOptionsViewCoverage`，将插件 `OptionsView` 文件格式策略快照覆盖纳入发布阻断项。

## 0.8.0-SNAPSHOT 未来 table format 版本保护

- TableProperties.validateReadable 默认拒绝高于当前 reader 支持范围的 table format version，并补充 TablePropertiesTest 覆盖，降低未来格式遗漏 incompatible feature 时的误读风险。

## 0.8.0-SNAPSHOT future-version 文档门禁

- `storageFormatDocs` 增加 `future-version` 关键词检查，覆盖未来 table format version fail-fast 的发布审核证据。

## 0.8.0-SNAPSHOT future-version 设计文档门禁

- `storageFormatDocs` 增加中英文设计文档 future table format version fail-fast 检查，确保设计与验收矩阵同步覆盖未来格式防误读。
## 0.8.0-SNAPSHOT future-version 回滚边界门禁

- `storageFormatDocs` 增加 future-version 诊断性读取关键词检查，确保验收矩阵明确关闭 `failOnUnknownTableFeature` 不是生产回滚策略。
## 0.8.0-SNAPSHOT futureVersionFailFastCoverage 门禁

- `storageFormatGates` 新增 `futureVersionFailFastCoverage`，独立阻断 future table format version fail-fast 与诊断性读取边界缺失。
## 0.8.0-SNAPSHOT malformed table format version 保护

- `TableProperties.read` 对非数字 `ldb.format.table.version` 输出明确 `Invalid table format version` 错误，并补充 malformed format-version 单测覆盖。
## 0.8.0-SNAPSHOT malformed-version 文档门禁

- `storageFormatDocs` 增加 `malformed-version` 关键词检查，覆盖 table format version 非数字或非正数损坏场景的发布审核证据。
## 0.8.0-SNAPSHOT malformed-version 设计文档门禁

- `storageFormatDocs` 增加中英文设计文档 malformed table format version 检查，确保非数字或非正数 table format version 的明确 fail-fast 行为进入发布审核链路。
## 0.8.0-SNAPSHOT 文件格式用户/运维文档门禁

- `storageFormatDocs` 扩展为检查 README、用户手册和运维 Runbook 的中英文文件格式入口，覆盖 `ldb.tableFormat`、`ldb.storageFormat`、`tableFormatVersion`、`failOnUnknownTableFeature`、`CheckReport.storageFormat`、`RepairReport.storageFormat` 和 `storage-format-0.8-acceptance`。
- 文件格式发布证据链现在同时覆盖实现、设计、验收矩阵、用户可发现性和运维归档要求。
## 0.8.0-SNAPSHOT storageFormatDocs 验收矩阵同步

- 文件格式验收矩阵中的 `storageFormatDocs` 阻断条件已同步 README、用户手册和运维 Runbook 覆盖范围，补充 `ldb.tableFormat`、`ldb.storageFormat`、`CheckReport.storageFormat`、`RepairReport.storageFormat` 证据要求。
## 0.8.0-SNAPSHOT storageFormatDocs 设计文档同步

- 0.8 文件格式设计文档中的 `storageFormatDocs` 描述已同步到实际门禁范围，覆盖验收矩阵、README、用户手册、运维 Runbook 和 table/storage format 证据词。
## 0.8.0-SNAPSHOT 文件格式 Options API 契约说明

- `Options` 和 `OptionsView` 的 table format 公开方法补充 JavaDoc，固化默认 v1、v2 opt-in、legacy 兼容、future/unknown/malformed fail-fast 和诊断性读取边界。
## 0.8.0-SNAPSHOT Options API 契约门禁

- `storageFormatDocs` 扩展为检查 `Options` / `OptionsView` 的 table format 公开方法和 `diagnostic-only` 边界，避免文件格式 API 契约说明从发布门禁中脱落。
## 0.8.0-SNAPSHOT Options API 契约验收同步

- 0.8 文件格式设计文档和验收矩阵同步 Options API 契约门禁范围，补充 `Options.tableFormatVersion`、`OptionsView.failOnUnknownTableFeature` 和 `diagnostic-only` 证据要求。
## 0.8.0-SNAPSHOT Options API 契约门禁闭环

- `storageFormatDocs` 扩展为同时检查源码 API 注释、0.8 文件格式设计文档和验收矩阵中的 Options API 契约证据词，确保 `diagnostic-only` 边界不会只存在于单一材料中。
## 0.8.0-SNAPSHOT 格式参考 Options API 契约

- `docs/storage-format.md` 与英文副本补充 0.8 table format 策略、Options API 契约和 `diagnostic-only` 边界，`storageFormatDocs` 同步检查格式参考中的相关证据词。
## 追加：0.8.0 正式发布上传

### 发布状态
  已将版本 `0.8.0` 执行正式发布上传：`.\gradlew.bat publish`。
  Gradle 构建结果：`BUILD SUCCESSFUL`。
  本次上传未执行额外的自动 release/publish 确认动作；但 Gradle 输出未证明远端已按 user-managed 模式创建部署，后续必须通过发布配置强制 USER_MANAGED 或 staging 待审核模式后再执行远程上传。
  Gradle 输出未暴露 deployment/repository id，后续需要用户到中央仓库发布入口核查 artifacts、POM、签名和依赖后确认发布。

### 下一开发版本
  发布上传完成后，工作区版本号已升级为 `0.9.0-SNAPSHOT`。
