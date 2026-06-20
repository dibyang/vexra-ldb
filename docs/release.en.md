# Release Guide

English | [中文](release.md)

This document describes the release preparation and Maven publication flow for `vexra ldb`. Before an actual release, confirm the version, tag, tests, and signing configuration.

## Pre Release Checklist

1. Confirm the working tree is clean.
2. Update `CHANGELOG.md` and move `Unreleased` entries into the target version.
3. Confirm README, design documents, and API documents match the code.
4. Confirm external commitments and upgrade gates:

  `docs/vexra ldb external commitment.md`
  `docs/ldb production readiness plan.md`
  `docs/operations.md`
  `docs/ldb plugin docs index.md`
  `docs/ldb plugin roadmap.md`
  `ldb longrun/README.md`

5. Run the full test suite:

```bash
./gradlew clean test
```

Windows PowerShell:

```powershell
.\gradlew.bat clean test
```

6. Generate local publication artifacts:

```bash
./gradlew clean publishToMavenLocal
```

## 0.6.0 Pre Release Verification Record

  Target version: `0.6.0`.
  Historical release prep state: `gradle.properties` was set to `version=0.6.0`; after publication, switch back to the next SNAPSHOT for continued development.
  RocksDB comparison baseline: before release, confirm `docs/ldb rocksdb gap next version plan.md` and its English copy still record the current comparison version, work package state, open question default decisions, and non goal boundaries.
  Changelog: `CHANGELOG.md` has been archived to `0.6.0`; further changes should go back under `Unreleased`.
  Required gates: `.\gradlew.bat clean test`, `.\gradlew.bat releaseGate`, and `.\gradlew.bat clean publishToMavenLocal`.
  API compatibility evidence: confirm `LdbApiCompatibilityTest` covers `multiGet`, `ldb.api.rocksdbGapPlan`, `ldb.recoveryEvidence`, `ldb.backupEvidence`, `ldb.columnFamilyEvidence`, `ldb.prefixReadiness`, and `ldbToolScan`.
  Tool evidence: confirm `LdbToolTest` covers default `properties` export, read only `scan <db> [limit]` base64 JSON, bad argument exit codes, and no database directory mutation.
  Recovery and backup evidence: archive `LDBFactory.check`, `repair plan`, `checkBackup`, backup/restore, checkpoint, and object store validation reports; failed samples must not be deleted.
  Operations closure: before release, confirm `docs/operations.md`, `docs/user manual.md`, README, and the API compatibility design all document `scan`, `prefixReadiness`, RocksDB gap default decisions, and unsupported capability boundaries.
  Long run policy: the short `releaseGate` is the hard gate; nightly/24h soak evidence is archived before release candidates, and any missing long run evidence must be called out in the release note with a rerun plan.
  Do not publish if `releaseGate` fails, open question defaults conflict with business confirmation, unsupported features are silently ignored by an adapter, or recovery/backup/scan evidence is missing.

## 0.5.0 Pre Release Verification Record

  Target version: `0.5.0`.
  Historical development baseline: `gradle.properties` remained `version=0.5.0-SNAPSHOT`; the Gradle release plugin switched to `0.5.0` during the release flow.
  Changelog: `CHANGELOG.md` still keeps the current changes under `Unreleased`; move them into `0.5.0` before the formal release.
  Main release themes: plugin classloader isolation, plugin compatibility testkit, capability enforcement hardening, plugin runtime governance, longrun plugin integration, runtime column family support, group commit, incremental backup, repair plan, and observability/reporting updates.
  Focused plugin verification: `.\gradlew.bat test   tests "*LdbPluginTest"   tests "*LongRunPluginResolverTest"   tests "*LongRunConfigTest"   tests "*SmokeRunnerTest"   tests "*ReportAnalyzerTest"` passed on Windows PowerShell.
  Full pre release gate still required before publishing: `.\gradlew.bat clean test`.
  Local publication gate still required before publishing: `.\gradlew.bat clean publishToMavenLocal`.
  Upgrade compatibility gate: validate opening data created by `vexra ldb:0.4.0` or document a clear migration error in the release note before publication.
  Longrun release gate: run at least the documented smoke/performance/plugin profiles and keep report archives according to `ldb longrun/README.md`.
  Production release gate: confirm 18.1 18.6 in `docs/ldb production readiness plan.md` are complete, then rerun `releaseGate`, old database upgrade checks, backup corruption injection, column family tombstone long stress, and the production gate longrun profile. Do not publish a formal release until the gates pass and reports are archived.

