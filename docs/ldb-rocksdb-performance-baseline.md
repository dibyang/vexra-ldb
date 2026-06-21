# LDB 与 RocksDB 性能基线

[English](ldb-rocksdb-performance-baseline.en.md) | 中文

本文记录 LDB 与 RocksDB 的可复跑性能对比口径。该基线只用于回答“当前大概是 RocksDB 的几成”以及观察趋势，不作为发布阻断门禁。

## 目标

- 使用固定 workload 比较 LDB 与 RocksDB，避免把 longrun 可靠性门禁数字误当成峰值性能。
- 每次发布候选版本至少保留一次本机基线报告。
- 初期只记录趋势，不设置硬阈值；等同一机器积累足够历史样本后，再讨论回归阈值。

## 场景

| 场景 | 含义 | 默认规模 |
| --- | --- | --- |
| `fillseq` | 顺序写入新库 | `num=200000`、`value_size=100` |
| `readrandom` | 预填充后随机读 | `reads=200000` |
| `overwrite` | 预填充后随机覆盖写 | `num=200000` |
| `readwhilewriting` | 预填充后并发读写 | `reads=200000`、写入 `num=200000` |

## 运行方式

先运行 LDB 单侧基准：

```powershell
.\gradlew.bat :ldb-longrun:ldbDbBenchReport `
  "-Pldb.dbBench.num=200000" `
  "-Pldb.dbBench.reads=200000" `
  "-Pldb.dbBench.valueSize=100"
```

输出目录：

- `ldb-longrun/build/reports/ldb-db-bench/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench/ldb-db-bench-summary.csv`

如果本机已安装 RocksDB `db_bench`，运行对比脚本：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\run-rocksdb-comparison.ps1 `
  -DbBenchPath db_bench `
  -Num 200000 `
  -Reads 200000 `
  -ValueSize 100
```

输出目录：

- `build/reports/rocksdb-comparison/ldb/ldb-db-bench-summary.json`
- `build/reports/rocksdb-comparison/rocksdb/*.log`
- `build/reports/rocksdb-comparison/comparison.csv`
- `build/reports/rocksdb-comparison/comparison.json`

如果已经先运行过 `ldbDbBenchReport`，只想汇总已有 LDB 结果或补跑 RocksDB 对比，可以传入已有报告：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\run-rocksdb-comparison.ps1 `
  -ExistingLdbSummary ldb-longrun\build\reports\ldb-db-bench\ldb-db-bench-summary.json
```

如果 Windows 本机没有 native `db_bench`，可以先用 RocksDB JNI 做 Java 口径对比：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\run-rocksdbjni-comparison.ps1 `
  -RocksDbJniVersion 10.10.1 `
  -Num 200000 `
  -Reads 200000 `
  -ValueSize 100 `
  -Runs 2
```

输出目录：

- `build/reports/rocksdbjni-comparison/rocksdbjni/rocksdbjni-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison/comparison.csv`
- `build/reports/rocksdbjni-comparison/comparison.json`
- `build/reports/rocksdbjni-comparison/comparison-stats.csv`
- `build/reports/rocksdbjni-comparison/comparison-stats.json`

## 当前本机 RocksDB JNI 对比结果

当前机器没有可直接调用的 native `db_bench`，因此先使用 `rocksdbjni-10.10.1` 做 Java 口径对比。该结果可以回答当前 Java 调用路径下的大概差距，但不能替代 RocksDB 官方 native `db_bench` 结论。

运行参数：

- 版本：`0.7.0-SNAPSHOT`
- RocksDB JNI：`10.10.1`
- `num=200000`
- `reads=200000`
- `value_size=100`
- `sync=false`
- `groupCommit=false`

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 结论 |
| --- | ---: | ---: | ---: | --- |
| `fillseq` | 295,391 | 74,328 - 126,336 | 2.34 - 3.97 | LDB 在该 Java 小 value 顺序写口径更快 |
| `warm_readrandom` | 295,635 | 259,081 - 323,673 | 0.91 - 1.14 | LDB warm 随机读已稳定超过 50% 目标 |
| `cold_readrandom` | 202,747 | 286,947 | 0.71 | LDB cold 随机读已超过 50% 目标 |
| `overwrite` | 122,276 | 55,325 - 81,973 | 1.49 - 2.21 | LDB 在该 Java 随机覆盖写口径更快 |
| `readwhilewriting` | 109,316 | 158,525 - 174,960 | 0.62 - 0.69 | LDB 并发读写约为 RocksDB JNI 的六到七成 |

