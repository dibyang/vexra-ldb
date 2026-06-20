# LDB 0.8.0 Storage Format Acceptance Matrix

## Purpose

This document breaks the `0.8.0-SNAPSHOT` storage-format goal into a release-auditable acceptance matrix. It links design documents, implementation points, tests, and `releaseGate.storageFormatGates`. It does not introduce new on-disk semantics; it records the evidence that must be proven before release.

## Acceptance Matrix

| Goal Area | Acceptance Item | Implementation Evidence | Test/Gate Evidence | Release Boundary |
| --- | --- | --- | --- | --- |
| SST/table self-description | v2 SSTs can opt in to a properties block | `Options.tableFormatVersion(2)`, `TableBuilder` metaindex `properties` entry, `TableProperties` reader | `TablePropertiesTest`, `tablePropertiesUnitCoverage` | Default writes remain v1; v2 must be explicit opt-in |
| Feature set and version protection | Read compatible/incompatible features and fail fast on unknown incompatible features or future table format versions | `TableProperties.validateReadable`, `Options.failOnUnknownTableFeature` | `TablePropertiesTest`, `tablePropertiesUnitCoverage`, `futureVersionFailFastCoverage` | Initial v2 has an empty `incompatible_features` set; future format versions are rejected by default |
| Legacy compatibility | SSTs without properties blocks are classified as v1 legacy | `TableProperties.legacy()`, `Options.allowLegacyTableFormat` | `TablePropertiesTest`, `defaultLegacyWritePolicy` | New versions read old databases by default |
| Mixed format | One database can contain v1/v2 SSTs and be explained by check reports | `CheckReport.tableFormats`, `legacyTables`, `v2Tables` | `LdbVerifyCheckTest.shouldReportMixedV1AndV2TableFormatsInCheckReport`, `mixedFormatCheckCoverage` | Older versions are not promised to read already-written v2 SSTs |
| Check evidence | Offline check emits storage format and per-SST table format evidence | `CheckReport#toJson()` | `checkReportStorageFormatEvidence` | check does not modify the database directory |
| Repair evidence | repair/repair-plan emit storage format evidence, and v2 SSTs are not downgraded in place | `RepairReport#toJson()`, `recordRepairTableFormat` | `LdbToolTest`, `LdbRepairTest.shouldReportV2TableFormatWhenRepairingFromReadableSst`, `repairReportStorageFormatEvidence` | repair rebuilds MANIFEST/CURRENT and keeps existing SST formats |
| Backup metadata | backup manifest/object refs include schema fields and stable chainId | `BACKUP-MANIFEST.json`, `OBJECT-REFS.json` schema fields | `LdbBackupTest`, `backupMetadataSchemaCoverage` | Legacy backups without schema fields remain checkable and restorable |
| Runtime observability | Runtime properties expose table/storage format summaries | `ldb.tableFormat`, `ldb.storageFormat` | `LdbObservabilityTest` | properties are parsed at table-open time, not on every get hot path |
| Plugin read-only configuration | Plugins can observe table format policy through `OptionsView` without mutating configuration | `OptionsView.tableFormatVersion()`, `writeTableProperties()`, `allowLegacyTableFormat()`, `failOnUnknownTableFeature()` | `LdbPluginTest.shouldExposeReadOnlyOptionsViewAndWriteEvent`, `pluginOptionsViewCoverage` | New methods are Java 8 default methods and keep v1/legacy/fail-fast defaults to reduce breakage for third-party implementations |
| Release gate | Release reports include dedicated storage format gates | `storageFormatGates`, `RELEASE-GATE-REPORT.json/md` | `releaseGate` | A failed storage format gate blocks release |

## Rollback And Migration Boundaries

| Scenario | Supported Strategy | Evidence |
| --- | --- | --- |
| Stop v2 writes | Restore new writes to `tableFormatVersion=1` | `defaultLegacyWritePolicy` |
| Existing v2 SSTs | Current version continues reading them; older versions are not promised to read them | `mixedFormatCheckCoverage`, `repairReportStorageFormatEvidence` |
| Future table format version | Fail fast by default; disabling `failOnUnknownTableFeature` is only for diagnostic reads and is not a production rollback strategy | `futureVersionFailFastCoverage`, `future-version` |
| Explain mixed-format state | Use `LDBFactory.check` or `ldb.tableFormat` | `CheckReport.tableFormats`, `VersionSet.tableFormatStats()` |
| Repair metadata rebuild | Rebuild MANIFEST/CURRENT only; preserve existing SST formats | `RepairReport.tableFormats` |
| Backup chain diagnostics | Use manifest `schemaVersion/chainId/generation` and object refs schema | `backupMetadataSchemaCoverage` |