## 0.2.0 Pre Release Verification Record

  Version: `gradle.properties` is set to `version=0.2.0`.
  Tests: `.\gradlew.bat clean test` was run in Windows PowerShell and passed.
  Local publication: `.\gradlew.bat clean publishToMavenLocal` was run and passed.
  Local Maven artifacts: confirmed `vexra ldb 0.2.0.jar`, `sources.jar`, `javadoc.jar`, `.pom`, `.module`, and matching `.asc` signature files were generated.
  CI: `.github/workflows/ci.yml` is included and covers Ubuntu/Windows JDK 8 `clean test` plus Ubuntu `clean publishToMavenLocal`.
  Changelog: `CHANGELOG.md` has moved this release's changes into `0.2.0`.

## Signing Configuration

The project reads the uncommitted `signing.properties` file from the repository root. This file is ignored by `.gitignore` and must not be committed.

Example:

```properties
signing.keyId=xxxxxxxx
signing.password=your password
signing.secretKeyRingFile=/path/to/secring.gpg

ossrhUsername=your username
ossrhPassword=your password
```

## rublication Repository Configuration

rublication repositories can be configured through Gradle properties:

```properties
snapshotsRepository=https://s01.oss.sonatype.org/content/repositories/snapshots/
releasesRepository=https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/
```

If these properties are not configured, Gradle publishes to `build/repos/snapshots` or `build/repos/releases` for local verification and does not upload to a remote repository.

## Version Release

The project uses the `net.researchgate.release` plugin. The release configuration requires the branch to match `master|v.*`.

Typical flow:

```bash
./gradlew release
```

After release, confirm:

  The Git tag has been created using the `v${version}` format.
  Maven artifacts include the main jar, sources jar, and javadoc jar.
  The rOM includes project name, description, license, developer, SCM, and issue tracker metadata.
  Signature files are complete in the publication repository.

## Rollback

  If remote publication fails before becoming public, delete the staging repository or clean local `build/repos`.
  If the tag has been pushed but artifacts were not published, delete the incorrect tag and release again.
  If artifacts have already been publicly released, do not overwrite the same version. rublish a new fixed version instead.

## 0.6.0 pre release addendum: deeper MultiGet optimization

This version completes the deeper MultiGet miss path optimization without changing the SST/table file format. L0 now batches unresolved keys across files from newest to oldest, non L0 levels group keys by SST file, and a single iterator is reused within each SST file for multiple keys.

Pre release 200k evidence:

| Item | Result |
|     |     |
| LDB read_optimized multiget_random | 200,302.818 ops/s |
| RocksDB JNI multiget_random | 243,015.078 ops/s |
| LDB/RocksDB JNI ratio | 82.42% |
| LDB tableReads | 34,315 |
| LDB iteratorRequests | 34,394 |
| LDB candidateFiles | 200,000 |

Evidence files:

  `ldb longrun/build/reports/ldb multiget optimized 200k/ldb db bench summary.json`
  `build/reports/rocksdbjni comparison multiget optimized 200k/comparison.csv`

Remaining file format gaps are deferred to the next version: the current SST/table format still lacks common RocksDB/LevelDB capabilities such as key prefix compression, restart points, filter block/metaindex, properties block, compression blocks, partitioned index/filter, and range tombstone/merge operand format support. This version is limited to read path fixes, profiling accuracy, and release evidence traceability.
## 0.6.0 pre release full gate result

Executed the full pre release gate:

```powershell
.\gradlew.bat clean test releaseGate publishToMavenLocal
```

Result: `BUILD SUCCESSFUL`.

Passed items:

| Item | Result |
|     |     |
| Main module tests | PASS |
| `ldb longrun:test` | PASS |
| `releaseGateUnitTest` | PASS |
| `upgradeCompatibilityTest` | PASS |
| `productionGateLongRun` | PASS |
| `releaseGate` | PASS |
| `publishToMavenLocal` | PASS |

`productionGateLongRun` summary: `SUMMARY status=PASS`, workload operations=3,592, final verify activeKeys=1,621.

Release decision: the current version has completed the full pre release gate and can move into release finalization.

