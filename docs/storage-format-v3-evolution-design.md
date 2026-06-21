# LDB v3 文件格式演进设计

[English](storage-format-v3-evolution-design.en.md) | 中文

## 背景

0.11.0 已经把 SST block-local index 推进到 v3 opt-in：写侧可以在 `tableFormatVersion(3)` 且 `writeBlockLocalIndex(true)` 时生成 per-data-block local index、`block_local_index` directory 和 `block.local_index.v1` incompatible feature；读侧能够识别 directory，并在 MultiGet 同 data block 多 key 场景下按需利用 local index 缩小块内线性解码窗口。

这一阶段的关键不再只是“能写、能读、能 benchmark”，而是把 v3 文件格式变成可长期演进、可诊断、可回滚、可发布治理的格式层。否则后续继续引入 Bloom/filter 扩展、partitioned index/filter、压缩块扩展、range tombstone 格式能力或更激进的 block seek 优化时，会把兼容性、损坏诊断和发布门禁散落在各处。

## 目标

| 目标 | 说明 |
| --- | --- |
| 固化 v3 兼容矩阵 | 明确新旧 reader、v1/v2/v3 SST、opt-in feature 之间的读写边界。 |
| 规范 meta block registry | 统一 `properties`、`block_local_index` 以及未来 meta block 的命名、必需性、缺失行为和 feature 关系。 |
| 增加格式自检能力 | 为 release gate、用户排障和损坏归类提供机器可读证据。 |
| 支撑默认开启决策 | 在 block-local index 进入默认候选前，要求性能、空间、scan 回归和 mixed-format 证据齐备。 |
| 保持 no-downgrade 可解释 | 用户一旦写入 v3 incompatible feature，必须能从文档和诊断中知道回退边界。 |

## 非目标

- 不在本文中改变 v3 当前磁盘布局。
- 不承诺旧版本 reader 可以读取带 `block.local_index.v1` 的 v3 SST。
- 不把 `writeBlockLocalIndex` 改为默认开启。
- 不在当前阶段引入 partitioned index/filter 或新的压缩块布局。
- 不把 repair 默认改成重写 SST 或重建 block-local index。

## v3 兼容矩阵

| Reader | SST 格式 | 行为 |
| --- | --- | --- |
| 新 reader | v1 legacy SST | 必须默认可读。 |
| 新 reader | v2 properties SST | 必须默认可读，并保留 unknown incompatible feature fail-fast。 |
| 新 reader | v3 且未声明 `block.local_index.v1` | 可读；按 v3 properties 解释，但读路径可回退到原 `Block.seek`。 |
| 新 reader | v3 且声明 `block.local_index.v1` | 仅当 reader 支持该 feature 且 directory/properties 一致时可读。 |
| 旧 reader | v1/v2 SST | 保持旧行为。 |
| 旧 reader | v3 SST | 不承诺可读；必须通过 version/feature 机制避免静默误读。 |
| 新 reader | mixed v1/v2/v3 DB | 必须支持，同一 DB 中不同 SST 可用不同格式。 |

## Meta Block Registry

后续所有 meta block 必须进入统一 registry，而不是在 writer/reader/check 中各自硬编码分散规则。

| Name | Status | Feature | Required When | Missing Behavior | Notes |
| --- | --- | --- | --- | --- | --- |
| `properties` | stable | `table.properties` | v2/v3 SST | v2/v3 打开失败或 check 报告 properties missing | 记录 format version、feature set 和格式证据。 |
| `filter.<policy>` | stable | filter policy name | 配置 Bloom/filter 时 | 无 filter 时读路径回退；声明不一致时 fail-fast | 已用于随机读候选 SST 跳过。 |
| `block_local_index` | v3 opt-in | `block.local_index.v1` | 声明 `block.local_index.v1` 时 | 打开失败，check 报 `BLOCK_LOCAL_INDEX_DIRECTORY_MISSING` | directory 映射 data block handle 到 local index block handle。 |
| `partitioned_index` | reserved | future incompatible feature | 未启用 | 未声明时忽略；声明但不支持时 fail-fast | 预留给后续 RocksDB gap 收敛。 |
| `partitioned_filter` | reserved | future incompatible feature | 未启用 | 未声明时忽略；声明但不支持时 fail-fast | 预留给后续 filter 扩展。 |

