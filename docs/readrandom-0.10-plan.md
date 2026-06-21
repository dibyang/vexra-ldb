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