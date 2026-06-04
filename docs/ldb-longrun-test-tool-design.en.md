# LDB Long-Running Stress And Fault-Injection Test Tool Design

English | [中文](ldb-longrun-test-tool-design.md)

## Background

LDB already has WAL, SSTable, checkpoint, backup, repair, check, compaction observability, and process-crash tests. These capabilities are currently spread across unit tests, tool commands, and reliability regressions. The project still needs an independently packaged long-running test tool that can run realistic workloads, inject real file corruption, verify recovery, observe space/resource reclamation, and emit a decisive report.

This design introduces `ldb-longrun` as an independent test application for business-like pressure simulation, long-running stability validation, recovery validation, resource reclamation observation, and release acceptance.

Implementation tracking document: `docs/ldb-longrun-test-tool-implementation-plan.en.md`.

## Goals

- Build as an independent Gradle subproject and keep it out of the main product runtime path.
- Publish a standalone `tar.gz` / `zip` containing `lib/`, `bin/`, `config/`, `README.md`, and `README.en.md`.
- Support `smoke`, `nightly`, `soak`, `reopen`, `crash/recovery`, `fault-injection`, and `comprehensive` modes.
- Verify committed data with a deterministic consistency model.
- Support parent/worker crash recovery by periodically killing workers and reopening the same `workDir`.
- Support copy-based real file corruption injection. The first version copies the whole DB directory and corrupts only the copy.
- Emit machine-readable metrics periodically and generate decisive reports on normal completion or manual `report`.
- Use live data bytes exposed by LDB to calculate space amplification.

## Non-Goals

- Do not add long-running tests, 30-day soak, or fault injection to default CI.
- Do not let the longrun subproject affect the main product API, startup path, or default artifacts.
- Do not support Windows crash-kill scripts in the first version. Linux is the only formal runtime platform.
- Do not enable fault injection by default in `comprehensive`.
- Do not provide an external visualization platform in the first version. Output CSV, properties, and Markdown reports only.

## Current Flow

- The repository is currently a Gradle Java project with only the root project included in `settings.gradle`.
- The reliability plan lives in `docs/ldb-reliability-plan.md` and its English copy.
- Existing tests already cover recovery, corruption, compaction, observability, backup, repair, and tools.
- `LDBFactory.check`, tool commands, compaction properties, and capacity watermark properties can serve as a base for longrun observation and reporting.

## Core Constraints

- Keep JDK8 compatibility.
- Use `properties` profiles in the first version.
- Use `work/` as the default output root. Do not use `build/` for runtime data.
- Each profile must have an independent `workDir`.
- Java code must acquire an exclusive lock for `workDir`.
- Starting the same `instance` twice is forbidden by default.
- Fault injection must use a dedicated profile and must not be mixed into `smoke` or default `comprehensive`.
- Metrics reporters must reset baselines after resume to avoid fake throughput spikes after crash/recovery.
- Design documents, README files, and profile documentation must be maintained in both Chinese and English using UTF-8.

## Architecture

```text
ldb-longrun
  CLI / Scripts
    start / run / stop / status / logs / restart / report
  Profile Loader
    properties parsing and CLI overrides
  Instance Manager
    instance, pid, log, workDir lock
  Workload Engine
    read, write, remove, commit, scan, reopen
  Consistency Model
    checksum, sequence, version, counters, ledger
  Crash Supervisor
    parent / worker model
  Fault Injector
    copy-based file corruption
  Metrics Reporter
    ops, reclamation, fault, event metrics
  Report Analyzer
    summary.md / summary.properties
  Packaging
    standalone tar.gz / zip
```

## Directory Layout

Keep the main product in the root project and add a Gradle subproject:

```text
settings.gradle
  include 'ldb-longrun'

ldb-longrun/
  build.gradle
  src/main/java/net/xdob/vexra/ldb/longrun/
    LongRunMain.java
    cli/
    config/
    instance/
    workload/
    model/
    verify/
    crash/
    fault/
    metrics/
    report/
    util/
  src/main/resources/profiles/
    smoke.properties
    nightly.properties
    soak.properties
    reopen.properties
    crash.properties
    fault-injection.properties
    comprehensive.properties
  src/test/java/net/xdob/vexra/ldb/longrun/
  src/dist/bin/
    longrun
  src/dist/config/
    *.properties
  README.md
  README.en.md
```

Distribution layout:

```text
ldb-longrun-<version>.tar.gz
ldb-longrun-<version>.zip
  lib/
    ldb-longrun.jar
    dependency jars
  bin/
    longrun
  config/
    *.properties
  README.md
  README.en.md
```

Runtime output:

```text
work/
  smoke/
  nightly/
  soak/
  reopen/
  crash/
  fault-injection/
  comprehensive/
logs/
run/
```