本次 RocksDB JNI runner 在当前 Windows/JDK 环境中显式 close 会触发 JNI dispose 符号不匹配，因此 warm 场景采用短进程不显式 close 的方式，每个场景使用独立目录，并由进程退出释放 native 资源。`cold_readrandom` 采用两段短进程：第一段预填充后退出，第二段重开同一目录并只计时读取，从而避开 JNI close 问题并保留冷启动读口径。LDB 运行过程中 Windows 目录 fsync 仍打印 `AccessDeniedException` WARN，但 `ldbDbBenchReport` 任务最终 `PASS`；该 WARN 与之前 releaseGate 记录一致，当前先作为环境口径记录，不把它解释为 workload 失败。

整体判断：在 Java/JNI 对比口径下，LDB 不是简单的“只有 RocksDB 十分之一”。写入类小 value 场景已经超过 RocksDB JNI；warm 随机读在本轮两次 RocksDB JNI 对照下处于 0.91 到 1.14 倍之间，cold 随机读达到 0.71 倍，均超过半档目标；并发读写约六到七成。后续如果要得出更权威的 RocksDB 对标结论，还需要补 native `db_bench`。

## 解读规则

- `ratioToRocksDb >= 0.5`：接近 RocksDB 半档性能，可以考虑对外说“部分场景达到 RocksDB 一半量级”。
- `0.1 <= ratioToRocksDb < 0.5`：十分之一到一半之间，应继续按“追赶中”表述。
- `ratioToRocksDb < 0.1`：低于十分之一，应优先分析写入、flush/compaction、缓存和同步落盘策略。
- `sync=false` 与 `sync=true` 必须分开比较，不能混用结论。

## 后续动作

1. 安装或构建 RocksDB `db_bench` 后，复跑 `scripts/run-rocksdb-comparison.ps1`。
2. 将 `comparison.csv` 中的 LDB/RocksDB 比例归档到本文件或发布记录。
3. 如果 `readwhilewriting` 出现明显异常，优先检查并发读写锁、MemTable 查询路径和 compaction 干扰。

## 2026-06-20 随机读专项证据

本次短参数验证用于证明 RR-05/RR-06/RR-07 的实现链路，参数为 `num=50000`、`reads=50000`、`value_size=100`、`batch_size=64`。它小于主基线的 `num=200000`，因此用于实现证据归档，不替代长期趋势基线。

| profile | 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 报告 |
| --- | ---: | ---: | ---: | ---: | --- |
| default | `warm_readrandom` | 426,928 | 528,133 | 0.8084 | `build/reports/rocksdbjni-comparison-rr-default/comparison.csv` |
| default | `cold_readrandom` | 187,288 | 461,805 | 0.4056 | `build/reports/rocksdbjni-comparison-rr-default/comparison.csv` |
| default | `multiget_random` | 208,268 | 513,548 | 0.4055 | `build/reports/rocksdbjni-comparison-rr-default/comparison.csv` |
| read_optimized | `warm_readrandom` | 393,160 | 504,415 | 0.7794 | `build/reports/rocksdbjni-comparison-rr-read-optimized/comparison.csv` |
| read_optimized | `cold_readrandom` | 201,693 | 460,801 | 0.4377 | `build/reports/rocksdbjni-comparison-rr-read-optimized/comparison.csv` |
| read_optimized | `multiget_random` | 215,978 | 443,409 | 0.4871 | `build/reports/rocksdbjni-comparison-rr-read-optimized/comparison.csv` |

LDB profile 并列表为 `build/reports/ldb-read-profile-comparison-rr/profile-comparison.csv`。LDB summary 已包含 `sstReadStats` 与 `blockCacheStats`；例如 default `multiget_random` 行记录 `pointGets=50000`、`tableReads=50000`、`mayContainRequests=50000`，说明该 benchmark 在 compact 后覆盖了 SST 文件筛选和 table cache 查询路径。

## 2026-06-20 本版本随机读不足修复结果

本节是本版本最终随机读专项证据，参数为 `num=200000`、`reads=200000`、`value_size=100`、`batch_size=64`。

| profile | 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 报告 |
| --- | ---: | ---: | ---: | ---: | --- |
| default | `warm_readrandom` | 327,318 | 473,241 | 0.6917 | `build/reports/rocksdbjni-comparison-200k-default/comparison.csv` |
| default | `cold_readrandom` | 157,705 | 320,820 | 0.4916 | `build/reports/rocksdbjni-comparison-200k-default/comparison.csv` |
| default | `multiget_random` | 137,833 | 321,336 | 0.4289 | `build/reports/rocksdbjni-comparison-200k-default/comparison.csv` |
| read_optimized | `warm_readrandom` | 293,233 | 521,253 | 0.5626 | `build/reports/rocksdbjni-comparison-200k-read-optimized/comparison.csv` |
| read_optimized | `cold_readrandom` | 224,487 | 250,363 | 0.8966 | `build/reports/rocksdbjni-comparison-200k-read-optimized/comparison.csv` |
| read_optimized | `multiget_random` | 172,418 | 197,650 | 0.8723 | `build/reports/rocksdbjni-comparison-200k-read-optimized/comparison.csv` |

