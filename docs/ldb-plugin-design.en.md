# LDB Plugin Capability Enhancement Design

## Background

LDB already provides `LdbPlugin` and `LdbPluginContext`, allowing upper-layer modules to participate in column-family declaration, open, write, checkpoint, and close flows without modifying the core storage engine. The current implementation is closer to a lightweight hook system: plugins run synchronously in the order they are added through `Options.addPlugin`; `beforeWrite` can reject a write; `afterWrite` is called after commit; checkpoint and close have matching callbacks.

As LDB continues to support embedded persistence, diagnostics, backup, and upper-layer integration, the plugin mechanism needs production-grade boundaries: failure semantics must be explainable, slow plugins must be observable, plugin-visible context should be as read-only as possible, write-batch mutation should be explicitly controlled, and multi-plugin ordering and failure behavior need documentation and tests.

## Goals

- Define commit, failure, and ordering semantics for each plugin lifecycle phase.
- Add plugin observability: plugin list, callback counts, failure counts, latency, and latest failure.
- Narrow mutable configuration exposure from plugin context.
- Plan read-only write events and explicit write-batch mutation boundaries.
- Enhance the system in compatible phases without changing WAL, SST, MANIFEST, or CURRENT formats.

## Non-Goals

- No dynamic plugin loading, classloader isolation, or third-party plugin marketplace.
- No remote plugin calls, network callbacks, transaction plugins, or cross-process sandboxing.
- Plugins are not a security boundary in this phase; they are still trusted internal extensions.
- This phase does not change existing callback method signatures or default execution order.

## Current Flow

| Capability | Current behavior | Risk/gap |
| --- | --- | --- |
| Plugin registration | `Options.addPlugin` calls `plugin.configure(options)` and stores the plugin | `configure` can mutate `Options`; no plugin descriptor exists |
| Open callback | `LDbImpl` calls `plugin.onOpen(context)` after open succeeds | `context.getOptions()` returns mutable `Options` |
| Pre-write callback | `beforeWrite(updates, options)` runs before WAL and MemTable writes | Plugins can mutate the batch; default capability is too strong |
| Post-write callback | `afterWrite(updates, options, snapshot)` runs after WAL and MemTable complete | Data is committed, but callback failure is still thrown to the caller and can be misread as write failure |
| Checkpoint callback | `beforeCheckpoint/afterCheckpoint` surround checkpoint creation | No latency, failure, or slow-callback stats |
| Close callback | close path calls `beforeClose` and `close` | close failures are aggregated, but not observable per plugin |
| Multi-plugin order | Plugins run in `Options` list order | No name, version, priority, or failurePolicy |

## Core Constraints

- Keep JDK8 compatibility.
- Do not change disk format or recovery semantics.
- Keep default plugin execution synchronous and inline to avoid changing commit ordering.
- `beforeWrite` failure must still mean no WAL write, no sequence advancement, and no MemTable application.
- `afterWrite` failure must be clearly documented as a post-commit notification failure and must not imply rollback.
- New property fields may be extended later; callers must not depend on fixed field order.

## Interface Design

### Lifecycle Semantics

| Callback | Phase | Can block the core operation | Failure semantics |
| --- | --- | --- | --- |
| `configure(Options)` | When the plugin is added to `Options` | Yes | Fails before open starts |
| `onOpen(LdbPluginContext)` | After database open completes | Yes | Open fails and created resources are closed |
| `beforeWrite(LdbWriteBatch, WriteOptions)` | Before WAL write | Yes | Write fails; data must not reach disk |
| `afterWrite(LdbWriteBatch, WriteOptions, Snapshot)` | After WAL and MemTable complete | No | Data is committed; failure is recorded as post-commit notification failure |
| `beforeCheckpoint(File)` | Before checkpoint creation | Yes | Checkpoint fails; source DB remains usable |
| `afterCheckpoint(File)` | After checkpoint creation and verification | No | Checkpoint exists; failure is recorded as post-commit notification failure |
| `beforeClose()` | Before close starts | No | close continues releasing resources and aggregates the failure |
| `close()` | Plugin resource release | No | close continues releasing other resources and aggregates the failure |

### Observability Properties

| Property | Meaning | Example fields |
| --- | --- | --- |
| `ldb.plugins` | Current plugin list | `0:com.example.AuditPlugin,1:com.example.MetricsPlugin` |
| `ldb.pluginStats` | Aggregate plugin stats | `callbacks=10,failures=1,maxMicros=1200,lastFailure=afterWrite:AuditPlugin` |
| `ldb.plugin.<index>.stats` | Per-plugin stats | `name=AuditPlugin,beforeWrite.count=3,afterWrite.failures=1,maxMicros=1200` |
| `ldb.plugin.lastFailure` | Latest plugin failure summary | `phase=afterWrite,plugin=AuditPlugin,message=...` |

### Read-Only Context

Short-term compatible plan:

- Keep `LdbPluginContext#getOptions()`, but document that the returned object must not be mutated after `onOpen`.
- Add tests ensuring a plugin cannot bypass core validation by mutating `Options` after open.

Medium-term plan:

