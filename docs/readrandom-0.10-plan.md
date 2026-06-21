# 0.10.0 readrandom 70% 专项计划

## 背景

0.9.0 已经把 `readrandom` 从 RocksDB JNI 的 29% 提升到 55.68%。当前 0.10.0 的主目标是继续压缩点查路径上的对象创建、迭代器包装和块定位开销，把 `warm_readrandom` 推进到 RocksDB JNI 的 70% 以上。

0.9.0 基准：

- LDB read-optimized `warm_readrandom` 200k：247,361.396 ops/s
- RocksDB JNI `warm_readrandom` 200k：444,235.456 ops/s
- 当前比例：55.68%

0.10.0 目标：

- LDB read-optimized `warm_readrandom` 200k 达到 RocksDB JNI 的 70% 以上。
- 点查和 MultiGet 优先复用 direct point lookup，避免为每个 key 创建完整 table/internal iterator 链。
- 文件格式保持兼容，文件格式增强放入后续版本。

## 当前主要不足

### 点查仍走通用 iterator 链

当前 `Level` / `Level0` 的单 key get 与 MultiGet 都通过 `TableCache.newIterator()` 创建 `InternalTableIterator`，再进入 `TableIterator`、index block iterator、data block iterator。这个路径适合顺序扫描，但对随机点查过重。

主要开销：

- 每次点查创建多层 iterator 包装对象。
- key 从 raw slice 到 `InternalKey` 的转换发生在通用迭代链中。
- MultiGet 虽然按文件分组复用 iterator，但仍没有使用 SST 内部 direct get。

### 观测项缺少 direct get 维度

当前表缓存统计能看到 table load、iterator request、filter mayContain 等指标，但还不能直接区分：

- 点查是否已经绕开 iterator。
- direct get 请求数、命中数、未命中数。
- 后续 block seek 优化是否真实影响点查路径。

### 深层块内优化仍有空间

`BlockIterator.seek()` 已经基于 restart point 做二分，再在 restart 区间内线性扫描。后续仍可继续评估：

- restart 区间过大时的块内二分或短索引。
- data block key 前缀解码的临时对象减少。
- block cache 命中路径上的 slice/value 生命周期优化。

## 本版本实现范围

### Direct point lookup

在 `Table` 增加面向内部 key 的 direct get：

1. 用 index block iterator 定位目标 data block。
2. 只打开命中的 data block。
3. 在 data block 中 seek 到目标 internal key。
4. 返回候选 block entry，由上层继续沿用当前 sequence/type/user-key 判断逻辑。

这个设计故意不把 value type 判断下沉到 table 层，避免 table 包依赖 impl 层的 `LookupKey`、`InternalKey`、`LookupResult` 语义。

### TableCache direct get

在 `TableCache` 增加 direct get 入口和统计：

- `directGetRequestCount`
- `directGetHitCount`
- `directGetMissCount`

单 key get 和 MultiGet 通过该入口读取 SST，保留现有 bloom filter、range delete 和 read stats 逻辑。

### Level / Level0 接入

替换点查路径：

- `Level.get(LookupKey, ReadStats)`
- `Level0.get(LookupKey, ReadStats)`
- `Level.get(List<LookupKey>, ReadStats)`
- `Level0.get(List<LookupKey>, ReadStats)`

命中语义保持不变：

- internal key 解析仍在 impl 层完成。
- user key 必须与请求 key 一致。
- `VALUE` 返回 value。
- `DELETION` 返回 deleted。
- 未命中返回 null。
- range delete 覆盖检查保持现有路径。

## 兼容性

- 不修改 SST 文件格式。
- 不修改 manifest、wal、sequence、comparator 语义。
- 不修改 public API。
- direct get 只改变读取实现路径，不改变读取结果。

## 发布门禁

本阶段完成后至少运行：

- 相关单元测试：覆盖 point get、delete、MultiGet、观测统计。
- readrandom 200k 对比基准：LDB read-optimized vs RocksDB JNI。
- 发布前门禁仍按既有 release skill：CHANGELOG、文档、UTF-8、release gate、Git traceability。

## 当前实现结果

0.10.0 首轮实现同时覆盖两条路径：

- SST direct point lookup：`TableCache.get` 进入 `Table.get`，再通过 `Block.seek` 避免通用 iterator 链和 `BlockIterator` 构造期首条预读。
- MemTable 最新点值索引：在没有 range tombstone 且读取 snapshot 覆盖最新版本时，`MemTable.get` 可直接通过 user key 命中最新 `VALUE` / `DELETION`，旧 snapshot、range delete 和未命中仍回退原 skiplist 路径。

200k read-optimized `warm_readrandom` 实测：

| 指标 | ops/s | 证据 |
| --- | ---: | --- |
| LDB | 566,788.838 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-memfast-010/ldb-db-bench-summary.json` |
| RocksDB JNI | 575,324.008 | `build/reports/rocksdbjni-comparison-memfast-010/comparison.csv` |
| LDB/RocksDB JNI | 98.52% | `566788.838 / 575324.008` |

结论：本轮已超过 70% 目标。当前 Windows 环境仍会输出目录 fsync `AccessDeniedException` WARN，但 benchmark 任务返回 `PASS`，该 WARN 与既有发布记录一致，暂按环境口径记录。

补充 SST / MultiGet 口径实测：

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 证据 |
| --- | ---: | ---: | ---: | --- |
| `cold_readrandom` | 184,805.871 | 286,456.905 | 64.52% | `build/reports/rocksdbjni-comparison-random-suite-010/comparison.csv` |
| `multiget_random` | 216,561.966 | 330,273.078 | 65.57% | `build/reports/rocksdbjni-comparison-random-suite-010/comparison.csv` |

补充结论：warm readrandom 已经达标，cold readrandom 和 MultiGet 仍有深层 SST/block/cache 优化空间。下一阶段优先继续压缩 SST data block 解码、range 内线性扫描和 MultiGet 批量定位成本。

## Batch block seek 优化范围

本轮继续推进 MultiGet 批量定位优化，不修改 SST 文件格式：

- `Table` 按 index block 定位结果把内部 key 分组到同一个 data block handle。
- 同一个 data block 只打开一次，避免 MultiGet 中同一 block 的重复 `openBlock`。
- 默认随机 MultiGet 仍对同一 block 内每个 key 执行 `Block.seek`，避免稀疏随机 key 使用顺序扫描时从最小 key 解码到最大 key 造成额外成本。
- `Block.seekAll` 作为后续密集批量场景能力保留；只有目标 key 在同一 block 内足够密集时才适合启用。
- `Level` / `Level0` 的 MultiGet 使用 table cache batch direct get；单 key get 保持原 direct get 路径。
- 不改变 range delete、snapshot、sequence、value type、user key 匹配语义；有 range delete 的文件仍沿用现有覆盖检查。

200k read-optimized `multiget_random` batch block reuse 实测：

| 指标 | ops/s | 说明 |
| --- | ---: | --- |
| 上一轮 LDB baseline | 216,561.966 | `rocksdbjni-comparison-random-suite-010` 对应 LDB 结果 |
| 本轮 LDB block reuse | 221,373.672 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-blockreuse-010/ldb-db-bench-summary.json` |
| LDB 自身提升 | 2.22% | `(221373.672 - 216561.966) / 216561.966` |
| 本轮 RocksDB JNI | 392,500.955 | `build/reports/rocksdbjni-comparison-blockreuse-010/comparison.csv` |
| 本轮 LDB/RocksDB JNI | 56.40% | `221373.672 / 392500.955` |

结论：同 block 复用打开是低风险正收益，但随机 MultiGet 的主瓶颈仍不在重复 `openBlock`，而在 SST/index/data block 定位、key 解码和 RocksDB JNI 本轮波动带来的对照口径差异。`Block.seekAll` 的顺序扫描策略在稀疏随机 key 下会退化，默认不启用，只保留给后续密集 batch 场景。

## 随机读 block cache 预热策略

为冷启动随机读增加显式 opt-in 的 `Options.blockCacheWarmOnOpen(true)`：

- 默认关闭，避免普通打开库被迫预读所有 data block。
- 仅在 `cacheBlocks=true` 时生效。
- 打开 SST 后枚举 index block 中的 data block handle，并调用 `openBlock` 放入 block cache。
- `ldb.sstReadStats` 新增 `blockCacheWarmupTables` 和 `blockCacheWarmupBlocks`，用于发布前确认预热是否实际发生。
- `ldbDbBenchReport` 通过 `-Pldb.dbBench.blockCacheWarmOnOpen=true` 显式启用该选项，用于衡量随机读场景把首次 block 加载前移到 open 阶段后的收益；默认保持关闭，避免 MultiGet 稀疏随机批量读被预热成本误伤。

200k read-optimized `cold_readrandom` 预热实测：