Known non blocking items:

  Gradle deprecated feature warnings remain and should be handled in a later Gradle 8 compatibility pass.
  Java deprecated/unchecked API compilation warnings remain.
  Windows directory force may emit an `AccessDeniedException` warning, but this gate completed successfully and does not block the current release.
## 0.7.0 formal release upload record

Release version: `0.7.0`.

Command:

```powershell
.\gradlew.bat clean releaseGate publish
```

Result: `BUILD SUCCESSFUL`.

Pre release gate:

| Item | Result |
|     |     |
| `releaseGateUnitTest` | PASS |
| `upgradeCompatibilityTest` | PASS |
| `productionGateLongRun` | PASS |
| `releaseGate` | PASS |
| `publishMavenPublicationToMavenRepository` | PASS |
| `publish` | PASS |

`productionGateLongRun` summary: `SUMMARY status=PASS`, operations=3,589, reads=1,610, writes=1,620, removes=359, activeKeys=1,620.

Release status: the `0.7.0` artifacts have been uploaded to the central repository release endpoint and are waiting for manual review / follow up release confirmation.

Next development version: after the upload, the workspace version has been advanced to `0.8.0-SNAPSHOT`.

Known non blocking items remain: Gradle deprecated feature warnings, Java deprecated/unchecked API warnings, and the Windows directory force `AccessDeniedException` warning.
## 0.8.0 Development Line Goal

The `0.8.0-SNAPSHOT` development line focuses on file format improvement and evolution. Design entry points:

  `docs/storage-format-0.8-design.md`
  `docs/storage-format-0.8-design.en.md`

Release acceptance direction:

  New versions must open old databases.
  Old SSTs must be classified as v1 legacy.
  Initial v2 SSTs must self describe through properties block, format version, and feature set.
  Unknown incompatible features must fail fast.
  check/repair/report must explain format version, features, properties, and checksum failure classes.
  release gate includes a `storageFormatGates` group for release-auditable storage-format evidence.
## 0.8.0 SF 01 Storage Format Reference Docs

Completed SF 01 for the `0.8.0-SNAPSHOT` storage format evolution workstream:

  `docs/storage-format.md`
  `docs/storage-format.en.md`

These reference documents record the current WAL, SST/table, MANIFEST, CURRENT, COLUMN FAMILIES, backup metadata, and check/repair behavior. They are the factual baseline for table format v2, properties blocks, feature sets, and release gate `storageFormatGates`.
## 0.8.0 SF 02/SF 03 Reader Skeleton

Completed the reader side skeleton for table properties block reading and feature set fail fast behavior. Old SSTs are classified as v1 legacy. If a new SST provides a metaindex `properties` entry, table open parses `formatVersion`, compatible features, and incompatible features.

This reader increment did not change the default SST/table write format. Later 0.8 increments added the v2 opt-in writer, check/repair/report output, and release gate `storageFormatGates`; the default remains v1.
## 0.8.0 SF 04 v2 Properties Block Opt In Writes

Completed the opt in writer skeleton for v2 properties blocks. The default remains v1. Only explicit `Options.tableFormatVersion(2)` makes TableBuilder write a properties block and add a `properties` entry to the metaindex.

Current properties cover format version, created_by, compatible/incompatible features, entry count, data block count, index type, filter policy/scope, compression, smallest/largest key, and checksum strategy.

This increment was later completed by focused tests, check/repair/report output, backup metadata schema evidence, plugin read-only option visibility, and release gate `storageFormatGates`. Final pre-release work is to execute the validation commands and archive their results.
## 0.8.0 SF 04 Focused Test Status

Added `TablePropertiesTest` to cover v1 legacy classification, v2 opt in properties write/read behavior, unknown incompatible feature fail fast behavior, and `tableFormatVersion` option boundaries.

Pre release validation pending: run the focused storage-format tests and `releaseGate`, then archive the `storageFormatGates` evidence.
## 0.8.0 SF 05 Diagnostic Property First Increment

Added runtime diagnostic properties:

  `ldb.tableFormat`
  `ldb.storageFormat`

They expose SST/table format version, legacy/v2 counts, feature sets, and current format policy for WAL/MANIFEST/CURRENT/COLUMN FAMILIES/backup metadata. Observability tests now cover both default v1 and v2 opt in summaries.