### Registry 规则

- meta block name 必须稳定、大小写敏感，并在中英文格式文档中登记。
- incompatible feature 与 meta block 的关系必须一一说明：声明 feature 后缺少必需 meta block 必须 fail-fast。
- compatible meta block 缺失时可以 fallback，但必须有诊断计数或 check 分类。
- properties 中的字段必须使用 `ldb.table.<domain>.<field>` 命名空间。
- 新增 meta block 必须同时更新 reader、check/repair/report、releaseGate 文档门禁和 no-downgrade 说明。

## 格式自检工具设计

建议新增面向 SST/table 的轻量自检能力，作为 release gate 和用户排障的共同基础。第一阶段可以先作为内部 checker 或 `check` 报告扩展，不急于暴露新 CLI。

| 检查项 | 证据字段 | 失败分类 |
| --- | --- | --- |
| footer/metaindex 可读 | `footerReadable`, `metaindexReadable` | `TABLE_FOOTER_CORRUPT`, `TABLE_METAINDEX_CORRUPT` |
| properties 存在且可解析 | `propertiesReadable`, `tableFormatVersion` | `TABLE_PROPERTIES_MISSING`, `TABLE_PROPERTIES_CORRUPT` |
| feature set 与 meta block 一致 | `features`, `metaBlocks` | `TABLE_FEATURE_META_MISMATCH` |
| `block_local_index` directory 存在 | `blockLocalIndexDirectoryPresent` | `BLOCK_LOCAL_INDEX_DIRECTORY_MISSING` |
| directory handle 合法 | `blockLocalIndexDirectoryHandle` | `BLOCK_LOCAL_INDEX_HANDLE_OUT_OF_RANGE` |
| local index block 可读 | `blockLocalIndexBlocksReadable` | `BLOCK_LOCAL_INDEX_BLOCK_CORRUPT` |
| covered block 数匹配 | `blockLocalIndexCoveredBlocks` | `BLOCK_LOCAL_INDEX_COVERAGE_MISMATCH` |
| scan/iterator 不加载 local index | `iteratorLoadsBlockLocalIndex=false` | `BLOCK_LOCAL_INDEX_SCAN_POLICY_VIOLATION` |

## 默认开启前置证据

`writeBlockLocalIndex` 进入默认候选前，必须同时满足下面条件：

| 维度 | 需要的证据 |
| --- | --- |
| cold readrandom | 至少 200k 规模，多次重复，v3 opt-in 不低于稳定 v1/v2 baseline。 |
| sparse MultiGet | 随机稀疏 batch 不回退，且说明同 block 命中率较低时的策略。 |
| dense same-block MultiGet | 明确证明 local index 在同 block 多 key 场景有收益。 |
| scan/iterator | 证明默认 iterator/scan 不加载 local index，且 scan benchmark 无明显回退。 |
| 空间放大 | 记录 index bytes、covered blocks、data block 原始字节，并设发布门禁上限。 |
| mixed-format | 同一 DB 中 v1/v2/v3 混合 flush/compaction/get/MultiGet/check 均通过。 |
| no-downgrade | release 文档明确写入 v3 后旧版本不可保证打开。 |

## 当前 0.11 证据归档

当前 200k 对比说明 eager 策略不可直接默认开启：每次 point get 都主动读取 local index 时，`cold_readrandom` 从 baseline `180,908.754 ops/s` 回退到 `158,232.367 ops/s`，仅为 baseline 的 `87.47%`；`multiget_random` 为 `157,482.882 ops/s`，为 baseline 的 `97.23%`。