- Add `OptionsView` or `OptionsSnapshot` exposing read-only getters.
- Add `LdbPluginContext#getOptionsView()`.
- Mark `getOptions()` as a compatibility method and recommend migration.

### Write Event Boundary

Short-term compatible plan:

- Keep mutable `beforeWrite(LdbWriteBatch, WriteOptions)`.
- Add stats and documentation marking batch mutation as a high-risk capability.
- Keep the second pre-write validation after plugin callbacks so mutation cannot bypass WAL encoding or column-family constraints.

Medium-term plan:

- Add a read-only `WriteEvent` or `WriteBatchView`:
  - `getColumnFamilies()`
  - `size()`
  - `isEmpty()`
  - `getWriteOptions()`
  - `getSnapshot()`
- Add plugin capability declarations such as `PluginCapability.MUTATE_WRITE_BATCH`.
- Plugins without mutation capability receive only read-only events.

### Plugin Descriptor

A later phase can add `LdbPluginDescriptor`:

| Field | Meaning |
| --- | --- |
| `name` | Stable plugin name |
| `version` | Plugin version |
| `order` | Execution order within a phase |
| `capabilities` | Capability declarations such as observe, mutate, checkpoint |
| `failurePolicy` | Failure behavior such as fail-fast or record-and-continue |

## Data Structures

### PluginStats

| Field | Meaning |
| --- | --- |
| `pluginIndex` | Plugin order in `Options` |
| `pluginClassName` | Plugin class name |
| `phaseCounts` | Callback count by lifecycle phase |
| `phaseFailures` | Failure count by lifecycle phase |
| `totalNanos` | Total callback latency |
| `maxNanos` | Maximum callback latency |
| `lastFailurePhase` | Latest failed phase |
| `lastFailureMessage` | Latest failure summary |

### PluginFailure

| Field | Meaning |
| --- | --- |
| `phase` | Failed callback phase |
| `pluginClassName` | Plugin class name |
| `message` | Exception summary |
| `committed` | For write-like callbacks, whether data was already committed |

## State Machine

Plugins follow the LDB instance lifecycle:

`CONFIGURED -> OPENING -> OPEN -> CLOSING -> CLOSED`

| State | Trigger | Allowed callbacks |
| --- | --- | --- |
| `CONFIGURED` | `Options.addPlugin` completed | `configure` |
| `OPENING` | LDB construction and recovery | No external callbacks until `onOpen` |
| `OPEN` | LDB opened successfully | `beforeWrite`, `afterWrite`, `beforeCheckpoint`, `afterCheckpoint` |
| `CLOSING` | `close()` called | `beforeClose`, `close` |
| `CLOSED` | Resources released | No more plugin callbacks |

Illegal states:

- No plugin callback may run after `CLOSED`.
- If `beforeWrite` fails, `afterWrite` must not run for that write.
- If `afterWrite` fails, already written WAL and MemTable state must not be rolled back.

## Sequence Flow

### Open Flow

1. The caller creates `Options` and registers plugins through `addPlugin`.
2. Each plugin runs `configure(options)` and may declare column families.
3. LDB creates column-family state, recovers versions, and creates WAL from the final `Options`.
4. After open succeeds, LDB calls `onOpen(context)` in order.
5. If any `onOpen` fails, open fails and already-created resources are closed.

### Write Flow

1. The caller creates a batch.
2. LDB validates the batch before callbacks.
3. LDB calls `beforeWrite` in order.
4. LDB validates the batch again so plugin mutation cannot bypass constraints.
5. LDB assigns sequences, writes WAL, syncs if needed, and applies MemTable.
6. LDB calls `afterWrite` in order and records latency and failures.
7. If `afterWrite` fails, LDB returns a post-commit notification failure, and docs/stats must mark data as committed.

### Checkpoint Flow

1. LDB calls `beforeCheckpoint(targetDir)`.
2. LDB flushes, suspends compaction, copies or hard-links files, and writes the checkpoint report.
3. LDB calls `afterCheckpoint(targetDir)`.
4. If `afterCheckpoint` fails, the checkpoint already exists and the failure is recorded as post-commit notification failure.

## Error Handling

| Scenario | Caller result | Data state | Stats |
| --- | --- | --- | --- |
| `configure` failure | Fails before open | No new LDB instance | Recorded in open exception |
| `onOpen` failure | Open fails | Created resources are closed | Recorded as open failure |
| `beforeWrite` failure | Write fails | No WAL write, no MemTable apply | `committed=false` |
| WAL/sync/MemTable failure | Write fails | Existing recovery semantics apply | Not a plugin failure |
| `afterWrite` failure | Post-commit notification failure | Committed | `committed=true` |
| `beforeCheckpoint` failure | Checkpoint fails | Source DB unchanged | `committed=false` |
| `afterCheckpoint` failure | Post-commit notification failure | Checkpoint exists | `committed=true` |
| `beforeClose/close` failure | Aggregated close failure | Best-effort resource release | close failure records plugin phase |

## Idempotency