| 指标 | ops/s | 说明 |
| --- | ---: | --- |
| 上一轮 LDB baseline | 184,805.871 | `rocksdbjni-comparison-random-suite-010` 对应 LDB 结果 |
| 本轮 LDB warm-on-open | 199,464.617 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-warmopen-010/ldb-db-bench-summary.json` |
| LDB 自身提升 | 7.93% | `(199464.617 - 184805.871) / 184805.871` |
| 本轮 RocksDB JNI | 300,707.294 | `build/reports/rocksdbjni-comparison-warmopen-010/comparison.csv` |
| 本轮 LDB/RocksDB JNI | 66.33% | `199464.617 / 300707.294` |

结论：显式 block cache open-time warmup 对 cold_readrandom 有正收益，但仍未达到 70%+；后续需要继续减少 data block key 解码和 block 内定位成本，或在文件格式版本中引入更轻量的 block-local index。

## Restart key 内存索引

在不修改 SST 文件格式的前提下，`Block` 构造时缓存每个 restart point 的完整 key：

- `Block.seek` 的 restart 二分直接比较 `restartKeys[mid]`，不再为了二分定位反复解码 restart entry。
- `Block.seekAll` 也复用同一 restart key cache。
- 代价是每个打开的 block 多持有一组 restart key slice；在 `blockCacheWarmOnOpen=true` 时，该成本前移到打开/预热阶段。
- 该策略为后续文件格式版本中的 block-local index 提供低风险内存侧验证。

## 后续版本候选
- 下一阶段文件格式设计已落入 `docs/storage-format-0.11-block-index-design.md` 及英文副本，重点转向持久化紧凑 block-local index，而不是 full-entry 内存索引。

- 文件格式增强：block-level key index、filter/layout metadata、format version capability。
- 更深层块内查找优化：减少 restart 区间线性扫描。
- 缓存策略优化：区分随机读/扫描读的 block cache admission。
- Long-run benchmark：随机读、混合读写、MultiGet 分布式热点。

## Bloom/filter miss/mixed 验证补充

- Bloom/filter 专项进入下一阶段验证：ldbDbBenchReport 新增 eadrandom_miss、eadrandom_mixed、multiget_mixed，用于区分全命中随机读与缺失/混合随机读；启用 BloomFilterPolicy 时，v3 properties 会记录 filter policy、scope、key count、filter block bytes 与 bits-per-key，方便发布前归档 miss-heavy 场景证据。

## Bloom/filter miss/mixed 50k 对照结果

本轮修正 `readrandom_miss`、`readrandom_mixed` 与 `multiget_mixed` benchmark：准备数据后关闭重开再计时，确保读路径进入 SST/Bloom；同时移除新增场景中的强制 `compactRange`，避免把 flush/compaction 成本计入 Bloom 读路径验证。修复 v3 filter properties 写入顺序后，`BlockBuilder` key 递增约束不再被破坏。

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Bloom 证据 |
| --- | ---: | ---: | ---: | --- |
| `readrandom_miss` | 277,468.554 | 301,242.565 | 0.9211 | `filterSkips=49604`, `mayContainFalse=49604` |
| `readrandom_mixed` | 277,333.582 | 435,665.684 | 0.6366 | `filterSkips=24793`, `mayContainFalse=24793` |
| `multiget_mixed` | 317,069.768 | 383,228.393 | 0.8274 | `filterSkips=24793`, `directGetBatchRequests=782` |

证据文件：`ldb-longrun/build/reports/ldb-db-bench-bloom-miss-mixed-50k/ldb-db-bench-summary.csv` 与 `build/reports/rocksdbjni-comparison-bloom-miss-mixed-50k/comparison.csv`。
## SST hit-path 50k 对照结果

本轮新增 `readrandom_hit`，并在 `ldb.sstReadStats` 中补充 hit-path 细分计数：`candidateEntryHits`、`candidateEntryMisses`、`bloomFalsePositives`、`tableIndexSeeks`、`tableDataBlockOpens` 与 `tableDataBlockSeeks`。该计数用于拆分 Bloom miss 已经跳过的部分与真实命中 key 仍需承担的 SST index/data-block 定位成本。

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 关键观察 |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 166,519.353 | 420,630.761 | 0.3959 | 50,000 次 hit 对应 50,000 次 index seek、data block open 与 data block seek |
| `readrandom_miss` | 326,763.001 | 422,757.903 | 0.7729 | Bloom 跳过 49,604 次，只有 396 次 false positive 进入 table read |
| `readrandom_mixed` | 295,314.365 | 438,877.912 | 0.6729 | 25,000 次真实 hit + 207 次 false positive，mixed 差距主要来自 hit 半边 |
| `multiget_mixed` | 293,598.151 | 229,949.655 | 1.2768 | batch direct get 将 tableReads 压到 782，证明按批分组能显著摊薄 hit-path 成本 |

证据文件：`ldb-longrun/build/reports/ldb-db-bench-hitpath-50k/ldb-db-bench-summary.csv` 与 `build/reports/rocksdbjni-comparison-hitpath-50k/comparison.csv`。

结论：下一阶段最值得做的是单点 hit-path 优化，而不是继续扩大 Bloom。优先方向是减少每个 hit 都重复执行的 index seek、data block open 与 block 内 seek 成本；`multiget_mixed` 的结果说明批量分组/复用是有效路径。
## 单点局部性 hit-path 50k 对照结果

本轮在不修改 SST 文件格式的前提下，为 `Table.get(Slice internalKey)` 增加两个低风险快路径：最近一次 index seek 的覆盖缓存，以及最近一次 data block 的直接复用。索引缓存只在新 key 不小于上一次 lookup key 且不超过当前 index limit key 时命中，避免跨越前一个 data block 边界；data block 缓存使用单个不可变 holder 通过 `volatile` 发布，避免并发读取到 handle/block 错配。

新增 `readrandom_sameblock` benchmark 用于观察同一 data block 内连续点查的局部性收益；普通 `readrandom_hit` 仍保留纯随机命中口径。

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 关键观察 |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 137,117.521 | 388,310.005 | 0.3531 | 纯随机命中只有 `tableIndexCacheHits=21`、`tableLastBlockHits=33`，符合预期，快路径不会污染普通随机读语义 |
| `readrandom_sameblock` | 294,151.734 | 542,632.462 | 0.5421 | 局部性读命中 `tableIndexCacheHits=47839`、`tableLastBlockHits=47839`，实际 `tableIndexSeeks=2161`、`tableDataBlockOpens=2161` |

证据文件：`ldb-longrun/build/reports/ldb-db-bench-sameblock-50k/ldb-db-bench-summary.json` 和 `build/reports/rocksdbjni-comparison-sameblock-50k/comparison.csv`。

结论：最近块/最近 index 覆盖缓存能显著压缩局部性点查中的重复 index seek 与 data block open；但纯随机 `readrandom_hit` 仍主要受每 key 的 SST 定位和 block 内 seek 成本影响。后续若继续冲击普通随机 hit，比起扩大单元素缓存，更有价值的是面向 block 内 seek 的更紧凑索引或 request-level read context，不过后者涉及 API/线程语义，需要单独设计。
## 请求级 single-key read context 设计与验收

第三步实现范围限定为内部请求级上下文，不改变 public API、不修改 SST 文件格式，也不把上下文跨线程共享。`Version.get` 为每次单 key 读取创建 `PointReadContext`，并在 `Level0` / `Level` 内传递；上下文只缓存最近一次实际参与 table read 的候选 SST 文件。当后续 level 内 lookup key 仍落在该文件的 user-key 范围内时，优先复用该文件，跳过重复的 level 文件定位和候选文件构造；真正的 data block / index block 复用继续由 `Table` 最近 index 覆盖缓存和最近 data block 缓存负责。

边界约束：
- 只在一次 public `get` 调用链内生效，不跨 API 调用保存状态。
- LevelN 只在最近文件覆盖当前 user key 时命中；Level0 仍按 newest-first 顺序处理候选文件，缓存文件只作为候选构造加速，不改变 L0 覆盖语义。
- range delete、snapshot sequence、value type 和 user key 匹配仍由原有逻辑判断。
- `ldb.sstReadStats` 新增 `pointReadContextFileHits` / `pointReadContextFileMisses`，用于证明该路径是否实际触发。

新增 `readrandom_burst` benchmark 用于观察应用层 burst/往返局部性点查；它和 `readrandom_sameblock` 一起覆盖连续邻近读，但保留普通 `readrandom_hit` 作为纯随机口径。
### read context 边界修正

实现落地时将 `PointReadContext` 提升为 `VersionSet` 内部的 `ThreadLocal` 短时上下文：同一线程、同一 current Version、同一列族，并且空闲时间不超过约 10ms 时，连续 public `get` 可以复用最近候选 SST 文件；一旦 Version 切换、列族变化或超过短时窗口，缓存会自动清空。该设计仍不改变 public API，也不跨线程共享状态。
### read context 窗口实现修正

为避免在每次 point get 中调用 `System.nanoTime()` 增加热路径开销，短时窗口改为轻量调用计数窗口：同线程、同 current Version、同列族下最多复用 4096 次连续 public `get`，窗口耗尽后自动清空并重新学习最近文件。
## read context 50k 对照结果

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 关键观察 |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 160,133.282 | 406,567.860 | 0.3939 | 普通随机 hit 的 `pointReadContextFileHits=49987`，但 `tableIndexCacheHits=21`、`tableLastBlockHits=33`，说明 request-level 文件复用生效但 block 局部性很低 |
| `readrandom_sameblock` | 312,031.173 | 555,629.640 | 0.5616 | `tableIndexCacheHits=47839`、`tableLastBlockHits=47839`，data block open 压到 `2161` |
| `readrandom_burst` | 273,191.052 | 493,927.653 | 0.5531 | 往返 burst 局部性下 `tableIndexCacheHits=13790`、`tableLastBlockHits=14153`，收益低于单调 sameblock 但明显高于普通 hit |

证据文件：`ldb-longrun/build/reports/ldb-db-bench-readcontext-50k-v2/ldb-db-bench-summary.json` 和 `build/reports/rocksdbjni-comparison-readcontext-50k/comparison.csv`。

结论：本阶段三项目标均已闭合：Table 最近 block 复用、index seek 覆盖缓存、以及 API 连续 single-key point get 的线程本地 read context。普通随机 hit 仍然受 block 内 seek / 解码成本限制；局部性和 burst 场景已经稳定超过 50% 对照线。
### Block seek anchor 试验结论

- 本阶段将 Block 打开时已构建的 restart key/offset 正式作为轻量内存 seek index；`Block.seek` 先通过 restart anchor 定位扫描起点，再在单个 restart 区间内线性扫描，避免 full-entry index 和冷 Block 预解码。
- 50k 验收中，`readrandom_hit` 为 139,821 ops/s，主路径 `blockSeekIndexHits=50,000`、`blockSeekIndexMisses=0`、`blockSeekIndexFallbacks=0`，证明随机单点读取已走 Block seek index。
- `readrandom_sameblock` 为 298,209 ops/s，`readrandom_burst` 为 226,772 ops/s，`multiget_mixed` 为 238,913 ops/s，`multiget_sameblock` 为 269,404 ops/s，`scan` 为 1,450,280 ops/s；scan 路径不使用 block seek index，统计为 0。
- 结论：restart anchor 是低风险、格式兼容的正确抽象，但单独依靠 Block 内 restart 索引不足以把 `readrandom_hit` 拉到 RocksDB JNI 的 50%；下一阶段仍应优先降低随机单点的 `tableIndexSeeks` 与 `tableDataBlockOpens`。
### Table 点读数组索引与稀疏 Block anchor 组合结论

- 新增 Table 打开阶段的 index block 数组索引，将单点读 `tableIndexSeeks` 从接近 50,000 降到 0，避免热路径重复解码 index block。
- 新增 Table 点读 data block direct-mapped cache，减少随机回访同一 data block 时进入全局 block cache 的成本；在最终 50k 验收中 `readrandom_hit` 的 `tableDataBlockOpens=11,085`、`tableLastBlockHits=38,915`。
- 恢复 Block 打开阶段的稀疏 entry anchor（每 4 条 entry 一个 anchor），只索引 restart 区间内稀疏 anchor，避免 full-entry index；该组合下 `multiget_mixed` 达到 RocksDB JNI 的 92.15%，`readrandom_sameblock` 达到 53.02%，`readrandom_burst` 达到 48.97%。
- 主验收 `readrandom_hit` 为 137,708 ops/s，仅为 RocksDB JNI 467,188 ops/s 的 29.48%，说明当前剩余瓶颈已经不在 table index seek，而在随机单点的 data block 命中/解码/比较路径；P0 50% 目标仍未完成。
### Block.seek 跳过非候选 value 解码结论

- `Block.seek` 与 `seekFromOffset` 改为只在命中候选 entry 时读取 value；扫描过程中未命中的 entry 只解 key 并通过 `SliceInput.skipBytes(valueLength)` 跳过 value，减少随机点读的 value Slice 创建与 position 移动成本。
- 50k 对照中，`readrandom_hit` 提升到 149,953 ops/s，为 RocksDB JNI 427,426 ops/s 的 35.08%；`readrandom_sameblock` 为 311,414 ops/s，达到 58.57%；`readrandom_burst` 为 320,670 ops/s，达到 62.48%。
- `multiget_mixed` 为 217,566 ops/s，达到 RocksDB JNI 的 99.34%；但批量路径仍有 `tableIndexSeeks=25,207`，说明下一步应把 Table 点读数组索引/direct cache 能力下沉到 MultiGet 分组路径。
- 主验收 `readrandom_hit` 尚未达到 50%，但本阶段证明剩余有效优化点在 data block seek 解码成本与批量路径索引复用，而不是继续增加 full-entry 内存索引。
### 负实验回收结论：保护 `readrandom_hit` 主指标

- `pointGetBlockCacheSlot` 的重 hash/mix 实验降低了 `readrandom_hit` 的 `tableDataBlockOpens`，但 50k `readrandom_hit` 从上一轮 149,953 ops/s 回落到 143,843 ops/s，`readrandom_sameblock` 和 `readrandom_burst` 也回退；结论是热路径 hash 成本高于减少碰撞带来的收益，当前版本不保留该策略。
- 4-way set-associative point data-block cache 将 `readrandom_hit` 的 `tableDataBlockOpens` 压到 1,416，但 50k `readrandom_hit` 只有 139,300 ops/s；说明随机 hit 当前更敏感于每次 lookup 的 CPU 探测成本，不能只以 block open 计数作为优化目标。
- 去掉 `Table.get` / MultiGet 返回候选后的二次比较，50k `readrandom_hit` 回落到 102,426 ops/s；虽然 `Block.seek` 理论上返回第一个 `>= target` 的 entry，但该保护检查在当前 JIT/调用形态下不能作为有效微优化删除。
- MultiGet 改用 Table 打开时构建的数组 index 后，`multiget_mixed` 的 `tableIndexSeeks` 可降为 0，但 50k `multiget_mixed` 回落到 224,801 ops/s，低于保留 `indexBlock.seek` 的已知结果；因此当前批量路径继续保留 per-key index block seek + same-block data-block reuse。
- 结论：当前应保留已验证正收益的 restart/sparse anchor、Table 单点数组 index、direct-mapped data-block cache 和 `Block.seek` skip-value；下一阶段若继续冲击 `readrandom_hit` 50%，应转向更低 CPU 成本的 data block key 解码/比较路径，而不是增加更复杂的运行时缓存探测。
### Block.readKey direct-copy 50k 验收结论

- `Block.readKey` 的共享前缀 key 重建不再创建 `SliceOutput` 临时包装对象，改为直接通过 `Slice.setBytes` 复制 shared prefix，并通过 `SliceInput.readBytes` 写入 non-shared suffix；文件格式、restart/sparse anchor、seek 语义均不变。
- 50k `read_optimized` 验收中，`readrandom_hit` 达到 161,956 ops/s，相比上一轮 final guard 的 155,081 ops/s 继续小幅提升；`blockSeekIndexHits=50,000`、`blockSeekIndexMisses=0`、`blockSeekIndexFallbacks=0`，证明主路径仍走轻量 Block seek index。
- 同机 RocksDB JNI 对照中，`readrandom_hit` 为 441,936 ops/s，当前 LDB/RocksDB JNI 为 36.65%，主目标 50% 仍未完成。
- sameblock/burst/MultiGet 未出现主观回退：`readrandom_sameblock` 为 315,204 ops/s，对照 54.94%；`readrandom_burst` 为 381,084 ops/s，对照 78.60%；`multiget_mixed` 为 294,708 ops/s，对照 87.45%；`scan` 为 1,519,138 ops/s。
- 结论：减少 key 重建临时对象是正收益方向，但剩余差距仍集中在 data block 内 key 解码/比较成本。下一步应继续寻找不增加 full-entry index、不增加复杂运行时缓存探测的低 CPU 成本路径。
### Block.seek scratch-key 复用实验回收

- 单次 `Block.seek` 内复用 shared-key 重建 scratch buffer 的实验已回收：50k `readrandom_hit` 从 direct-copy 版本的 161,956 ops/s 回落到 153,643 ops/s，不满足主验收指标优先原则。
- 该实验虽然让 `multiget_mixed`、`multiget_sameblock` 和 `scan` 在短跑中出现波动性提升，但目标明确以 `readrandom_hit` 为主验收，因此当前版本不保留 scratch-key 复用。
- 当前保留的正收益点仍是 `Block.readKey` direct-copy：去掉 `SliceOutput` 临时对象，但不复用跨 entry 的 key buffer，避免改变 JIT/逃逸分析下的主点查热路径形态。
### InternalUserComparator Slice 级比较 50k 验收结论

- `InternalUserComparator.compare(Slice, Slice)` 不再为每次比较构造两个 `InternalKey` 对象，也不再解包 `ValueType`；比较逻辑改为直接比较 internal key 的 user-key Slice，并直接读取尾部 packed sequence/type 中的 sequence number，保持 user-key 升序、sequence 降序语义不变。
- 50k `read_optimized` 验收中，`readrandom_hit` 达到 174,459 ops/s；同机 RocksDB JNI 为 396,410 ops/s，LDB/RocksDB JNI 达到 44.01%，距离 50% 主目标继续缩小但尚未完成。
- 周边 workload：`readrandom_sameblock` 为 322,833 ops/s，对照 61.81%；`readrandom_burst` 为 311,130 ops/s，对照 63.67%；`multiget_mixed` 为 277,267 ops/s，对照 136.68%；`multiget_sameblock` 为 373,474 ops/s；`scan` 为 1,254,277 ops/s。
- 统计链路保持正确：`readrandom_hit` 的 `blockSeekIndexHits=50,000`、`blockSeekIndexMisses=0`、`blockSeekIndexFallbacks=0`，`tableIndexSeeks=0`；`scan` 不触发 block seek index。
- 结论：减少 comparator 对象构造是当前最有效的方向之一。下一步应继续沿着 Slice 级 internal-key/user-key 比较做更低对象成本优化，例如减少 user-key `slice(...)` 包装对象，仍然避免 full-entry index 和复杂运行时缓存探测。
### Bytewise raw-array user-key 比较实验回收

- 在 `InternalUserComparator` 内为默认 `BytewiseComparator` 增加 raw-array user-key 比较、避免 user-key `slice(...)` 包装对象的实验已回收：50k `readrandom_hit` 从 Slice 级 internal-key compare 的 174,459 ops/s 回落到 151,875 ops/s，`readrandom_sameblock` 和 `readrandom_burst` 也明显回退。
- 该实验说明当前 JIT/调用形态下，手写 raw-array 快路径不一定优于现有 `Slice.compareTo`/`BytewiseComparator` 组合；当前版本只保留“不构造 InternalKey”的正收益优化。
### InternalUserComparator 长度 guard 移除实验回收

- 尝试移除 `InternalUserComparator.compare(Slice, Slice)` 开头的 internal key 长度 `checkArgument` guard 后，50k `readrandom_hit` 从 Slice 级 internal-key compare 已验证的 174,459 ops/s 回退到 132,221 ops/s；该实验不满足主指标优先原则，已回收。
- 虽然该检查看起来属于防御性逻辑，但当前 JIT/调用形态下，移除它没有带来热路径收益，反而明显污染主随机命中基线；当前版本恢复显式长度校验，仅保留“不构造 `InternalKey` 对象”的正收益优化。
- 后续若继续优化 comparator，应优先寻找不改变调用形态稳定性的 Slice 区间比较能力，而不是删除边界 guard 或引入手写 raw-array 快路径。
### Slice 区间比较快路实验回收

- 尝试在 `Slice` 增加 offset/length 区间比较，并让默认 `BytewiseComparator` 下的 `InternalUserComparator.compare` 直接比较 user-key 子区间，以避免 `left.slice(...)` / `right.slice(...)` 包装对象；该实现对自定义 comparator 保留原 slice 语义，但默认 bytewise 热路径走新区间比较。
- 50k `read_optimized` 短基准显示该实验让主指标 `readrandom_hit` 回退到 141,790 ops/s，明显低于 Slice 级 internal-key compare 已验证的 174,459 ops/s 基线；因此当前版本不保留 `Slice.compareTo(offset,length,...)` 快路。
- 该结果与此前 raw-array user-key 快路回退结论一致：当前 JIT/调用形态下，减少 `slice(...)` 包装并不天然转化为吞吐提升。后续 comparator 优化需要先找更强热点证据，避免继续在 user-key bytewise 手写路径上消耗主指标。
### Sequence 解包内联实验回收

- 尝试在 `InternalUserComparator.compare` 中把 `SequenceNumber.unpackSequenceNumber(...)` 直接内联为 `packed >>> 8`，以减少比较器热路径中的一次静态方法调用；该改动语义等价，但改变了当前 JIT 可见的调用形态。
- 50k `read_optimized` 短基准显示 `readrandom_hit` 回退到 164,108 ops/s，低于 guard 恢复后的 171,696 ops/s 和历史 Slice 级 internal-key compare 174,459 ops/s；`readrandom_sameblock`、`readrandom_burst` 与 `scan` 也同步走弱，因此该实验已回收。
- 当前版本继续通过 `SequenceNumber.unpackSequenceNumber` 表达 packed sequence/type 的格式语义，把优化边界留给 JVM 内联，而不是在业务比较器里手工展开。
### Block sparse anchor interval=2 实验回收

- 尝试将 `Block` 打开期稀疏 seek anchor 从每 4 条 entry 一个收紧为每 2 条 entry 一个；该实验仍然只索引 restart 区间内的稀疏 anchor，不改变 SST 文件格式，也不是 full-entry index。
- 50k `read_optimized` 短基准显示主指标 `readrandom_hit` 回退到 146,226 ops/s，低于当前 guard 恢复后的 171,696 ops/s 和历史 174,459 ops/s 基线；`readrandom_sameblock`、`readrandom_burst` 与 `multiget_mixed` 也同步走弱。
- 结论：更密的内存 anchor 会增加打开期解码和热路径 anchor 二分/对象压力，当前版本继续保留每 4 条 entry 一个 anchor 的平衡点。后续若继续缩短 block 内扫描范围，应优先考虑持久化 compact block-local index 的 admission/布局，而不是简单提高当前内存 anchor 密度。
### Block sparse anchor interval=8 实验回收

- 尝试将 `Block` 打开期稀疏 seek anchor 从每 4 条 entry 一个放宽为每 8 条 entry 一个；该实验仍然通过 restart 区间内 sparse anchor 缩小扫描范围，不改变 SST 文件格式，也不是 full-entry index。
- 第一轮 50k `read_optimized` 短基准中，`readrandom_hit` 一度达到 177,100 ops/s，高于 guard 恢复后的 171,696 ops/s 和历史 174,459 ops/s 基线；但 `readrandom_sameblock`、`readrandom_burst`、`multiget_mixed` 与 `scan` 均低于恢复基线，说明收益不具备 workload 普适性。
- 同参数复测中，`readrandom_hit` 回落到 157,745 ops/s，`readrandom_sameblock` 为 322,031 ops/s，`readrandom_burst` 为 343,650 ops/s，`multiget_mixed` 为 297,009 ops/s，`scan` 为 1,313,819 ops/s；主指标不稳定且周边路径没有整体胜出，因此当前版本不保留 interval=8。
- 结论：当前内存 sparse anchor 的简单密度调参已接近收益边界。interval=4 仍是当前稳定折中；后续应转向更明确的 admission/layout 设计或更细粒度热点证据，而不是继续盲调 anchor 间隔。
### Block sparse anchor 精确命中直返实验回收

- 尝试为 `Block` 打开期稀疏 anchor 增加 value offset/length，仅当 `targetKey` 精确等于 sparse anchor key 时直接返回 anchor value；非精确命中仍从 anchor offset 沿用原扫描路径。该实验仍只覆盖稀疏 anchor，不是 full-entry index，也不改变 SST 文件格式。
- 50k `read_optimized` 短基准显示 `readrandom_hit` 回退到 143,404 ops/s，明显低于当前恢复基线；`multiget_mixed` 与 `scan` 也同步走弱。虽然 `readrandom_sameblock` 短跑达到 333,930 ops/s，但主验收指标优先，且收益不具备整体性。
- 结论：给当前内存 sparse anchor 增加更多字段和额外精确比较，会提高热路径对象/比较成本，少数精确 anchor 命中不足以抵消该成本。当前版本继续保持 anchor 仅保存 key、entry offset 和 previous key，用于缩小扫描范围，而不是承担候选 entry 直返职责。
### blockSeekIndex 统计语义边界确认

- 尝试把 `blockSeekIndexHits/Misses` 改为根据 `Block.seek` 是否返回候选 entry 来区分 hit/miss；该实现能让“有索引但没有候选 entry”的极端情况进入 miss，但会在 `Table.seekWithBlockSeekIndex` 热路径上增加结果分支。
- 50k `read_optimized` 验证中，`readrandom_hit` 短跑回落到 130,404 ops/s；同时 miss/mixed 场景的逻辑未命中已经由 `candidateEntryMisses`、`bloomFalsePositives` 与 Bloom skip 统计表达，`Block.seek` 对 false positive 通常仍会返回第一个 `>= target` 的候选 entry，因此 `blockSeekIndexMisses` 并不等价于业务 key miss。
- 当前版本回收该热路径分支，继续保持 `blockSeekIndexHits` 表示“使用了 Block 打开期 seek index 的次数”，`blockSeekIndexFallbacks` 表示没有 seek index 时的回退次数，`blockSeekIndexMisses` 仅保留为 `SeekResult` 状态预留，不作为逻辑未命中指标。发布和性能验收应结合 `candidateEntryHits/Misses`、`filterSkips` 与 `bloomFalsePositives` 判断业务命中/未命中。
### Block.readKey shared-key null guard 实验回收

- 尝试将 `Block.readKey` shared-prefix 路径中的 Guava `checkState(previousKey != null, ...)` 替换为手写 `if (previousKey == null) throw new IllegalStateException(...)`，希望减少热路径通用前置检查工具成本；该实验不改变文件格式、anchor、key 重建算法或异常保护语义。
- 50k `read_optimized` 短基准显示 `readrandom_hit` 回退到 128,680 ops/s，`readrandom_sameblock` 为 274,510 ops/s，`scan` 为 1,184,321 ops/s，均弱于当前恢复基线。
- 结论：该 guard 形态不是当前瓶颈，原 `checkState` 调用形态更可能被 JIT 良好处理。当前版本恢复 `checkState`，继续把有效优化集中在已验证的 direct-copy key 重建，而不是继续手写微分支。
### restart key 精确命中起点实验回收

- 尝试将 `Block.restartIndexBefore` 的二分条件从 `restartKey < target` 改为 `restartKey <= target`，使 target 精确等于 restart key 时从该 restart 区间开始扫描，而不是从前一个 restart 区间开始。该实验不改变文件格式、不增加索引条目，也仍然只依赖 restart/anchor 缩小扫描范围。
- 50k `read_optimized` 短基准显示 `readrandom_hit` 为 164,277 ops/s，低于当前恢复基线和历史 174,459 ops/s 基线；`readrandom_sameblock` 为 285,996 ops/s，`readrandom_burst` 为 309,092 ops/s，`multiget_mixed` 为 309,195 ops/s，也没有整体胜出。
- 结论：精确 restart key 起点在当前 workload 下触发比例或调用形态不足以抵消分支/比较形态变化带来的成本。当前版本恢复原 `restartKey < target` 策略，继续依赖稀疏 anchor 缩小 restart 区间内扫描范围。
### Block sparse anchor interval=3/5 实验回收与当前边界

为确认当前每 4 条 entry 一个稀疏 anchor 是否只是局部偶然最优，本轮继续补测 `SEEK_ANCHOR_INTERVAL=3` 与 `SEEK_ANCHOR_INTERVAL=5`。两个实验都只影响 `Block` 打开阶段构建的轻量内存索引，仍然只在 restart 区间内构建稀疏 anchor，不改变 SST 文件格式，也不引入 full-entry index。

50k `read_optimized` 结果如下，基线采用当前恢复后的 interval=4 结果：`readrandom_hit=179,215.195 ops/s`，同机 RocksDB JNI `readrandom_hit=369,105.296 ops/s`，比例为 `48.55%`。

| 配置 | readrandom_hit | readrandom_sameblock | readrandom_burst | multiget_mixed | scan | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| interval=4 当前稳定基线 | 179,215.195 | 369,652.697 | 329,674.387 | 335,029.251 | 2,412,568.517 | 当前保留 |
| interval=3 | 159,506.271 | 395,670.416 | 366,295.268 | 280,144.532 | 1,680,175.276 | 主指标、MultiGet、scan 回退，已回收 |
| interval=5 | 151,884.968 | 400,606.679 | 393,660.182 | 309,080.221 | 1,835,044.206 | 主指标、MultiGet、scan 回退，已回收 |

证据路径：
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k/ldb-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison-current-50k/comparison.csv`
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k-anchor3/ldb-db-bench-summary.json`
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k-anchor5/ldb-db-bench-summary.json`