## Configuration Design

Override order:

```text
built-in defaults < profile file < system properties -Dkey=value < CLI --key=value
```

Example profile:

```properties
run.name=smoke
run.instance=smoke-1
run.duration=5m
run.seed=20260602
run.workDir=work/smoke

workload.mode=mixed
workload.keySpace=100000
workload.valueSizeMin=64
workload.valueSizeMax=4096
workload.readRatio=0.55
workload.writeRatio=0.35
workload.removeRatio=0.10
workload.commitEveryOps=1000

metrics.interval=10s
state.interval=30s
check.reopenInterval=0

crash.enabled=false
crash.interval=0
crash.cycles=0

fault.enabled=false
fault.interval=0
fault.kinds=
fault.maxBytes=4096
fault.retainedCopies=5

limits.maxDbSizeGb=20
threshold.maxSizeAmplification=5.0
threshold.suspiciousLogLines=0
```

Default profile plan:

| profile | duration | workDir | reopen | crash | fault |
| --- | ---: | --- | --- | --- | --- |
| smoke | 5m | `work/smoke` | off | off | off |
| nightly | 12h | `work/nightly` | optional | off | off |
| soak | 7d/30d | `work/soak` | optional | off | off |
| reopen | 1h+ | `work/reopen` | on | off | off |
| crash | 30m+ | `work/crash` | off | on | off |
| fault-injection | 30m+ | `work/fault-injection` | off | off | on |
| comprehensive | 12h | `work/comprehensive` | on | on | off |

## CLI And Script Design

Java CLI:

```text
longrun run      --config <file> [--key=value]
longrun start    --config <file> [--key=value]
longrun stop     --instance <name>
longrun status   --instance <name>
longrun logs     --instance <name>
longrun restart  --config <file> [--key=value]
longrun report   --workDir <dir>
longrun worker   --config <file> --resume=true|false
```

Linux script behavior:

| Command | Behavior |
| --- | --- |
| `start` | Start in the background by default, write `run/<instance>.pid`, write logs to `logs/<instance>.out` |
| `run` | Run in the foreground |
| `status` | Check pid file and process liveness |
| `logs` | Tail `logs/<instance>.out` by default |
| `stop` | Stop gracefully, optionally force-stop after timeout |
| `restart` | Stop and then start |
| `report` | Re-analyze existing metrics, state, and logs |

`instance` derivation:

1. CLI `--run.instance`.
2. Profile `run.instance`.
3. Default to `run.name`.

The `workDir` lock uses `FileChannel.tryLock()` on `work/<profile>/.longrun.lock`. Lock failure exits immediately and marks the run as failed so two processes cannot write the same DB directory.

## Data Structures

Value encoding:

```text
magic
formatVersion
keyId
sequence
valueVersion
operationType
payloadLength
payloadBytes
checksumCrc32c
```

State files:

```text
state/
  committed-state.properties
  counters.csv
  ledger.log
  checkpoint.tmp
  checkpoint.committed
```

Key semantics:

- Update `committed-state` only after a successful commit.
- Checkpoints represent worker progress, not business commit facts.
- Crash recovery verifies only confirmed committed data.
- The ledger is a bounded ring of recently committed operations.

## State Machine

```text
NEW
  -> RUNNING
  -> VERIFYING
  -> REOPENING
  -> RECOVERING
  -> FAULT_INJECTING
  -> REPORTING
  -> PASS / WARN / FAIL
```

Illegal transitions:

- `FAULT_INJECTING` must not corrupt the main DB.
- `RECOVERING` must not treat an uncommitted checkpoint as required data.
- `REPORTING` must enter `FAIL` when a consistency failure is found.

## Sequence Flows

Normal run:

```text
load profile -> acquire workDir lock -> open DB -> workload loop -> periodic metrics/state -> final verify -> report
```

Reopen:

```text
commit -> close DB -> open DB -> verify -> reopenChecks++ -> continue workload
```

Crash/recovery:

```text
parent start worker -> sleep crash.interval -> kill -9 worker -> restart worker resume=true -> recovery verify -> continue
```

Fault injection:

```text
commit -> verify main DB -> close -> copy full DB dir -> corrupt copy -> readonly open copy -> classify -> reopen main DB -> verify main DB
```

## Error Handling

- A consistency failure immediately causes `FAIL`.
- Main DB damage during fault injection immediately causes `FAIL`.
- Any `UNEXPECTED_*` fault result causes `FAIL`.
- If a profile requires reopen/recovery but the corresponding check count is zero, the report is `FAIL`.
- Missing metrics are `FAIL` in key profiles and at least `WARN` in short smoke runs.
- Suspicious log patterns are configurable. The first default list includes `ERROR`, `Corruption`, `Checksum`, `panic`, `leak`, and `Exception`; the final list is pending release-process confirmation.

