# LDB Long-Run Stress And Benchmark Report Design

English | [中文](ldb-longrun-benchmark-design.md)

## Background

LDB already has lightweight benchmark and soak tests, operation histograms, block cache stats, compaction/write-stall metrics, and capacity watermark properties. These entry points prove local behavior, but they are not enough to support release confidence close to RocksDB maturity: LDB still lacks a unified report format, long-term trend signals, failure attribution, release-gate thresholds, and environment-level scenarios such as low disk, high concurrency, and repeated crash-reopen loops.

## Goals

- Design a repeatable long-run stress matrix and machine-readable reports.
- Unify metric collection for write, read, snapshot, compaction, backup, repair, and crash/reopen workloads.
- Define the release-gate thresholds that must pass before publication.
- Keep ordinary unit tests lightweight; long-running stress tests must be explicit tasks.

## Non-Goals

- Do not run hour-level stress tests by default in ordinary `test`.
- Do not introduce an external visualization system at this stage; emit JSON and Markdown reports first.
- Do not treat one local machine run as a universal production performance promise.

## Current Flow

| Capability | Current state |
| --- | --- |
| Micro benchmark | Covers write, random read, snapshot scan, manual compaction, and checkpoint |
| Soak | Covers local compaction, multi-column-family, write-stall, and reopen scenarios |
| Observability | `getProperty` exposes operation, block cache, WAL, file bytes, and compaction stats |
| Reports | Mostly assertions and property strings; no unified release report yet |

## Core Constraints

- Keep JDK8 compatibility.
- Stress tasks must be enabled explicitly so daily CI remains fast.
- Reports must include environment information: OS, JDK, CPU, memory, disk path, LDB version, and configuration.
- Every workload must record its seed for reproduction.
- On failure, preserve the DB directory or a minimal diagnostic summary for later repair/check analysis.

## Interface Design

Recommended new Gradle tasks or profiles:

| Entry | Semantics |
| --- | --- |
| `longRunTest` | Runs a 10-30 minute local long-run test |
| `releaseSoakTest` | Runs the pre-release matrix and allows longer durations |
| `benchmarkReport` | Runs short baselines and emits reports |
| `-Pldb.longrun.durationMinutes` | Controls run duration |
| `-Pldb.longrun.outputDir` | Controls report output directory |
| `-Pldb.longrun.seed` | Fixes the random seed |

Report files:

```text
build/reports/ldb-longrun/
  summary.json
  summary.md
  operations.csv
  failures.json
  properties-before.json
  properties-after.json
```

## Data Structures

Core `summary.json` fields:

| Field | Meaning |
| --- | --- |
| `version` | LDB project version |
| `commit` | Git commit, nullable |
| `environment` | OS/JDK/CPU/memory/disk information |
| `workloads[]` | Configuration, seed, and duration of each workload |
| `metrics` | Throughput, latency, stall, compaction, cache, and file-byte metrics |
| `checks` | Reopen/check/backup/restore/repair results |
| `thresholds` | Release-gate thresholds and pass/fail state |
| `failures[]` | Exception, failed phase, and preserved path |

## Workload Matrix

| Workload | Goal | Core metrics |
| --- | --- | --- |
| `write-burst` | Write peaks and write-stall behavior | ops/s, p99, stall count, WAL bytes |
| `read-mixed` | Mixed read/write stability | get p50/p99, cache hit rate, slow ops |
| `snapshot-scan` | Long snapshots plus scans | cursor open/close, snapshot visibility, obsolete files |
| `compaction-heavy` | Long compaction correctness | compaction success/failure/cancel, pending bytes |
| `backup-restore-loop` | Backup/restore closed loop | backup/check/restore/reopen success rate |
| `repair-plan-loop` | Pre-corruption diagnostics | repair-plan explainability and latency |
| `crash-reopen` | Process-level recovery | synced-data recovery rate, WAL replay latency |
| `low-disk-simulated` | Precondition for disk exceptions | write failure semantics, directory cleanup, report preservation |

## State Machine

`PREPARE -> WARMUP -> RUNNING -> VERIFYING -> REPORTING -> PASSED/FAILED`

- `PREPARE` creates isolated work directories and captures configuration snapshots.
- `WARMUP` primes the workload; data from this phase is excluded from final metrics.
- `RUNNING` executes the workload and samples properties periodically.
- `VERIFYING` runs reopen, check, backup, repair, and other closed-loop validation.
- `REPORTING` writes JSON, Markdown, and CSV outputs.

## Sequence Flow

1. Read Gradle parameters and default configuration.
2. Create an independent DB directory and seed for each workload.
3. Sample `getProperty` periodically and record operation latency histograms.
4. After the workload, run `check`, reopen, and the necessary backup/restore checks.
5. Aggregate threshold decisions and write reports.
6. On failure, preserve the failed DB path and record the minimum reproduction command.

## Error Handling

- Workload exception: stop the current workload, run best-effort check/repair-plan, and mark the report failed.
- Child-process crash: the parent records the exit code and DB directory.
- Report write failure: fail the test and print the report directory.
- Threshold miss: controlled by `-Pldb.longrun.failOnThreshold`; the build can either fail or mark a warning.

## Idempotency

- Each run writes to an independent timestamp/seed directory.
- The same seed and configuration should produce the same key distribution.
- Cleanup tasks only remove the current report directory and must not touch user data.

## Rollback Strategy

This framework only adds tests and reports by default; it does not affect the LDB runtime. If a report task is unstable, remove it from the release gate while keeping unit tests and existing properties.

## Compatibility

- No disk-format changes.
- Report JSON fields should be added without reversing existing semantics.
- Older versions may not support every property; reports should mark those entries as `missingProperty`.

## Release-Gate Recommendations

| Gate | Initial threshold |
| --- | --- |
| Data correctness | `check` and reopen pass after every workload |
| Write p99 | No more than 30% worse than the previous baseline |
| Read p99 | No more than 30% worse than the previous baseline |
| Compaction failure | Must be 0 |
| Cursor leak | openCount == closeCount |
| Backup/restore | 100% success |
| Repair plan | Does not modify the directory and emits JSON successfully |

## Test Plan

- Report-generation unit test: a short workload produces complete JSON and Markdown.
- Threshold-decision test: construct pass and fail reports.
- Failure-preservation test: after a workload exception, the report includes the DB path.
- Child-process crash/reopen test reuses existing crash-injection entry points.

## Risks

| Risk | Mitigation |
| --- | --- |
| Local performance variance causes false judgments | Start with wide thresholds and record environment plus baseline |
| Long runs slow CI | Keep disabled by default; enable explicitly for release or nightly runs |
| Reports become too large | Split CSV files; keep only aggregate data in summary |

## Phased Implementation Plan

| Phase | Content | Acceptance |
| --- | --- | --- |
| 1 | Define report JSON/Markdown schema and short report generator | Unit tests generate reports |
| 2 | Wire write/read/snapshot/compaction workloads | Local 10-minute report remains stable |
| 3 | Wire backup/restore/repair-plan/crash-reopen | Closed-loop workflow report passes |
| 4 | Add thresholds and baseline comparison | Release gate can fail configurably |
| 5 | Add low-disk or permission-failure environment scripts | Failure report is explainable |