结论：当前 open-time 稀疏 entry anchor 的简单间隔调参已经触达边界。`interval=4` 是当前同时兼顾 `readrandom_hit`、局部性读、MultiGet 和 scan 的稳定折中；继续试探 `2/3/5/8` 都没有形成可保留的整体收益。若要把 `readrandom_hit` 从当前 `48.55%` 推过 50%，下一阶段应转向文件格式层面持久化“稀疏 entry anchor + previousKey/等价恢复信息”，或者寻找新的 data-block key 解码/比较热点，而不是继续调整当前内存 anchor 密度。
### 下一版文件格式索引设计入口

基于当前 `readrandom_hit` 默认路径未稳定达到 50%、`interval=2/3/5/8` 均不可保留，以及 v3 restart-anchor local index 单点强制接入回退的证据，下一阶段文件格式优化不再复用 v3 `block.local_index.v1` 的 restart-anchor 语义，而是单独设计稀疏 entry-anchor 索引。设计入口见 `docs/storage-format-0.12-entry-anchor-index-design.md`，英文副本见 `docs/storage-format-0.12-entry-anchor-index-design.en.md`。
## 本轮补充：默认 MultiGet 同块批量 seekAll 接线

本轮在不修改文件格式的前提下，把 `Table.get(List<Slice>)` 中已经存在但未接入的 `seekDenseBlock` 批量路径接到默认读路径：当同一 data block 内 lookup 数达到阈值，且 SST 未声明 opt-in `block_local_index` 时，批量读按 key 排序后调用 `Block.seekAll`，避免为密集同块 MultiGet 重复执行每个 key 的 restart/anchor seek。若表声明了 v3 block-local index，则继续走原显式 opt-in local-index 分支，避免改变文件格式策略。

