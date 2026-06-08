# LDB Plugin Improvement Execution Plan

## Goal

Convert commitments G1-G6 into executable work: split each item into explicit steps, verification artifacts, and close-out criteria so execution does not drift across teams.

## Shared Execution Rules

- Every item must provide execution evidence (logs, reports, tests, or docs) as part of close.
- If code changes are involved, update `vexra-ldb-external-commitment.*.md`, `ldb-plugin-roadmap.*.md`, and `ldb-plugin-docs-index.*.md` together.
- When verification fails, update planning evidence first, then continue implementation so acceptance semantics stay consistent with code.

## G1-G6 Execution Cards

| ID | Required Action | Current Status | Scope | Completion Evidence | Verification |
| --- | --- | --- | --- | --- | --- |
| G1 | Build a one-to-one mapping for A1-A12 across test classes, longrun profiles, and log fields. | Verified | Documentation + tests | Added acceptance matrix execution mapping to `vexra-ldb-external-commitment.*.md`. | Focused Gradle tests passed. |
| G2 | Add consolidated plugin-composition regression for version-range, external dir, capability enforcement, compatibility testkit, and async combinations. | Verified | longrun + tests | Added resolver, external classloader handle, compatibility, capability enforcement, resource budget, and SmokeRunner composition coverage. | `LongRunPluginResolverTest`, `LdbPluginTest`, `SmokeRunnerTest`. |
| G3 | Complete column-family id/name constraint templates in the developer guide. | Verified | Developer guide | Added id/name range, naming, conflict, and deprecation template. | `ldb-plugin-developer-guide.*.md`. |
| G4 | Fix counter compatibility evidence artifacts. | Verified | Tests + docs | Added cross-cf counter batch and reopen evidence. | `LdbCoreBehaviorTest.shouldPreserveCounterAddLongBatchAcrossColumnFamiliesAndReopen`. |
| G5 | Lock write strategy and performance report linkage. | Verified | longrun reporting + release checklist | Persisted `workloadSyncWrites`, group commit, and plugin async settings into run state and summary report. | `SmokeRunner`, `ReportAnalyzer`, `CHANGELOG*.md`. |
| G6 | Finalize upgrade compatibility gate. | Verified | Release checks | Added upgrade compatibility gate table and release-note boundary requirements. | `vexra-ldb-external-commitment.*.md`. |

## Verification Result

Focused verification passed:

```bash
.\gradlew.bat test --tests net.xdob.vexra.ldb.LdbCoreBehaviorTest --tests net.xdob.vexra.ldb.longrun.config.LongRunConfigTest --tests net.xdob.vexra.ldb.longrun.config.LongRunPluginResolverTest --tests net.xdob.vexra.ldb.longrun.workload.SmokeRunnerTest --tests net.xdob.vexra.ldb.longrun.report.ReportAnalyzerTest
```

Result: `BUILD SUCCESSFUL`.

## Exit Criteria

This execution plan is complete because:
- Every G1-G6 item has a concrete artifact and verification entry.
- Linked documents now report the same verified status.
- Focused tests cover the new code and report fields.

## Note

This document records the completed execution of the improvement plan. Future capability changes continue through the existing engineering workflow and are tracked in code/design docs.