改为 smart policy 后，point get 保持原 `Block.seek` 路径，不主动读取 local index；MultiGet 仅在同一 data block group 中存在多个 key 时使用 local index。该策略下 v3 opt-in `cold_readrandom` 达到 `204,225.049 ops/s`，为 baseline 的 `112.89%`；`multiget_random` 达到 `171,515.540 ops/s`，为 baseline 的 `105.89%`。

报告证据：

- `ldb-longrun/build/reports/ldb-db-bench-bi05-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-bi05-v3bli-smart-200k/ldb-db-bench-summary.json`

结论：当前 v3 block-local index 可以作为 opt-in 能力继续保留；默认开启仍需 repeated runs、dense same-block MultiGet、scan 回归和空间放大证据。

## 发布门禁建议

| Gate | 要求 |
| --- | --- |
| `storageFormatDocs` | 中英文格式参考、v3 演进设计、block-local index 设计和 release 文档均记录 v3/no-downgrade 边界。 |
| `tableFormatPolicyCoverage` | `ldb.tableFormatPolicy` 必须暴露 v3 opt-in/default 状态和 rollback action。 |
| `blockLocalIndexFormatCoverage` | Options、properties、metaindex、reader、check/report 对 `block.local_index.v1` 覆盖完整。 |
| `blockLocalIndexBenchmarkEvidence` | 归档 cold_readrandom、multiget_random、dense same-block MultiGet、scan 对比。 |
| `gitReleaseTraceability` | 正式发布仍要求本地提交、tag、push 和 user-managed staging 待人工确认。 |

## 分阶段计划

| 阶段 | 交付 | 验收 |
| --- | --- | --- |
| V3E-01 | 本文档与英文副本 | 兼容矩阵、registry、自检和默认开启证据要求明确。 |
| V3E-02 | check/report 扩展设计 | 损坏分类和 evidence 字段稳定。 |
| V3E-03 | 代码实现 registry 常量与自检骨架 | 不改变默认读写行为，旧 SST 回归通过。 |
| V3E-04 | releaseGate 接入 | 文档、格式覆盖、benchmark evidence 均可被 gate 检查。 |
| V3E-05 | 默认开启评审 | 只有证据满足前置条件后，才允许讨论默认开启。 |
## V3E-02 当前落地边界

当前实现已先把 v3/block-local index evidence 接入离线 `check` 和 `repair` 报告骨架，不改变默认读写路径。报告会在 `storageFormat` 和 `tableFormats` 中归档：

- `v3Tables`
- `blockLocalIndexTables`
- `blockLocalIndexBytes`
- `blockLocalIndexCoveredBlocks`
- 每个 SST 的 `blockLocalIndex`、`blockLocalIndexPolicy`、`blockLocalIndexInterval`、`blockLocalIndexBytes` 和 `blockLocalIndexCoveredBlocks`

这一步只证明可读 SST 的 properties/feature 证据能被 check/repair 统一汇总；更深的 directory handle 越界、单 block 覆盖不一致、local index block checksum 等损坏分类仍留给 V3E-03/V3E-04。

## V3E-03 当前落地边界

当前实现已把 block-local index 自检骨架接入离线 `check` 和 `repair` 路径。对于声明 `block.local_index.v1` 的可读 SST，诊断路径会读取 directory 并验证：

- directory 是否存在且非空
- `ldb.table.block_local_index.covered_blocks` 是否等于 directory entry 数
- local index block handle 是否落在 SST 文件边界内
- local index block 是否可读取并通过现有 block trailer/checksum 路径

报告中的 `blockLocalIndexEvidence` 会归档 `coverageMatches`、`handlesInRange`、`blocksReadable` 和 `failureCount`；失败会以 `BLOCK_LOCAL_INDEX_DIRECTORY_MISSING`、`BLOCK_LOCAL_INDEX_COVERAGE_MISMATCH`、`BLOCK_LOCAL_INDEX_HANDLE_OUT_OF_RANGE`、`BLOCK_LOCAL_INDEX_BLOCK_CORRUPT` 等分类进入 check failures。该自检仍只在离线路径执行，不改变普通 open/get/iterator 策略。

