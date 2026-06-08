# LDB Plugin Capability Overview

English | [中文](ldb-plugin-roadmap.md)

## Purpose

This document records current plugin capabilities and boundaries, and it is aligned with the follow-up items from external commitments. The executable commitment plan is in [vexra-ldb External Commitment](vexra-ldb-external-commitment.en.md#improvement-plan-executable), while plugin development steps are in [Plugin Developer Guide](ldb-plugin-developer-guide.en.md) and the entrypoint is [Plugin Documentation Index](ldb-plugin-docs-index.en.md).
Execution details and acceptance checklists are maintained in [Plugin Improvement Execution Plan](ldb-plugin-improvement-execution-plan.en.md).

## Current Capabilities

| Capability | Status | Notes |
| --- | --- | --- |
| Lifecycle callbacks | Supported | `configure`, `onOpen`, `beforeWrite`, `afterWrite`, checkpoint, and close. |
| Plugin descriptor | Supported | `LdbPluginDescriptor` exposes name, version, order, failurePolicy, and capabilities. |
| Capability declarations | Supported | `LdbPluginCapability` declares observe, mutate, checkpoint, and metadata read. |
| Capability enforcement | Opt-in | `ldb.plugin.capability.enforcement=true` restricts unauthorized batch mutation, metadata reads, and checkpoint hooks. |
| Plugin ordering | Supported | Defaults to descriptor order; longrun can override with `ldb.plugin.<name>.order`. |
| longrun config | Supported | `ldb.plugins`, `-P`, and `ldb.plugin.<name>.*`. |
| Provider discovery | Supported | `LdbPluginProvider`, `LdbPluginLoader`, and `ServiceLoader`; disabled by default. |
| Provider version range | Supported | `ldb.plugin.<name>.versionRange` mismatch fails startup. |
| External plugin directory | Opt-in | `ldb.plugin.external.enabled=true` and `ldb.plugin.dir`; external providers use an independent classloader released when plugins close. |
| Slow-plugin governance | Supported | Timeout, cumulative callback budget, auto-disable, degradation reason, and stats. |
| Compatibility testkit | Supported | `LdbPluginCompatibility` checks the minimum provider/plugin contract. |
| Async post-commit notification | Opt-in | Only `afterWrite` and `afterCheckpoint` can run async. |
| Isolation-boundary observability | Supported | Current plugins are trusted in-process extensions, exposed as `ldb.plugin.sandbox=false`. |
| Sample plugin | Supported | `sample-audit` provider and `plugin-sample.properties`. |

## Current Boundaries

- Plugins remain trusted in-process extensions, not a security sandbox.
- Provider discovery and external plugin directories are disabled by default to avoid implicit workload changes.
- `beforeWrite`, `beforeCheckpoint`, and `onOpen` remain synchronous fail-fast.
- `afterWrite` and `afterCheckpoint` are post-commit notification phases; failures do not roll back committed data.
- Async applies only to post-commit notifications and must be enabled explicitly.
- New capabilities do not change WAL, SST, MANIFEST, or CURRENT disk formats.

## Follow-up Items Aligned to External Commitment (G1-G6)

These items are not capability downgrades; they translate existing commitments into concrete governance and verification. G1-G6 are now verified.

| ID | Follow-up Item | Current Status |
| --- | --- | --- |
| G1 | Commitment-to-test trace matrix | Verified |
| G2 | Plugin composition regression coverage | Verified |
| G3 | Column-family id/name constraints | Verified |
| G4 | Counter compatibility evidence | Verified |
| G5 | Write strategy and performance report linkage | Verified |
| G6 | Version upgrade compatibility gate | Verified |

## Core Documents

- [Plugin Developer Guide](ldb-plugin-developer-guide.en.md)
- [Plugin Capability Enhancement Design](ldb-plugin-design.en.md)
- [Plugin Loading and Discovery Design](ldb-plugin-loading-design.en.md)
- [Plugin Isolation and Async Execution Evaluation](ldb-plugin-isolation-async-design.en.md)
- [vexra-ldb External Commitment for users and plugin extension developers](vexra-ldb-external-commitment.en.md)

## Future Direction

When real business plugins are integrated, first use longrun to measure callback latency, failure policy behavior, and throughput impact. Only when data shows meaningful synchronous callback overhead should stronger isolation or advanced async execution be considered.