验证结果：`compileJava` 通过；`LdbCoreBehaviorTest`、`LdbObservabilityTest`、`TablePropertiesTest` 通过。50k read-optimized 快速门禁中，`multiget_sameblock` 达到 `323,423.424 ops/s`，`multiget_mixed` 达到 `330,660.713 ops/s`，`scan` 达到 `2,166,809.676 ops/s`。单独复跑 `readrandom_hit` 为 `195,440.834 ops/s`，统计仍显示 `blockSeekIndexHits=50000`、`mayContainRequests=13`，说明本轮 MultiGet 接线没有改变单点读的 Block seek-index 热路径。

边界：本轮没有把 `readrandom_hit` 严格证明到 RocksDB JNI 50% 以上；它是对 MultiGet 周边回退风险的补强。下一步如果继续冲 P0，应优先做单点 hit-path 中剩余的 table/block cache 与 InternalKey 解码成本，而不是把 v4 entry-anchor index 强行放回默认热路径。
## 计划补充：命中后 InternalKey 轻量解码

下一步优化不改变 Block/SST 格式，也不引入 full-entry index。当前 table direct get 返回候选 entry 后，`Level` / `Level0` 会为每次命中构造完整 `InternalKey` 对象，用于比较 user key、读取 sequence 和 value type。该成本位于 `readrandom_hit` 和 MultiGet 命中后的共同路径上。

本轮计划在 `InternalKey` 中补充 encoded-key 轻量读取方法：直接基于 `Slice` 判断 user key 是否匹配，并读取 trailer 中的 sequence/type；`Level` / `Level0` 复用这些方法，避免热路径创建完整 `InternalKey`。该优化只改变内存解码方式，不改变可见语义、文件格式或比较器顺序。
### 轻量 InternalKey 解码实验结论：不保留

本轮曾尝试让 `Level` / `Level0` 在候选 entry 命中后直接从 encoded internal key 中比较 user key 并读取 sequence/type，避免构造完整 `InternalKey`。该方案编译与目标读路径测试通过，单独 `readrandom_hit` 曾达到 `216,060.839 ops/s`，但周边验证不稳定，`multiget_mixed` 多次低于前一轮安全区间。按照本专项“同时看 sameblock/burst/scan/MultiGet 是否回退”的约束，该实验代码已撤回，不进入默认热路径。

保留结论：命中后对象分配仍可能是后续方向，但必须用更细粒度 profiler 或稳定多轮基准证明不会压低 MultiGet，再考虑重新设计；当前版本不保留该改动。
## 计划补充：MultiGet 复用 table-open-time point-get index

当前单点 `Table.get(Slice)` 已经在 table 打开时把 index block 解成 `pointGetIndexLimitKeys` / `pointGetIndexBlockHandles` 数组，并通过二分定位 data block，避免每次点查构造 index iterator 或重复解码 index entry。`Table.get(List<Slice>)` 批量路径仍对每个 key 调用 `indexBlock.seek`，再解析 block handle。

本轮实验把批量路径的 data block 定位改为复用 `findPointGetBlockHandle`，让 MultiGet 与单点读共享同一 table-open-time 轻量索引。该实验不改变 Block/SST 格式、不增加 full-entry index，也不改变 block 内 seek anchor 策略。验收仍以 `readrandom_hit` 为主，并观察 `readrandom_sameblock`、`readrandom_burst`、`scan`、`multiget_mixed` 和 `multiget_sameblock`；若 MultiGet 或 sameblock 出现稳定回退则撤回。
### MultiGet 复用 point-get index 实验结论：不保留

本轮尝试让 `Table.get(List<Slice>)` 使用 `findPointGetBlockHandle` 复用 table-open-time point-get index 数组，替代每个 key 的 `indexBlock.seek`。该方案编译与目标读路径测试通过，统计显示 `multiget_mixed` 中 `tableIndexSeeks` 从 `25207` 降到 `0`，并转为 `tableIndexCacheHits=25207`。

但性能没有收益：50k `multiget_mixed` 为 `242,850.954 ops/s`，低于上一轮安全区间。说明当前 mixed 批量场景的瓶颈不在 index block seek 本身，或者数组二分/last-index 复用没有改善整体局部性。该实验代码已撤回，默认批量路径继续使用原 `indexBlock.seek` 分组策略。
## 计划补充：MultiGet data-block 分组观测

前两轮实验说明，盲目优化命中后解码或 batch index 定位都不能稳定提升 `multiget_mixed`。下一步先补观测字段，而不是继续改热路径：在 `Table.get(List<Slice>)` 中统计每次 batch direct get 形成的 data-block group 数、参与 batch 的 key 数、达到 dense 阈值的 group/key 数，以及实际调用 `seekDenseBlock` 的次数。

该改动只增加 `readStats` 输出，不改变读语义、不改变 Block/SST 格式、不增加 full-entry index。验收目标是让 `multiget_mixed` 结果能解释：低值来自几乎每个 key 都落在不同 data block，还是来自 dense group 未触发、block cache/open 成本或其它路径。后续优化必须基于这些字段选择方向。
### MultiGet data-block 分组观测结论

新增观测字段已经能解释 `multiget_mixed` 与 `multiget_sameblock` 的差异。50k `multiget_mixed` 中，`directGetBatchKeys=25207`，但 `tableBatchDataBlockGroups=24917`，平均每个 data-block group 约 `1.01` 个 key；`tableBatchDenseBlockGroups=0`、`tableBatchSeekAllCount=0`，说明 mixed 基本是稀疏随机点查集合，当前同块 `seekAll` 优化不会触发，低值不能归因于 dense batch 路径。

50k `multiget_sameblock` 中，`directGetBatchKeys=50000`，`tableBatchDataBlockGroups=2158`，`tableBatchDenseBlockGroups=1857`，`tableBatchDenseBlockKeys=48805`，`tableBatchSeekAllCount=1857`。这证明同块批量优化确实覆盖密集 batch 场景，且 block seek-index 统计中 `blockSeekIndexHits=1195` 只对应未进入 dense `seekAll` 的稀疏尾部 key。

结论：下一步若继续提升 `multiget_mixed`，不要再围绕同块 `seekAll` 或 batch index 定位猜测；它本质上更接近大量随机 point get。优化重点应回到单点 hit-path 的 data block 打开/cache 命中、candidate entry 解码和 benchmark 稳定性。
## 计划补充：direct-read data block cache 观测拆分

当前 `readStats` 中的 `tableDataBlockOpens` 表示 Table 层在 point get/MultiGet 路径上请求 data block，但它没有区分本地 point-get block cache miss 后，是命中全局 `BlockCache`，还是最终读取并解码 SST block。因此 `readrandom_hit` 中的 block 打开成本还无法进一步归因。

本轮只补观测字段：在 direct-read 打开 data block 时记录全局 block cache hit/miss，以及最终真实 read/decode 次数。该改动不改变缓存策略、不改变 Block/SST 格式、不增加 full-entry index。目标是判断下一步应优化 point-get 本地 cache、全局 block cache，还是 block decode/open 成本。
### direct-read data block cache 观测结论

新增 direct-read cache 字段把 `readrandom_hit` 的 block 打开成本拆清楚了。50k `readrandom_hit` 中，`tableDataBlockOpens=11085`、`tableDirectReadBlockCacheHits=9696`、`tableDirectReadBlockCacheMisses=1389`、`tableDirectReadBlockReads=1389`。这说明 Table 本地 point-get block cache miss 之后，大多数请求并没有重新读盘或解码 block，而是命中了全局 `BlockCache`；真实 read/decode 只有约 `2.78%` 的 point get。

50k `multiget_mixed` 中，`tableDataBlockOpens=24917`、`tableDirectReadBlockCacheHits=23528`、`tableDirectReadBlockCacheMisses=1389`、`tableDirectReadBlockReads=1389`，与 mixed 的稀疏 data-block 分组结论一致：它更像大量随机 point get，主要成本是大量不同 data block 的 direct-read 路径，而不是 dense `seekAll`。

结论：下一步最有希望的优化不是继续减少真实 block read，也不是文件格式索引；真实 read/decode 已经较少。更值得尝试的是降低 Table 本地 direct-map point-cache 冲突带来的全局 `BlockCache` 同步查找成本，例如小规模 set-associative point cache 或更稳的局部 cache 命中策略，但必须用 `readrandom_hit`、sameblock/burst、scan 和 MultiGet 同时验证。
## 计划补充：Table 本地 point-get block cache 2-way 实验

基于 direct-read cache 观测，`readrandom_hit` 中 Table 本地 point-get block cache miss 后多数命中了全局 `BlockCache`，说明真实 read/decode 已经较少，剩余成本可能来自本地 direct-map cache 冲突和全局 cache 同步查找。本轮实验把 Table 本地 point-get block cache 从每个 slot 一个条目扩展为 primary/secondary 两个最近条目：primary 命中直接返回；secondary 命中提升为 primary；miss 时新 block 进入 primary，旧 primary 下沉为 secondary。

该实验只改变 Table 进程内缓存策略，不改变 Block/SST 格式、不增加 full-entry index。验收仍以 `readrandom_hit` 为主，同时观察 sameblock、burst、scan、`multiget_mixed` 和 direct-read cache hit/read 字段；若局部性变差或 MultiGet 回退则撤回。
### Table 本地 point-get block cache 2-way 实验结论：不保留

2-way 本地 point-get block cache 实验证明了观测方向是正确的：50k `readrandom_hit` 单独复跑达到 `222,654.270 ops/s`，且 `tableDataBlockOpens` 从 direct-map 观测中的 `11085` 降到 `2110`，全局 `BlockCache` hit 从 `9696` 降到 `721`。但是 sameblock 周边不稳定并持续偏低：两次单独 `readrandom_sameblock` 分别为 `313,327.184 ops/s` 和 `302,395.456 ops/s`，低于前一轮安全区间。

