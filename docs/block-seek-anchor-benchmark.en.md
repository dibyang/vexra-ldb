# Block.seek In-Memory Seek-Anchor Benchmark Notes

This document records local `Block.seek` benchmark results for in-memory seek anchors, covering
the tradeoff between `decodedEntries`, `sharedKeyRebuilds`, thread allocation, and retained anchor
key bytes on random point-lookups.

## Background

When `Block.seek` searches inside a prefix-compressed block, it starts from a restart point or seek
anchor and decodes entries sequentially. For shared-prefix entries, the next entry cannot be decoded
unless the previous full key is available, so shared-key rebuilds cannot be skipped blindly. The
current optimization direction is to shorten the linear decoding distance with denser in-memory
seek anchors.

## Current Mainline Strategy

- `SEEK_ANCHOR_INTERVAL = 2`
- This only affects in-memory anchors created after a `Block` is loaded and does not change the
  on-disk block format.
- `blockSeekMicroBenchReport` reports:
  - `decodedEntriesPerOp`
  - `sharedKeyRebuildsPerOp`
  - `seekAnchorCount`
  - `seekAnchorRetainedKeyBytes`

## Micro-Benchmark Comparison

Configuration:

- `entries=8192`
- `reads=200000`
- `valueSize=100`
- `seed=20260623`

### Anchor Interval Comparison

| anchor interval | ops/s | bytes/op | decodedEntriesPerOp | sharedKeyRebuildsPerOp | seekAnchorCount | seekAnchorRetainedKeyBytes |
|---:|---:|---:|---:|---:|---:|---:|
| 2 | 496,848 | 236.964 | 1.623 | 1.499 | 3,584 | 172,032 |
| 1 | 496,472 | 199.047 | 1.062 | 1.000 | 7,680 | not retained |

`interval=1` reduces decoding and allocation in the micro-benchmark, but the real
`ldbDbBenchReport` run did not show stable gains for `readrandom_mixed`; allocation also became
less favorable while the anchor count doubled. It is therefore not kept on the mainline.

### Restart Interval Matrix (anchor interval=2)

| restart interval | ops/s | bytes/op | decodedEntriesPerOp | sharedKeyRebuildsPerOp | seekAnchorCount | seekAnchorRetainedKeyBytes |
|---:|---:|---:|---:|---:|---:|---:|
| 4 | 478,914 | 254.584 | 2.002 | 1.500 | 2,048 | 98,304 |
| 8 | 425,898 | 245.910 | 1.747 | 1.499 | 3,072 | 147,456 |
| 16 | 643,819 | 236.964 | 1.623 | 1.499 | 3,584 | 172,032 |
| 32 | 571,240 | 232.472 | 1.561 | 1.499 | 3,840 | 184,320 |

## Conclusion

- The current `interval=2` setting remains the safer mainline tradeoff compared with `interval=1`.
- `interval=1` should not be enabled by default: it reduces micro allocation, but real read
  workloads did not show stable gains and retained anchor metadata grows substantially.
- Future adaptive-anchor work should use both `seekAnchorRetainedKeyBytes` and real dbBench
  `readrandom_mixed` / `multiget_random` results as admission gates.

## Adaptive Anchor Policy

The current in-memory policy keeps `interval=2` for normal large restart ranges, but avoids spending
the same retained-key memory on low-benefit ranges:

- restart ranges with at most four entries do not build in-memory seek anchors;
- small restart ranges or ranges with a low shared-key ratio downshift to interval=4;
- regular prefix-compressed ranges keep interval=2, preserving the proven seek-distance reduction.

This is an in-memory reader policy only. It does not change the on-disk block format or persisted
inline seek-index encoding.

`readrandom_mixed` now also emits `workloadStats`, splitting hit and miss lookups by count, found
count, latency, and thread allocation. Filter-skip and cache-hit/cache-miss evidence remains in the
existing `sstReadStats` and `blockCacheStats` fields, so mixed-path tuning can correlate caller-level
hit/miss cost with storage-engine counters.

## Lightweight Pre-Release Gate

`blockSeekPerfGate` is now wired into `releaseGate`. The gate first runs
`blockSeekMicroBenchReport`, then checks that key JSON report fields exist and are positive:

- `decodedEntriesPerOp`
- `sharedKeyRebuildsPerOp`
- `seekAnchorCount`
- `seekAnchorRetainedKeyBytes`

This gate only protects the benchmark entry point, report schema, and core observation signals. It
does not enforce a hard `opsPerSecond` threshold because throughput is host-sensitive; real
performance decisions still require `ldbDbBenchReport` and same-host comparisons.