## Idempotency

- Repeated `stop` commands must be stable.
- `report` can be repeated and must not modify business data.
- Fault-copy retention deletes only old copies and keeps historical metrics.
- Worker resume can reload `committed-state` repeatedly without advancing committed sequence.

## Rollback Strategy

Longrun is an independent subproject and artifact. If the tool has a problem, remove the longrun artifact from the release process or skip its profiles without affecting the main product jar. If the new LDB live-data-bytes property creates compatibility risk, keep existing properties and add a new read-only property without changing current API behavior.

## Compatibility

- Longrun depends on the main product public API and read-only observability properties.
- The main product must not depend on the longrun subproject.
- No existing disk format changes are introduced.
- Default CI remains unchanged.
- The main product Maven coordinates remain unchanged.

## Rollout And Migration

1. Add the Chinese and English design documents and subproject skeleton.
2. Implement the smoke profile and report analyzer.
3. Add reopen, crash/recovery, and fault injection.
4. Add explicit longrun acceptance to the release process without default CI integration.
5. The 7-day soak report archive process is pending release-owner confirmation.

## Metrics Format

`metrics/ops.csv`:

```csv
timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks
```

`metrics/reclamation.csv`:

```csv
timeMillis,status,message,beforeFileSize,afterFileSize,shrinkBytes,fillRate,estimatedReclaimedBytes,candidateChunks,backoffCount,noProgressCount,successCount
```

`metrics/fault.csv`:

```csv
timeMillis,eventId,kind,status,message,offset,length,beforeSize,afterSize,filePath
```

`metrics/events.log`:

```text
timeMillis,type,status,message
```

Throughput rules:

- Reset the baseline whenever `workerEpoch` changes.
- Mark the first sample after crash/recovery as warmup and exclude it from formal throughput statistics.
- Do not emit formal performance conclusions when short runs have too few samples.
- Long runs use the stable-window 5th percentile and median to calculate `throughputDropRatio`.

## Report Format

Output files:

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

Required fields:

- Operations
- Commits
- Reopen Checks
- Recovery Checks
- Final Size Bytes
- Metric Samples
- Avg/Min/Max Ops/s
- Throughput Drop Ratio
- Reclamation Events
- Reclamation Success Events
- Reclamation Backoff Events
- Reclamation Shrink Bytes
- Final Size GB
- Size Per Million Ops GB
- Size Amplification
- Fault Injection Events
- Fault Injection Recovered Events
- Fault Injection Detected Events
- Fault Injection Unexpected Events
- Fault Injection Status Counts
- Fault Injection Kind Counts
- Suspicious Log Lines
- Failures
- Warnings
- Recent Events

Report status:

| Status | Meaning |
| --- | --- |
| `PASS` | All hard requirements are satisfied |
| `WARN` | Non-blocking risks exist and need human explanation |
| `FAIL` | Consistency, recovery, report, fault, or space-amplification hard requirements failed |

## Fault Injection Design

The first version uses copy-based injection and copies the whole DB directory:

1. Commit the main DB.
2. Run full verify on the main DB.
3. Close the main DB.
4. Copy the whole DB directory into `work/<profile>/fault/fault-N/`.
5. Corrupt only the copy.
6. Open the copy read-only.
7. Run business verify on the copy.
8. Classify the result.
9. Reopen the main DB.
10. Run full verify on the main DB.
11. Record the fault event.
12. Delete old copies exceeding `fault.retainedCopies`.

Corruption kinds:

| kind | Behavior |
| --- | --- |
| `truncate` | Randomly truncate the file tail |
| `bit-flip` | Randomly flip bits |
| `zero-range` | Zero a random byte range |
| `random-range` | Write random bytes into a random range |
| `partial-page` | Corrupt part of a page or block |

Result classification:

| Status | Meaning | Failure |
| --- | --- | --- |
| `RECOVERED` | The copy opens and business verification passes | No |
| `DETECTED` | The engine rejects corruption during open | No |
| `DETECTED_BY_VERIFY` | The copy opens but business verification detects corruption | No |
| `UNEXPECTED_OPEN_ERROR` | Unexpected open error | Yes |
| `UNEXPECTED_MAIN_DB_DAMAGE` | Main DB was damaged | Yes |
| `UNEXPECTED_UNCLASSIFIED` | Could not classify the result | Yes |

`RECOVERED` is not necessarily a failure because corruption may land in unused, obsolete, or recoverable regions.

## Crash/Recovery Design

Parent:

```text
load profile
start worker resume=false
sleep crash.interval
kill -9 worker
workerEpoch++
start worker resume=true
repeat until crash.cycles or duration reached
start final worker for verify/report
```

Worker:

```text
acquire workDir lock
if resume=true: recovery verify
load committed-state
run workload
periodic commit
after commit: update committed-state
periodic checkpoint
append metrics
```

Recovery semantics:

- Only confirmed committed data must be preserved.
- Checkpoints may contain uncommitted progress and must not be treated as required data.
- Increment `recoveryChecks` after each successful recovery verify.
- `recoveryChecks` must be written into the final report.

## Reopen Design

Reopen profile:

```text
run workload
commit
close DB
open DB
full/sample/ledger verify
reopenChecks++
continue
```

Acceptance:

- `reopenChecks > 0`.
- No consistency failure after reopen.
- The report must show `Reopen Checks`.

## Space And Resource Reclamation Observation

LDB exposes live data bytes, and longrun calculates:

```text
sizeAmplification = physicalSize / liveDataBytes
```

Collected data:

- Total physical size of the DB directory.
- WAL / SST / MANIFEST / LOG classified sizes.
- LDB live data bytes.
- Compaction/reclaim events.
- Before/after file size.
- Shrink bytes.
- Estimated reclaimed bytes.
- Candidate chunks.
- Backoff/no-progress/success counters.

Acceptance:

| Metric | Default Threshold |
| --- | --- |
| sizeAmplification | `<= 5x` |
| suspiciousLogLines | `0` |
| faultUnexpectedEvents | `0` |
| long run reclamation events | `> 0`, otherwise WARN |
| backoff ratio | WARN if persistently high |

## Test Plan

The `ldb-longrun` subproject must have its own JUnit tests covering:

- Configuration parsing and defaults.
- CLI and system-property overrides.
- `workDir` locking.
- Instance isolation.
- Metrics baseline reset and worker epoch handling.
- Report analysis.
- Crash/recovery state recovery.
- Reopen verification.
- Fault-kind parsing.
- File corruption injection.
- Fault-copy retention limit.
- UTF-8 documentation encoding.
- Distribution package construction.

Recommended Gradle tasks:

```text
:ldb-longrun:test
:ldb-longrun:distZip
:ldb-longrun:distTar
:ldb-longrun:longrunSmoke
```

`longrunSmoke` must still be explicit and must not enter default CI.

## Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Longrun accidentally corrupts the main DB | High | Copy-based fault injection, main-DB verify, dedicated fault profile |
| Crash metrics create fake spikes | Medium | `workerEpoch` and baseline reset |
| Live data bytes are inaccurate | Medium | Use an LDB read-only property and report the source |
| Long-run artifacts exhaust disk | High | `limits.maxDbSizeGb` and `fault.retainedCopies` |
| Default CI becomes slow | High | Keep all longrun tasks explicit |
| Suspicious log patterns false-positive | Medium | Make patterns configurable and confirm the final list before release |

## Phased Implementation Plan

| Phase | Deliverable | Validation |
| --- | --- | --- |
| P0 | Chinese and English design documents | Human review |
| P1 | Gradle subproject, CLI, profiles, distribution | `distZip` / `distTar` |
| P2 | Workload, consistency model, verification | smoke PASS |
| P3 | Metrics, report, thresholds | report unit tests |
| P4 | Reopen | reopen 1h PASS |
| P5 | Crash/recovery | crash 30m PASS |
| P6 | Fault injection | fault 30m with no unexpected results |
| P7 | Space reclamation observation | comprehensive 12h PASS |
| P8 | Soak acceptance | 24h/72h/7d PASS |

## Release Acceptance

Run at least:

| profile | Duration | Standard |
| --- | ---: | --- |
| smoke | 5m | PASS |
| reopen | 1h | PASS, `reopenChecks > 0` |
| crash/recovery | 30m | PASS, `recoveryChecks > 0` |
| fault-injection | 30m | no unexpected results |
| comprehensive | 12h | PASS |
| soak | 24h or 72h | PASS |
| formal release | 7d | at least one recommended PASS |

Hard requirements:

- No main DB consistency failure.
- No committed data loss.
- `reopenChecks > 0`.
- `recoveryChecks > 0`.
- `faultInjectionEvents > 0`.
- `faultInjectionUnexpectedEvents = 0`.
- `suspiciousLogLines = 0`.
- `sizeAmplification <= 5x`.
- Report status is `PASS`; any `WARN` must have a clear and reasonable explanation.

## Pending Decisions

- Final default suspicious log pattern list.
- 7-day soak runtime environment, owner, report archive location, and release gate process.
- LDB live data bytes property name and exact semantics.
- Whether the longrun subproject follows the main version exactly or uses a separate classifier/package name.
