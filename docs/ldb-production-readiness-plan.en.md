# LDB Production Readiness Plan

English | [中文](ldb-production-readiness-plan.md)

## Background

LDB has completed several key capabilities: plugin isolation, runtime column families, group commit, incremental backup object storage, column-family tombstone lifecycle, and long-run benchmark reporting. The next major risk is no longer a single missing feature. It is the lack of repeatable production-grade evidence: whether old-version data can be upgraded and opened, whether backup corruption is detected, whether long-lived column-family tombstones are physically collected safely, whether long-run results can become a release gate, and whether operators have a clear incident runbook.

This document records Phase 18, moving LDB from lab-ready to production-trustworthy, and acts as the acceptance baseline for later code, tests, operations documentation, and release work.

## Goals

- Define a repeatable release gate covering unit tests, compatibility samples, longrun, backup validation, and report archiving.
- Verify that data directories created by `0.4.0` and later historical versions can be opened by `0.5.0`, checked, or rejected with a clear migration error.
- Strengthen backup object-store and column-family tombstone corruption injection, long-lifecycle, and physical-GC evidence.
- Provide production users with clear runbooks for operation, backup, restore, validation, upgrade, and incident handling.
- Keep JDK 8, Gradle Wrapper, UTF-8 documents, and bilingual documentation in sync.

## Non-Goals

- This phase does not implement MergeOperator, PrefixExtractor, transactions, TTL, custom Env, or full RocksDB CLI compatibility.
- Long-running soak tests are not added to ordinary `test` by default, so day-to-day development remains fast.
- Benchmark numbers are not claimed to represent every production environment; they are only repeatable baselines for the recorded hardware, configuration, and workload.
- No new WAL/SST/MANIFEST disk-format change is introduced by this plan. Any future format change requires a focused compatibility design.

## Current Flow

| Capability | Current entry | Status |
| --- | --- | --- |
| Regular tests | `.\gradlew.bat test` | Covers core API, recovery, backup, column families, tool commands, and plugin tests |
| Local publication artifacts | `.\gradlew.bat clean publishToMavenLocal` | Generates jar, sources, javadoc, pom, and module files |
| Longrun reports | `:ldb-longrun:benchmarkReport`, `:ldb-longrun:longRunTest`, `:ldb-longrun:releaseSoakTest` | Already emits `summary.json`, `operations.csv`, `failures.json`, and before/after property snapshots |
| Incremental backup | `LDBFactory.createIncrementalBackup/checkBackup/planPurgeBackups` | Has `objects/`, `OBJECT-REFS.json`, and dry-run cleanup planning |
| Column-family lifecycle | runtime list/create/empty-drop, non-empty drop tombstones, rename | Stable cfId and tombstone semantics are supported, but long-lifecycle proof is still needed |
| Release guide | `docs/release.md`, `CHANGELOG.md` | Records 0.5.0 pre-release checks, but lacks a unified production gate |

## Core Constraints

- All changes remain JDK 8 compatible.
- Release gates must be explicit and should not make ordinary development tests run long soak workloads by default.
- Compatibility tests must use read-only fixtures or temporary copies.
- Corruption injection may only target temporary directories or test copies, never real backup roots.
- Each reviewable increment should be committed locally when complete.
- Design, README, Release, Changelog, and operations documents must keep English copies and language-switch links.

## Interface Design

| Entry | Type | Planned responsibility | Output/failure semantics |
| --- | --- | --- | --- |
| `releaseGate` | Gradle task | Aggregate short gates: `test`, old-version fixture open, backup validation, lightweight longrun profile, and report generation | Non-zero on failure; keeps `build/reports/ldb-release-gate/` |
| `:test --tests "*Upgrade*"` | Test filter | Validate historical fixture open, read, check, and backup/restore | Failures include version, fixture name, and phase |
| `:ldb-longrun:releaseSoakTest` | Gradle task | Run the release soak profile and validate compaction, backup, snapshot, plugins, and report stability | Keeps report directory and archives `failures.json` on failure |
| `LDBFactory.checkBackup` | API/tool foundation | Validate backup directory, object-store references, and manifest consistency | Corrupt inputs return `ok=false` or test assertions |
| `LDBFactory.planPurgeBackups` | API/tool foundation | Dry-run the GC impact of backup object storage | Produces no delete side effects |
| `docs/operations.md` | Operations document | Describe production startup, backup, restore, upgrade, check, and incident handling | Maintained in Chinese and English |

