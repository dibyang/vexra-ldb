# 发布说明

[English](release.en.md) | 中文

本文档说明 `vexra ldb` 的发布准备和 Maven 发布流程。实际发布前请先确认版本号、标签、测试和签名配置。

## 发布前检查

1. 确认工作区干净。
2. 更新 `CHANGELOG.md`，把 `Unreleased` 内容归档到目标版本。
3. 确认 README、设计文档和 API 文档与代码一致。
4. 确认外部承诺和升级门禁：

  `docs/vexra ldb external commitment.md`
  `docs/ldb production readiness plan.md`
  `docs/operations.md`
  `docs/ldb plugin docs index.md`
  `docs/ldb plugin roadmap.md`
  `ldb longrun/README.md`

5. 运行完整测试：

```bash
./gradlew clean test
```

Windows PowerShell:

```powershell
.\gradlew.bat clean test
```

6. 生成本地发布产物：

```bash
./gradlew clean publishToMavenLocal
```

## 0.6.0 发布前验证记录

  目标版本：`0.6.0`。
  当时发布准备状态：`gradle.properties` 已切换为 `version=0.6.0`；后续继续开发时应切回下一 SNAPSHOT。
  RocksDB 对标 baseline：发布前确认 `docs/ldb rocksdb gap next version plan.md` 和英文副本仍记录当前对标版本、工作包状态、开放问题默认决策和非目标边界。
  变更记录：`CHANGELOG.md` 已归档到 `0.6.0`；如继续追加变更，应重新放入 `Unreleased`。
  必跑门禁：`.\gradlew.bat clean test`、`.\gradlew.bat releaseGate`、`.\gradlew.bat clean publishToMavenLocal`。
  API 兼容证据：确认 `LdbApiCompatibilityTest` 覆盖 `multiGet`、`ldb.api.rocksdbGapPlan`、`ldb.recoveryEvidence`、`ldb.backupEvidence`、`ldb.columnFamilyEvidence`、`ldb.prefixReadiness` 和 `ldbToolScan`。
  工具证据：确认 `LdbToolTest` 覆盖 `properties` 默认导出、`scan <db> [limit]` 只读 base64 JSON、坏参数退出码和不修改库目录。
  恢复与备份证据：归档 `LDBFactory.check`、`repair plan`、`checkBackup`、backup/restore、checkpoint 和对象仓库校验报告；失败样本不得删除。
  运维闭环：发布前确认 `docs/operations.md`、`docs/user manual.md`、README 和 API 兼容设计已同步 `scan`、`prefixReadiness`、RocksDB gap 默认决策和不支持能力边界。
  长压测策略：短链路 `releaseGate` 是硬门禁；nightly/24h soak 作为发布候选证据归档，缺失时必须在 release note 中说明风险和补跑计划。
  不得发布条件：`releaseGate` 失败、开放问题默认决策与业务确认冲突、unsupported 能力被适配层静默忽略、或任何恢复/备份/scan 证据缺失。

## 0.5.0 发布前验证记录

  目标版本：`0.5.0`。
  当时开发基线：`gradle.properties` 保持 `version=0.5.0-SNAPSHOT`；正式发布流程由 Gradle release 插件切换为 `0.5.0`。
  变更记录：`CHANGELOG.md` 当前仍保留 `Unreleased` 内容，正式发布前应归档到 `0.5.0`。
  主要发布主题：插件 classloader 隔离、插件兼容性 testkit、capability enforcement 强化、插件运行资源治理、longrun 插件集成、运行时列族、group commit、增量备份、repair plan 和观测/报告增强。
  插件聚焦验证：已在 Windows PowerShell 执行 `.\gradlew.bat test   tests "*LdbPluginTest"   tests "*LongRunPluginResolverTest"   tests "*LongRunConfigTest"   tests "*SmokeRunnerTest"   tests "*ReportAnalyzerTest"`，结果通过。
  正式发布前仍需执行完整门禁：`.\gradlew.bat clean test`。
  正式发布前仍需执行本地发布门禁：`.\gradlew.bat clean publishToMavenLocal`。
  升级兼容门禁：发布前验证可打开 `vexra ldb:0.4.0` 创建的数据目录，或在 release note 中明确迁移错误和处理方式。
  longrun 发布门禁：至少执行文档化的 smoke/performance/plugin profile，并按 `ldb longrun/README.md` 归档报告。
  生产级发布门禁：确认 `docs/ldb production readiness plan.md` 中 18.1 18.6 已完成并复跑 `releaseGate`、旧库升级、备份损坏注入、列族 tombstone 长压测和 production gate longrun；未通过或未归档报告前不得发布正式版本。