## V3E-04 当前落地边界

Gradle `releaseGate` 已新增独立 `blockLocalIndexFormatCoverage` 门禁，和既有 `storageFormatGates` 一起参与整体 PASS/FAILED 判定。该门禁要求：

- 中英文 block-local index 设计文档存在并记录 `block.local_index.v1`
- 中英文 v3 文件格式演进设计文档存在并记录 `blockLocalIndexFormatCoverage` 与 `BLOCK_LOCAL_INDEX_COVERAGE_MISMATCH`
- `Table` 暴露离线 `getBlockLocalIndexFormatEvidence` 和 `getBlockLocalIndexFormatFailures`
- `LDBFactory` 的 check/repair 报告包含 `blockLocalIndexEvidence` 和 `blockLocalIndexFailures`
- `TablePropertiesTest` 覆盖 `BLOCK_LOCAL_INDEX_FEATURE`
- `LdbVerifyCheckTest` 覆盖 v3 block-local index check 报告
- `LdbRepairTest` 覆盖 v3 block-local index repair 报告

该门禁目前验证格式覆盖和离线自检闭环，不代表默认开启条件已经满足；`blockLocalIndexBenchmarkEvidence` 仍需要 dense same-block MultiGet、scan 回归、重复 200k 对比和空间放大证据。

## V3E-05 当前落地边界

`ldbDbBenchReport` 已新增两个面向 block-local index 默认开启评审的 benchmark 名称：

- `multiget_sameblock`：按连续 key 组成 batch，提高同 data block 多 key 命中概率，用于观察 local index 对 dense same-block MultiGet 的收益。
- `scan`：通过 `SnapshotCursor` 顺序扫描，用于确认 v3/block-local index 不会让 iterator/scan 路径加载额外索引或出现明显回归。

Gradle `releaseGate` 新增 `blockLocalIndexBenchmarkEvidence` 门禁，要求 v3 演进文档和 `LdbDbBenchMain`/`LdbDbBenchMainTest` 同时覆盖 `cold_readrandom`、稀疏 `multiget_random`、dense same-block MultiGet、scan 回归以及 v3 opt-in 参数。该门禁当前验证 benchmark 入口和证据链可归档，不直接宣称性能阈值已经满足；默认开启前仍需要正式 200k 多轮结果和空间放大上限。

## V3E-05 200k 基准归档与默认开启结论

本轮补充了正式 200k 对比，用于判断 v3 block-local index 是否具备默认开启条件。对比范围覆盖 `cold_readrandom`、稀疏 `multiget_random`、密集同 block `multiget_sameblock` 和 `scan`。

| 场景 | baseline | v3 block-local index opt-in | v3 / baseline |
| --- | ---: | ---: | ---: |
| `cold_readrandom` | `194,755.962 ops/s` | `211,992.343 ops/s` | `108.85%` |
| `multiget_random` | `167,724.829 ops/s` | `173,929.302 ops/s` | `103.70%` |
| `multiget_sameblock` | `208,109.117 ops/s` | `458,276.345 ops/s` | `220.21%` |
| `scan` | `3,463,269.430 ops/s` | `2,912,335.781 ops/s` | `84.09%` |

证据路径：

- `ldb-longrun/build/reports/ldb-db-bench-v3e05-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e05-v3bli-200k/ldb-db-bench-summary.json`

结论：v3 block-local index 在 opt-in 模式下已经证明对 `cold_readrandom`、稀疏 MultiGet 和密集同 block MultiGet 有收益，尤其 `multiget_sameblock` 达到 baseline 的 `220.21%`。但是本轮 `scan` 只有 baseline 的 `84.09%`，存在约 `15.91%` 回归；因此当前版本仍不应默认开启 `writeBlockLocalIndex`。默认开启前还需要至少补齐多轮 200k 稳定性复测、scan 回归根因确认/消除，以及 block-local index 空间放大上限。
## V3E-06 scan 路径解耦与可观测性补强