按照本专项必须同时观察 sameblock/burst/scan/MultiGet 的约束，2-way cache 逻辑已撤回，不进入默认路径。保留结论是：本地 point-cache 冲突确实存在，但简单 2-way MRU 会改变 sameblock 局部性，后续若继续做，应考虑只针对随机稀疏模式的自适应策略，或先增加更细的 local-cache collision/promote 统计。
## 计划补充：Table 本地 point-cache 命中/冲突观测

2-way 本地 cache 实验证明 direct-map slot 冲突会导致大量请求落回全局 `BlockCache`，但简单 primary/secondary MRU 会压低 `readrandom_sameblock`。下一步先补更细的观测字段，而不是继续改变策略：区分 `lastPointGetBlock` 命中、direct-map slot 命中、slot miss，以及 slot 中已有其它 block 时发生的 collision。

该改动只扩展 `readStats`，不改变本地 cache 替换策略、不改变 Block/SST 格式、不增加 full-entry index。目标是判断冲突主要来自随机 hit 的大范围跳转，还是 sameblock/burst 中的局部模式；后续自适应策略必须基于这些字段限制启用范围。
### Table 本地 point-cache 命中/冲突观测结论

新增细分字段解释了为什么固定 2-way cache 会伤 sameblock。50k `readrandom_hit` 中，`tablePointGetLastBlockHits=33`、`tablePointGetSlotHits=38882`、`tablePointGetSlotMisses=11085`、`tablePointGetSlotCollisions=9974`。随机 hit 几乎没有 last-block 连续命中，slot collision 很高，因此它会大量落回全局 `BlockCache`。

50k `readrandom_sameblock` 中，`tablePointGetLastBlockHits=47839`、`tablePointGetSlotHits=916`、`tablePointGetSlotMisses=1245`、`tablePointGetSlotCollisions=313`。sameblock 主要依赖 `lastPointGetBlock`，slot collision 很少。50k `readrandom_burst` 中，`tablePointGetLastBlockHits=14153`、`tablePointGetSlotHits=34613`、`tablePointGetSlotMisses=1234`、`tablePointGetSlotCollisions=309`，说明 burst 介于两者之间，但也不是高 collision 模式。

结论：如果继续尝试本地 cache 优化，不能用固定 2-way 替换 direct-map；应只在随机 hit 模式启用自适应 secondary cache。可行触发条件应类似：last-hit 比例极低且 slot collision 比例高；sameblock/burst 因 last-hit 或 slot-hit 已高，应保持当前 direct-map + last-block 快路径。
## 计划补充：随机冲突模式自适应 secondary point-cache

基于 point-cache 命中/冲突观测，随机 `readrandom_hit` 的 `lastPointGetBlock` 命中极低且 slot collision 很高，而 sameblock/burst 的 last-hit 或 slot-hit 已经足够高。下一步实验不再固定启用 2-way cache，而是在 Table 内部通过观测字段自适应启用 secondary cache：只有当 slot miss 样本足够多、slot collision 比例高、last-block 命中比例极低时，才检查 secondary slot 并在 miss 后维护 secondary 条目。

该策略只服务随机冲突模式，sameblock/burst 默认保持原 direct-map + last-block 快路径。改动不改变 Block/SST 格式、不增加 full-entry index。验收仍以 `readrandom_hit` 为主，同时要求 `readrandom_sameblock`、`readrandom_burst`、`scan`、`multiget_mixed` 不出现稳定回退。
### 自适应 secondary point-cache 实验结论：不保留

本轮按随机冲突模式实现了自适应 secondary point-cache：只有在 slot miss 样本足够、collision 比例高且 last-block 命中比例极低时启用。统计上它确实减少了随机 hit 的本地 cache miss：50k `readrandom_hit` 中 `tableDataBlockOpens` 从 direct-map 观测的 `11085` 降到 `3127`，`tableDirectReadBlockCacheHits` 从 `9696` 降到 `1738`。但是主指标没有稳定收益，`secondary128` 版本的 `readrandom_hit` 只有 `154,794.976 ops/s`；保守阈值版本单独复跑也只有 `195,141.218 ops/s`。因此该策略虽然减少了全局 cache 查找次数，但额外分支、数组访问和替换维护成本抵消了收益。

按照本专项以 `readrandom_hit` 为主验收指标的约束，自适应 secondary cache 代码已撤回，不进入默认路径。保留结论：本地 slot collision 是事实，但解决它不能只靠 Java 层二级数组；下一步若继续，需要更低成本的 hash/slot 策略或更直接减少 per-get 操作，而不是增加 cache 层级。
## 计划补充：point-get 本地 block cache 按 index position 分槽

前面的观测证明，随机 `readrandom_hit` 的 direct-map point-cache slot collision 很高，而增加 secondary cache 层级的分支和替换成本又会抵消收益。下一步实验不增加 cache 层级，只改变单点 `Table.get(Slice)` 选择本地 block-cache slot 的依据：复用 table-open-time `pointGetIndex` 的 data-block index position 作为 slot 输入，而不是只用 block offset/dataSize hash。

该实验不改变 Block/SST 格式、不增加 full-entry index，也不影响 batch `Table.get(List<Slice>)` 的分组策略。目标是降低随机 hit 的 slot collision 和全局 `BlockCache` 回退次数，同时保持 sameblock/burst 仍走 `lastPointGetBlock` 快路径。验收仍以 `readrandom_hit` 为主，并观察 sameblock、burst、scan、MultiGet 是否回退。
### point-cache 按 index position 分槽实验结论：不保留

本轮尝试让单点 `Table.get(Slice)` 使用 table-open-time index position 作为本地 point-cache slot 输入。统计上 collision 被完全消除：50k `readrandom_hit`、`readrandom_sameblock`、`readrandom_burst` 的 `tablePointGetSlotCollisions` 都为 `0`，真实 block read 次数也分别保持在约 `1389`、`1103`、`1095`。

但性能全面回退：`readrandom_hit=146,626.491 ops/s`、`readrandom_sameblock=217,524.089 ops/s`、`readrandom_burst=196,089.197 ops/s`。这说明消除 collision 本身不足以提升性能，index-position slot 可能破坏了原 offset/dataSize hash 下的访问局部性或引入了额外路径成本。该实验代码已撤回，默认仍使用原 blockHandle hash slot。
## 计划补充：blockSeekIndex miss 统计口径补齐

当前 `blockSeekIndexHits/Fallbacks` 已能证明默认 data block 走了打开时构建的轻量 seek index，但 `blockSeekIndexMisses` 语义不够完整。下一步只调整统计口径：当 data block 有 seek index 时，执行一次 `Block.seek`；若返回候选 entry，则记 `blockSeekIndexHits`；若返回 `null`，则记 `blockSeekIndexMisses`；当 data block 没有 seek index 时仍记 `blockSeekIndexFallbacks`。

该改动不改变 seek 行为，不增加额外 block seek，不改变 Block/SST 格式，也不引入 full-entry index。目标是让 hit/miss/fallback 三个字段都能作为发布前读路径观测证据。
### blockSeekIndex miss 统计验证结论

调整后 `blockSeekIndexHits/Misses/Fallbacks` 的口径更清晰：`Hits` 表示 data block 有打开时构建的 seek index，且 `Block.seek` 返回了候选 entry；`Misses` 表示有 seek index 但 block 内没有任何候选 entry；`Fallbacks` 表示 data block 没有 seek index。50k `readrandom_hit` 结果为 `blockSeekIndexHits=50000`、`blockSeekIndexMisses=0`、`blockSeekIndexFallbacks=0`，证明默认命中路径全部使用 block seek index。

50k `readrandom_miss` 结果为 `blockSeekIndexHits=48561`、`blockSeekIndexMisses=0`、`blockSeekIndexFallbacks=0`，同时 `candidateEntryMisses=48561`。这说明业务 miss 并不等同于 block seek-index miss：block 内 seek 仍会返回第一个大于等于目标 internal key 的候选 entry，之后由上层 user-key/sequence/type 语义判断为 candidate miss。后续报告应避免把 `blockSeekIndexMisses` 解读为 readrandom 业务 miss。
## 当前保留状态 50k 小门禁基线

本轮在当前默认保留路径上运行 50k `read_optimized` 小门禁，覆盖 `readrandom_hit`、`readrandom_sameblock`、`readrandom_burst`、`multiget_mixed`、`multiget_sameblock` 和 `scan`。该轮不修改代码，用于确认 Block 打开时构建的轻量 seek index 是否仍在默认热路径生效，并检查 sameblock/burst/scan/MultiGet 是否出现明显回退。