## 0.2.0 发布前验证记录

  版本号：`gradle.properties` 已切换为 `version=0.2.0`。
  测试：已在 Windows PowerShell 执行 `.\gradlew.bat clean test`，结果通过。
  本地发布：已执行 `.\gradlew.bat clean publishToMavenLocal`，结果通过。
  本地 Maven 产物：已确认 `vexra ldb 0.2.0.jar`、`sources.jar`、`javadoc.jar`、`.pom`、`.module` 以及对应 `.asc` 签名文件生成。
  CI：已纳入 `.github/workflows/ci.yml`，覆盖 Ubuntu/Windows JDK 8 `clean test` 和 Ubuntu `clean publishToMavenLocal`。
  变更记录：`CHANGELOG.md` 已将本轮发布内容归档到 `0.2.0`。

## 签名配置

项目会读取根目录下未提交的 `signing.properties`。该文件已在 `.gitignore` 中忽略，不能提交到仓库。

示例：

```properties
signing.keyId=xxxxxxxx
signing.password=your password
signing.secretKeyRingFile=/path/to/secring.gpg

ossrhUsername=your username
ossrhPassword=your password
```

## 发布仓库配置

发布仓库可以通过 Gradle property 指定：

```properties
snapshotsRepository=https://s01.oss.sonatype.org/content/repositories/snapshots/
releasesRepository=https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/
```

如果没有配置上述属性，Gradle 会发布到 `build/repos/snapshots` 或 `build/repos/releases`，用于本地验证，不会上传远端仓库。

## 版本发布

当前项目使用 `net.researchgate.release` 插件，发布配置要求分支匹配 `master|v.*`。

典型流程：

```bash
./gradlew release
```

发布后需要确认：

  Git tag 已创建，格式为 `v${version}`。
  Maven 产物包含主 jar、sources jar 和 javadoc jar。
  POM 中包含项目名称、描述、许可证、开发者、SCM 和 issue tracker。
  发布仓库中的签名文件完整。

## 回滚

  如果远端发布失败但未公开，可删除 staging repository 或清理本地 `build/repos`。
  如果 tag 已推送但产物未发布，应删除错误 tag 并重新发布。
  如果产物已公开发布，不应覆盖同一版本；请发布新的修复版本。

## 0.6.0 发布前补充：MultiGet 深层优化

本版本在不修改 SST/table 文件格式的前提下，完成 MultiGet miss path 的深层优化：L0 按文件新旧顺序批量处理未命中 key，非 L0 level 按 SST 文件分组 key，并在同一 SST 文件内复用一个 iterator 服务多个 key。

发布前 200k 口径验证结果：

| 项目 | 结果 |
|     |     |
| LDB read_optimized multiget_random | 200,302.818 ops/s |
| RocksDB JNI multiget_random | 243,015.078 ops/s |
| LDB/RocksDB JNI 比例 | 82.42% |
| LDB tableReads | 34,315 |
| LDB iteratorRequests | 34,394 |
| LDB candidateFiles | 200,000 |

证据文件：

  `ldb longrun/build/reports/ldb multiget optimized 200k/ldb db bench summary.json`
  `build/reports/rocksdbjni comparison multiget optimized 200k/comparison.csv`

仍保留到下一版本的格式能力不足：当前 SST/table 文件格式仍缺少 RocksDB/LevelDB 常见的 key 前缀压缩、restart points、filter block/metaindex、properties block、compression block、partitioned index/filter，以及 range tombstone/merge operand 等扩展格式能力。本版本只修复读路径、profiling 口径和发布证据链。
## 0.6.0 发布前完整 gate 结果

已执行发布前完整 gate：

```powershell
.\gradlew.bat clean test releaseGate publishToMavenLocal
```

结果：`BUILD SUCCESSFUL`。

通过项：

