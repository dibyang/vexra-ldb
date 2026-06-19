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
  -ValueSize 100
```

输出目录：

- `build/reports/rocksdbjni-comparison/rocksdbjni/rocksdbjni-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison/comparison.csv`
- `build/reports/rocksdbjni-comparison/comparison.json`

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
| `fillseq` | 286,508 | 102,664 | 2.79 | LDB 在该 Java 小 value 顺序写口径更快 |
| `readrandom` | 132,910 | 462,859 | 0.29 | LDB 约为 RocksDB JNI 的 29% |
| `overwrite` | 102,906 | 84,439 | 1.22 | LDB 在该 Java 随机覆盖写口径略快 |
| `readwhilewriting` | 99,450 | 170,468 | 0.58 | LDB 约为 RocksDB JNI 的 58% |

本次 RocksDB JNI runner 在当前 Windows/JDK 环境中显式 close 会触发 JNI dispose 符号不匹配，因此 standalone runner 采用短进程不显式 close 的方式，每个场景使用独立目录，并由进程退出释放 native 资源。这个口径会略微偏向 RocksDB JNI，因为不把 close 成本计入结果。LDB 运行过程中 Windows 目录 fsync 仍打印 `AccessDeniedException` WARN，但 `ldbDbBenchReport` 任务最终 `PASS`；该 WARN 与之前 releaseGate 记录一致，当前先作为环境口径记录，不把它解释为 workload 失败。

整体判断：在 Java/JNI 对比口径下，LDB 不是简单的“只有 RocksDB 十分之一”。写入类小 value 场景已经接近或超过 RocksDB JNI；随机读仍明显落后，约三成；并发读写约六成。后续如果要得出更权威的 RocksDB 对标结论，还需要补 native `db_bench`。

## 解读规则

- `ratioToRocksDb >= 0.5`：接近 RocksDB 半档性能，可以考虑对外说“部分场景达到 RocksDB 一半量级”。
- `0.1 <= ratioToRocksDb < 0.5`：十分之一到一半之间，应继续按“追赶中”表述。
- `ratioToRocksDb < 0.1`：低于十分之一，应优先分析写入、flush/compaction、缓存和同步落盘策略。
- `sync=false` 与 `sync=true` 必须分开比较，不能混用结论。

## 后续动作

1. 安装或构建 RocksDB `db_bench` 后，复跑 `scripts/run-rocksdb-comparison.ps1`。
2. 将 `comparison.csv` 中的 LDB/RocksDB 比例归档到本文件或发布记录。
3. 如果 `readwhilewriting` 出现明显异常，优先检查并发读写锁、MemTable 查询路径和 compaction 干扰。
