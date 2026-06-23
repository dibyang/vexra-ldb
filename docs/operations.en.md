# LDB Operations Runbook

English | [中文](operations.md)

This document is for production-readiness validation and day-to-day operations of `vexra-ldb`. Exercise commands with file-system side effects on a copy, checkpoint, or backup first. In real incidents, preserve evidence before running repair.

## Basic Principles

- Stop further writes before checkpoint, backup, check, or repair.
- `check`, `properties`, `scan`, `check-backup`, and `repair-plan` are read-only diagnostics.
- `repair`, `backup`, `incremental-backup`, `restore`, and `checkpoint` create file-system side effects. Confirm directories, permissions, and free disk space first.
- Archive `BACKUP-REPORT.json`, `RESTORE-REPORT.json`, `BACKUP-MANIFEST.json`, `OBJECT-REFS.json`, and longrun/release-gate reports.
- Do not delete failed samples when old-version upgrades, column-family tombstones, object-store corruption, or longrun failures are involved.

## Pre-Release Gates

Run at least:

```powershell
.\gradlew.bat releaseGate
.\gradlew.bat clean publishToMavenLocal
```

`releaseGate` aggregates:

- Regular unit tests.
- The `0.4.0` old-version upgrade compatibility fixture.
- The production-gate longrun profile.
- `storageFormatGates` for storage-format release evidence, covering SST/table properties, compatibility policy, future/malformed version fail-fast behavior, check/repair/backup/plugin evidence, and the default v1 write policy.
- `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json` and a Markdown summary.

Formal release candidates should also run a longer production-gate or soak:

```powershell
.\gradlew.bat :ldb-longrun:productionGateLongRun "-Pldb.longrun.durationMinutes=30"
.\gradlew.bat :ldb-longrun:releaseSoakTest "-Pldb.longrun.durationMinutes=1440"
```

## Upgrade Flow

1. Stop writes on the old version and create a backup or checkpoint.
2. Run `check` with the new version on a copy.
3. Open the copy with the new version and verify key data, column families, snapshot cursor, and business read paths.
4. Run a backup/restore loop on the copy.
5. Archive `ldb.tableFormat`, `ldb.storageFormat`, `CheckReport.storageFormat`, and `RepairReport.storageFormat`, confirming old SSTs are classified as v1 legacy and v2 SSTs only come from explicit opt-in.
6. Switch the real database only after validation passes. On failure, preserve the old database copy, `RELEASE-GATE-REPORT.json`, and check reports.

The hard old-version compatibility gate is covered by `LdbUpgradeCompatibilityTest`. If `vexra-ldb-0.4.0.jar` is missing locally, install the formal 0.4.0 artifact into the local Maven cache first.

## Backup

Full backup:

```text
ldb backup <db> <backupRoot>
```

Incremental backup:

```text
ldb incremental-backup <db> <backupRoot>
```

Always validate after backup:

```text
ldb check-backup <backupDir>
```

Checkpoint:

```text
ldb checkpoint <db> <targetDir>
```

Notes:

- Place `backupRoot` on a separate disk or mount when possible.
- Incremental backups maintain `objects/` and `OBJECT-REFS.json`; run `planPurgeBackups` or the matching dry-run process before cleanup.
- If the object store has missing objects, wrong reference counts, orphan objects, or a corrupt manifest, `check-backup` must fail and restore must not continue.

## Restore

Restore to an empty directory:

```text
ldb restore <backupDir> <targetDir>
```

Validate after restore:

```text
ldb check <targetDir>
ldb properties <targetDir>
```

On restore failure:

- Do not manually patch a partial target directory.
- Preserve `RESTORE-REPORT.json`, the backup directory, and target-directory state.
- If the target directory was not created, restore failed fast during validation. Fix the backup source first.

## Check And Repair

Read-only diagnostics:

```text
ldb check <db>
ldb properties <db> ldb.tableFormat ldb.storageFormat
ldb properties <db> [property...]
ldb scan <db> [limit]
ldb repair-plan <db>
```

`scan` reads only the default column family and emits a key-ordered JSON sample. The default limit is 100, and key/value bytes are base64-encoded for incident diagnostics.

Repair:

```text
ldb repair <db>
```

Recommended flow:

1. Stop writes and preserve a snapshot of the original directory.
2. Run `check` and `repair-plan`.
3. Archive `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables` so mixed-format state or future-format fail-fast behavior is explainable.
4. Copy the original database to an isolated directory.
5. Run `repair` on the isolated directory.
6. Open the repaired directory and verify critical business data.
7. Replace the real database or switch through business migration only after validation passes.

