# LDB and RocksDB Performance Baseline

English | [中文](ldb-rocksdb-performance-baseline.md)

This document records the repeatable performance comparison method between LDB and RocksDB. The baseline answers the rough question of whether LDB is near one half or one tenth of RocksDB, and tracks trends. It is not a release-blocking gate.

## Goals

- Compare LDB and RocksDB with fixed workloads instead of treating longrun reliability-gate numbers as peak performance.
- Keep at least one local baseline report for each release candidate.
- Record trends first; add hard regression thresholds only after enough samples exist on the same machine.

## Scenarios

| Scenario | Meaning | Default size |
| --- | --- | --- |
| `fillseq` | Sequential writes into a fresh database | `num=200000`, `value_size=100` |
| `readrandom` | Random reads after prefill | `reads=200000` |
| `overwrite` | Random overwrites after prefill | `num=200000` |
| `readwhilewriting` | Concurrent reads and writes after prefill | `reads=200000`, writes `num=200000` |

## How To Run

Run the LDB-only baseline first:

```powershell
.\gradlew.bat :ldb-longrun:ldbDbBenchReport `
  "-Pldb.dbBench.num=200000" `
  "-Pldb.dbBench.reads=200000" `
  "-Pldb.dbBench.valueSize=100"
```

Outputs:

- `ldb-longrun/build/reports/ldb-db-bench/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench/ldb-db-bench-summary.csv`

If RocksDB `db_bench` is installed locally, run the comparison script:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\run-rocksdb-comparison.ps1 `
  -DbBenchPath db_bench `
  -Num 200000 `
  -Reads 200000 `
  -ValueSize 100
```

Outputs:

- `build/reports/rocksdb-comparison/ldb/ldb-db-bench-summary.json`
- `build/reports/rocksdb-comparison/rocksdb/*.log`
- `build/reports/rocksdb-comparison/comparison.csv`
- `build/reports/rocksdb-comparison/comparison.json`

If `ldbDbBenchReport` has already produced an LDB summary and you only want to aggregate the existing LDB result or add the RocksDB side, pass the existing report:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\run-rocksdb-comparison.ps1 `
  -ExistingLdbSummary ldb-longrun\build\reports\ldb-db-bench\ldb-db-bench-summary.json
```

If native `db_bench` is not available on Windows, use RocksDB JNI for a Java-level comparison first:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\run-rocksdbjni-comparison.ps1 `
  -RocksDbJniVersion 10.10.1 `
  -Num 200000 `
  -Reads 200000 `
  -ValueSize 100 `
  -Runs 2
```

Outputs:

- `build/reports/rocksdbjni-comparison/rocksdbjni/rocksdbjni-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison/comparison.csv`
- `build/reports/rocksdbjni-comparison/comparison.json`
- `build/reports/rocksdbjni-comparison/comparison-stats.csv`
- `build/reports/rocksdbjni-comparison/comparison-stats.json`

## Current Local RocksDB JNI Comparison

The current machine does not have a directly callable native `db_bench`, so this pass uses `rocksdbjni-10.10.1` for a Java-level comparison. These numbers are useful for the current Java call path, but they do not replace official RocksDB native `db_bench` results.

Parameters:

- Version: `0.7.0-SNAPSHOT`
- RocksDB JNI: `10.10.1`
- `num=200000`
- `reads=200000`
- `value_size=100`
- `sync=false`
- `groupCommit=false`

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Conclusion |
| --- | ---: | ---: | ---: | --- |
| `fillseq` | 295,391 | 74,328 - 126,336 | 2.34 - 3.97 | LDB is faster in this Java small-value sequential-write profile |
| `warm_readrandom` | 295,635 | 259,081 - 323,673 | 0.91 - 1.14 | LDB warm random reads are above the 50% target |
| `cold_readrandom` | 202,747 | 286,947 | 0.71 | LDB cold random reads are above the 50% target |
| `overwrite` | 122,276 | 55,325 - 81,973 | 1.49 - 2.21 | LDB is faster in this Java random-overwrite profile |
| `readwhilewriting` | 109,316 | 158,525 - 174,960 | 0.62 - 0.69 | LDB concurrent read/write is about 60% to 70% of RocksDB JNI |

In the current Windows/JDK environment, explicit close in the RocksDB JNI runner triggers a JNI dispose symbol mismatch. Warm scenarios therefore use short-lived processes without explicit close, keep one directory per scenario, and let process exit release native resources. `cold_readrandom` uses two short processes: the first process pre-fills and exits, and the second process reopens the same directory and times only reads. This avoids the JNI close issue while preserving the cold-read profile. The LDB run still printed Windows directory fsync `AccessDeniedException` warnings, but the `ldbDbBenchReport` task ended with `PASS`; this is consistent with earlier releaseGate observations and is recorded as an environment note rather than a workload failure.

Overall: under the Java/JNI comparison profile, LDB is not simply one tenth of RocksDB. Small-value write profiles are above RocksDB JNI; warm random reads are between 0.91x and 1.14x of RocksDB JNI in this two-run comparison, and cold random reads reach 0.71x. Both random-read profiles exceed the half-speed target. Concurrent read/write is about 60% to 70%. A more authoritative RocksDB comparison still requires native `db_bench`.

## Interpretation