Later 0.8 increments completed this work: `LdbTool check` output, structured check/repair report fields, and release gate `storageFormatGates` are connected.
## 0.8.0 SF 05 Check Report Fields

Offline check reports now include storage format evidence:

  `storageFormat`
  `tableFormats`
  `legacyTables`
  `v2Tables`
  `incompatibleTables`

`LdbTool check` naturally emits these JSON fields. `LdbVerifyCheckTest` covers check report evidence for v2 opt in SSTs.

Later 0.8 increments completed this work: release gate `storageFormatGates` is connected, and repair reports now carry dedicated structured storage format fields.
## 0.8.0 SF 05 Release Gate Integration

`releaseGate` now includes a `storageFormatGates` group and uses it in the overall PASS/FAILED decision. Current gates cover:

  Existence of storage format reference docs and the 0.8 design docs.
  `TablePropertiesTest` coverage for v1 legacy, v2 opt in, and incompatible feature fail fast behavior.
  `LdbVerifyCheckTest` coverage for check report storage format fields.
  Default writes remain table format v1, while v2 properties blocks require explicit opt in.

Report outputs: `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json` and `.md`.
## 0.8.0-SNAPSHOT Storage Format Release Preparation Addendum

  Backup manifests now include `schemaVersion`, stable `chainId`, and `generation`; object reference metadata now includes `schemaVersion/objectStoreVersion/generatedBy`, allowing release checks to identify the backup metadata schema explicitly.
  Incremental backup chains inherit `chainId` from the parent manifest, reducing the risk of diagnosing a multi generation chain as multiple unrelated chains.
  Backup format regression assertions have been added. No test command was run in this pass; the release gate should run before publication.
## 0.8.0-SNAPSHOT Repair Format Report Addendum

- `REPAIR-REPORT.json` and `repair-plan` output now include `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables`, making SST/table format state observable during repair.
- `storageFormatGates` now includes `repairReportStorageFormatEvidence`, complementing check and backup schema gates for offline tool format evidence.
## 0.8.0-SNAPSHOT v2 Repair Format Preservation Addendum

- Added a v2 SST repair regression scenario showing that repair rebuilds metadata from an existing v2 SST without downgrading or rewriting it in place, while preserving `formatVersion=2/table.properties/v2Tables` evidence in the report.
## 0.8.0-SNAPSHOT Mixed-Format Check Addendum

- Added a mixed v1/v2 SST check regression scenario. Release gate `mixedFormatCheckCoverage` covers `legacyTables/v2Tables/tableFormats` evidence.

## 0.8.0-SNAPSHOT Storage Format Acceptance Matrix

- Added `docs/storage-format-0.8-acceptance.md` and its English copy, centralizing release evidence for SST/table self-description, feature sets, legacy compatibility, mixed format, check/repair/backup, runtime observability, and release gates.
- Extended the `storageFormatDocs` gate to require the acceptance matrix and to check that both language copies include `storageFormatGates` and `mixedFormatCheckCoverage`.
- Added a `storageFormatGates` blocking-item table to the acceptance matrix, and extended `storageFormatDocs` to check all storage format gate names to avoid release-audit omissions.
- Added table-format read-only getters to `OptionsView`, allowing plugins to observe `tableFormatVersion/writeTableProperties/allowLegacyTableFormat/failOnUnknownTableFeature`, covered by `LdbPluginTest`; the new getters are Java 8 default methods with v1/legacy/fail-fast defaults.
- Extended the `storageFormatDocs` gate to check `OptionsView` and `failOnUnknownTableFeature` evidence in the acceptance matrix, preventing plugin-observability documentation omissions.

## 0.8.0-SNAPSHOT OptionsView Storage-Format Snapshot Fix

- OptionsSnapshot now captures and overrides `tableFormatVersion/writeTableProperties/allowLegacyTableFormat/failOnUnknownTableFeature`, so plugins observe the actual storage-format policy from database open time instead of interface defaults.
- This does not change the on-disk format; it strengthens the implementation evidence for the storageFormatDocs / OptionsView acceptance item.

## 0.8.0-SNAPSHOT pluginOptionsViewCoverage Gate