| 项目 | 结果 |
|     |     |
| 主模块测试 | PASS |
| `ldb longrun:test` | PASS |
| `releaseGateUnitTest` | PASS |
| `upgradeCompatibilityTest` | PASS |
| `productionGateLongRun` | PASS |
| `releaseGate` | PASS |
| `publishToMavenLocal` | PASS |

`productionGateLongRun` 摘要：`SUMMARY status=PASS`，workload operations=3,592，final verify activeKeys=1,621。

发布判断：当前版本已完成发布前完整 gate，可以进入版本发布收尾。

非阻塞已知项：

  Gradle deprecated features 提示仍存在，后续版本需要处理 Gradle 8 兼容性。
  Java deprecated/unchecked API 编译提示仍存在。
  Windows 下目录 force 可能出现 `AccessDeniedException` warning，但本次 gate 最终 PASS，不阻断当前版本发布。
## 0.7.0 正式发布上传记录

发布版本：`0.7.0`。

执行命令：

```powershell
.\gradlew.bat clean releaseGate publish
```

结果：`BUILD SUCCESSFUL`。

发布前 gate：

| 项目 | 结果 |
|     |     |
| `releaseGateUnitTest` | PASS |
| `upgradeCompatibilityTest` | PASS |
| `productionGateLongRun` | PASS |
| `releaseGate` | PASS |
| `publishMavenPublicationToMavenRepository` | PASS |
| `publish` | PASS |

`productionGateLongRun` 摘要：`SUMMARY status=PASS`，operations=3,589，reads=1,610，writes=1,620，removes=359，activeKeys=1,620。

发布状态：`0.7.0` 发布包已上传到中央仓库发布入口，等待人工审核/后续发布确认。

后续开发版本：上传完成后，工作区版本号已升级为 `0.8.0-SNAPSHOT`。

非阻塞已知项仍保持：Gradle deprecated features 提示、Java deprecated/unchecked API 提示、Windows 目录 force 的 `AccessDeniedException` warning。
## 0.8.0 开发线目标

`0.8.0-SNAPSHOT` 开发线聚焦文件格式完善与改进。设计入口：

  `docs/storage-format-0.8-design.md`
  `docs/storage-format-0.8-design.en.md`

发布验收方向：

  新版本必须打开旧库。
  旧 SST 必须被识别为 v1 legacy。
  新 SST v2 首批通过 properties block、format version 和 feature set 自描述。
  未知不兼容 feature 必须 fail fast。
  check/repair/report 必须能解释格式版本、feature、properties 和 checksum 错误分类。
  release gate 已增加 `storageFormatGates` 分组，用于归档可发布审核的文件格式证据。
## 0.8.0 SF 01 文件格式参考文档

已完成 `0.8.0-SNAPSHOT` 文件格式演进的 SF 01：

  `docs/storage-format.md`
  `docs/storage-format.en.md`

该参考文档记录当前 WAL、SST/table、MANIFEST、CURRENT、COLUMN FAMILIES、backup metadata 和 check/repair 行为，作为后续 table format v2、properties block、feature set 和 release gate `storageFormatGates` 的事实基线。
## 0.8.0 SF 02/SF 03 读侧骨架

已完成 table properties block reader 与 feature set fail fast 的读侧骨架：旧 SST 识别为 v1 legacy，新 SST 如果提供 metaindex `properties` entry，则会在打开 table 时解析 `formatVersion`、compatible features 和 incompatible features。

该 reader 增量不改变默认 SST/table 写入格式。后续 0.8 增量已补上 v2 opt-in writer、check/repair/report 输出和 release gate `storageFormatGates`；默认写入仍保持 v1。
## 0.8.0 SF 04 v2 properties block opt in 写入

已完成 v2 properties block 的 opt in 写入骨架。默认仍写 v1；只有显式设置 `Options.tableFormatVersion(2)` 时，TableBuilder 才会写 properties block 并在 metaindex 中加入 `properties` entry。

当前 properties 覆盖：format version、created_by、compatible/incompatible features、entry count、data block count、index type、filter policy/scope、compression、smallest/largest key 和 checksum 策略。

该增量后续已补齐专项测试、check/repair/report 输出、backup metadata schema 证据、插件只读配置可见性和 release gate `storageFormatGates`。正式发布前剩余工作是执行验证命令并归档结果。
## 0.8.0 SF 04 专项测试状态