## Data Structures

### Release Gate Report

Plan to add `RELEASE-GATE-REPORT.json` and a Markdown summary in the same directory:

| Field | Meaning |
| --- | --- |
| `version` | Current project version |
| `commit` | Git commit used for the gate run |
| `javaVersion` | JDK version |
| `startedAt` / `finishedAt` | Gate start and finish time |
| `gates[]` | Name, command, duration, result, and report path for each gate |
| `artifacts[]` | Archived paths for longrun, backup, compatibility fixture, and test reports |
| `failures[]` | Failed gate, exception summary, and suggested diagnostic entry |
| `result` | `PASSED` or `FAILED` |

### Old-Version Upgrade Fixtures

Historical fixtures should be stored as test resources or generated test fixtures. Recommended layout:

```text
src/test/resources/upgrade/
  0.4.0/
    README.md
    basic-db.zip
    column-family-db.zip
    backup-root.zip
```

Each fixture must record the creation version, creation command, expected key/value data, column-family set, and backup validation expectation.

## State Machine

| State | Trigger | Next state |
| --- | --- | --- |
| `PLANNED` | Documents are recorded and phase scope is confirmed | `IMPLEMENTING` |
| `IMPLEMENTING` | An 18.x increment starts implementation | `GATE_RUNNING` or `PLANNED` |
| `GATE_RUNNING` | release gate, longrun, or corruption matrix is running | `PASSED` or `FAILED` |
| `PASSED` | All required gates pass and reports are archived | `RELEASE_ALLOWED` |
| `FAILED` | Any required gate fails | `RELEASE_BLOCKED` |
| `RELEASE_BLOCKED` | Fixes are applied and gates are rerun | `GATE_RUNNING` |

Illegal transition: `IMPLEMENTING` must not move directly to `RELEASE_ALLOWED` without running gates; a failed phase must not be closed without archived reports.

## Sequence Flow

1. Record this document plus the reliability plan, project design, README, Release guide, and Changelog.
2. 18.1 adds old-version upgrade fixtures and compatibility tests.
3. 18.2 adds the `releaseGate` aggregate task and machine-readable report.
4. 18.3 completes backup object-store corruption injection.
5. 18.4 completes column-family tombstone long-lifecycle stress and physical-GC proof.
6. 18.5 defines the production-gate longrun profile and benchmark report archival rules.
7. 18.6 adds operations runbooks and points the formal release checklist at the production gate.

## Error Handling

- `releaseGate` returns non-zero when any sub-gate fails and preserves reports already generated.
- Old-version fixture open failures must distinguish “incompatible with a clear migration error” from “unknown recovery error.”
- Backup object-store corruption injection must verify that check/restore does not create partial target directories.
- Longrun failures must preserve workload config, property snapshots, failure summary, and relevant log paths.
- Operations runbooks must use a conservative order: stop further writes, create checkpoint/backup when possible, then check/repair.

## Idempotency

- `releaseGate` writes reports into a timestamped or build-numbered directory, so repeated runs do not overwrite previous conclusions.
- Old-version fixtures are read-only zip files or copied into temporary directories before testing.
- Corruption injection always runs on temporary copies and cleans the test workDir before reruns.
- `planPurgeBackups` defaults to dry-run and does not mutate object-store reference counts.
- Longrun profiles use explicit workDir configuration and preserve failed workDirs for diagnostics.

## Rollback Strategy

- This document itself changes no disk format or runtime behavior and can be reverted as documentation.
- If a new `releaseGate` step is too slow or flaky, it can be temporarily removed from the aggregate task while retaining the standalone task and risk record.
- If an old-version fixture reveals real incompatibility, block the release or document migration steps, downgrade boundaries, and recovery behavior in the release note.
- If backup or longrun exposes a stability defect, preserve the failing sample and report before fixing.

## Compatibility