## Required Pre-Release Evidence

| Command | Expected Result |
| --- | --- |
| `.\gradlew.bat test` | All unit/integration tests pass, including table/check/repair/backup format coverage |
| `.\gradlew.bat releaseGate` | `storageFormatGates`, compatibility gates, and production gates pass, generating `RELEASE-GATE-REPORT.json` and `RELEASE-GATE-REPORT.md` |

## storageFormatGates Blocking Items

| Gate | Blocking Condition |
| --- | --- |
| `storageFormatDocs` | Chinese/English format reference, design documents, acceptance matrix, README, user manual, operations runbook, or Options API contract are missing, or table/backup/repair/mixed-format/malformed-version/future-version/rollback, `ldb.tableFormat`, `ldb.storageFormat`, `CheckReport.storageFormat`, `RepairReport.storageFormat`, `Options.tableFormatVersion`, `OptionsView.failOnUnknownTableFeature`, or `diagnostic-only` keywords are missing |
| `tablePropertiesUnitCoverage` | table properties v1/v2/malformed-version/fail-fast coverage fails |
| `futureVersionFailFastCoverage` | future table format version fail-fast or diagnostic-read boundary coverage fails |
| `checkReportStorageFormatEvidence` | check reports lack `storageFormat/tableFormats` evidence |
| `mixedFormatCheckCoverage` | mixed v1/v2 SST report coverage fails |
| `repairReportStorageFormatEvidence` | repair/repair-plan lack format report evidence, or v2 repair format preservation coverage fails |
| `backupMetadataSchemaCoverage` | backup manifest/object refs schema coverage fails |
| `pluginOptionsViewCoverage` | Plugin `OptionsView` does not cover table-format read-only getters, or plugins observe interface defaults instead of the actual open-time configuration snapshot |
| `defaultLegacyWritePolicy` | default writes no longer remain table format v1, or v2 no longer requires explicit opt-in |
| `storageFormatDocs` / `OptionsView` | The acceptance matrix lacks plugin read-only configuration or table-format getter coverage such as `failOnUnknownTableFeature` |
## 0.9.0-SNAPSHOT SF-06 Production Observability

| Acceptance Item | Evidence | Release Gate |
| --- | --- | --- |
| Observable v2 write switch | `ldb.tableFormatPolicy` emits `newWrites=v1` or `newWrites=v2-properties` | `tableFormatPolicyCoverage` |
| Observable rollback boundary | `ldb.tableFormatPolicy` emits `rollback=new-writes-tableFormatVersion-1` | `tableFormatPolicyCoverage` |
| Observable risk switch | `unknownFeaturePolicy=fail-fast`, or `diagnostic-only` when explicitly disabled | `tableFormatPolicyCoverage` |

`tableFormatPolicyCoverage` requires tests for default v1, explicit v2, rollback wording, and fail-fast policy evidence so SF-06 is release-gated by implementation evidence rather than documentation only.

## 0.9.0-SNAPSHOT RR-01 Filter Block Random-Read Acceptance

| Acceptance item | Evidence | Gate |
| --- | --- | --- |
| Bloom filter block write | With `BloomFilterPolicy` configured, SST metaindex writes `filter.<policyName>` and the reader loads the filter block with the matching policy | `filterBlockCoverage` |
| Random miss short circuit | For a full user key inside an SST key range but absent from the table, `mayContain=false` skips the table iterator | `filterBlockCoverage` |
| Observability loop | `ldb.sstReadStats` emits `filterSkips`, `mayContainRequests`, and `mayContainFalse` for pre-release readrandom miss evidence | `filterBlockCoverage` |

`filterBlockCoverage` requires unit-test evidence for BloomFilterPolicy-backed filter block writes, reader-side `mayContain=false`, and `filterSkips>0`. The feature remains optional: without a matching `FilterPolicy` or filter block, readers must conservatively return may-contain=true to avoid skipping real data.