已补充 `TablePropertiesTest`，用于约束 v1 legacy 识别、v2 opt in properties 写入/读取、unknown incompatible feature fail fast 和 `tableFormatVersion` 参数边界。

待发布前验证：执行包含 `TablePropertiesTest` 的格式专项测试，并把 `storageFormatGates` 结果归档到 release gate。
## 0.8.0 SF 05 诊断属性首段

已新增运行时诊断属性：

  `ldb.tableFormat`
  `ldb.storageFormat`

它们用于观察当前库中的 SST/table format version、legacy/v2 计数、feature set，以及 WAL/MANIFEST/CURRENT/COLUMN FAMILIES/backup metadata 的当前格式策略。已补充 observability 测试覆盖默认 v1 与 v2 opt in 摘要。

该项后续已补齐：`LdbTool check` 输出、结构化 check/repair 报告字段和 release gate `storageFormatGates` 均已接入。
## 0.8.0 SF 05 check 报告字段

离线 check 报告已包含 storage format 证据：

  `storageFormat`
  `tableFormats`
  `legacyTables`
  `v2Tables`
  `incompatibleTables`

`LdbTool check` 会自然输出这些 JSON 字段。已补充 `LdbVerifyCheckTest` 覆盖 v2 opt in SST 的 check report 证据。

该项后续已补齐：release gate `storageFormatGates` 已接入，repair 报告已携带 storage format 结构化字段。
## 0.8.0 SF 05 release gate 接入

`releaseGate` 已新增 `storageFormatGates` 分组，并纳入整体 PASS/FAILED 判定。当前 gate 覆盖：

  文件格式参考文档和 0.8 设计文档存在性。
  `TablePropertiesTest` 对 v1 legacy、v2 opt in 和 incompatible feature fail fast 的覆盖。
  `LdbVerifyCheckTest` 对 check report storage format 字段的覆盖。
  默认写入仍为 table format v1，v2 properties block 需要显式 opt in。

报告输出：`build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json` 与 `.md`。
## 0.8.0-SNAPSHOT 文件格式发布准备追加

  备份清单补充 `schemaVersion`、稳定 `chainId` 和 `generation`，对象引用表补充 `schemaVersion/objectStoreVersion/generatedBy`，发布前检查可以明确识别备份元数据 schema。
  增量备份链的 `chainId` 优先继承父清单，降低多代增量备份在诊断和迁移时被误判为多条链的风险。
  已增加备份格式回归断言；本轮未执行测试命令，等待发布前统一 release gate。
## 0.8.0-SNAPSHOT repair 格式报告追加

- `REPAIR-REPORT.json` 与 `repair-plan` 输出新增 `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables`，发布前可解释 repair 过程中的 SST/table 格式状态。
- `storageFormatGates` 新增 `repairReportStorageFormatEvidence`，与 check 和 backup schema 门禁共同覆盖离线工具链格式证据。
## 0.8.0-SNAPSHOT v2 repair 格式保持追加

- 新增 v2 SST repair 回归场景，证明 repair 从已有 v2 SST 重建元数据时不会降级或原地改写 SST，报告中会保留 `formatVersion=2/table.properties/v2Tables` 证据。
## 0.8.0-SNAPSHOT mixed-format check 追加

- 新增 v1/v2 SST 混合格式 check 回归场景，发布门禁 `mixedFormatCheckCoverage` 覆盖 `legacyTables/v2Tables/tableFormats` 证据。

## 0.8.0-SNAPSHOT 文件格式验收矩阵

- 新增 `docs/storage-format-0.8-acceptance.md` 与英文副本，集中列出 SST/table 自描述、feature set、旧库兼容、mixed-format、check/repair/backup、运行时观测和 release gate 的发布验收证据。
- `storageFormatDocs` 门禁扩展为检查验收矩阵存在，并要求中英文矩阵包含 `storageFormatGates` 与 `mixedFormatCheckCoverage`。
- 验收矩阵新增 `storageFormatGates` 阻断项表，`storageFormatDocs` 门禁同步检查所有 storage format gate 名称，避免发布审核漏项。
- `OptionsView` 补充 table format 只读 getter，插件可观测 `tableFormatVersion/writeTableProperties/allowLegacyTableFormat/failOnUnknownTableFeature`，并由 `LdbPluginTest` 覆盖；新增 getter 使用 Java 8 default methods，默认保持 v1/legacy/fail-fast 策略。
- `storageFormatDocs` 门禁扩展为检查验收矩阵中的 `OptionsView` 与 `failOnUnknownTableFeature` 证据，避免插件可观测性说明缺失。