LDB profile 并列表为 `build/reports/ldb-read-profile-comparison-200k/profile-comparison.csv`。本轮修复后，default `multiget_random` 的 `sstReadStats` 记录 `pointGets=200000`、`candidateFiles=200000`、`tableReads=200000`、`mayContainRequests=200000`，候选文件统计口径已不再为 0。

结论：本版本 default profile 的 warm 随机读高于 0.5x；read_optimized profile 的 cold 随机读和 `multiget_random` 均高于 0.5x，当前版本随机读专项目标已闭合。native RocksDB `db_bench` 仍保留为外部环境对照入口，不作为本版本本机完成条件。

## 补充基线：MultiGet SST 深层优化后 200k 结果

本轮补充验证聚焦 `read_optimized` profile 下的 `multiget_random`。优化点是按 SST 文件批量处理 MultiGet 未命中 key，并在同一 SST 内复用 iterator；该优化不修改文件格式。

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI |
| --- | ---: | ---: | ---: |
| read_optimized / multiget_random / 200k / batch=64 | 200,302.818 | 243,015.078 | 82.42% |

LDB 读取统计：pointGets=200,000，level0Gets=200,000，levelGets=1,200,000，candidateFiles=200,000，tableReads=34,315，tableRequests=234,394，tableLoads=45，iteratorRequests=34,394，mayContainRequests=200,000。

证据路径：

- `ldb-longrun/build/reports/ldb-multiget-optimized-200k/ldb-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison-multiget-optimized-200k/comparison.csv`
## V3E-09 cache admission 后随机读专项对照

本轮使用 `ldb-longrun/build/reports/ldb-db-bench-v3e09-cache-admission2-200k/ldb-db-bench-summary.json` 作为 LDB 现有结果，并通过 `scripts/run-rocksdbjni-comparison.ps1` 复跑 RocksDB JNI 10.10.1 支持的随机读场景。命令如下：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-rocksdbjni-comparison.ps1 -ExistingLdbSummary "ldb-longrun\build\reports\ldb-db-bench-v3e09-cache-admission2-200k\ldb-db-bench-summary.json" -OutputDir "build\reports\rocksdbjni-comparison-v3e09-random-supported-200k" -Benchmarks "cold_readrandom,multiget_random" -Num 200000 -Reads 200000 -BatchSize 64 -Runs 1
```

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | 报告 |
| --- | ---: | ---: | ---: | --- |
| `cold_readrandom` | 187,734.903 | 308,937.564 | 0.6077 | `build/reports/rocksdbjni-comparison-v3e09-random-supported-200k/comparison.csv` |
| `multiget_random` | 178,359.627 | 262,404.208 | 0.6797 | `build/reports/rocksdbjni-comparison-v3e09-random-supported-200k/comparison.csv` |

补充边界：本轮 LDB runner 还输出了 `multiget_sameblock=274,764.640 ops/s` 和 `scan=2,666,513.787 ops/s`，但当前 RocksDB JNI runner 仅支持 `cold_readrandom` 与 `multiget_random` 对照；`multiget_sameblock` 是 LDB 本轮用于验证 data-block 分组复用和 admission 效果的密集批量场景，不在本次 RocksDB JNI ratio 中声明。

结论：在当前 Windows/JDK/RocksDB JNI 10.10.1 Java 对照口径下，`read_optimized + blockCacheAdmissionMinReads=2` 已使 `cold_readrandom` 与 `multiget_random` 均超过 0.5x RocksDB JNI，随机读专项目标保持闭合。native RocksDB `db_bench` 仍作为更权威的外部环境对照入口。
## Bloom/filter miss/mixed 50k 对照结果

本轮修正 `readrandom_miss`、`readrandom_mixed` 与 `multiget_mixed` benchmark：准备数据后关闭重开再计时，确保读路径进入 SST/Bloom；同时移除新增场景中的强制 `compactRange`，避免把 flush/compaction 成本计入 Bloom 读路径验证。修复 v3 filter properties 写入顺序后，`BlockBuilder` key 递增约束不再被破坏。

| 场景 | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Bloom 证据 |
| --- | ---: | ---: | ---: | --- |
| `readrandom_miss` | 277,468.554 | 301,242.565 | 0.9211 | `filterSkips=49604`, `mayContainFalse=49604` |
| `readrandom_mixed` | 277,333.582 | 435,665.684 | 0.6366 | `filterSkips=24793`, `mayContainFalse=24793` |
| `multiget_mixed` | 317,069.768 | 383,228.393 | 0.8274 | `filterSkips=24793`, `directGetBatchRequests=782` |

证据文件：`ldb-longrun/build/reports/ldb-db-bench-bloom-miss-mixed-50k/ldb-db-bench-summary.csv` 与 `build/reports/rocksdbjni-comparison-bloom-miss-mixed-50k/comparison.csv`。