- Added `pluginOptionsViewCoverage` to `storageFormatGates`, promoting the `OptionsView` storage-format policy snapshot coverage in `LdbPluginTest` to an independent release gate.
- `storageFormatDocs` now also requires the acceptance matrix to include this gate name, preventing plugin observability from remaining documentation-only evidence.

## 0.8.0-SNAPSHOT Future Table-Format Version Guard

- TableProperties.validateReadable now rejects table format versions newer than the current reader support range under the default fail-fast policy, preventing future v3/vN SSTs from being silently misread if they miss an incompatible feature marker.
- TablePropertiesTest adds future format-version fail-fast coverage, and `tablePropertiesUnitCoverage` now covers v1/v2/future-version/fail-fast behavior.

## 0.8.0-SNAPSHOT future-version Documentation Gate

- `storageFormatDocs` now requires both acceptance-matrix copies to include `future-version`, ensuring future table-format version fail-fast protection cannot disappear from release-audit material.

## 0.8.0-SNAPSHOT future-version Design Documentation Gate

- `storageFormatDocs` now also requires both 0.8 design documents to include future table-format version fail-fast coverage, keeping design, acceptance matrix, and release gate evidence aligned against future-format silent misreads.
## 0.8.0-SNAPSHOT future-version Rollback Boundary Gate

- `storageFormatDocs` now requires the acceptance matrix to include diagnostic-read wording for future-version handling, making it explicit that disabling `failOnUnknownTableFeature` is diagnostic-only and not a production rollback strategy.
## 0.8.0-SNAPSHOT futureVersionFailFastCoverage Gate

- Added `futureVersionFailFastCoverage` to `storageFormatGates`, splitting future table-format version fail-fast and diagnostic-read boundaries out of `tablePropertiesUnitCoverage` as an independent release-audit item.
## 0.8.0-SNAPSHOT Malformed Table-Format Version Guard

- `TableProperties.read` now rejects non-numeric `ldb.format.table.version` values with an explicit `Invalid table format version` error, preventing damaged properties from surfacing as raw parse failures or ambiguous state.
- `TablePropertiesTest` adds malformed format-version coverage, and `tablePropertiesUnitCoverage` now includes malformed/future format-version fail-fast behavior.
## 0.8.0-SNAPSHOT malformed-version Documentation Gate

- `storageFormatDocs` now requires both acceptance-matrix copies to include `malformed-version`, ensuring explicit fail-fast evidence for non-numeric or non-positive table format versions cannot disappear from release-audit material.
## 0.8.0-SNAPSHOT malformed-version Design Documentation Gate

- `storageFormatDocs` now also requires both 0.8 design documents to include malformed table format version error-handling coverage, keeping design, implementation, and acceptance evidence aligned for explicit fail-fast behavior on non-numeric or non-positive table format versions.
## 0.8.0 Storage Format Release Review Checklist

Current version: `gradle.properties` is `version=0.8.0`.

Release theme: this version moves the LDB file format workstream into a release-auditable state without switching the default write format. Newly written SST/table files remain v1 by default; v2 table properties require explicit `Options.tableFormatVersion(2)` opt-in.

Release review must cover:

| Scope | Current Evidence | Release Decision |
| --- | --- | --- |
| SST/table self-description | `TableBuilder` can opt in to writing the `properties` metaindex entry, and `TableProperties` reads format version and feature sets | v2 can be written and read, while default writes remain v1 |
| Compatibility policy | Old SSTs without a properties block are classified as v1 legacy; `allowLegacyTableFormat` allows old databases by default | New versions read old databases by default |
| Misread prevention | Unknown incompatible features, future table format versions, and malformed table format versions all have fail-fast paths | Unknown or damaged formats must not be silently misread |
| Migration/rollback boundary | Stop v2 writes by restoring `tableFormatVersion=1`; diagnostic reads for future versions are not a production rollback strategy | Rollback boundaries are documented in the acceptance matrix |
| Check/repair evidence | `CheckReport` and `RepairReport` emit `storageFormat/tableFormats/legacyTables/v2Tables/incompatibleTables` | Offline tools explain mixed-format and repair-time format state |
| Backup metadata | `BACKUP-MANIFEST.json` and `OBJECT-REFS.json` carry schema/chain/generation evidence | Backup chain diagnostics have format-version anchors |
| Plugin read-only observability | `OptionsView` exposes the table-format policy snapshot, and `OptionsSnapshot` overrides the actual open-time configuration | Plugins observe the real policy instead of interface defaults |
| Release gate | `releaseGate.storageFormatGates` covers docs, table properties, future/malformed versions, check, mixed-format, repair, backup, plugin, and default legacy policy | Any failed storage format gate blocks release |