针对 V3E-05 中 `scan` 只有 baseline `84.09%` 的回归，本阶段先完成一项低风险修复：v3 SST 打开时仍会校验声明了 `block.local_index.v1` 的表必须存在 `block_local_index` metaindex handle，但不再 eager 读取 directory block 内容。directory 仅在同 data block 多 key MultiGet 或离线 check/repair 自检时按需加载。

这使 iterator/scan 路径不再因为打开 v3 SST 而额外读取 block-local-index directory。为后续基准判定可追踪，`ldb.sstReadStats` 也补充归档：

- `blockLocalIndexTables`
- `blockLocalIndexDirectoryLoadedTables`
- `blockLocalIndexDirectoryEntries`
- `blockLocalIndexSeekCount`
- `blockLocalIndexHitCount`
- `blockLocalIndexFallbackCount`

发布结论保持不变：该修复降低了 scan 路径被 block-local index 元数据牵连的风险，但默认开启仍必须等待新的 200k 对比证明 `scan` 无明显回归，并补齐空间放大上限。
## V3E-06 200k 阈值策略复测

在 directory lazy-load 之后，又将 MultiGet 的 block-local index 启用策略收紧为同一 data block group 至少 `8` 个 lookup 才使用 local index。该策略避免稀疏随机 MultiGet 因偶发同块命中而加载 directory/local-index，同时保留密集同 block batch 的优化空间。

本轮相邻 200k 对比结果如下：

| 场景 | baseline | v3 lazy + threshold8 | v3 / baseline |
| --- | ---: | ---: | ---: |
| `cold_readrandom` | `232,465.180 ops/s` | `212,625.059 ops/s` | `91.47%` |
| `multiget_random` | `188,663.529 ops/s` | `170,094.033 ops/s` | `90.16%` |
| `multiget_sameblock` | `237,016.924 ops/s` | `254,079.856 ops/s` | `107.20%` |
| `scan` | `1,267,053.752 ops/s` | `2,724,543.230 ops/s` | `215.03%` |

关键读路径证据：

- `cold_readrandom`：`blockLocalIndexDirectoryLoadedTables=0`，`blockLocalIndexSeekCount=0`
- `multiget_random`：`blockLocalIndexDirectoryLoadedTables=0`，`blockLocalIndexSeekCount=0`，说明稀疏随机 batch 不再加载 local index
- `multiget_sameblock`：`blockLocalIndexDirectoryLoadedTables=12`，`blockLocalIndexSeekCount=195282`，`blockLocalIndexHitCount=190315`，说明密集同块 batch 仍会使用 local index
- `scan`：`blockLocalIndexDirectoryLoadedTables=0`，`blockLocalIndexSeekCount=0`，证明 iterator/scan 路径未加载 block-local index

证据路径：

- `ldb-longrun/build/reports/ldb-db-bench-v3e06-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e06-v3bli-threshold8-200k/ldb-db-bench-summary.json`

结论：lazy directory 与 threshold8 策略已经修复了 scan 路径牵连，并降低了 sparse MultiGet 的 local-index 误用；但 `cold_readrandom` 和 `multiget_random` 仍低于 baseline，因此当前版本继续保持 `writeBlockLocalIndex` 显式 opt-in，不进入默认开启候选。后续默认开启前，必须继续压低 v3 元数据/文件大小对 cold read 和 sparse MultiGet 的影响，并补齐空间放大上限。
## V3E-07 block-local index interval4 取舍验证

在 V3E-06 lazy directory 与 threshold8 策略基础上，补充验证 `blockLocalIndexInterval=4`。该配置是当前 API 默认值，目标是降低 local-index 锚点密度，减少 v3 元数据/空间成本对 cold read 和 sparse MultiGet 的影响。

与同一轮 baseline 以及 `interval=1` 对比：