- `ratioToRocksDb >= 0.5`: close to half of RocksDB; it is reasonable to say some scenarios reach the half-range.
- `0.1 <= ratioToRocksDb < 0.5`: between one tenth and one half; describe it as catching up.
- `ratioToRocksDb < 0.1`: below one tenth; prioritize writes, flush/compaction, cache behavior, and sync policy analysis.
- `sync=false` and `sync=true` must be compared separately.

## Next Steps

1. Install or build RocksDB `db_bench`, then rerun `scripts/run-rocksdb-comparison.ps1`.
2. Archive the LDB/RocksDB ratios from `comparison.csv` into this document or release notes.
3. If `readwhilewriting` is abnormal, inspect concurrent read/write locking, MemTable lookup, and compaction interference first.

## Focused Random-Read Evidence On 2026-06-20

This short run validates the RR-05/RR-06/RR-07 implementation paths with `num=50000`, `reads=50000`, `value_size=100`, and `batch_size=64`. It is intentionally smaller than the main `num=200000` baseline and should be read as implementation evidence rather than a replacement trend baseline.

| Profile | Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Report |
| --- | ---: | ---: | ---: | ---: | --- |
| default | `warm_readrandom` | 426,928 | 528,133 | 0.8084 | `build/reports/rocksdbjni-comparison-rr-default/comparison.csv` |
| default | `cold_readrandom` | 187,288 | 461,805 | 0.4056 | `build/reports/rocksdbjni-comparison-rr-default/comparison.csv` |
| default | `multiget_random` | 208,268 | 513,548 | 0.4055 | `build/reports/rocksdbjni-comparison-rr-default/comparison.csv` |
| read_optimized | `warm_readrandom` | 393,160 | 504,415 | 0.7794 | `build/reports/rocksdbjni-comparison-rr-read-optimized/comparison.csv` |
| read_optimized | `cold_readrandom` | 201,693 | 460,801 | 0.4377 | `build/reports/rocksdbjni-comparison-rr-read-optimized/comparison.csv` |
| read_optimized | `multiget_random` | 215,978 | 443,409 | 0.4871 | `build/reports/rocksdbjni-comparison-rr-read-optimized/comparison.csv` |

The LDB profile report is `build/reports/ldb-read-profile-comparison-rr/profile-comparison.csv`. The LDB summaries now include `sstReadStats` and `blockCacheStats`; for example, the default `multiget_random` row records `pointGets=50000`, `tableReads=50000`, and `mayContainRequests=50000`, proving the benchmark exercises SST filtering and table-cache lookup after compaction.

## Current-Version Random-Read Gap Closure Results On 2026-06-20

This is the final current-version random-read evidence set, using `num=200000`, `reads=200000`, `value_size=100`, and `batch_size=64`.

| Profile | Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Report |
| --- | ---: | ---: | ---: | ---: | --- |
| default | `warm_readrandom` | 327,318 | 473,241 | 0.6917 | `build/reports/rocksdbjni-comparison-200k-default/comparison.csv` |
| default | `cold_readrandom` | 157,705 | 320,820 | 0.4916 | `build/reports/rocksdbjni-comparison-200k-default/comparison.csv` |
| default | `multiget_random` | 137,833 | 321,336 | 0.4289 | `build/reports/rocksdbjni-comparison-200k-default/comparison.csv` |
| read_optimized | `warm_readrandom` | 293,233 | 521,253 | 0.5626 | `build/reports/rocksdbjni-comparison-200k-read-optimized/comparison.csv` |
| read_optimized | `cold_readrandom` | 224,487 | 250,363 | 0.8966 | `build/reports/rocksdbjni-comparison-200k-read-optimized/comparison.csv` |
| read_optimized | `multiget_random` | 172,418 | 197,650 | 0.8723 | `build/reports/rocksdbjni-comparison-200k-read-optimized/comparison.csv` |

The LDB side-by-side profile report is `build/reports/ldb-read-profile-comparison-200k/profile-comparison.csv`. After this fix, the default `multiget_random` `sstReadStats` row records `pointGets=200000`, `candidateFiles=200000`, `tableReads=200000`, and `mayContainRequests=200000`; candidate-file accounting is no longer reported as 0.

Conclusion: the current default profile keeps warm random reads above 0.5x; the read-optimized profile keeps both cold random reads and `multiget_random` above 0.5x, closing the current-version random-read workstream. Native RocksDB `db_bench` remains an external comparison entry point, not a local completion prerequisite for this version.

## Supplemental baseline: 200k result after deeper MultiGet SST optimization

This supplemental run focuses on `multiget_random` under the `read_optimized` profile. The optimization batches unresolved MultiGet keys by SST file and reuses one iterator within each SST; it does not change the file format.

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI |
| --- | ---: | ---: | ---: |
| read_optimized / multiget_random / 200k / batch=64 | 200,302.818 | 243,015.078 | 82.42% |

LDB read statistics: pointGets=200,000, level0Gets=200,000, levelGets=1,200,000, candidateFiles=200,000, tableReads=34,315, tableRequests=234,394, tableLoads=45, iteratorRequests=34,394, mayContainRequests=200,000.

Evidence paths:

- `ldb-longrun/build/reports/ldb-multiget-optimized-200k/ldb-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison-multiget-optimized-200k/comparison.csv`