Final pre-release evidence still required:

| Command | Required Result |
| --- | --- |
| `.\gradlew.bat test` | Full tests pass, including table/check/repair/backup/plugin file-format coverage |
| `.\gradlew.bat releaseGate` | `storageFormatGates` and all other release gates pass, generating `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json` and `.md` |

Current state: implementation and documentation are aligned for the formal release candidate. The command outputs above must still be collected and archived before the final release.
## 0.8.0-SNAPSHOT User/Operations Storage-Format Documentation Gate

- `storageFormatDocs` now also checks the Chinese and English README, user manual, and operations runbook so `ldb.tableFormat`, `ldb.storageFormat`, `tableFormatVersion`, `failOnUnknownTableFeature`, `CheckReport.storageFormat`, `RepairReport.storageFormat`, and the `storage-format-0.8-acceptance` entry point cannot disappear from release-audit material.
- This extends storage-format evidence from implementation/design acceptance into user discoverability and operational evidence archiving; missing any required entry blocks `releaseGate.storageFormatGates`.
## 0.8.0-SNAPSHOT storageFormatDocs Acceptance Matrix Alignment

- The 0.8 storage-format acceptance matrix now aligns the `storageFormatDocs` blocking item with README, user manual, and operations runbook coverage, and explicitly treats `ldb.tableFormat`, `ldb.storageFormat`, `CheckReport.storageFormat`, and `RepairReport.storageFormat` as release-audit evidence.
## 0.8.0-SNAPSHOT storageFormatDocs Design Documentation Alignment

- The `storageFormatDocs` description in the 0.8 storage-format design documents now matches the actual gate scope: in addition to format reference and design documents, it covers the acceptance matrix, README, user manual, and operations runbook, and requires evidence terms such as `ldb.tableFormat`, `ldb.storageFormat`, `CheckReport.storageFormat`, and `RepairReport.storageFormat`.
## 0.8.0-SNAPSHOT Storage-Format Options API Contract Documentation

- Added JavaDoc to table-format public methods in `Options` and `OptionsView`, documenting default v1 writes, v2 properties opt-in, no in-place rewrite of existing SSTs, legacy v1 compatibility, unknown/future/malformed fail-fast behavior, and the rule that disabling `failOnUnknownTableFeature` is diagnostic-only rather than a production rollback strategy.
## 0.8.0-SNAPSHOT Options API Contract Gate

- `storageFormatDocs` now also checks the table-format public methods in `Options` and `OptionsView`, ensuring `tableFormatVersion`, `writeTableProperties`, `allowLegacyTableFormat`, `failOnUnknownTableFeature`, and the `diagnostic-only` diagnostic-read boundary remain part of the API contract.
## 0.8.0-SNAPSHOT Options API Contract Acceptance Alignment

- The `storageFormatDocs` blocking condition in the 0.8 storage-format design documents and acceptance matrix now includes the Options API contract scope, explicitly treating `Options.tableFormatVersion`, `OptionsView.failOnUnknownTableFeature`, and `diagnostic-only` as release-audit evidence.
## 0.8.0-SNAPSHOT Options API Contract Gate Closure

- `storageFormatDocs` now checks not only `Options` / `OptionsView` source API comments, but also `Options.tableFormatVersion`, `OptionsView.failOnUnknownTableFeature`, and `diagnostic-only` evidence terms in the 0.8 storage-format design documents and acceptance matrix, keeping the API contract, design explanation, and acceptance blocking conditions aligned.
## 0.8.0-SNAPSHOT Format Reference Options API Contract

- `docs/storage-format.md` and its English copy now document the 0.8 table-format policy and Options API contract, including default v1 writes, v2 opt-in, legacy v1 compatibility, future/unknown/malformed fail-fast behavior, the `diagnostic-only` diagnostic boundary, and the `OptionsView` read-only policy snapshot.
- `storageFormatDocs` now checks `Options.tableFormatVersion`, `Options.failOnUnknownTableFeature`, `OptionsView.tableFormatVersion`, and `diagnostic-only` evidence terms in the format reference.
## 0.8.0 pre-release verification record