## Column-Family Tombstones

- Dropped cfIds must not be reused, even after physical data cleanup.
- Dropped records in `COLUMN-FAMILIES` are part of recovery history and must not be edited out manually.
- backup, restore, check, and repair must preserve and understand dropped records so historical MANIFEST/WAL entries are not misclassified as unknown column families.
- If `Unknown column family id` appears, first verify whether `COLUMN-FAMILIES` is missing or edited.

## Incident Order

| Scenario | First action | Do not |
| --- | --- | --- |
| Open fails | Stop writes, copy the directory, run `check` | Repeatedly repair the original directory |
| Backup validation fails | Preserve backup root, run `check-backup`, inspect `OBJECT-REFS.json` | Delete `objects/` or hand-edit ref counts |
| Restore fails | Preserve `RESTORE-REPORT.json`, retry with an empty directory | Overwrite an existing target directory |
| File-format anomaly | Archive `ldb.tableFormat`, `ldb.storageFormat`, and check/repair storage-format fields | Disable `failOnUnknownTableFeature` and continue production writes |
| longrun fails | Archive workDir, `report/`, and logs | Delete the failed workDir before diagnosis |
| Old-version upgrade fails | Preserve old DB copy and release-gate report | Write to the old DB with the new version |

## Evidence To Archive

- `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json`
- `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.md`
- `ldb-longrun/build/reports/ldb-longrun/*/report/`
- `BACKUP-REPORT.json`
- `RESTORE-REPORT.json`
- `BACKUP-MANIFEST.json`
- `OBJECT-REFS.json`
- `REPAIR-REPORT.json` or `repair-plan` output
- `ldb.tableFormat` and `ldb.storageFormat`
- `CheckReport.storageFormat`, `tableFormats`, `legacyTables`, `v2Tables`, and `incompatibleTables`
- `RepairReport.storageFormat`, `tableFormats`, `legacyTables`, `v2Tables`, and `incompatibleTables`
## 0.9.0-SNAPSHOT SF-06: v2 storage-format production check

Before enabling v2 writes in production, archive `ldb.tableFormatPolicy`, `ldb.tableFormat`, and `ldb.storageFormat`. Before enablement, expect `productionState=default-legacy`; after enablement, expect `productionState=explicit-v2` and `newWrites=v2-properties`. To roll back new writes, restore `Options.tableFormatVersion(1)` and archive `newWrites=v1` again. `failOnUnknownTableFeature=false` is diagnostic-only and must not be used as a production rollback strategy.

## readrandom / Bloom Filter Pre-Release Check

If the release targets random-read miss optimization, archive `ldb.sstReadStats` after a benchmark or gate that enables `BloomFilterPolicy`. Expect at least `mayContainRequests>0`; for in-range missing-key tests, expect `mayContainFalse>0` and `filterSkips>0`. If `filterSkips` remains 0, check whether the test key falls outside all SST ranges, whether the filter policy was not configured, or whether the SSTs were written by older options.

## v3 Block-Local Index Operations

- `writeBlockLocalIndex` remains explicit opt-in and is not a default write policy.
- For online observation, start with `ldb.sstReadStats`: `blockLocalIndexSeekCount`, `blockLocalIndexHitCount`, `blockLocalIndexFallbackCount`, and `blockLocalIndexDirectoryLoadedTables`.
- If fallback counts keep increasing, run offline `check` first to collect `BLOCK_LOCAL_INDEX_*` classes, then decide whether to stop v3 compaction/flush, roll new writes back to v1/v2, or recover from checkpoint/backup.
- Point get and MultiGet fall back to ordinary data-block seek when local-index data is corrupt. This fallback is a degradation guard, not proof that the file format is healthy.
### Pre-Default Observation For Block-Local Indexes

When deciding whether v3 block-local indexes can be enabled more broadly, operations should not look only at hit count. Check `blockLocalIndexSpaceAmplificationPpm`, `blockLocalIndexSkippedBlocks`, and scan benchmarks together. If ppm is high or scan regresses, keep `writeBlockLocalIndex=false` as the default and only opt in workloads with clear evidence.

The fixed pre-release comparison entry point is `.\gradlew.bat :ldb-longrun:ldbBlockLocalIndexComparisonReport`. It generates separate baseline and v3 candidate reports and covers `cold_readrandom`, `multiget_random`, `multiget_sameblock`, and `scan` by default. Formal reviews should archive both output directories' `summary.json`, `results.csv`, and `tableFormatStats`, then combine those results with `blockLocalIndexSpaceAmplificationPpm` before deciding whether the feature remains opt-in.
