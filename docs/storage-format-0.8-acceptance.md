# LDB 0.8.0 文件格式验收矩阵

## 目的

本文把 `0.8.0-SNAPSHOT` 文件格式目标拆成可发布审核的验收矩阵，用于连接设计文档、代码实现、测试用例和 `releaseGate.storageFormatGates`。本文不新增磁盘格式语义，只记录当前版本发布前必须可证明的证据。

## 验收矩阵

| 目标域 | 验收项 | 当前实现证据 | 测试/门禁证据 | 发布边界 |
| --- | --- | --- | --- | --- |
| SST/table 自描述 | v2 SST 可 opt-in 写入 properties block | `Options.tableFormatVersion(2)`、`TableBuilder` metaindex `properties` entry、`TableProperties` reader | `TablePropertiesTest`、`tablePropertiesUnitCoverage` | 默认仍写 v1，v2 必须显式 opt-in |
| feature set 与版本保护 | 读取 compatible/incompatible features，未知 incompatible 或未来 table format version fail-fast | `TableProperties.validateReadable`、`Options.failOnUnknownTableFeature` | `TablePropertiesTest`、`tablePropertiesUnitCoverage`、`futureVersionFailFastCoverage` | v2 首批 `incompatible_features` 为空；未来格式版本默认拒绝读取 |
| 旧库兼容 | 缺少 properties block 的 SST 识别为 v1 legacy | `TableProperties.legacy()`、`Options.allowLegacyTableFormat` | `TablePropertiesTest`、`defaultLegacyWritePolicy` | 新版本默认可读旧库 |
| mixed-format | 同一库内 v1/v2 SST 可被 check 报告解释 | `CheckReport.tableFormats`、`legacyTables`、`v2Tables` | `LdbVerifyCheckTest.shouldReportMixedV1AndV2TableFormatsInCheckReport`、`mixedFormatCheckCoverage` | 不要求旧版本读取已写入的 v2 SST |
| check 证据 | 离线 check 输出 storage format 与每个 SST 的 table format | `CheckReport#toJson()` | `checkReportStorageFormatEvidence` | check 不修改数据库目录 |
| repair 证据 | repair/repair-plan 输出 storage format，且 v2 SST 不被原地降级 | `RepairReport#toJson()`、`recordRepairTableFormat` | `LdbToolTest`、`LdbRepairTest.shouldReportV2TableFormatWhenRepairingFromReadableSst`、`repairReportStorageFormatEvidence` | repair 重建 MANIFEST/CURRENT，不原地重写已有 SST |
| backup metadata | 备份 manifest/object refs 带 schema 字段和稳定 chainId | `BACKUP-MANIFEST.json`、`OBJECT-REFS.json` schema 字段 | `LdbBackupTest`、`backupMetadataSchemaCoverage` | 旧备份缺少 schema 字段仍可检查和恢复 |
| 运行时观测 | 在线属性暴露 table/storage format 摘要 | `ldb.tableFormat`、`ldb.storageFormat` | `LdbObservabilityTest` | properties 只在 table 打开阶段解析，不进入每次 get 热路径 |
| 插件只读配置 | 插件可通过 `OptionsView` 观测 table format 策略但不能修改配置 | `OptionsView.tableFormatVersion()`、`writeTableProperties()`、`allowLegacyTableFormat()`、`failOnUnknownTableFeature()` | `LdbPluginTest.shouldExposeReadOnlyOptionsViewAndWriteEvent`、`pluginOptionsViewCoverage` | 新增方法使用 Java 8 default methods，默认保持 v1/legacy/fail-fast 策略，降低第三方实现破坏面 |
| release gate | 发布报告包含 storage format 专项门禁 | `storageFormatGates`、`RELEASE-GATE-REPORT.json/md` | `releaseGate` | 未通过任一 storage format gate 不得发布 |

## 回滚与迁移边界

| 场景 | 支持策略 | 证据 |
| --- | --- | --- |
| 需要停止 v2 写入 | 把新写入配置恢复为 `tableFormatVersion=1` | `defaultLegacyWritePolicy` |
| 已存在 v2 SST | 当前版本继续读取；不承诺旧版本读取 | `mixedFormatCheckCoverage`、`repairReportStorageFormatEvidence` |
| 遇到未来 table format version | 默认 fail-fast；只允许关闭 `failOnUnknownTableFeature` 做诊断性读取，不作为生产回滚策略 | `futureVersionFailFastCoverage`、`future-version` |
| 需要解释混合格式状态 | 使用 `LDBFactory.check` 或 `ldb.tableFormat` | `CheckReport.tableFormats`、`VersionSet.tableFormatStats()` |
| repair 重建元数据 | 只重建 MANIFEST/CURRENT；已有 SST 保持原格式 | `RepairReport.tableFormats` |
| 备份链诊断 | 使用 manifest `schemaVersion/chainId/generation` 和 object refs schema | `backupMetadataSchemaCoverage` |