| 场景 | ops/s | 关键统计 |
| --- | ---: | --- |
| `readrandom_hit` | 173,332.155 | `blockSeekIndexHits=50000`, `blockSeekIndexMisses=0`, `blockSeekIndexFallbacks=0`, `tableDataBlockOpens=11085`, `tablePointGetSlotCollisions=9974` |
| `readrandom_sameblock` | 347,573.416 | `blockSeekIndexHits=50000`, `tablePointGetLastBlockHits=47839`, `tableDataBlockOpens=1245` |
| `readrandom_burst` | 333,896.282 | `blockSeekIndexHits=50000`, `tablePointGetLastBlockHits=14153`, `tablePointGetSlotHits=34613` |
| `multiget_mixed` | 353,465.053 | `directGetBatchKeys=25207`, `tableBatchDataBlockGroups=24917`, `tableBatchDenseBlockGroups=0`, `blockSeekIndexHits=25207` |
| `multiget_sameblock` | 413,432.248 | `tableBatchDenseBlockGroups=1857`, `tableBatchDenseBlockKeys=48805`, `tableBatchSeekAllCount=1857`, `blockSeekIndexHits=1195` |
| `scan` | 1,265,646.556 | 扫描路径不触发 point-get/block-seek 统计，`blockSeekIndexHits=0` 符合预期 |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-retained-gate-50k/ldb-db-bench-summary.json`。

结论：功能目标已经闭环到当前默认路径：命中型点查和稀疏/密集 MultiGet 均没有 seek-index fallback，说明 Block 打开时构建的轻量 restart/anchor seek index 已参与默认读取路径；dense same-block MultiGet 也继续通过 `seekAll` 批量路径触发。性能目标仍未闭环：本轮 `readrandom_hit=173,332.155 ops/s` 低于此前若干 50k 样本，且尚未配对同轮 RocksDB JNI 对照，因此不能声明 P0 的 “至少 50%” 已经完成。下一步应先跑同配置 RocksDB JNI 对照或重复门禁确认方差，再决定继续优化 point-hit 成本还是进入发布收口。
## 同轮 RocksDB JNI 50k 对照结论

本轮使用 `scripts/run-rocksdbjni-comparison.ps1` 对当前 LDB summary 做同配置 RocksDB JNI 10.10.1 对照。RocksDB JNI runner 当前不支持 `multiget_sameblock` 和 `scan`，因此这两个场景只作为 LDB 侧回退门禁记录；双方可比的场景为 `readrandom_hit`、`readrandom_sameblock`、`readrandom_burst` 和 `multiget_mixed`。

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 结论 |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 173,332.155 | 516,413.693 | 0.3356 | 主验收未达 50%，继续作为下一步优化焦点 |
| `readrandom_sameblock` | 347,573.416 | 634,482.339 | 0.5478 | 局部性点查超过 50%，当前 recent block/index 路径可保留 |
| `readrandom_burst` | 333,896.282 | 596,462.975 | 0.5598 | burst 局部性超过 50%，未观察到该侧明显回退 |
| `multiget_mixed` | 353,465.053 | 464,217.209 | 0.7614 | 稀疏 mixed MultiGet 高于 50%，当前 batch direct get 和 Bloom 路径可保留 |

证据文件：`build/reports/rocksdbjni-comparison-current-retained-gate-50k-supported4/comparison.csv`。

结论：当前版本的 Block 轻量 seek index、seek 统计和 MultiGet/dense same-block 回退观察已经完成，但 P0 主性能目标仍未完成，因为纯随机 `readrandom_hit` 只有 RocksDB JNI 的 `33.56%`。下一阶段最有价值的工作应继续聚焦单点随机 hit-path 的每次 `Block.seek`/候选 key 解码/上层 InternalKey 判定成本；不要继续优先改 MultiGet 或 dense same-block 路径，因为它们本轮已经超过 50% 或属于 LDB 专用门禁。
## 计划补充：仅限单点 get 的轻量 InternalKey 判定实验

同轮 RocksDB JNI 对照显示，`readrandom_sameblock`、`readrandom_burst` 和 `multiget_mixed` 已经超过 50%，但纯随机 `readrandom_hit` 仍只有 `33.56%`。下一步不再优先改 MultiGet 或 dense same-block 路径，而是聚焦单点 `Level.get` / `Level0.get` 在 table candidate 命中后的每次 `InternalKey` 构造与 user-key/type/sequence 判定成本。

本轮实验只在单 key get 热路径中增加 encoded internal key 的轻量读取工具：直接比较 encoded key 的 user-key 前缀，并从 trailer 读取 sequence/type；`get(List)` 的 MultiGet 后处理暂不接入该工具，避免重现上一轮轻量解码实验中 mixed MultiGet 不稳定的问题。该实验不改变 Block/SST 格式、不改变 comparator 顺序、不增加 full-entry index，也不改变 range delete、snapshot、deletion/value 语义。验收仍以 `readrandom_hit` 为主，同时复跑 sameblock、burst、scan 和 MultiGet，若主指标无稳定收益或周边回退则撤回。
### 仅限单点 get 的轻量 InternalKey 判定实验结论：不保留

本轮将轻量 encoded internal-key 判定限制在单 key `Level.get` / `Level0.get`，MultiGet 后处理保持原实现。编译通过，`LdbCoreBehaviorTest`、`LdbObservabilityTest` 和 `TablePropertiesTest` 通过，但 50k `read_optimized` 小门禁没有收益：`readrandom_hit=155,714.149 ops/s`，低于当前保留基线 `173,332.155 ops/s`；`multiget_mixed=252,269.161 ops/s` 也低于上一轮安全区间。sameblock、burst、sameblock MultiGet 和 scan 没有形成足以抵消主指标下降的稳定收益。

因此该实验代码撤回，不进入默认路径。保留结论是：命中后 `InternalKey` 构造可能仍是成本点，但用 Java 层 raw-array user-key 比较和 trailer helper 替换完整对象构造，并不能在当前 JIT/数据分布下稳定提升主指标；下一步不应继续沿这个实现细节微调，而应回到 `Block.seek` 扫描长度、候选 entry 解码次数或更直接的 block-local 定位成本。
## 计划补充：Block.seek restart 区间扫描边界实验

当前 `Block.seek` 已经通过 restart key cache 和稀疏 anchor 将起点缩小到某个 restart 区间内，但扫描循环仍以 data block 末尾作为最终边界。按照 restart 二分语义，若目标 key 落在当前 restart key 与下一个 restart key 之间，则候选 entry 要么在当前 restart 区间内，要么就是下一个 restart 的首条 entry；不需要继续扫描后续 restart 区间。

本轮实验只收窄单次 `Block.seek` 的线性扫描边界：从 restart/anchor 起点扫描到当前 restart 区间末尾；若仍没有候选且存在下一个 restart，则最多读取下一个 restart 的首条 entry 作为候选。该实验不改变 restart/anchor 索引密度，不引入 full-entry index，不改变 SST 文件格式，也不影响 `seekAll` 的 dense batch 策略。验收仍以 `readrandom_hit` 为主，并观察 sameblock、burst、scan、MultiGet；若主指标无收益或周边回退，则撤回。
### Block.seek restart 区间扫描边界实验结果：保留候选，主目标未完成

本轮实现后，`Block.seek` 从选中的 restart/anchor 起点只扫描到当前 restart 区间末尾；若仍未找到候选且存在下一个 restart，则最多读取下一个 restart 的首条 entry。该改动不增加索引密度、不引入 full-entry index，也不改变 SST 文件格式。`compileJava` 通过，`LdbCoreBehaviorTest`、`LdbObservabilityTest` 和 `TablePropertiesTest` 通过。

50k `read_optimized` LDB 小门禁结果：

| 场景 | ops/s | 关键观察 |
| --- | ---: | --- |
| `readrandom_hit` | 184,356.673 | 高于当前保留基线 `173,332.155`；`blockSeekIndexHits=50000`, `Fallbacks=0` |
| `readrandom_sameblock` | 456,277.234 | 局部性点查保持较高水平；`tablePointGetLastBlockHits=47839` |
| `readrandom_burst` | 386,821.158 | burst 局部性未观察到明显回退 |
| `multiget_mixed` | 370,280.406 | 稀疏 mixed MultiGet 高于上一轮保留基线；`tableBatchDenseBlockGroups=0` 符合预期 |
| `multiget_sameblock` | 386,844.502 | dense batch 仍触发 `tableBatchSeekAllCount=1857`，但低于上一轮 `413,432.248`，需继续作为回退观察项 |
| `scan` | 1,730,636.770 | scan 路径不依赖该 seek 优化，未观察到负面信号 |

同轮 RocksDB JNI 10.10.1 支持项对照：

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI |
| --- | ---: | ---: | ---: |
| `readrandom_hit` | 184,356.673 | 490,988.399 | 0.3755 |
| `readrandom_sameblock` | 456,277.234 | 603,847.961 | 0.7556 |
| `readrandom_burst` | 386,821.158 | 561,305.192 | 0.6891 |
| `multiget_mixed` | 370,280.406 | 472,288.474 | 0.7840 |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-regionlimit-50k/ldb-db-bench-summary.json` 和 `build/reports/rocksdbjni-comparison-blockseek-regionlimit-50k-supported4/comparison.csv`。

结论：该改动是当前阶段的可保留候选，因为它提升了主指标 `readrandom_hit`，并且 sameblock、burst、mixed MultiGet 和 scan 均保持健康；但 P0 主目标仍未完成，`readrandom_hit` 只有 RocksDB JNI 的 `37.55%`。后续仍需继续减少每次随机 point get 在 block 内的候选 entry 解码和比较成本，同时密切观察 `multiget_sameblock` 是否形成稳定回退。
## 计划补充：Block.seek anchor 子区间扫描边界实验

上一轮 restart 区间扫描边界实验说明，限制 `Block.seek` 的线性扫描终点可以减少不必要的候选 entry 解码。当前 `Block.seek` 在命中某个稀疏 anchor 后，仍会从该 anchor 扫描到当前 restart 区间末尾；但按照 anchor 的 key 顺序，如果目标 key 位于当前 anchor 与下一个 anchor 之间，则候选 entry 要么在当前 anchor 子区间内，要么就是下一个 anchor 对应的首条 entry。

本轮实验不增加 anchor 密度，也不引入 full-entry index，只复用已构建的 `seekAnchors`：当 seek 从某个 anchor 开始时，将扫描 limit 收窄到下一个 anchor 的 offset；若当前子区间没有候选，则最多读取下一个 anchor 的首条 entry 作为候选。没有下一个 anchor 时，仍使用上一轮的 restart 区间边界。验收继续以 `readrandom_hit` 为主，并观察 sameblock、burst、scan、MultiGet；若主指标无收益或周边回退，则撤回。
### Block.seek anchor 子区间扫描边界实验结论：不保留

本轮在 restart 区间扫描边界的基础上，继续尝试把 anchor 命中后的扫描 limit 收窄到下一个 anchor offset，并在子区间未命中时只读取下一个 anchor 首条 entry。编译和目标测试通过，但 50k `read_optimized` 小门禁没有优于上一轮 restart 区间版本：`readrandom_hit=180,902.748 ops/s`，低于上一轮 `184,356.673 ops/s`；`readrandom_sameblock=369,043.727 ops/s`、`readrandom_burst=342,644.351 ops/s`、`multiget_mixed=301,320.991 ops/s` 也低于上一轮安全区间。`multiget_sameblock=419,345.938 ops/s` 虽然较高，但不足以抵消主指标和多个周边场景的下降。

因此 anchor 子区间 limit 代码撤回，不进入默认路径。保留结论是：对当前稀疏 anchor 间隔而言，进一步增加“下一个 anchor 候选读取”分支和 selection 对象成本，可能抵消减少少量线性扫描的收益。默认路径保留上一轮 restart 区间边界优化，而不继续细分 anchor 子区间。
## 计划补充：Block.seek 实际扫描 entry 数观测

当前已经确认 restart 区间扫描边界优化是可保留候选，但 `readrandom_hit` 仍未达到 RocksDB JNI 50%。继续优化前，需要先确认剩余成本是否真的来自 block 内线性扫描，而不是 comparator、prefix key 重建、Table 本地 cache miss 或上层 candidate 判定。

本轮只增加观测字段，不改变 seek 决策：`Block.seekWithIndex` 返回本次 seek 实际解码的 entry 数，`Table` 汇总为 `blockSeekEntryScans` 和 `blockSeekMaxEntryScans`，并通过 `ldb.sstReadStats` 输出。该改动不改变 Block/SST 格式、不增加 full-entry index、不改变 anchor 密度，也不改变 `readrandom_hit` 或 MultiGet 语义。验收目标是解释当前 `readrandom_hit` 中平均每次 block seek 的扫描长度，指导下一步是否继续压扫描，还是转向 key 重建/comparator/cache 成本。
### Block.seek 实际扫描 entry 数观测结果

本轮新增 `blockSeekEntryScans` 和 `blockSeekMaxEntryScans` 观测字段后，50k `read_optimized` 小门禁结果显示：

