# LDB Plugin Isolation And Async Execution Evaluation

English | [中文](ldb-plugin-isolation-async-design.md)

## Background

Plugins currently run synchronously in the same JVM and thread path as LDB. This model is simple, predictable, and aligned with trusted internal extensions. P5 captures which phases must remain synchronous fail-fast, which phases may later be evaluated for async execution, and how slow plugins should be observed and degraded.

## Goals

- Define lifecycle phase execution, failure, and degradation policies.
- Keep async execution disabled in the first phase.
- Expose `ldb.plugin.executionPolicy` for tools and longrun.
- Define entry criteria for any later async or isolation implementation.

## Non-Goals

- No async callback thread pool in this phase.
- No classloader isolation.
- No plugin security boundary.
- No semantic change for `beforeWrite`, `beforeCheckpoint`, or `onOpen`.

## Current Policy

| Phase | Current execution | Async allowed | Failure policy |
| --- | --- | --- | --- |
| `configure` | sync | no | fail-fast |
| `onOpen` | sync | no | fail-fast |
| `beforeWrite` | sync | no | fail-fast, no partial commit |
| `afterWrite` | sync | candidate | post-commit, record or throw by policy |
| `beforeCheckpoint` | sync | no | fail-fast |
| `afterCheckpoint` | sync | candidate | post-commit, record or throw by policy |
| `beforeClose` | sync | later evaluation | record and continue closing |
| `close` | sync | later evaluation | record and continue closing |

Current property:

```text
ldb.plugin.executionPolicy=asyncEnabled=false,configure=syncFailFast,onOpen=syncFailFast,beforeWrite=syncFailFast,afterWrite=syncPostCommitCandidate,beforeCheckpoint=syncFailFast,afterCheckpoint=syncPostCommitCandidate,beforeClose=syncRecordAndClose,close=syncRecordAndClose
```

## Slow Plugin Observability

Implemented fields:

| Field | Meaning |
| --- | --- |
| `ldb.pluginStats` | Aggregate callbacks, failures, maxMicros, and latest failure |
| `ldb.plugin.<index>.stats` | Per-plugin order, capabilities, failure policy, phase counts, and failures |
| `ldb.plugin.lastFailure` | Latest failed phase, plugin, committed flag, and message |
| longrun `plugin.properties` | Persisted plugin state for reports |

Potential future fields:

| Field | Purpose |
| --- | --- |
| `timeoutCount` | Count slow-plugin timeouts |
| `disabled` | Mark automatic plugin disablement |
| `degradationReason` | Latest degradation reason |
| `queueDepth` | Queue depth for async candidate phases |

## Async Entry Criteria

Async implementation should start only when all conditions are met:

- At least one real plugin or longrun profile shows meaningful synchronous callback cost.
- Queue capacity, drop policy, close wait time, and timeout handling are defined.
- Async `afterWrite` cannot make callers misread the commit boundary.
- Properties and logs identify degraded or disabled plugins.
- Correctness-sensitive phases remain synchronous fail-fast.

## Degradation Policies

| Policy | Phase | Notes |
| --- | --- | --- |
| record-only | post-commit notification | Record failure without changing committed data |
| skip-notification | post-commit notification | Skip later notifications with explicit records |
| disable-plugin | slow or repeatedly failing plugin | Requires explicit config or future circuit-breaker rules |
| fail-fast | correctness-sensitive phases | Fixed for `beforeWrite`, `beforeCheckpoint`, and `onOpen` |

## Compatibility

- Current implementation does not change execution order or threading.
- `RECORD_AND_CONTINUE` remains limited to post-commit notification phases.
- `ldb.plugin.executionPolicy` is a read-only observability property.
- Rollback does not affect on-disk data.

## Tests

| Test | Coverage |
| --- | --- |
| post-commit failure | `afterWrite`/`afterCheckpoint` failures do not roll back data or checkpoint output |
| fail-fast phase | correctness-sensitive failure blocks the core operation |
| execution policy property | property and longrun report can read the policy |
| plugin stats | slow or failing plugins are visible in per-plugin stats |

## Conclusion

P5 currently lands policy and observability only. Async execution remains disabled. Future async or isolation work should use this document's entry criteria as the gate.
