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
  -ValueSize 100
```

Outputs:

- `build/reports/rocksdbjni-comparison/rocksdbjni/rocksdbjni-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison/comparison.csv`
- `build/reports/rocksdbjni-comparison/comparison.json`

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
| `fillseq` | 286,508 | 102,664 | 2.79 | LDB is faster in this Java small-value sequential-write profile |
| `readrandom` | 132,910 | 462,859 | 0.29 | LDB is about 29% of RocksDB JNI |
| `overwrite` | 102,906 | 84,439 | 1.22 | LDB is slightly faster in this Java random-overwrite profile |
| `readwhilewriting` | 99,450 | 170,468 | 0.58 | LDB is about 58% of RocksDB JNI |

In the current Windows/JDK environment, explicit close in the RocksDB JNI runner triggers a JNI dispose symbol mismatch. The standalone runner therefore uses a short-lived process without explicit close, keeps one directory per scenario, and lets process exit release native resources. This slightly favors RocksDB JNI because close cost is not counted. The LDB run still printed Windows directory fsync `AccessDeniedException` warnings, but the `ldbDbBenchReport` task ended with `PASS`; this is consistent with earlier releaseGate observations and is recorded as an environment note rather than a workload failure.

Overall: under the Java/JNI comparison profile, LDB is not simply one tenth of RocksDB. Small-value write profiles are near or above RocksDB JNI, random reads are still clearly behind at about 30%, and concurrent read/write is about 60%. A more authoritative RocksDB comparison still requires native `db_bench`.

## Interpretation

- `ratioToRocksDb >= 0.5`: close to half of RocksDB; it is reasonable to say some scenarios reach the half-range.
- `0.1 <= ratioToRocksDb < 0.5`: between one tenth and one half; describe it as catching up.
- `ratioToRocksDb < 0.1`: below one tenth; prioritize writes, flush/compaction, cache behavior, and sync policy analysis.
- `sync=false` and `sync=true` must be compared separately.

## Next Steps

1. Install or build RocksDB `db_bench`, then rerun `scripts/run-rocksdb-comparison.ps1`.
2. Archive the LDB/RocksDB ratios from `comparison.csv` into this document or release notes.
3. If `readwhilewriting` is abnormal, inspect concurrent read/write locking, MemTable lookup, and compaction interference first.