| 场景 | ops/s | block seek 次数 | entry 扫描总数 | 平均每次 seek 扫描 | 最大扫描 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 190,046.504 | 50,000 | 169,512 | 3.39 | 5 |
| `readrandom_sameblock` | 355,809.588 | 50,000 | 169,404 | 3.39 | 5 |
| `readrandom_burst` | 385,418.240 | 50,000 | 169,236 | 3.38 | 5 |
| `multiget_mixed` | 409,824.308 | 25,207 | 85,725 | 3.40 | 5 |
| `multiget_sameblock` | 458,997.733 | 1,195 | 3,561 | 2.98 | 5 |
| `scan` | 1,520,792.272 | 0 | 0 | 0 | 0 |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-scanstats-50k/ldb-db-bench-summary.json`。

结论：当前 open-time restart/anchor seek index 和 restart 区间扫描边界已经把单次 block seek 的线性扫描压到很短，`readrandom_hit` 平均只解码约 `3.39` 条 entry，最大也只有 `5` 条。继续增加更细 anchor 分支、full-entry index 或更复杂的子区间定位，预期收益很有限，且前面的 anchor 子区间实验已经显示额外分支成本可能抵消收益。下一步更有价值的方向应从“减少扫描条数”转向“降低每条候选 entry 的固定成本”：prefix key 重建、comparator 调用、`SliceInput`/`BlockEntry` 对象创建，或 Table 本地 cache / 全局 BlockCache 查询路径。
## 计划补充：Block.seek offset-reader 固定成本实验

`blockSeekEntryScans` 证明当前平均每次 seek 只解码约 3.39 条 entry，继续减少扫描条数的空间有限。下一步尝试降低每条 entry 的固定成本：`Block.seek` 当前每次都会创建 `SliceInput`，并通过通用 input 方法读取 varint、key 和 value。本轮实验只把 `Block.seek` 热路径改为基于整数 offset 的轻量解码循环，直接从 `Slice` 按 offset 读取 varint、key 和 value，避免每次 point get 构造 `SliceInput`。

该实验不改变 restart/anchor 选择，不改变扫描边界，不增加 full-entry index，不改变 Block/SST 格式，也不影响 `BlockIterator`、`seekAll` 和离线 v4 entry-anchor 诊断路径。验收仍以 `readrandom_hit` 为主，同时观察 sameblock、burst、scan、MultiGet；若主指标无收益或周边回退，则撤回。
### Block.seek offset-reader 实验结论：不保留，保留扫描统计

本轮尝试将 `Block.seek` 热路径从 `SliceInput` 改为基于整数 offset 的直接解码循环。编译和目标测试通过，但 50k `read_optimized` 小门禁没有形成可保留收益：`readrandom_hit=190,243.695 ops/s`，与扫描统计版本的 `190,046.504 ops/s` 基本持平，属于噪声级差异；`readrandom_sameblock=324,110.657 ops/s`，明显低于扫描统计版本的 `355,809.588 ops/s`。`readrandom_burst`、`multiget_sameblock` 和 `scan` 较高，但不足以抵消 sameblock 回退和主指标收益不足。

因此 offset-reader 代码撤回，不进入默认路径；`blockSeekEntryScans` / `blockSeekMaxEntryScans` 观测字段保留。保留结论是：当前 `SliceInput` 对象成本不是足够明确的主瓶颈，或者 offset 直读引入的额外静态 helper、位运算和边界检查抵消了对象减少收益。下一步不应继续围绕 varint reader 微调，而应优先考虑 `BlockEntry`/key/value Slice 对象创建、comparator 调用，或 Table 本地 cache / 全局 BlockCache 查询路径。
## 计划补充：单点 Table.get 候选 compare 去重实验

当前 `Block.seek` 的语义是返回第一个大于等于目标 internal key 的候选 entry；上一轮扫描统计也证明默认命中路径全部使用 `blockSeekIndexHits`，没有 fallback。`Table.get(Slice)` 单点路径在拿到 candidate 后仍会再次执行 `comparator.compare(candidate.getKey(), internalKey) < 0` 检查。对默认单点 hot path 来说，这次比较理论上是冗余的，每次 `readrandom_hit` 都会支付一次 internal-key comparator 成本。

本轮实验只移除单点 `Table.get(Slice)` 中这次候选 compare，保留 MultiGet、block-local index、entry-anchor 诊断路径中的候选 compare，避免扩大语义风险。该实验不改变 Block/SST 格式、不改变 seek index、不增加 full-entry index，也不改变 `Block.seek` 返回契约。验收仍以 `readrandom_hit` 为主，并观察 sameblock、burst、scan、MultiGet；若主指标无收益或周边回退，则撤回。
### 单点 Table.get 候选 compare 去重实验结论：不保留

本轮只移除单点 `Table.get(Slice)` 在 `Block.seek` 后的 `comparator.compare(candidate.getKey(), internalKey) < 0` 检查，MultiGet 和其他诊断/索引路径保持原样。`compileJava`、`LdbCoreBehaviorTest`、`LdbObservabilityTest` 和 `TablePropertiesTest` 均通过，但 50k `read_optimized` 小门禁没有收益：`readrandom_hit=179,478.951 ops/s`，低于扫描统计版本的 `190,046.504 ops/s`；`multiget_mixed=299,264.468 ops/s` 也明显低于扫描统计版本的 `409,824.308 ops/s`。`readrandom_sameblock=402,920.692 ops/s` 和 `multiget_sameblock=451,382.902 ops/s` 不能抵消主指标和 mixed MultiGet 的回退。

因此该实验代码已撤回，不进入默认路径。保留结论是：单点候选 compare 虽然看起来冗余，但在当前 JIT、分支布局和调用路径下，移除它没有形成稳定收益，反而会压低 `readrandom_hit` 和 mixed MultiGet。后续不应继续围绕这一次 compare 微调；更有价值的方向仍是围绕每次随机 point get 的固定成本做更系统的观测，例如候选 entry 的对象创建、key/value `Slice` 构造、comparator 调用分布，以及 Table 本地 cache / 全局 `BlockCache` 查询路径。
## 计划补充：Table 候选 compare 固定成本观测

前一轮实验说明，直接移除单点 `Table.get(Slice)` 的候选 compare 并不能稳定提升 `readrandom_hit`，但这并不等于上层候选判定完全没有成本。当前需要先把该成本显式暴露出来，而不是继续猜测或继续移除保护检查。

本轮只新增观测字段，不改变读语义和缓存策略：`tableCandidateCompares` 统计 `Table` 层对 block 返回候选 entry 执行上层 comparator 判定的次数；`tableCandidateRejects` 统计这些候选被上层判定拒绝的次数。单点 get、稀疏 MultiGet direct-get 路径和 dense `seekAll` 路径都会累计这两个字段。该改动不改变 Block/SST 格式，不改变 restart/anchor seek index，不引入 full-entry index，也不改变 `Block.seek` 返回契约。验收目标是用下一轮 `readrandom_hit`、sameblock、burst、scan 和 MultiGet 门禁判断：候选 compare 是否几乎每次命中都发生、拒绝比例是否足以证明该保护仍有语义价值，以及后续是否值得继续优化 candidate 判定而不是 block 内扫描。
### Table 候选 compare 固定成本观测结果

新增 `tableCandidateCompares` / `tableCandidateRejects` 后，50k `read_optimized` 小门禁通过，覆盖 `readrandom_hit`、`readrandom_sameblock`、`readrandom_burst`、`multiget_mixed`、`multiget_sameblock` 和 `scan`。本轮结果显示，命中型点查和 MultiGet 都稳定支付了上层候选 compare 成本，但在该数据分布下没有发生 candidate reject。

| 场景 | ops/s | tableCandidateCompares | tableCandidateRejects | blockSeekIndexHits | blockSeekEntryScans |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 196,089.812 | 50,000 | 0 | 50,000 | 169,512 |
| `readrandom_sameblock` | 421,025.686 | 50,000 | 0 | 50,000 | 169,404 |
| `readrandom_burst` | 472,053.044 | 50,000 | 0 | 50,000 | 169,236 |
| `multiget_mixed` | 314,795.053 | 25,207 | 0 | 25,207 | 85,725 |
| `multiget_sameblock` | 384,311.778 | 50,000 | 0 | 1,195 | 3,561 |
| `scan` | 1,668,168.018 | 0 | 0 | 0 | 0 |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-candidate-compare-stats-50k/ldb-db-bench-summary.json`。

结论：候选 compare 是单点 hit 和 MultiGet 的稳定固定成本，尤其 `readrandom_hit` 每 50k 次操作对应 50k 次 compare；但本轮 `tableCandidateRejects=0` 也说明，在纯命中数据分布下直接删除该保护虽然看起来语义上可行，却已被前一轮性能实验否定。后续不应再次简单移除 compare，而应考虑是否能在不改变分支布局和语义保护的前提下降低 compare 输入构造成本，或者把优化重点转向候选 entry 的 key/value `Slice` 构造与 block 内对象创建。
## 计划补充：Block.seek key/value/entry 固定成本观测

`blockSeekEntryScans` 已经证明当前每次 `Block.seekWithIndex` 平均只扫描约 3 到 4 条 entry，继续减少扫描条数的空间有限。上一轮 `tableCandidateCompares` 又证明上层候选 compare 是稳定固定成本，但简单删除 compare 不保留。因此下一步需要把 block 内部每次 seek 实际产生的 key/value/entry 成本拆出来。

本轮只新增观测字段，不改变 seek 决策和读语义：`blockSeekKeyReads` 统计 `Block.seekWithIndex` 热路径中读取/重建 candidate key 的次数；`blockSeekValueReads` 统计命中候选后读取 value slice 的次数；`blockSeekEntryCreations` 统计为返回候选而创建 `BlockEntry` 的次数。该统计只覆盖 `seekWithIndex` 路径，不改变 `seekAll` dense batch 路径，不改变 Block/SST 格式，不增加 full-entry index，也不改变 restart/anchor 索引密度。验收仍以 `readrandom_hit` 为主，同时观察 sameblock、burst、scan 和 MultiGet 是否回退。
### Block.seek key/value/entry 固定成本观测结果

新增 `blockSeekKeyReads`、`blockSeekValueReads` 和 `blockSeekEntryCreations` 后，50k `read_optimized` 小门禁通过。结果显示，`blockSeekKeyReads` 与 `blockSeekEntryScans` 完全一致，说明当前每扫描一条候选 entry 都会读取/重建一次 key；而 `blockSeekValueReads` 和 `blockSeekEntryCreations` 等于实际返回候选的次数，说明 value slice 与 `BlockEntry` 只在命中候选时创建一次。

| 场景 | ops/s | blockSeekEntryScans | blockSeekKeyReads | blockSeekValueReads | blockSeekEntryCreations |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 194,961.265 | 169,512 | 169,512 | 50,000 | 50,000 |
| `readrandom_sameblock` | 365,184.214 | 169,404 | 169,404 | 50,000 | 50,000 |
| `readrandom_burst` | 448,251.997 | 169,236 | 169,236 | 50,000 | 50,000 |
| `multiget_mixed` | 396,244.867 | 85,725 | 85,725 | 25,207 | 25,207 |
| `multiget_sameblock` | 522,063.990 | 3,561 | 3,561 | 1,195 | 1,195 |
| `scan` | 1,503,673.474 | 0 | 0 | 0 | 0 |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-object-stats-50k/ldb-db-bench-summary.json`。

结论：后续优化不应优先针对 value slice 或返回 `BlockEntry` 的创建次数，因为每次 point get 只发生一次；更值得关注的是每次 block seek 平均约 3.39 次的 key 重建和 comparator 输入构造。下一步若继续优化，应尝试在不增加 full-entry index、不改变文件格式的前提下减少未命中扫描 entry 的完整 key materialization 成本，例如比较前的轻量 key 视图、复用 scratch key，或只在最终候选处构造完整返回 key。但这些方向此前有回退先例，必须继续以 `readrandom_hit` 为主并同时看 sameblock、burst、scan 和 MultiGet。
## 计划补充：Block.seek shared/unshared key-read 观测

上一轮已经确认 `blockSeekKeyReads` 与 `blockSeekEntryScans` 一致，但还不能判断 key 重建成本主要来自 shared-prefix 拼接拷贝，还是来自完整 key 的 Slice 读取与 comparator 输入固定开销。由于 LevelDB block 的 prefix-compression 依赖 previous key，若大多数扫描 entry 都是 `sharedKeyLength > 0`，后续优化才更值得围绕减少拼接/拷贝或 scratch-key 复用展开；若 unshared key 占比较高，则应更多关注 compare 路径和对象固定成本。

本轮只新增观测字段：`blockSeekSharedKeyReads` 统计 `sharedKeyLength > 0` 的 key read 次数，`blockSeekUnsharedKeyReads` 统计 `sharedKeyLength == 0` 的 key read 次数。该改动不改变 Block/SST 格式，不改变 restart/anchor seek index，不增加 full-entry index，不改变 `Block.seek` 语义，也不改变 `seekAll` dense batch 路径。验收继续以 `readrandom_hit` 为主，同时看 sameblock、burst、scan 和 MultiGet 是否回退。
### Block.seek shared/unshared key-read 观测结果

新增 `blockSeekSharedKeyReads` / `blockSeekUnsharedKeyReads` 后，50k `read_optimized` 小门禁通过。结果显示，随机点查和稀疏 MultiGet 中绝大多数 key read 都来自 shared-prefix 重建，而不是 restart 首条 unshared key 读取。

| 场景 | ops/s | keyReads | sharedKeyReads | unsharedKeyReads | sharedRatio |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 196,737.618 | 169,512 | 150,139 | 19,373 | 88.57% |
| `readrandom_sameblock` | 396,646.906 | 169,404 | 149,802 | 19,602 | 88.43% |
| `readrandom_burst` | 387,488.463 | 169,236 | 149,682 | 19,554 | 88.45% |
| `multiget_mixed` | 356,246.642 | 85,725 | 76,031 | 9,694 | 88.69% |
| `multiget_sameblock` | 439,475.442 | 3,561 | 2,541 | 1,020 | 71.36% |
| `scan` | 1,339,663.691 | 0 | 0 | 0 | 0% |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-sharedkey-stats-50k/ldb-db-bench-summary.json`。

结论：`readrandom_hit` 的 block 内固定成本主要集中在 shared-prefix key 重建，约 `88.57%` 的扫描 key 都需要基于 previous key 拼接完整 key。后续若继续优化，应优先考虑降低 shared key 拼接/拷贝成本，或者避免对非最终候选构造完整 key；但由于 earlier scratch-key/raw-compare 类实验曾回退，下一轮必须仍以小步实验为主，并继续使用 `readrandom_hit` 主验收，同时看 sameblock、burst、scan 和 MultiGet。
## 计划补充：Block.seek 双缓冲 scratch shared-key 实验

shared/unshared key-read 观测显示，`readrandom_hit` 中约 `88.57%` 的扫描 key 来自 shared-prefix 重建。直接绕过 comparator 做 composite compare 对自定义 comparator 和 internal-key 语义风险较高，因此本轮不改变比较语义，仍构造完整 key 并继续使用原 comparator。实验只尝试降低 shared key 重建时的分配成本：在单次 `Block.seekWithIndex` 热路径内维护两个可增长 byte[] scratch buffer，shared key 重建时交替写入 scratch，避免为每个扫描 entry 都新建 backing byte[]；当命中最终候选需要返回 `BlockEntry` 时，再把 scratch key 复制为稳定 key，避免返回对象引用被下一次 seek 覆盖。

该实验只作用于 `seekWithIndex` 的 `seekWithValueOnMatch` 热路径，不改变 `seekAll` dense batch、iterator、floor、block-open anchor 构建，不改变 Block/SST 文件格式，不增加 full-entry index，不改变 restart/anchor index 密度，也不改变 comparator 语义。验收仍以 `readrandom_hit` 为主，同时观察 sameblock、burst、scan 和 MultiGet；若主指标无稳定收益或周边回退，则撤回。
### Block.seek 双缓冲 scratch shared-key 实验结论：不保留

本轮实现了仅限 `seekWithIndex` 热路径的双缓冲 scratch shared-key 重建：shared key 交替写入两个可增长 byte[]，比较仍使用原 comparator，最终返回候选时再复制为稳定 key。编译和核心测试通过，但 50k `read_optimized` 小门禁没有收益，`readrandom_hit=165,874.450 ops/s`，明显低于上一轮 shared-key 观测版本的 `196,737.618 ops/s`；`multiget_mixed=326,004.959 ops/s` 也低于上一轮 `356,246.642 ops/s`。`readrandom_burst=488,150.155 ops/s` 局部较高，但不能抵消主指标回退。