| 场景 | baseline | interval=1 | interval=4 |
| --- | ---: | ---: | ---: |
| `cold_readrandom` | `232,465.180 ops/s` | `212,625.059 ops/s` (`91.47%`) | `211,307.754 ops/s` (`90.90%`) |
| `multiget_random` | `188,663.529 ops/s` | `170,094.033 ops/s` (`90.16%`) | `189,448.836 ops/s` (`100.42%`) |
| `multiget_sameblock` | `237,016.924 ops/s` | `254,079.856 ops/s` (`107.20%`) | `225,199.916 ops/s` (`95.01%`) |
| `scan` | `1,267,053.752 ops/s` | `2,724,543.230 ops/s` (`215.03%`) | `3,272,417.286 ops/s` (`258.27%`) |

`interval=4` 读路径证据显示：

- `cold_readrandom` 和 `scan` 均为 `blockLocalIndexDirectoryLoadedTables=0`、`blockLocalIndexSeekCount=0`
- `multiget_random` 为 `blockLocalIndexDirectoryLoadedTables=0`、`blockLocalIndexSeekCount=0`，并回到 baseline 的 `100.42%`
- `multiget_sameblock` 仍会加载 local index，但收益从 `interval=1` 的 `107.20%` 降至 `95.01%`

证据路径：

- `ldb-longrun/build/reports/ldb-db-bench-v3e06-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e06-v3bli-threshold8-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e06-v3bli-threshold8-interval4-200k/ldb-db-bench-summary.json`

结论：`interval=4` 对 sparse MultiGet 更安全，但会削弱 dense same-block 收益；`interval=1` 保留 dense 收益，但 cold/sparse 成本较高。当前版本仍不进入默认开启候选。下一步应优先降低 v3 block-local index 的空间/元数据成本，或引入更明确的 workload admission：稀疏随机读默认避开 local index，密集 batch 或显式 profile 才使用更密集锚点。
### V3E-08 写入侧 admission：跳过单锚点本地索引块

本轮在 `TableBuilder` 写入侧增加 block-local-index admission：只有当当前 data block 按 `blockLocalIndexInterval` 计算后至少能产生 2 个 anchor 时，才写出对应的 local-index block。低收益的单锚点块不再写出目录项，properties 也只在实际存在 local-index 目录项时声明 `block.local_index.v1` 不兼容特性与 `ldb.table.block_local_index=true`。

该策略修复了一个发布前语义风险：如果用户打开 V3 local-index，但表内数据块太小或 interval 太稀疏，旧逻辑会倾向于为低收益块写入额外元数据；更激进的 admission 又可能造成 properties 声明与 metaindex 目录不一致。当前规则把“配置开关”与“表内实际格式特性”分开：只有真正写出 local-index 的 SST 才声明该格式特性，读端也不会因为小表未写目录而误判格式损坏。

200k read-optimized 样本补充如下，基线仍采用同一轮本地环境中的 V3E-06 baseline 结果作为参考：cold_readrandom 232,465.180 ops/s，multiget_random 188,663.529 ops/s，multiget_sameblock 237,016.924 ops/s，scan 1,267,053.752 ops/s。

| 配置 | cold_readrandom | multiget_random | multiget_sameblock | scan | 关键观测 |
| --- | ---: | ---: | ---: | ---: | --- |
| V3 local-index interval=1 + admission(>=2 anchors) | 167,208.911 | 157,276.574 | 255,594.549 | 2,475,633.577 | same-block 触发目录加载 12 张表、5556 个目录项、195282 次 seek、190315 次 hit；cold/scan 未加载目录。 |
| V3 local-index interval=4 + admission(>=2 anchors) | 183,962.008 | 168,337.671 | 261,546.185 | 2,483,605.102 | 默认块布局下 interval=4 过稀，所有 local-index 块被 admission 跳过，properties 不声明 local-index 特性。 |

结论：写入侧 admission 可以避免低收益 local-index 元数据污染文件格式，并保持 scan 路径不加载目录。`interval=1` 仍能在 dense same-block MultiGet 中提供命中证据，但 cold/sparse MultiGet 仍未达到默认启用标准；`interval=4` 在默认布局下更接近“安全跳过”策略。V3 local-index 继续保持 opt-in，不作为本版本默认格式策略。
### V3E-09 冷随机读 block cache admission

