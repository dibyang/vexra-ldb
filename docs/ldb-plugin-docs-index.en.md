# LDB Plugin Documentation Index

English | [中文](ldb-plugin-docs-index.md)

## Quick Entry

| Task | Recommended document |
| --- | --- |
| Understand current plugin capabilities and boundaries | [Plugin Capability Overview](ldb-plugin-roadmap.en.md) |
| Write, test, and enable a plugin in longrun | [Plugin Developer Guide](ldb-plugin-developer-guide.en.md) |
| Review external commitments and execution backlog | [vexra-ldb External Commitment for users and plugin extension developers](vexra-ldb-external-commitment.en.md) |
| Drive execution and close items | [Plugin Improvement Execution Plan](ldb-plugin-improvement-execution-plan.en.md) |
| Check runnable examples | [Plugin Developer Guide](ldb-plugin-developer-guide.en.md) sample section |

## Document Layers

| Layer | Documents | Maintenance rule |
| --- | --- | --- |
| User guide | `ldb-plugin-developer-guide.*.md` | For plugin authors; keep runnable config, samples, and operational notes. |
| Capability snapshot | `ldb-plugin-roadmap.*.md` | Keep current boundaries and aligned status for external commitment items. |
| External commitments | `vexra-ldb-external-commitment.*.md` | Freeze user and plugin-developer dependencies, acceptance matrix, and executable improvement plan. |

## Current Completion (Delivered)

Plugin gaps G1-G8 are complete. New plugin authors should start from [Plugin Developer Guide](ldb-plugin-developer-guide.en.md) and `plugin-sample.properties`.

| ID | Completed capability | Current document location |
| --- | --- | --- |
| G1 | Optional capability enforcement | Capability, metadata/checkpoint enforcement, governance config, and FAQ in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |
| G2 | Optional async post-commit notifications | Governance config and boundary sections in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |
| G3 | Explicit non-sandbox isolation boundary | Governance boundary section in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |
| G4 | Slow-plugin timeout, cumulative budget, degradation, and auto-disable | Governance config and test recommendations in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |
| G5 | Provider version-range checks | Longrun enablement and FAQ in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |
| G6 | Longrun plugin order override | Longrun enablement in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |
| G7 | External plugin directory discovery and classloader isolation | Longrun enablement and FAQ in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |
| G8 | `sample-audit` sample plugin and profile | Built-in sample section in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md). |

## Backlog (Commitment Execution)

The external commitment adds six follow-up items (G1-G6), all now verified.
Each item completion should trigger synchronized updates to:
1. `vexra-ldb-external-commitment.*.md`
2. `ldb-plugin-roadmap.*.md`
3. Plugin developer guide (if config/tests/samples are touched)
4. `ldb-plugin-improvement-execution-plan.en.md`

| ID | Backlog item | Entry |
| --- | --- | --- |
| G1 | Commitment-to-test traceability matrix | [External Commitment - Improvement Plan](vexra-ldb-external-commitment.en.md#improvement-plan-executable)<br/>[Execution Plan](ldb-plugin-improvement-execution-plan.en.md) |
| G2 | Plugin composition regression | [External Commitment - Improvement Plan](vexra-ldb-external-commitment.en.md#improvement-plan-executable)<br/>[Execution Plan](ldb-plugin-improvement-execution-plan.en.md) |
| G3 | Column-family id/name constraints | [External Commitment - Improvement Plan](vexra-ldb-external-commitment.en.md#improvement-plan-executable)<br/>[Execution Plan](ldb-plugin-improvement-execution-plan.en.md) |
| G4 | Counter compatibility evidence | [External Commitment - Improvement Plan](vexra-ldb-external-commitment.en.md#improvement-plan-executable)<br/>[Execution Plan](ldb-plugin-improvement-execution-plan.en.md) |
| G5 | Write strategy and performance report linkage | [External Commitment - Improvement Plan](vexra-ldb-external-commitment.en.md#improvement-plan-executable)<br/>[Execution Plan](ldb-plugin-improvement-execution-plan.en.md) |
| G6 | Version upgrade compatibility gate | [External Commitment - Improvement Plan](vexra-ldb-external-commitment.en.md#improvement-plan-executable)<br/>[Execution Plan](ldb-plugin-improvement-execution-plan.en.md) |

## Completion Record

| Stage | Content | Commit |
| --- | --- | --- |
| P1-P5 | Foundational plugin capabilities | `4aeccc4` |
| S1 | Order override and provider versionRange | `0a83c18` |
| S2 | Capability enforcement | `57c19ea` |
| S3 | Slow-plugin governance and sandbox property | `b5abe2e` |
| S4 | Async afterWrite/afterCheckpoint | `b32fb97` |
| S5 | External plugin directory discovery | `3f0264d` |
| S6 | `sample-audit` sample plugin and developer guide | `bc86be4` |

## Maintenance Rules

- When adding plugin capabilities, update design or developer guide before code.
- When adding longrun plugin config, update the developer guide and sample profiles together.
- When changing completion/backlog status, update this index, [external commitment](vexra-ldb-external-commitment.en.md), and [capability overview](ldb-plugin-roadmap.en.md).
- Keep Chinese and English docs structurally aligned.