## 0.8.0-SNAPSHOT OptionsView 文件格式快照修复

- OptionsSnapshot 已补齐 `tableFormatVersion/writeTableProperties/allowLegacyTableFormat/failOnUnknownTableFeature` 四个字段和 override，插件通过 OptionsView 读取到的是打开数据库时的真实文件格式策略，而不是接口默认值。
- 该修复不改变磁盘格式；它补强 storageFormatDocs / OptionsView 验收项的实现证据。

## 0.8.0-SNAPSHOT pluginOptionsViewCoverage 门禁

- `storageFormatGates` 新增 `pluginOptionsViewCoverage`，把 `LdbPluginTest` 中的 `OptionsView` 文件格式策略快照覆盖提升为独立发布门禁。
- `storageFormatDocs` 同步要求验收矩阵包含该 gate 名称，避免插件可观测性只停留在文档描述里。

## 0.8.0-SNAPSHOT 未来 table format 版本保护

- TableProperties.validateReadable 现在会在默认 fail-fast 策略下拒绝高于当前 reader 支持范围的 table format version，避免未来 v3/vN SST 遗漏 incompatible feature 时被 0.8 reader 静默误读。
- TablePropertiesTest 新增 future format-version fail-fast 覆盖；`tablePropertiesUnitCoverage` 说明同步扩展到 v1/v2/future-version/fail-fast。

## 0.8.0-SNAPSHOT future-version 文档门禁

- `storageFormatDocs` 现在要求中英文验收矩阵包含 `future-version`，确保未来 table format version fail-fast 保护不会从发布审核材料中丢失。

## 0.8.0-SNAPSHOT future-version 设计文档门禁

- `storageFormatDocs` 现在同时要求中英文 0.8 设计文档包含 future table format version fail-fast 说明，确保设计、验收矩阵和 release gate 对未来格式防误读保持一致。
## 0.8.0-SNAPSHOT future-version 回滚边界门禁

- `storageFormatDocs` 现在要求验收矩阵包含 future-version 的诊断性读取说明，明确关闭 `failOnUnknownTableFeature` 只用于诊断，不作为生产回滚策略。
## 0.8.0-SNAPSHOT futureVersionFailFastCoverage 门禁

- `storageFormatGates` 新增 `futureVersionFailFastCoverage`，把未来 table format version fail-fast 和诊断性读取边界从 `tablePropertiesUnitCoverage` 中拆出为独立发布审核项。
## 0.8.0-SNAPSHOT malformed table format version 保护

- `TableProperties.read` 现在会把非数字 `ldb.format.table.version` 明确拒绝为 `Invalid table format version`，避免 properties 损坏时冒出裸解析异常或进入不明确状态。
- `TablePropertiesTest` 增加 malformed format-version 覆盖，`tablePropertiesUnitCoverage` 同步扩展为 malformed/future format-version fail-fast。
## 0.8.0-SNAPSHOT malformed-version 文档门禁

- `storageFormatDocs` 现在要求中英文验收矩阵包含 `malformed-version`，确保 table format version 非数字或非正数损坏场景的明确 fail-fast 证据不会从发布审核材料中丢失。
## 0.8.0-SNAPSHOT malformed-version 设计文档门禁

- `storageFormatDocs` 现在同时要求中英文 0.8 设计文档包含 malformed table format version 异常处理说明，确保设计、实现和验收矩阵都覆盖非数字或非正数 table format version 的明确 fail-fast 行为。
## 0.8.0 文件格式发布审核总表

当前版本号：`gradle.properties` 为 `version=0.8.0`。

发布主题：本版本只把 LDB 文件格式演进推进到可审核状态，不默认切换写入格式。默认新写 SST/table 仍为 v1；v2 table properties 需要显式 `Options.tableFormatVersion(2)` opt-in。

发布审核必须覆盖：