Release version: `0.8.0`.

Release theme: storage-format completion and improvement. Default writes remain SST/table v1, while v2 properties blocks require explicit `Options.tableFormatVersion(2)` opt-in.

Release documentation status: `CHANGELOG.en.md`, `README.en.md`, the storage-format reference, the 0.8 design, the acceptance matrix, user manual, and operations runbook are part of the release-audit evidence chain; Chinese copies are maintained in sync.

Required pre-release commands:

```powershell
.\gradlew.bat test
.\gradlew.bat releaseGate
.\gradlew.bat publishToMavenLocal
```

Pre-release verification results:

| Command | Result | Evidence |
| --- | --- | --- |
| `.\gradlew.bat test` | PASS, `BUILD SUCCESSFUL` | Main module tests and `ldb-longrun:test` passed |
| `.\gradlew.bat releaseGate` | PASS, `BUILD SUCCESSFUL` | `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json`, version `0.8.0`, all `storageFormatGates` passed |
| `.\gradlew.bat publishToMavenLocal` | PASS, `BUILD SUCCESSFUL` | Main jar, sources jar, javadoc jar, POM, module metadata, and signatures published to Maven Local |

`productionGateLongRun` result: `SUMMARY status=PASS`, with operations=3593, reads=1611, writes=1622, removes=360, activeKeys=1622.

Artifact metadata check: generated `build/publications/maven/pom-default.xml` contains `The Apache License, Version 2.0`.

Known non-blocking items: Gradle still reports deprecated features; Windows may still emit a force-directory `AccessDeniedException` warning, but `releaseGate` passed and this does not block the `0.8.0` release preparation.

Release-preparation conclusion: `0.8.0` has completed the release-version switch, documentation updates, release gates, and local publication verification. No central repository upload has been executed yet.
## 0.8.0 formal release upload record

Release version: `0.8.0`.

Release upload command:

```powershell
.\gradlew.bat publish
```

Release upload result: `BUILD SUCCESSFUL`.

Release status: the `0.8.0` publish command completed successfully and no additional automatic release/publish confirmation step was executed; however, the Gradle output did not prove that the remote deployment was created in user-managed mode. Future remote uploads must force USER_MANAGED or an equivalent staging review mode in the release configuration before publishing.

Review information: the Gradle output did not expose a deployment/repository id or Central Portal review link. The user must review artifacts, POM, signatures, and dependencies in the central repository portal before confirming publication.

Next development version: after the upload, the workspace version has been advanced to `0.9.0-SNAPSHOT`.
## 0.9.0 REL-01 Release Workflow Fix

The `0.9.0-SNAPSHOT` development line adds release workflow safeguards:

- `verifyUserManagedReleaseConfig`: verifies that release repository settings cannot auto-publish a Maven Central visible release.
- `publishUserManagedRelease`: the dedicated formal remote upload entry point, requiring a non-`-SNAPSHOT` version, an explicit remote `releasesRepository`, and USER_MANAGED/user-managed or staging review semantics.
- `releaseGate.userManagedReleaseConfig`: records the current release repository configuration; it fails when AUTOMATIC mode appears or review mode cannot be proven. `releaseGate.gitReleaseTraceability`: before a formal upload, the release commit must be pushed upstream and the `v${version}` tag must exist both locally and on origin.

The default local dry-run repository remains allowed for local verification. Real central-repository uploads must use `publishUserManagedRelease`; plain `publish` must not be used as proof of user-managed publication semantics again. Before `publishUserManagedRelease`, the release-version commit must be committed, pushed to GitHub/upstream, tagged, and the `v${version}` tag must be pushed, otherwise the task fails and blocks central upload.
### REL-01 Verification Results

Executed:

```powershell
.\gradlew.bat verifyUserManagedReleaseConfig
.\gradlew.bat releaseGate
```

Result: both commands completed with `BUILD SUCCESSFUL`.

`RELEASE-GATE-REPORT` now includes release-workflow gates:

| Gate | Result | Evidence |
| --- | --- | --- |
| `userManagedReleaseConfig` | PASS | `https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/` |
| `gitReleaseTraceability` | PASS | Current version is `0.9.0-SNAPSHOT`, reported as `not-required-for-snapshot`; formal non-SNAPSHOT uploads are enforced by `publishUserManagedRelease` |

