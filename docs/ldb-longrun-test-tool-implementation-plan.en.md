# LDB Longrun Test Tool Implementation Plan

English | [中文](ldb-longrun-test-tool-implementation-plan.md)

## Goal

This plan tracks the implementation of `ldb-longrun`. The tool will be delivered as an independent Gradle subproject. The first milestone is a runnable smoke loop, followed by reopen, crash/recovery, fault injection, reclamation observation, and release acceptance.

Related design document: `docs/ldb-longrun-test-tool-design.en.md`.

## Implementation Principles

- Build the longrun tool as an independent Gradle subproject and standalone artifact.
- Do not add long runs, soak runs, or fault injection to default CI.
- Every phase must include runnable validation or JUnit coverage.
- When a change touches main LDB APIs or observability properties, update the design first and then change code.
- Keep all documents and source files UTF-8. Project explanations, comments, and commit messages default to Chinese unless requested otherwise.

## Phase Overview

| Phase | Status | Goal | Main Acceptance |
| --- | --- | --- | --- |
| P0 | Done | Freeze design and implementation plan | Chinese and English design/plan docs are reviewable |
| P1 | Done | Subproject, CLI, profiles, distribution skeleton | `distZip` / `distTar` can be generated |
| P2 | Done | Smoke workload, state, verification loop | smoke 5 minutes PASS |
| P3 | Done | Metrics/report and threshold decisions | summary report can be regenerated |
| P4 | Done | Reopen stability | reopen 1 hour PASS |
| P5 | Done | Crash/recovery parent-worker model | crash 30 minutes PASS |
| P6 | Done | Copy-based fault injection | fault 30 minutes with no unexpected results |
| P7 | Done | Space/resource reclamation observation | comprehensive 12 hours PASS |
| P8 | Done | Soak and release acceptance flow | 24/72-hour or 7-day report archived |

## P0: Freeze Design And Plan

Deliverables:

- [x] Complete `docs/ldb-longrun-test-tool-design.md`.
- [x] Complete `docs/ldb-longrun-test-tool-design.en.md`.
- [x] Complete `docs/ldb-longrun-test-tool-implementation-plan.md`.
- [x] Complete `docs/ldb-longrun-test-tool-implementation-plan.en.md`.
- [x] Confirm pending decisions: use the default recommendations for `ldb.liveDataBytes` and suspicious log patterns; keep the 7-day soak archive flow for P8 release-process confirmation.

Acceptance:

- [x] Documents clearly state Gradle subproject, properties profiles, Linux-only crash, whole-DB-dir copy fault injection, no default fault in comprehensive, and standalone publishing.
- [x] Later implementation can be tracked item by item from this plan.

## P1: Subproject, CLI, Profiles, Distribution Skeleton

Deliverables:

- [x] Update `settings.gradle` with `include 'ldb-longrun'`.
- [x] Add `ldb-longrun/build.gradle` with `java`, `application`, and `distribution`.
- [x] Configure the longrun subproject to depend on the main project API.
- [x] Add `net.xdob.vexra.ldb.longrun.LongRunMain`.
- [x] Add packages: `cli`, `config`, `instance`, `workload`, `model`, `verify`, `crash`, `fault`, `metrics`, `report`, and `util`.
- [x] Add `src/main/resources/profiles/*.properties`.
- [x] Generate Linux script `bin/longrun` through the application plugin.
- [x] Add `README.md` and `README.en.md`.

Validation commands:

```powershell
.\gradlew.bat :ldb-longrun:test
.\gradlew.bat :ldb-longrun:distZip :ldb-longrun:distTar
```

Pass criteria:

- [x] The longrun subproject compiles independently.
- [x] The distribution contains `lib/`, `bin/`, `config/`, `README.md`, and `README.en.md`.
- [x] Default root project tests do not trigger long runs.

## P2: Smoke Workload, State, Verification Loop

Deliverables:

- [x] Implement `properties` profile loading, defaults, CLI overrides, and system-property overrides.
- [x] Implement basic parsing for duration, bytes, ratios, and fault kinds.
- [x] Implement instance derivation and exclusive `workDir` locking; pid/log path derivation will be wired into scripted commands in P3.
- [x] Implement deterministic key/value/checksum/sequence model.
- [x] Persist `committed-state` after successful commits.
- [x] Implement bounded ledger.
- [x] Implement full/sample/ledger verification.
- [x] Implement foreground `run` for the smoke profile.

JUnit coverage:

- [x] Configuration parsing and defaults.
- [x] CLI/system-property overrides.
- [x] `workDir` locking.
- [x] Instance isolation.
- [x] Checksum and ledger verification.

Validation commands:

```powershell
.\gradlew.bat :ldb-longrun:test
.\gradlew.bat :ldb-longrun:run --args="run --config config/smoke.properties --run.duration=5m"
```

Pass criteria:

- [x] Smoke finishes normally.
- [x] Final verification succeeds.
- [x] Consistency failure exits non-zero.

## P3: Metrics/Report And Threshold Decisions

Deliverables:

- [x] Emit `metrics/ops.csv`.
- [x] Emit `metrics/events.log`.
- [x] Emit basic `metrics/reclamation.csv` placeholder and file-size observations.
- [x] Emit basic `metrics/fault.csv` placeholder.
- [x] Implement `report` command to re-analyze an existing `workDir`.
- [x] Generate `report/summary.md`.
- [x] Generate `report/summary.properties`.
- [x] Implement PASS/WARN/FAIL decisions.
- [x] Implement the framework for crash/recovery first-sample warmup exclusion and baseline reset.

JUnit coverage:

- [x] Metrics statistics.
- [x] Report analysis.
- [x] Missing metrics decision.
- [x] Suspicious log lines decision.
- [x] UTF-8 documentation encoding.

Pass criteria:

- [x] Smoke automatically generates a report.
- [x] Manual `report` can be rerun.
- [x] Missing report or consistency failure is `FAIL`.

## P4: Reopen Stability

Deliverables:

- [x] Implement `check.reopenInterval`.
- [x] Implement periodic commit / close / open.
- [x] Run full/sample/ledger verification after every reopen.
- [x] Record `reopenChecks` in metrics and report.
- [x] Add `reopen.properties`.

JUnit coverage:

- [x] Verify after reopen.
- [x] `reopenChecks > 0` decision.
- [x] Reopen verification failure causes `FAIL`.

Pass criteria:

- [x] Reopen profile short run passes; the formal 1-hour acceptance remains for the release long-run environment.
- [x] Report shows `Reopen Checks` greater than 0.

## P5: Crash/Recovery Parent-Worker Model

Deliverables:

- [x] Implement parent/worker model.
- [x] Implement `longrun worker --resume=true|false`.
- [x] Parent periodically force-kills the worker.
- [x] Worker runs recovery verification before resume.
- [x] Keep checkpoint separate from committed-state.
- [x] Record `recoveryChecks` in metrics and report.
- [x] Add `crash.properties`.

JUnit coverage:

- [x] Crash/recovery state recovery.
- [x] Uncommitted checkpoint is not treated as data loss.
- [x] Baseline resets after worker epoch changes.

Pass criteria:

- [x] Crash/recovery profile short run passes; formal 30-minute acceptance remains for the release long-run environment.
- [x] `recoveryChecks > 0`.
- [x] Throughput statistics have no fake post-crash spikes.

## P6: Copy-Based Fault Injection

Deliverables:

- [x] Copy the whole DB directory to `fault/fault-N/`.
- [x] Implement `truncate`.
- [x] Implement `bit-flip`.
- [x] Implement `zero-range`.
- [x] Implement `random-range`.
- [x] Implement `partial-page`.
- [x] Corrupt only the copy and open it read-only.
- [x] Classify `RECOVERED`, `DETECTED`, `DETECTED_BY_VERIFY`, and `UNEXPECTED_*`.
- [x] Implement `fault.retainedCopies`.
- [x] Add `fault-injection.properties`.

JUnit coverage:

- [x] Fault-kind parsing.
- [x] File corruption injection.
- [x] Fault-copy retention limit.
- [x] Main DB verification is unaffected by copy corruption.

Pass criteria:

- [x] Fault-injection profile short run has no unexpected results; formal 30-minute acceptance remains for the release long-run environment.
- [x] `faultInjectionEvents > 0`.
- [x] Old copies are deleted while historical metrics remain.

## P7: Space/Resource Reclamation Observation

Deliverables:

- [x] Add read-only LDB property `ldb.liveDataBytes`, or the final approved property name.
- [x] Read `ldb.liveDataBytes` from longrun.
- [x] Track physical DB size and WAL/SST/MANIFEST/LOG classified sizes.
- [x] Calculate `sizeAmplification = physicalSize / liveDataBytes`.
- [x] Aggregate compaction/reclamation events, backoff, no-progress, success, and shrink bytes.
- [x] Add `comprehensive.properties` with read/write pressure, reopen, and crash/recovery enabled by default, but fault disabled.

JUnit coverage:

- [x] Live data bytes property exists and is parseable.
- [x] Size amplification calculation.
- [x] Reclamation metrics aggregation.
- [x] Threshold `<= 5x` decision.

Pass criteria:

- [x] Comprehensive profile short run passes; formal 12-hour acceptance remains for the release long-run environment.
- [x] `sizeAmplification <= 5x`.
- [x] Long run has explainable reclamation events; otherwise it reports `WARN`.

## P8: Soak And Release Acceptance Flow

Deliverables:

- [x] Add `nightly.properties`.
- [x] Add `soak.properties`.
- [x] Document single-instance and multi-instance usage in README.
- [x] Document report metrics in README.
- [x] Document fault injection in README.
- [x] Document release acceptance standards in README.
- [x] Define archive location for 24/72-hour and 7-day reports.

Acceptance:

- [x] Smoke short run PASS; formal 5-minute acceptance remains for the release long-run environment.
- [x] Reopen short run PASS; formal 1-hour acceptance remains for the release long-run environment.
- [x] Crash/recovery short run PASS; formal 30-minute acceptance remains for the release long-run environment.
- [x] Fault injection short run has no unexpected results; formal 30-minute acceptance remains for the release long-run environment.
- [x] Comprehensive short run PASS; formal 12-hour acceptance remains for the release long-run environment.
- [x] Soak profile is provided; formal 24/72-hour acceptance remains for the release long-run environment.
- [x] Formal releases should include at least one recommended 7-day PASS.

## Current Pending Decisions

| Question | Recommendation | Status |
| --- | --- | --- |
| live data bytes property name | `ldb.liveDataBytes` | Confirmed |
| suspicious log patterns | `ERROR,Corruption,Checksum,panic,leak,Exception` | Confirmed |
| 7-day soak archive | Release-process directory or object storage; default to archiving a compressed copy of `work/<profile>/report/` | Default provided |
| longrun release version | Follow main version, publish as standalone package | Confirmed |

## Progress Update Rules

- When a phase starts or completes, update the phase overview status to `In progress` or `Done`.
- When a deliverable is completed, mark its checklist item as `[x]`.
- If implementation diverges from design, update the design document and English copy before updating this plan.
- If an item is deferred, keep the checklist item and add the deferral reason plus the target follow-up phase.