- Before publishing `0.5.0`, at least `0.4.0` fixtures must be validated. Later versions should keep the most recent formal version and one long-lived fixture.
- When the new version can read old data, it must validate point gets, snapshot cursor, column-family registry, check, and backup/restore.
- If a future disk format is introduced, old versions opening new databases must fail fast instead of silently corrupting data.
- All gates and scripts must keep Windows PowerShell and common Linux shell execution paths clear.

## Rollout and Migration

| Order | Content | Release impact |
| --- | --- | --- |
| 18.1 | Old-version upgrade fixtures | Hard pre-release gate for 0.5.0 |
| 18.2 | `releaseGate` aggregate task | Unified pre-release entry |
| 18.3 | Backup object-store corruption injection | Improves backup/restore trust |
| 18.4 | Column-family tombstone long stress | Proves long-term cleanup safety after drop/rename |
| 18.5 | production-gate longrun profile | Stabilizes performance and reliability regression reports |
| 18.6 | Operations runbook | Completes production-facing operations guidance |

## Test Plan

- Unit tests: report JSON serialization, gate result aggregation, failure summaries.
- Compatibility tests: old-version fixture open, read, check, and backup/restore.
- Fault injection: missing object files, wrong reference counts, corrupt manifest, orphan objects, restore target rollback.
- Long-lifecycle tests: column-family drop/rename across reopen, compaction, backup, repair, and snapshot cursor.
- Longrun: short gate profile, release soak profile, and failure report archiving.
- Release verification: `.\gradlew.bat test`, `.\gradlew.bat clean publishToMavenLocal`, and `.\gradlew.bat releaseGate`.

## Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| release gate becomes too slow for development | Medium | Split into short CI gate, release gate, and nightly soak |
| Historical fixtures drift or cannot be reproduced | High | Record fixture description, creation version, expected data, and validation commands |
| Benchmark thresholds fluctuate by hardware | Medium | Record environment info; make stability and error rate hard gates first, keep performance thresholds conservative |
| Corruption injection accidentally targets real data | High | Only allow temporary copies and test workDirs; explicitly forbid injection on real directories |
| Runbook drifts from tool behavior | Medium | Release checklist requires operations docs to be checked |

## Phased Implementation Plan

| Phase | Title | Deliverables | Acceptance |
| --- | --- | --- | --- |
| 18.1 | Old-version upgrade compatibility fixtures | `0.4.0` fixtures, compatibility tests, fixture README | Done: new version opens and validates fixtures, or emits a clear migration error |
| 18.2 | Production-grade `releaseGate` aggregate task | Gradle task, `RELEASE-GATE-REPORT.json`, Markdown summary | Done: any failed gate fails the aggregate task and preserves reports |
| 18.3 | Backup object-store corruption matrix | Tests for missing objects, bad refs, orphan objects, corrupt manifest, and restore rollback | Done: `checkBackup`/restore fail fast on corrupt input and do not pollute targets |
| 18.4 | Column-family tombstone long stress and physical-GC proof | Long-lifecycle drop/rename/reopen/compact/backup/repair tests | Done: tombstones do not break old snapshots and cfIds are not reused; more aggressive physical GC remains future hardening |
| 18.5 | production-gate longrun profile | Release-gate profile and benchmark report archival rules | Done: reports are reviewable, and failures preserve workload plus property snapshots |
| 18.6 | Operations manual and incident runbook | `docs/operations.md` and English copy | Done: covers backup, restore, upgrade, check, repair, and pre-release gates |

## Phase Status And Next Stages

Phase 18 now has the minimal `0.5.0` pre-release closed loop. Future work should not treat 18.1-18.6 as pending planning items; they are release gates to rerun and archive continuously.

The next stages move to Phases 19-22 in `docs/ldb-reliability-plan.md`:

| Phase | Title | Production value |
| --- | --- | --- |
| 19 | Checkpoint/backup production evidence | Prove cross-filesystem behavior, low-disk failures, permission failures, long backup chains, and report archival |
| 20 | WAL lifecycle and production write policy | Define WAL archive/retention/cleanup rules and establish long group-commit baselines |
| 21 | Operational ecosystem and external observability | Unify CLI/report indexing and feed external metrics or trend analysis |
| 22 | RocksDB advanced API compatibility reviews | Review MergeOperator, PrefixExtractor, transactions, TTL, custom Env, and similar advanced features independently |