| 范围 | 当前证据 | 发布判定 |
| --- | --- | --- |
| SST/table 自描述 | `TableBuilder` 可 opt-in 写 `properties` metaindex entry，`TableProperties` 可读 format version 和 feature set | v2 可写可读，但默认仍写 v1 |
| 兼容策略 | 旧 SST 无 properties block 时识别为 v1 legacy；`allowLegacyTableFormat` 默认允许旧库 | 新版本默认可读旧库 |
| 防误读策略 | unknown incompatible features、future table format version、malformed table format version 均有 fail-fast 路径 | 不允许静默误读未知或损坏格式 |
| 迁移/回滚边界 | 停止 v2 写入时恢复 `tableFormatVersion=1`；未来版本诊断读取不作为生产回滚策略 | 回滚边界已写入验收矩阵 |
| check/repair 证据 | `CheckReport` 和 `RepairReport` 输出 `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables` | 离线工具可解释 mixed-format 和 repair 过程格式状态 |
| backup metadata | `BACKUP-MANIFEST.json` 与 `OBJECT-REFS.json` 带 schema/chain/generation 证据 | 备份链诊断具备格式版本锚点 |
| 插件只读可观测性 | `OptionsView` 暴露 table format 策略快照，`OptionsSnapshot` 覆盖真实打开时配置 | 插件看到真实策略而非接口默认值 |
| 发布门禁 | `releaseGate.storageFormatGates` 覆盖 docs、table properties、future/malformed、check、mixed-format、repair、backup、plugin、default legacy policy | 任一 storage format gate 失败不得发布 |

待取得的最终发布证据：

| 命令 | 必须结果 |
| --- | --- |
| `.\gradlew.bat test` | 全量测试通过，覆盖 table/check/repair/backup/plugin 文件格式相关用例 |
| `.\gradlew.bat releaseGate` | `storageFormatGates` 与其他发布门禁全部通过，并生成 `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json` 和 `.md` |

当前状态：实现和文档已进入正式发布候选口径；最终发布前必须补齐并归档上述命令输出。
## 0.8.0-SNAPSHOT 文件格式用户/运维文档门禁

- `storageFormatDocs` 现在同时检查 README、用户手册和运维 Runbook 的中英文副本，确保 `ldb.tableFormat`、`ldb.storageFormat`、`tableFormatVersion`、`failOnUnknownTableFeature`、`CheckReport.storageFormat`、`RepairReport.storageFormat` 和 `storage-format-0.8-acceptance` 入口不会从发布审核材料中丢失。
- 该门禁把文件格式能力从实现/设计验收扩展到用户可发现性和运维证据归档，发布前缺少任一入口都会阻断 `releaseGate.storageFormatGates`。
## 0.8.0-SNAPSHOT storageFormatDocs 验收矩阵同步

- 0.8 文件格式验收矩阵中的 `storageFormatDocs` 阻断项已同步扩展到 README、用户手册和运维 Runbook，并明确 `ldb.tableFormat`、`ldb.storageFormat`、`CheckReport.storageFormat`、`RepairReport.storageFormat` 也是发布审核证据。
## 0.8.0-SNAPSHOT storageFormatDocs 设计文档同步

- 0.8 文件格式设计文档中的 `storageFormatDocs` 描述已同步到实际门禁范围：除格式参考和设计文档外，也覆盖验收矩阵、README、用户手册和运维 Runbook，并要求 `ldb.tableFormat`、`ldb.storageFormat`、`CheckReport.storageFormat`、`RepairReport.storageFormat` 等证据词。
## 0.8.0-SNAPSHOT 文件格式 Options API 契约说明

- `Options` 与 `OptionsView` 的 table format 公开方法已补充 JavaDoc，明确默认 v1 写入、v2 properties opt-in、不原地改写已有 SST、legacy v1 兼容、unknown/future/malformed fail-fast，以及关闭 `failOnUnknownTableFeature` 仅用于诊断性读取而非生产回滚策略。
## 0.8.0-SNAPSHOT Options API 契约门禁

- `storageFormatDocs` 现在同时检查 `Options` 和 `OptionsView` 的 table format 公开方法，确保 `tableFormatVersion`、`writeTableProperties`、`allowLegacyTableFormat`、`failOnUnknownTableFeature` 以及 `diagnostic-only` 诊断性读取边界不会从 API 契约中丢失。
## 0.8.0-SNAPSHOT Options API 契约验收同步