## 发布前必须取得的实证

| 命令 | 期望 |
| --- | --- |
| `.\gradlew.bat test` | 所有单元/集成测试通过，包括 table/check/repair/backup 格式覆盖 |
| `.\gradlew.bat releaseGate` | `storageFormatGates`、兼容性 gate 和生产 gate 全部通过，并生成 `RELEASE-GATE-REPORT.json` 与 `RELEASE-GATE-REPORT.md` |

## storageFormatGates 阻断项

| Gate | 阻断条件 |
| --- | --- |
| `storageFormatDocs` | 中英文格式参考、设计文档、验收矩阵、README、用户手册、运维 Runbook 或 Options API 契约缺失，或缺少 table/backup/repair/mixed-format/malformed-version/future-version/rollback、`ldb.tableFormat`、`ldb.storageFormat`、`CheckReport.storageFormat`、`RepairReport.storageFormat`、`Options.tableFormatVersion`、`OptionsView.failOnUnknownTableFeature`、`diagnostic-only` 等关键字段 |
| `tablePropertiesUnitCoverage` | table properties v1/v2/malformed-version/fail-fast 覆盖未通过 |
| `futureVersionFailFastCoverage` | future table format version fail-fast 或诊断性读取边界覆盖未通过 |
| `checkReportStorageFormatEvidence` | check report 缺少 `storageFormat/tableFormats` 证据 |
| `mixedFormatCheckCoverage` | v1/v2 SST 混合格式报告覆盖未通过 |
| `repairReportStorageFormatEvidence` | repair/repair-plan 缺少格式报告证据，或 v2 repair 格式保持覆盖未通过 |
| `backupMetadataSchemaCoverage` | backup manifest/object refs schema 覆盖未通过 |
| `pluginOptionsViewCoverage` | 插件 `OptionsView` 未覆盖 table format 只读 getter，或插件看到的是接口默认值而不是打开数据库时的真实配置快照 |
| `defaultLegacyWritePolicy` | 默认写入不再保持 table format v1，或 v2 不再需要显式 opt-in |
| `storageFormatDocs` / `OptionsView` | 验收矩阵缺少插件只读配置和 `failOnUnknownTableFeature` 等 table format getter 说明 |
## 0.9.0-SNAPSHOT SF-06 生产化观测

| 验收项 | 证据 | 发布门禁 |
| --- | --- | --- |
| v2 写入开关可观测 | `ldb.tableFormatPolicy` 输出 `newWrites=v1` 或 `newWrites=v2-properties` | `tableFormatPolicyCoverage` |
| 回滚口径可观测 | `ldb.tableFormatPolicy` 输出 `rollback=new-writes-tableFormatVersion-1` | `tableFormatPolicyCoverage` |
| 风险开关可观测 | `unknownFeaturePolicy=fail-fast`，关闭时为 `diagnostic-only` | `tableFormatPolicyCoverage` |

`tableFormatPolicyCoverage` 要求单测覆盖默认 v1、显式 v2、回滚说明和 fail-fast 策略证据，确保 SF-06 不只停留在文档描述。

## 0.9.0-SNAPSHOT RR-01 filter block 随机读验收

| 验收项 | 证据 | 门禁 |
| --- | --- | --- |
| Bloom filter block 写入 | 配置 `BloomFilterPolicy` 后，SST metaindex 写入 `filter.<policyName>`，读侧用同名策略加载 filter block | `filterBlockCoverage` |
| 随机 miss 短路 | 对落在 SST key 范围内但不存在的 full user key，`mayContain=false` 后跳过 table iterator | `filterBlockCoverage` |
| 观测闭环 | `ldb.sstReadStats` 输出 `filterSkips`、`mayContainRequests`、`mayContainFalse`，用于发布前归档 readrandom miss 优化证据 | `filterBlockCoverage` |

`filterBlockCoverage` 要求单测覆盖 BloomFilterPolicy 支撑的 filter block 写入、读侧 `mayContain=false` 和 `filterSkips>0`。该能力保持可选：未配置同名 `FilterPolicy` 或未找到 filter block 时，读侧必须保守返回 may-contain=true，避免误跳过真实数据。