New mandatory conditions before a formal central upload: clean worktree, release commit pushed upstream, local `v${version}` tag exists, and origin has the same tag.

## 0.9.0-SNAPSHOT RR-01 Bloom/Filter Block Random-Read Release-Preparation Record

Phase objective: add SST Bloom/filter block evidence for random-read miss short-circuiting and raise the readrandom ratio versus RocksDB JNI to at least 50%.

Executed validation:

```powershell
.\gradlew.bat :test --tests *LdbObservabilityTest
.\gradlew.bat releaseGate
.\gradlew.bat :ldb-longrun:ldbDbBenchReport "-Pldb.dbBench.outputDir=build/reports/ldb-db-bench-read-optimized-rr01" "-Pldb.dbBench.dbDir=build/reports/ldb-db-bench-read-optimized-rr01/db" "-Pldb.dbBench.benchmarks=warm_readrandom" "-Pldb.dbBench.num=200000" "-Pldb.dbBench.reads=200000" "-Pldb.dbBench.readProfile=read_optimized" "-Pldb.dbBench.blockCacheSize=65536"
powershell -ExecutionPolicy Bypass -File .\scripts\run-rocksdbjni-comparison.ps1 -ExistingLdbSummary "ldb-longrun\build\reports\ldb-db-bench-read-optimized-rr01\ldb-db-bench-summary.json" -OutputDir "build\reports\rocksdbjni-comparison-readrandom-rr01" -Benchmarks "warm_readrandom" -Num 200000 -Reads 200000 -Runs 1
```

Validation results:

| Item | Result | Evidence |
| --- | --- | --- |
| Bloom/filter unit test | PASS, `BUILD SUCCESSFUL` | `LdbObservabilityTest` asserts `filterSkips>0`, `mayContainRequests>0`, and `mayContainFalse>0` |
| Release gate | PASS, `BUILD SUCCESSFUL` | `build/reports/ldb-release-gate/RELEASE-GATE-REPORT.json`, including `filterBlockCoverage` |
| LDB read_optimized warm_readrandom | `247,361.396 ops/s` | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-rr01/ldb-db-bench-summary.json` |
| RocksDB JNI warm_readrandom | `444,235.456 ops/s` | `build/reports/rocksdbjni-comparison-readrandom-rr01/comparison.csv` |
| LDB/RocksDB JNI ratio | `55.68%` | `247361.396 / 444235.456`, meeting the P0 target of at least 50% |

Known non-blocking item: Windows may still emit `Failed to force LDB directory ... AccessDeniedException`; this run's `releaseGate` and benchmark both finished with PASS results and do not block release preparation.
## 0.9.0 Release-Candidate Preparation Record

Release window: 2026-06-20.

Release theme: release workflow fixes, v2 storage-format production observability, and Bloom/filter block random-read optimization.

Preparation actions:

- Version changed from `0.9.0-SNAPSHOT` to `0.9.0`.
- `CHANGELOG.md` / `CHANGELOG.en.md` archive the 0.9.0 changes as a formal release entry instead of snapshot development notes.
- Formal release gates require the release commit to be pushed to GitHub/upstream and require `v0.9.0` to exist both locally and on the remote; without Git traceability, `releaseGate.gitReleaseTraceability` must fail.
- Central upload must still use `publishUserManagedRelease`; plain `publish` must not replace user-managed publication proof.

Required release-preparation command:

```powershell
.\gradlew.bat test releaseGate publishToMavenLocal
```

If `releaseGate` is run immediately after switching the version but before commit/tag, `gitReleaseTraceability` is expected to block it. The correct order is committing the version switch, pushing to GitHub, creating and pushing the `v0.9.0` tag, then running the release gate again.
## 0.9.0 Release Completion Record

Release status: 0.9.0 has completed the Central repository publication flow.

Post-release closure:

- The workspace version has been advanced to `0.10.0-SNAPSHOT` so development no longer continues on the formal `0.9.0` version.
- Future `publishUserManagedRelease` runs now execute `submitUserManagedReleaseRepository` after upload, submitting the open manual staging repository into validation / follow-up publication flow and writing `USER-MANAGED-DEPLOYMENT.json`.
- This automation only submits the open repository. It does not perform the final Central release/publish; Central visibility still requires explicit user confirmation.