- Plugin callbacks can run again when callers retry; plugin implementations must make external side effects idempotent.
- `afterWrite` external notifications must not be treated as the source of truth for LDB commit. If notification fails, data visibility follows the WAL/MemTable state before the callback.
- Plugin stats are diagnostic data and do not participate in recovery or visibility decisions.

## Rollback Strategy

- This design does not change disk format, so old databases remain openable after code rollback.
- Plugin stat properties are read-only observability; rollback only removes those properties.
- `OptionsView`, `WriteEvent`, and similar APIs should be introduced compatibly so old plugins continue to run.
- If default batch mutation is later disabled, provide a compatibility switch or migration warning first.

## Compatibility

| Dimension | Strategy |
| --- | --- |
| Source compatibility | Do not remove existing `LdbPlugin` methods or change parameter types |
| Binary compatibility | Prefer new default methods to avoid breaking existing plugin implementations |
| Data compatibility | Do not write new WAL/SST/MANIFEST/CURRENT formats |
| Behavior compatibility | Keep default synchronous execution order; stats do not affect core write semantics |
| Operational compatibility | New properties may add fields; callers parse by key |

## Rollout and Migration

1. Add docs and tests first to pin down current factual semantics.
2. Add plugin stats and slow-callback logging, enabled by default as read-only observability.
3. Add read-only `OptionsView` and recommend new plugins use it.
4. Add read-only `WriteEvent` while retaining the old mutable batch callback.
5. Evaluate `LdbPluginDescriptor` and failure policy.
6. Decide later whether default plugin behavior should move from mutable batch access to read-only observation.

## Test Plan

| Test | Purpose |
| --- | --- |
| `afterWrite` failure followed by reopen | Verify data was committed and recovered |
| `beforeWrite` failure | Verify no WAL write, no sequence advancement, and no `afterWrite` |
| Multi-plugin order | Verify registration order |
| Plugin failure | Verify failed phase, following-plugin behavior, and stats |
| Post-checkpoint failure | Verify checkpoint exists and report is present |
| Close plugin failure | Verify other resources are still released and close failure is aggregated |
| Plugin stats property | Verify callback counts, failure counts, latency, and latest failure are parseable |
| `OptionsView` | Verify plugins cannot accidentally mutate runtime config through context |
| Batch mutation compatibility | Verify old plugins still run and second validation remains effective |

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Caller treats `afterWrite` failure as commit failure | Business retry can duplicate writes or external side effects | Docs, exception message, and stats mark `committed=true` |
| Slow plugin callback delays writes | Worse write p99 | Slow logs, latency stats, later async evaluation |
| Plugin mutation makes writes opaque | Caller writes are silently changed | Read-only event, capability declaration, second validation |
| Context exposes mutable `Options` | Runtime config is accidentally changed | `OptionsView`, snapshots, and tests |
| Multi-plugin failure policy is unclear | Hard debugging or inconsistent side effects | Descriptor and failure-policy evaluation |

## Phased Plan

| Phase | Deliverable | Acceptance |
| --- | --- | --- |
| 1 | This design document and English copy | Done: docs are merged and factual plugin semantics plus roadmap are clear |
| 2 | Plugin failure-semantics tests | Done: `beforeWrite`, `afterWrite`, and post-checkpoint failure semantics are covered |
| 3 | Plugin stats properties | Done: `ldb.plugins`, `ldb.pluginStats`, and per-plugin stats tests pass |
| 4 | Read-only config view | Done: added `OptionsView` snapshot API while keeping old `getOptions()` compatible |
| 5 | Read-only write event | Done: added `WriteEvent` and `WriteBatchView` while retaining old callbacks |
| 6 | Plugin descriptor and failure-policy evaluation | Done: added minimal descriptor and `failurePolicy`; record-and-continue currently applies only to post-commit notification phases |

## Implemented Status

- Added `LdbPluginDescriptor` and `LdbPluginFailurePolicy` to expose plugin name, version, order, and failure policy.
- Added `OptionsView`; `LdbPluginContext#getOptionsView()` returns an open-time configuration snapshot, while `getOptions()` remains compatible.
- Added `WriteEvent` and `WriteBatchView`; new plugins can observe writes through read-only events, while old mutable batch callbacks remain available.
- Added plugin observability properties: `ldb.plugins`, `ldb.pluginStats`, `ldb.plugin.<index>.stats`, and `ldb.plugin.lastFailure`.
- `afterWrite` and `afterCheckpoint` failures are explicitly reported as post-commit notification failures; data or checkpoint output is not rolled back.
- `RECORD_AND_CONTINUE` applies only to post-commit notification phases. Correctness-sensitive phases such as `beforeWrite`, `beforeCheckpoint`, and `onOpen` remain fail-fast.

## Open Questions

- Should `afterWrite` failure still throw `DBException` to the caller, or only be recorded in `ldb.pluginStats` while returning success? Short-term recommendation: keep throwing, but make the message explicit that the write was committed and only post-commit notification failed.
- Should batch mutation remain a default capability forever? Short-term recommendation: keep it compatible; long-term recommendation: migrate it to an explicit capability.
- Should plugin stats also be included in JSON reports, or only exposed through `getProperty`? Short-term recommendation: expose through properties only.
