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

## 后续版本候选

- 文件格式增强：block-level key index、filter/layout metadata、format version capability。
- 更深层块内查找优化：减少 restart 区间线性扫描。
- 缓存策略优化：区分随机读/扫描读的 block cache admission。
- Long-run benchmark：随机读、混合读写、MultiGet 分布式热点。