- 0.8 文件格式设计文档和验收矩阵中的 `storageFormatDocs` 阻断条件已同步 Options API 契约范围，明确 `Options.tableFormatVersion`、`OptionsView.failOnUnknownTableFeature` 和 `diagnostic-only` 属于发布审核证据。
## 0.8.0-SNAPSHOT Options API 契约门禁闭环

- `storageFormatDocs` 现在不仅检查 `Options` / `OptionsView` 源码 API 注释，也检查 0.8 文件格式设计文档和验收矩阵中的 `Options.tableFormatVersion`、`OptionsView.failOnUnknownTableFeature` 与 `diagnostic-only` 证据词，确保 API 契约、设计说明和验收阻断条件保持一致。
## 0.8.0-SNAPSHOT 格式参考 Options API 契约

- `docs/storage-format.md` 与英文副本已补充 0.8 table format 策略和 Options API 契约，明确默认 v1、新写 v2 opt-in、legacy v1 兼容、future/unknown/malformed fail-fast、`diagnostic-only` 诊断边界和 `OptionsView` 只读策略快照。
- `storageFormatDocs` 同步检查格式参考中的 `Options.tableFormatVersion`、`Options.failOnUnknownTableFeature`、`OptionsView.tableFormatVersion` 和 `diagnostic-only` 证据词。
## 0.8.0 发布前验证记录

发布版本：`0.8.0`。

发布主题：文件格式完善与改进。默认写入格式仍为 SST/table v1，v2 properties block 需要显式 `Options.tableFormatVersion(2)` opt-in。

发布文档状态：`CHANGELOG.md`、`README.md`、文件格式参考、0.8 设计、验收矩阵、用户手册和运维 Runbook 已纳入发布审核证据链；英文副本同步维护。

发布前必须执行：

```powershell
.\gradlew.bat test
.\gradlew.bat releaseGate
.\gradlew.bat publishToMavenLocal
```

发布前验证结果：

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `.\gradlew.bat test` | PASS，`BUILD SUCCESSFUL` | 主模块测试与 `ldb-longrun:test` 通过 |
| `.\gradlew.bat releaseGate` | PASS，`BUILD SUCCESSFUL` | `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json`，版本 `0.8.0`，`storageFormatGates` 全部 PASS |
| `.\gradlew.bat publishToMavenLocal` | PASS，`BUILD SUCCESSFUL` | 主 jar、sources jar、javadoc jar、POM、module 和签名发布到 Maven Local |

`productionGateLongRun` 结果：`SUMMARY status=PASS`，本轮 operations=3593，reads=1611，writes=1622，removes=360，activeKeys=1622。

产物元数据检查：生成的 `build/publications/maven/pom-default.xml` 已包含 `The Apache License, Version 2.0`。

已知非阻塞项：Gradle 仍提示 deprecated features；Windows 下 force LDB directory 仍可能出现 `AccessDeniedException` warning，但 `releaseGate` 最终 PASS，不阻断 `0.8.0` 发布准备。

发布准备结论：`0.8.0` 已完成发布前版本切换、文档更新、发布门禁和本地发布产物验证；尚未执行中央仓库上传。
## 0.8.0 正式发布上传记录

发布版本：`0.8.0`。

发布上传命令：

```powershell
.\gradlew.bat publish
```

发布上传结果：`BUILD SUCCESSFUL`。

发布状态：`0.8.0` 发布命令已返回成功，且未执行额外的自动 release/publish 确认动作；但本次 Gradle 输出未证明远端已按 user-managed 模式创建部署，后续必须通过发布配置强制 USER_MANAGED 或 staging 待审核模式后再执行远程上传。

审核信息：Gradle 输出未暴露 deployment/repository id 或 Central Portal 审核链接。后续需要用户登录中央仓库发布入口，核查 artifacts、POM、签名和依赖后再确认发布。

下一开发版本：上传完成后，工作区版本号已升级为 `0.9.0-SNAPSHOT`。
## 0.9.0 REL-01 发布链路修正

`0.9.0-SNAPSHOT` 开发线新增发布链路防线：