本轮增加 direct read data block 的 block cache admission 策略：`Options.blockCacheAdmissionMinReads(int)` 默认值为 1，保持历史行为；显式设置为 2 时，direct get/MultiGet 路径上的 data block 第一次 miss 只记录 admission 候选，第二次触达才进入 block cache。元数据块、iterator/scan 打开的 block、`blockCacheWarmOnOpen` 预热路径仍保持强制缓存，避免把随机读策略扩散到 scan 或格式读取路径。

该策略针对 cold_readrandom 和 sparse MultiGet 的缓存污染问题：一次性随机触达的 block 不立即占用 LRU 主缓存；重复触达的热点 block 仍能被 admitted。`BlockCache.stats()` 新增 `admissionRequests`、`admissionSkips`、`admissionAdmits`，用于确认策略是否真实生效。

200k read-optimized 相邻样本如下，均使用 table format v1、关闭 V3 local-index，以隔离 cache admission 效果。

| 配置 | cold_readrandom | multiget_random | multiget_sameblock | scan | 关键观测 |
| --- | ---: | ---: | ---: | ---: | --- |
| admissionMinReads=1 | 177,048.621 | 161,744.394 | 216,031.923 | 2,470,462.532 | 默认行为，miss 后立即缓存；admission 统计为 0。 |
| admissionMinReads=2 | 187,734.903 | 178,359.627 | 274,764.640 | 2,666,513.787 | cold 路径 `admissionSkips=5556`、`admissionAdmits=5556`；MultiGet random `admissionSkips=5564`、`admissionAdmits=5564`；scan 不触发 admission。 |

结论：二次触达 admission 是一个低风险可选策略，能减少冷随机读和 sparse MultiGet 对 block cache 的一次性污染，并通过统计字段直接观测。由于默认值仍为 1，本版本保持兼容默认行为；发布后建议在 read-optimized 场景继续扩大样本，评估是否把特定 profile 的默认值提升到 2。
### V3E-10 单点 get 强制使用 block-local index 的负实验

本轮验证将 `Table.get(Slice)` 在声明 `block.local_index.v1` 的 SST 上改为直接尝试 `seekDataBlock(..., allowLocalIndex=true)`，使单点随机命中路径也加载 persisted block-local index。该实验的目标是确认 50k v3 opt-in 中 `blockLocalIndexSeekCount=0` 是否只是接入遗漏，以及 persisted local index 是否能继续提升 `readrandom_hit`。

50k read-optimized 样本显示，该实验虽然让 `readrandom_hit` 记录 `blockLocalIndexDirectoryLoadedTables=1`、`blockLocalIndexDirectoryEntries=1389`、`blockLocalIndexSeekCount=50000`、`blockLocalIndexHitCount=48680`，但主指标从未强制接入 local index 的 v3 opt-in 样本 `192,795.093 ops/s` 回退到 `123,006.344 ops/s`。周边 workload 也同步走弱：`readrandom_sameblock=246,336.120 ops/s`，`readrandom_burst=234,339.995 ops/s`，`scan=1,782,257.978 ops/s`。

证据路径：
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k-v3-local-index/ldb-db-bench-summary.json`
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k-v3-local-index-single-get/ldb-db-bench-summary.json`

结论：当前 persisted block-local index 是 restart-anchor 粒度；单点 get 强制使用它会绕过 `Block` 打开阶段构建的稀疏 entry anchor 和热路径内存索引，CPU 成本高于收益。因此该实验不保留。后续若要让文件格式索引服务 `readrandom_hit`，不能只复用 restart-anchor 目录，而应设计能表达稀疏 entry anchor、必要 previousKey 或等价低成本恢复信息的下一版格式；在此之前，单点随机读继续优先使用当前 Block open-time 轻量内存索引。