因此该实验代码已撤回，不进入默认路径。保留结论是：虽然 shared-prefix key 重建是主要重复成本，但用 Java 层双缓冲 scratch 减少 byte[] 分配会引入额外分支、Slice 包装和最终候选复制成本，整体抵消甚至超过收益。后续不应继续沿 scratch-buffer 复用微调；如果继续优化 shared-key 成本，应考虑更结构化的方式，例如只在 comparator 可安全专用化时减少非候选 key 的完整 materialization，或者从更高层减少进入 block seek 的次数。
## 计划补充：单点 get 进入 block seek 的来源观测

scratch-key 实验证明 Java 层减少 shared-key 分配并不能带来稳定收益。下一步需要判断是否还有高价值入口优化：单点 `Table.get(Slice)` 的 `Block.seekWithIndex` 调用主要来自 `lastPointGetBlock` 命中、direct-map slot 命中，还是 slot miss 后打开 data block。如果大多数 seek 已经来自 last-block/slot-hit，则继续优化 cache 入口价值有限；如果 slot-miss 后仍大量进入 seek，则后续才值得继续研究本地 point cache 或更高层减少 block seek 次数。

本轮只新增观测字段，不改变缓存策略和读语义：`tablePointGetSeekAfterLastBlockHits` 统计单点 get 在 `lastPointGetBlock` 命中后进入 block seek 的次数；`tablePointGetSeekAfterSlotHits` 统计 direct-map slot 命中后进入 block seek 的次数；`tablePointGetSeekAfterSlotMisses` 统计 slot miss/open data block 后进入 block seek 的次数。该改动不改变 Block/SST 格式，不增加 full-entry index，不改变 restart/anchor seek index，也不改变 MultiGet 路径。验收继续以 `readrandom_hit` 为主，同时观察 sameblock、burst、scan 和 MultiGet。
### 单点 get 进入 block seek 的来源观测结论：不保留新增字段

本轮临时新增 `tablePointGetSeekAfterLastBlockHits`、`tablePointGetSeekAfterSlotHits` 和 `tablePointGetSeekAfterSlotMisses`，用于确认单点 get 进入 `Block.seekWithIndex` 前来自哪类 point-cache 路径。50k `read_optimized` 小门禁通过，但新增字段通过前后差值判断实现，会给单点 hot path 增加额外 long 读取和分支，因此不适合长期保留。

观测结果如下：

| 场景 | tableDataBlockSeeks | afterLastBlock | afterSlotHit | afterSlotMiss |
| --- | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 50,000 | 33 | 38,882 | 11,085 |
| `readrandom_sameblock` | 50,000 | 47,839 | 916 | 1,245 |
| `readrandom_burst` | 50,000 | 14,153 | 34,613 | 1,234 |
| `multiget_mixed` | 25,207 | 0 | 0 | 0 |
| `multiget_sameblock` | 50,000 | 0 | 0 | 0 |
| `scan` | 0 | 0 | 0 | 0 |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-pointget-seek-source-stats-50k/ldb-db-bench-summary.json`。

结论：该观测与已有 `tablePointGetLastBlockHits`、`tablePointGetSlotHits`、`tablePointGetSlotMisses` 信息完全对齐，说明现有字段已经足够判断入口来源；新增字段代码已撤回，不进入默认路径。后续若要减少进入 block seek 的次数，随机 hit 主要应关注 direct-map slot hit 后仍需 seek 的成本，而 sameblock 主要依赖 last-block 快路径，继续改 point-cache 替换策略价值有限且容易伤局部性。
## 发布基线收口：移除非必要 hot-path 诊断脚手架

前面为定位 `readrandom_hit` 瓶颈，临时加入了 `blockSeekEntryScans`、`blockSeekKeyReads`、`blockSeekSharedKeyReads`、`blockSeekValueReads`、`blockSeekEntryCreations`、`tableCandidateCompares` 等细粒度诊断字段。这些字段帮助确认了：当前 block 内平均扫描条数很低，重复成本主要来自 shared-prefix key 重建，scratch-key 复用不是有效解法，point-cache 入口来源也可由已有字段推断。

但这些字段本身位于 `Block.seekWithIndex` 或 `Table.get` hot path，会引入额外整数累加、分支和 `SeekResult` 字段传递成本。发布默认路径只保留本专项目标明确需要的 `blockSeekIndexHits`、`blockSeekIndexMisses`、`blockSeekIndexFallbacks`，以及既有 table/cache 路径统计；其它细粒度诊断字段从代码和 `readStats` 输出中移除，历史结论保留在本文档中。该收口不改变 Block/SST 格式，不改变 restart/anchor seek index，不增加 full-entry index，也不改变读语义。
### 发布基线收口门禁结果

移除非必要 hot-path 诊断脚手架后，50k `read_optimized` 小门禁通过，并完成同轮 RocksDB JNI 10.10.1 支持项对照。默认路径现在只保留本专项明确要求的 `blockSeekIndexHits`、`blockSeekIndexMisses`、`blockSeekIndexFallbacks`，不再保留 `blockSeekEntryScans`、key-read、candidate-compare 等排查字段。

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 结论 |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 185,760.216 | 508,764.486 | 36.51% | 主目标仍未达到 50% |
| `readrandom_sameblock` | 380,087.663 | 620,748.648 | 61.23% | 局部点查超过 50% |
| `readrandom_burst` | 432,627.746 | 600,666.981 | 72.02% | burst 局部性超过 50% |
| `multiget_mixed` | 339,170.917 | 429,020.306 | 79.06% | sparse mixed MultiGet 超过 50% |
| `multiget_sameblock` | 510,305.622 | 不支持 | - | LDB-only 回退门禁，表现健康 |
| `scan` | 1,897,230.802 | 不支持 | - | LDB-only 回退门禁，表现健康 |

证据文件：`ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-release-baseline-no-diagnostics-50k/ldb-db-bench-summary.json` 和 `build/reports/rocksdbjni-comparison-release-baseline-no-diagnostics-50k-supported4/comparison.csv`。

结论：当前 release baseline 已去掉诊断脚手架，功能目标仍保持：默认命中型点查继续使用 block-open-time restart/anchor seek index，且 `blockSeekIndexFallbacks=0`。但是 P0 主性能目标仍未完成，`readrandom_hit` 只有 RocksDB JNI 的 `36.51%`。后续若继续冲 50%，应避免再增加 hot-path 诊断常驻字段，优先考虑更结构化的设计变更，而不是 Java 层微调 shared-key 分配或 point-cache 替换策略。
## 计划补充：InternalUserComparator 默认 bytewise 比较快路径实验

发布基线已经移除了非常驻诊断脚手架，并保留了 Block 打开时构建的 restart/anchor 轻量 seek index；但 50k 对照中 `readrandom_hit` 仍只有 RocksDB JNI 的 `36.51%`，主目标尚未完成。前序观测说明 block 内平均扫描条数已经较低，继续压缩扫描范围、增加 anchor 细分或做 Java 层 scratch-key 复用都没有形成稳定收益。因此下一步优先降低每次 scanned key 比较的固定成本，而不是改变文件格式或增加 full-entry index。

本轮只针对默认 `leveldb.BytewiseComparator` 场景，在 `InternalUserComparator.compare` 内增加 bytewise user-key 快路径：当 user comparator 是内置 table `BytewiseComparator` 时，直接按 raw array/offset 比较 left/right 的 user-key 区间，避免为比较输入创建两个 user-key `Slice`；如果是自定义 comparator，仍保持原来的 `userComparator.compare(left.slice(...), right.slice(...))` 路径。sequence/type trailer 的倒序比较语义保持不变，custom comparator、Block/SST 文件格式、restart/anchor seek index、full-entry index 策略和 `Block.seek` 返回契约均不改变。

验收仍以 `readrandom_hit` 为主，同时观察 `readrandom_sameblock`、`readrandom_burst`、`scan`、`multiget_mixed` 和 `multiget_sameblock` 是否回退。若主指标没有稳定收益，或周边场景明显回退，则撤回该快路径，只保留实验结论。

### InternalUserComparator 默认 bytewise 比较快路径实验结论：不保留

本轮实现了仅限默认 table `BytewiseComparator` 的 raw-byte user-key 比较快路径，自定义 comparator 仍走原 Slice 路径。`compileJava`、`LdbCoreBehaviorTest`、`LdbObservabilityTest` 和 `TablePropertiesTest` 均通过；50k `read_optimized` 快速门禁也通过，但结果不足以保留：`readrandom_hit=186,439.370 ops/s`，仅略高于 release baseline 的 `185,760.216 ops/s`，属于噪声级改善；同轮 RocksDB JNI `readrandom_hit=493,383.237 ops/s`，LDB/RocksDB JNI 只有 `37.79%`，仍远低于 50% 主目标。

周边场景中，`readrandom_sameblock=598,217.074 ops/s`、`readrandom_burst=599,872.107 ops/s` 表现较好，`multiget_mixed=382,956.593 ops/s` 也高于 release baseline；但 `multiget_sameblock=384,730.508 ops/s` 明显低于 release baseline 的 `510,305.622 ops/s`，`scan=1,778,840.338 ops/s` 也低于 release baseline 的 `1,897,230.802 ops/s`。结合更早的 Slice offset/raw-array 比较实验曾明显回退，本轮确认：在当前 JIT 和调用形态下，手写 user-key bytewise 快路径不能稳定转化为 `readrandom_hit` 主收益，并会带来周边路径波动。

因此该代码已撤回，不进入默认路径。保留结论是：后续不应继续围绕 `InternalUserComparator.compare` 的 `left.slice(...)/right.slice(...)` 做手写 raw-byte 微调；若要继续逼近 50%，更值得转向结构性减少进入 block seek 的次数，或重新设计下一版本文件格式中的紧凑 block-local index。

## 计划补充：Table point-cache slot holder 复用实验

前序 point-get seek 来源观测显示，纯随机 `readrandom_hit` 中大量单点读取来自 direct-map slot 命中后继续进入 `Block.seekWithIndex`：50k 样本里 `tablePointGetSlotHits` 约为 38,882 次。当前 `Table.getPointGetDataBlock` 在 slot 命中时会为 `lastPointGetBlock` 新建一个不可变 holder，即使 slot 中已有完全相同的 block handle 和 block 对象；这会在随机 hit 热路径上产生额外对象分配和发布成本。

本轮实验不改变缓存策略、不改变 block seek 行为，也不改变 Block/SST 文件格式。实现上把 point-get direct cache 的两个并行数组 `BlockHandle[]` / `Block[]` 合并为 `LastPointGetBlock[]`：slot miss 打开 block 时创建一个 holder 并同时存入 slot 与 `lastPointGetBlock`；slot hit 时直接把 slot 中已有 holder 赋给 `lastPointGetBlock`，避免重复 new holder。并发读仍通过不可变 holder 发布，避免 handle/block 错配；slot 碰撞统计按 holder 是否存在继续保留。

验收仍以 `readrandom_hit` 为主，同时观察 `readrandom_sameblock`、`readrandom_burst`、`scan`、`multiget_mixed` 和 `multiget_sameblock` 是否回退。若主指标没有稳定收益，或周边场景明显回退，则撤回该 holder 复用实现。

### Table point-cache slot holder 复用实验结论：不保留

本轮将 point-get direct cache 从 `BlockHandle[]` / `Block[]` 双数组改为 `LastPointGetBlock[]` holder 数组，slot hit 时复用 holder，避免为 `lastPointGetBlock` 重复创建对象。`compileJava`、目标核心测试和文档编码检查均通过，但 50k `read_optimized` 快速门禁出现大面积回退：`readrandom_hit=174,039.623 ops/s`，低于 release baseline 的 `185,760.216 ops/s`；`readrandom_sameblock=116,216.178 ops/s`、`readrandom_burst=173,682.031 ops/s`、`multiget_mixed=173,418.965 ops/s` 均明显低于安全区间；`multiget_sameblock=308,690.947 ops/s` 和 `scan=1,339,222.340 ops/s` 也同步走弱。

因此该实现已撤回，不进入默认路径。保留结论是：虽然 slot hit 上重复创建 holder 看起来是随机 hit 热路径上的固定成本，但把 direct cache 改成 holder 数组会改变数组访问、对象布局和 JIT 形态，实际收益不足且会严重伤害局部性场景。后续不应继续围绕 `LastPointGetBlock` holder 复用做微调；要继续冲击 50%，需要转向更结构化的 block-local index / 文件格式方向，或者找到能减少 `Block.seek` 次数但不改变 hot-path 对象布局的方案。