- `verifyUserManagedReleaseConfig`：验证 release 仓库配置不会自动发布 Maven Central 可见版本。
- `publishUserManagedRelease`：正式远程上传专用入口，要求非 `-SNAPSHOT` 版本、显式远程 `releasesRepository`，且仓库配置必须包含 USER_MANAGED/user-managed 或 staging 待审核语义。
- `releaseGate.userManagedReleaseConfig`：发布门禁记录当前 release 仓库配置；若出现 AUTOMATIC 或无法证明待审核模式则失败。`releaseGate.gitReleaseTraceability`：正式版本上传前必须证明发布 commit 已推送 upstream，`v${version}` tag 已在本地和 origin 存在。

默认本地 dry-run 仓库仍允许用于本地验证；真实中央仓库上传必须使用 `publishUserManagedRelease`，不得再用普通 `publish` 代替 user-managed 语义证明。执行 `publishUserManagedRelease` 前必须完成发布版本提交、推送 GitHub/upstream、创建并推送 `v${version}` tag；否则任务失败并阻止中央仓库上传。
### REL-01 验证结果

已执行：

```powershell
.\gradlew.bat verifyUserManagedReleaseConfig
.\gradlew.bat releaseGate
```

结果：两条命令均 `BUILD SUCCESSFUL`。

`RELEASE-GATE-REPORT` 已包含发布链路门禁：

| Gate | 结果 | 证据 |
| --- | --- | --- |
| `userManagedReleaseConfig` | PASS | `https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/` |
| `gitReleaseTraceability` | PASS | 当前为 `0.9.0-SNAPSHOT`，报告为 `not-required-for-snapshot`；正式非 SNAPSHOT 上传时由 `publishUserManagedRelease` 强制检查 |

正式中央仓库上传前新增强制条件：工作区干净、发布 commit 已推送 upstream、本地存在 `v${version}` tag、origin 存在同名 tag。

## 0.9.0-SNAPSHOT RR-01 Bloom/filter block 随机读发布准备记录

本阶段目标：补齐 SST Bloom/filter block 随机读 miss 短路证据，并把 readrandom 对 RocksDB JNI 的比例提升到至少 50%。

已执行验证：

```powershell
.\gradlew.bat :test --tests *LdbObservabilityTest
.\gradlew.bat releaseGate
.\gradlew.bat :ldb-longrun:ldbDbBenchReport "-Pldb.dbBench.outputDir=build/reports/ldb-db-bench-read-optimized-rr01" "-Pldb.dbBench.dbDir=build/reports/ldb-db-bench-read-optimized-rr01/db" "-Pldb.dbBench.benchmarks=warm_readrandom" "-Pldb.dbBench.num=200000" "-Pldb.dbBench.reads=200000" "-Pldb.dbBench.readProfile=read_optimized" "-Pldb.dbBench.blockCacheSize=65536"
powershell -ExecutionPolicy Bypass -File .\scripts\run-rocksdbjni-comparison.ps1 -ExistingLdbSummary "ldb-longrun\build\reports\ldb-db-bench-read-optimized-rr01\ldb-db-bench-summary.json" -OutputDir "build\reports\rocksdbjni-comparison-readrandom-rr01" -Benchmarks "warm_readrandom" -Num 200000 -Reads 200000 -Runs 1
```

验证结果：

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Bloom/filter 单测 | PASS，`BUILD SUCCESSFUL` | `LdbObservabilityTest` 断言 `filterSkips>0`、`mayContainRequests>0`、`mayContainFalse>0` |
| 发布门禁 | PASS，`BUILD SUCCESSFUL` | `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json`，包含 `filterBlockCoverage` |
| LDB read_optimized warm_readrandom | `247,361.396 ops/s` | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-rr01/ldb-db-bench-summary.json` |
| RocksDB JNI warm_readrandom | `444,235.456 ops/s` | `build/reports/rocksdbjni-comparison-readrandom-rr01/comparison.csv` |
| LDB/RocksDB JNI 比例 | `55.68%` | `247361.396 / 444235.456`，达到至少 50% 的 P0 目标 |

已知非阻塞项：Windows 环境仍可能输出 `Failed to force LDB directory ... AccessDeniedException` warning；本轮 `releaseGate` 和 benchmark 均最终 PASS，不阻塞发布准备。