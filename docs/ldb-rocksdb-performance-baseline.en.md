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

## First Local Result

The current machine does not have a directly callable `db_bench`, so the first pass only records the LDB-side baseline. The RocksDB ratio should be filled after installing or building `db_bench`.

Parameters:

- Version: `0.7.0-SNAPSHOT`
- `num=200000`
- `reads=200000`
- `value_size=100`
- `sync=false`
- `groupCommit=false`

| Scenario | LDB ops/s | Notes |
| --- | ---: | --- |
| `fillseq` | 251,394 | Sequential writes |
| `readrandom` | 118,020 | Random reads, 200,000/200,000 hits |
| `overwrite` | 109,196 | Random overwrites |
| `readwhilewriting` | 95,117 | Concurrent reads and writes, 400,000 total operations |

The run still printed Windows directory fsync `AccessDeniedException` warnings, but the `ldbDbBenchReport` task ended with `PASS`. This is consistent with the earlier releaseGate observation and is recorded as an environment note rather than a workload failure.

## Interpretation

- `ratioToRocksDb >= 0.5`: close to half of RocksDB; it is reasonable to say some scenarios reach the half-range.
- `0.1 <= ratioToRocksDb < 0.5`: between one tenth and one half; describe it as catching up.
- `ratioToRocksDb < 0.1`: below one tenth; prioritize writes, flush/compaction, cache behavior, and sync policy analysis.
- `sync=false` and `sync=true` must be compared separately.

## Next Steps

1. Install or build RocksDB `db_bench`, then rerun `scripts/run-rocksdb-comparison.ps1`.
2. Archive the LDB/RocksDB ratios from `comparison.csv` into this document or release notes.
3. If `readwhilewriting` is abnormal, inspect concurrent read/write locking, MemTable lookup, and compaction interference first.
