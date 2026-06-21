# 0.10.0 readrandom 70% Plan

## Background

Version 0.9.0 raised `readrandom` from 29% of RocksDB JNI to 55.68%. The main goal for 0.10.0 is to keep reducing object allocation, iterator wrapping, and block lookup overhead on the point-read path, pushing `warm_readrandom` above 70% of RocksDB JNI.

0.9.0 baseline:

- LDB read-optimized `warm_readrandom` 200k: 247,361.396 ops/s
- RocksDB JNI `warm_readrandom` 200k: 444,235.456 ops/s
- Current ratio: 55.68%

0.10.0 target:

- LDB read-optimized `warm_readrandom` 200k reaches more than 70% of RocksDB JNI.
- Point get and MultiGet reuse a direct point lookup path instead of building a full table/internal iterator chain for every key.
- The SST file format remains compatible; file-format improvements are deferred to a later version.

## Current gaps

### Point reads still use the generic iterator chain

Single-key get and MultiGet in `Level` / `Level0` currently create an `InternalTableIterator` through `TableCache.newIterator()`, then go through `TableIterator`, the index block iterator, and the data block iterator. This path is suitable for scans, but too heavy for random point reads.

Major costs:

- Multiple iterator wrapper objects are created per point read.
- Raw slice to `InternalKey` conversion happens inside the generic iteration path.
- MultiGet groups keys by file and reuses an iterator, but still does not use an SST-level direct get path.

### Observability does not expose direct get metrics

The table cache stats currently show table loads, iterator requests, and filter checks, but cannot directly distinguish:

- Whether point reads bypass the iterator path.
- Direct get request, hit, and miss counts.
- Whether future block-seek optimizations affect the point-read path.

### Deeper block-level optimization remains available

`BlockIterator.seek()` already performs binary search on restart points and then scans linearly within the restart region. Future work can still evaluate:

- Block-internal binary search or short indexes when restart regions are large.
- Reducing temporary objects during data block key prefix decoding.
- Optimizing slice/value lifetimes on block-cache hit paths.

## Scope for this version

### Direct point lookup

Add an internal-key direct get API to `Table`:

1. Locate the target data block with the index block iterator.
2. Open only the matching data block.
3. Seek the target internal key inside that data block.
4. Return the candidate block entry and keep sequence/type/user-key checks in the upper layer.

The table layer intentionally does not evaluate value types. This avoids coupling the table package to `LookupKey`, `InternalKey`, and `LookupResult` semantics from the impl package.

### TableCache direct get

Add a direct get entry point and metrics to `TableCache`:

- `directGetRequestCount`
- `directGetHitCount`
- `directGetMissCount`

Single-key get and MultiGet use this entry point while preserving the existing bloom filter, range delete, and read stats behavior.

### Level / Level0 integration

Replace the point-read path in:

- `Level.get(LookupKey, ReadStats)`
- `Level0.get(LookupKey, ReadStats)`
- `Level.get(List<LookupKey>, ReadStats)`
- `Level0.get(List<LookupKey>, ReadStats)`

Lookup semantics remain unchanged:

- Internal key parsing still happens in the impl layer.
- The user key must match the requested key.
- `VALUE` returns the value.
- `DELETION` returns deleted.
- Misses return null.
- Range delete coverage checks keep the existing path.

## Compatibility

- No SST file format changes.
- No manifest, WAL, sequence, or comparator semantic changes.
- No public API changes.
- Direct get changes only the read implementation path, not the result semantics.

## Release gates

After this stage, run at least:

- Relevant unit tests covering point get, delete, MultiGet, and observability stats.
- `readrandom` 200k comparison benchmark: LDB read-optimized vs RocksDB JNI.
- Existing release gates from the release skill: CHANGELOG, docs, UTF-8, release gate, and Git traceability.

## Current implementation result

The first 0.10.0 implementation covers two paths:

- SST direct point lookup: `TableCache.get` enters `Table.get`, then uses `Block.seek` to bypass the generic iterator chain and the first-entry pre-read in `BlockIterator` construction.
- MemTable latest point-value index: when there is no range tombstone and the read snapshot covers the latest version, `MemTable.get` can hit the latest `VALUE` / `DELETION` directly by user key. Old snapshots, range deletes, and misses still fall back to the original skiplist path.

200k read-optimized `warm_readrandom` result:

| Metric | ops/s | Evidence |
| --- | ---: | --- |
| LDB | 566,788.838 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-memfast-010/ldb-db-bench-summary.json` |
| RocksDB JNI | 575,324.008 | `build/reports/rocksdbjni-comparison-memfast-010/comparison.csv` |
| LDB/RocksDB JNI | 98.52% | `566788.838 / 575324.008` |

Conclusion: this run exceeds the 70% target. The current Windows environment still prints the directory fsync `AccessDeniedException` warning, but the benchmark task returns `PASS`; this matches earlier release notes and is recorded as an environment note.

Additional SST / MultiGet results:

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Evidence |
| --- | ---: | ---: | ---: | --- |
| `cold_readrandom` | 184,805.871 | 286,456.905 | 64.52% | `build/reports/rocksdbjni-comparison-random-suite-010/comparison.csv` |
| `multiget_random` | 216,561.966 | 330,273.078 | 65.57% | `build/reports/rocksdbjni-comparison-random-suite-010/comparison.csv` |

Additional conclusion: warm readrandom has reached the target, while cold readrandom and MultiGet still have deeper SST/block/cache optimization room. The next stage should continue reducing SST data-block decoding cost, linear scans inside restart ranges, and MultiGet batch lookup overhead.

## Batch block seek optimization scope

This round continues the MultiGet batch lookup optimization without changing the SST file format:

- `Table` groups internal keys by the data block handle found through the index block.
- The same data block is opened only once, avoiding repeated `openBlock` calls for keys that land in the same block.
- Default random MultiGet still uses `Block.seek` per key inside the same block, avoiding the extra cost of decoding from the smallest key to the largest key when random keys are sparse.
- `Block.seekAll` is kept as a follow-up capability for dense batch scenarios; it should only be enabled when target keys are dense enough inside the same block.
- MultiGet in `Level` / `Level0` uses table-cache batch direct get; single-key get keeps the existing direct get path.
- Range delete, snapshot, sequence, value type, and user-key matching semantics remain unchanged; files with range deletes still use the existing coverage check.

200k read-optimized `multiget_random` batch block reuse and restart-key cache best-observed result:

| Metric | ops/s | Note |
| --- | ---: | --- |
| Previous LDB baseline | 216,561.966 | LDB result paired with `rocksdbjni-comparison-random-suite-010` |
| LDB block reuse intermediate | 221,373.672 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-blockreuse-010/ldb-db-bench-summary.json` |
| Current LDB block reuse + restart-key cache | 245,912.746 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-restartkey-multiget-010/ldb-db-bench-summary.json` |
| LDB self-improvement vs baseline | 13.55% | `(245912.746 - 216561.966) / 216561.966` |
| Current RocksDB JNI | 211,965.247 | `build/reports/rocksdbjni-comparison-restartkey-multiget-010/comparison.csv` |
| Current LDB/RocksDB JNI | 116.02% | `245912.746 / 211965.247` |

Conclusion: reusing the same opened block is a low-risk positive change, and caching restart-point keys removes repeated restart-entry decoding on the block seek path. The sequential `Block.seekAll`, full-entry block-local index, and batch index-block seek strategies all regressed sparse random keys in follow-up 200k runs, so they are not enabled by default. Later short reruns on the same Windows host showed lower MultiGet results down to 175,019.961 ops/s, so the 245,912.746 ops/s figure is archived as best observed evidence rather than a stable SLA.

## Random-read block-cache warmup strategy

Add an explicit opt-in `Options.blockCacheWarmOnOpen(true)` for cold-start random reads:

- It is disabled by default so ordinary database open does not pre-read all data blocks.
- It only takes effect when `cacheBlocks=true`.
- After opening an SST, the table enumerates data block handles from the index block and calls `openBlock` to admit them into the block cache.
- `ldb.sstReadStats` adds `blockCacheWarmupTables` and `blockCacheWarmupBlocks` so release evidence can prove whether warmup happened.
- `ldbDbBenchReport` enables this option explicitly with `-Pldb.dbBench.blockCacheWarmOnOpen=true` to measure the benefit of moving first-touch block loading to open time; it remains disabled by default to avoid charging sparse random MultiGet for warmup cost.

200k read-optimized `cold_readrandom` warmup and restart-key cache best-observed result:

| Metric | ops/s | Note |
| --- | ---: | --- |
| Previous LDB baseline | 184,805.871 | LDB result paired with `rocksdbjni-comparison-random-suite-010` |
| LDB warm-on-open intermediate | 199,464.617 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-warmopen-010/ldb-db-bench-summary.json` |
| Current LDB warm-on-open + restart-key cache | 267,264.591 | `ldb-longrun/build/reports/ldb-db-bench-read-optimized-restartkey-coldwarm-010/ldb-db-bench-summary.json` |
| LDB self-improvement vs baseline | 44.62% | `(267264.591 - 184805.871) / 184805.871` |
| Current RocksDB JNI | 250,603.108 | `build/reports/rocksdbjni-comparison-restartkey-coldwarm-010/comparison.csv` |
| Current LDB/RocksDB JNI | 106.65% | `267264.591 / 250603.108` |

Conclusion: explicit block-cache open-time warmup plus restart-key caching produced a best-observed cold_readrandom run above RocksDB JNI. The option remains opt-in and disabled by default, because charging all database opens for full data-block warmup would be unsafe for ordinary workloads and sparse random MultiGet. Later short reruns on the same Windows host ranged lower, down to 170,951.232 ops/s, so release notes treat the result as benchmark evidence with notable local variance rather than a stable SLA.

## Restart-key in-memory index

Without changing the SST file format, `Block` now caches the full key at every restart point during construction:

- `Block.seek` compares `restartKeys[mid]` directly during restart binary search instead of repeatedly decoding restart entries.
- `Block.seekAll` reuses the same restart-key cache.
- The tradeoff is one extra restart-key slice array per opened block; when `blockCacheWarmOnOpen=true`, this cost moves to open/warmup time.
- This provides a low-risk in-memory validation step before a future file-format-level block-local index.

## Block-local full-entry index evaluation

A current-version in-memory full-entry seek index was evaluated but is not enabled in the release path:

- Building complete entry indexes during warm-on-open moved too much decoding work into the cold_readrandom measured path and regressed the 200k run.
- Enabling indexed batch lookup for sparse random MultiGet groups also regressed the latest 200k MultiGet run.
- The release keeps the proven restart-key cache and same-block open reuse, while deferring persisted or more compact block-local indexes to a future file-format version.
- Batch index-block positioning was evaluated for same-SST MultiGet but is not enabled because the 200k sparse random MultiGet run regressed; per-key index seek plus same-block data-block reuse remains the release path.

## Follow-up candidates

- Bloom/filter moves into the next validation stage: `ldbDbBenchReport` now adds `readrandom_miss`, `readrandom_mixed`, and `multiget_mixed` so all-hit random reads can be separated from miss-heavy and mixed random reads. When `BloomFilterPolicy` is enabled, v3 properties record the filter policy, scope, key count, filter block bytes, and bits-per-key for pre-release evidence.
- Next-stage file-format design has been started in `docs/storage-format-0.11-block-index-design.md` and its English copy, focusing on compact persisted block-local indexes instead of full-entry in-memory indexes.
- File-format improvements: persisted compact block-level key index, filter/layout metadata, and format-version capabilities.
- Deeper block lookup optimization: reduce restart-region scans without eagerly decoding every entry in sparse random workloads.
- Cache policy optimization: separate block cache admission for random reads and scans.
- Long-run benchmarks: random reads, mixed read/write workloads, and MultiGet hotspot distributions.

## Bloom/filter miss/mixed 50k comparison result

This round fixes the `readrandom_miss`, `readrandom_mixed`, and `multiget_mixed` benchmarks so they prepare data, close/reopen, and then time the SST/Bloom read path. The new scenarios no longer force `compactRange`, avoiding flush/compaction cost in the Bloom read-path measurement. The v3 filter-property write order was also fixed so `BlockBuilder` key ordering remains valid.

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Bloom evidence |
| --- | ---: | ---: | ---: | --- |
| `readrandom_miss` | 277,468.554 | 301,242.565 | 0.9211 | `filterSkips=49604`, `mayContainFalse=49604` |
| `readrandom_mixed` | 277,333.582 | 435,665.684 | 0.6366 | `filterSkips=24793`, `mayContainFalse=24793` |
| `multiget_mixed` | 317,069.768 | 383,228.393 | 0.8274 | `filterSkips=24793`, `directGetBatchRequests=782` |

Evidence files: `ldb-longrun/build/reports/ldb-db-bench-bloom-miss-mixed-50k/ldb-db-bench-summary.csv` and `build/reports/rocksdbjni-comparison-bloom-miss-mixed-50k/comparison.csv`.
## SST hit-path 50k comparison result

This round adds `readrandom_hit` and extends `ldb.sstReadStats` with hit-path counters: `candidateEntryHits`, `candidateEntryMisses`, `bloomFalsePositives`, `tableIndexSeeks`, `tableDataBlockOpens`, and `tableDataBlockSeeks`. These counters separate the Bloom-skipped miss side from the real hit side that still pays SST index/data-block lookup cost.

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Key observation |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 166,519.353 | 420,630.761 | 0.3959 | 50,000 hits produce 50,000 index seeks, data-block opens, and data-block seeks |
| `readrandom_miss` | 326,763.001 | 422,757.903 | 0.7729 | Bloom skips 49,604 reads; only 396 false positives reach table read |
| `readrandom_mixed` | 295,314.365 | 438,877.912 | 0.6729 | 25,000 real hits plus 207 false positives; the mixed gap is mostly the hit side |
| `multiget_mixed` | 293,598.151 | 229,949.655 | 1.2768 | batch direct get reduces tableReads to 782, proving grouped reuse amortizes hit-path cost |

Evidence files: `ldb-longrun/build/reports/ldb-db-bench-hitpath-50k/ldb-db-bench-summary.csv` and `build/reports/rocksdbjni-comparison-hitpath-50k/comparison.csv`.

Conclusion: the next valuable work is single-key hit-path optimization rather than more Bloom expansion. The priority is reducing per-hit repeated index seek, data-block open, and in-block seek cost; `multiget_mixed` shows that grouping/reuse is an effective direction.
## Single-key locality hit-path 50k comparison result

This round adds two low-risk fast paths to `Table.get(Slice internalKey)` without changing the SST file format: a recent index-seek coverage cache and recent data-block reuse. The index cache only hits when the new key is not less than the previous lookup key and does not exceed the current index-limit key, avoiding ambiguity around the previous data-block boundary. The data-block cache is published as one immutable holder through `volatile` to avoid handle/block mismatch under concurrent reads.

The new `readrandom_sameblock` benchmark captures consecutive point gets inside nearby keys in the same data block; regular `readrandom_hit` remains the pure random-hit workload.

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Key observation |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 137,117.521 | 388,310.005 | 0.3531 | Pure random hits produced only `tableIndexCacheHits=21` and `tableLastBlockHits=33`, as expected; the fast path does not distort ordinary random-read semantics |
| `readrandom_sameblock` | 294,151.734 | 542,632.462 | 0.5421 | Locality reads hit `tableIndexCacheHits=47839` and `tableLastBlockHits=47839`, reducing actual `tableIndexSeeks` and `tableDataBlockOpens` to `2161` |

Evidence files: `ldb-longrun/build/reports/ldb-db-bench-sameblock-50k/ldb-db-bench-summary.json` and `build/reports/rocksdbjni-comparison-sameblock-50k/comparison.csv`.

Conclusion: recent-block and recent-index coverage caching significantly reduces repeated index seeks and data-block opens for locality-heavy point reads. Pure random `readrandom_hit` still mostly pays per-key SST positioning and in-block seek cost. If the next stage targets pure random hits, a compact in-block seek index or request-level read context is more valuable than expanding the one-entry cache; the request-level path needs a separate design because it touches API/threading semantics.
## Request-level single-key read context design and acceptance

The third step is implemented as an internal request-level context. It does not change the public API, does not modify the SST file format, and does not share context across threads. `Version.get` creates a `PointReadContext` for each single-key read and passes it through `Level0` / `Level`. The context only caches the most recent candidate SST file that actually participated in a table read. If a later lookup in the same level is still covered by that file's user-key range, the file is reused before repeating level file positioning and candidate construction. Actual index-block and data-block reuse remains handled by the table-level recent index coverage cache and recent data-block cache.

Boundaries:
- The context is scoped to one public `get` call chain and is not retained across API calls.
- LevelN hits only when the recent file covers the current user key. Level0 still processes candidates in newest-first order; the cached file only accelerates candidate construction and does not change L0 overlap semantics.
- Range deletes, snapshot sequence, value type, and user-key matching stay in the existing logic.
- `ldb.sstReadStats` now exposes `pointReadContextFileHits` / `pointReadContextFileMisses` to prove whether the path is actually active.

The new `readrandom_burst` benchmark captures application burst / back-and-forth locality point reads. Together with `readrandom_sameblock`, it covers adjacent-locality reads while keeping regular `readrandom_hit` as the pure random-hit workload.
### read context boundary correction

The implementation promotes `PointReadContext` to a `ThreadLocal` short-lived context inside `VersionSet`: consecutive public `get` calls can reuse the most recent candidate SST file only when they run on the same thread, the same current Version, the same column family, and within an idle window of about 10ms. The cache is cleared automatically when the Version changes, the column family changes, or the short-lived window expires. This still keeps the public API unchanged and avoids cross-thread shared state.
### read context window implementation correction

To avoid adding `System.nanoTime()` overhead to every point get, the short-lived window now uses a lightweight call-count window: within the same thread, current Version, and column family, up to 4096 consecutive public `get` calls may reuse the recent candidate file. When the window is exhausted, the context clears itself and relearns the recent file.
## read context 50k comparison result

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Key observation |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 160,133.282 | 406,567.860 | 0.3939 | Pure random hits recorded `pointReadContextFileHits=49987`, but only `tableIndexCacheHits=21` and `tableLastBlockHits=33`, proving request-level file reuse is active while block locality remains low |
| `readrandom_sameblock` | 312,031.173 | 555,629.640 | 0.5616 | `tableIndexCacheHits=47839` and `tableLastBlockHits=47839`; data-block opens dropped to `2161` |
| `readrandom_burst` | 273,191.052 | 493,927.653 | 0.5531 | Back-and-forth burst locality recorded `tableIndexCacheHits=13790` and `tableLastBlockHits=14153`, below monotonic sameblock but clearly above pure hit |

Evidence files: `ldb-longrun/build/reports/ldb-db-bench-readcontext-50k-v2/ldb-db-bench-summary.json` and `build/reports/rocksdbjni-comparison-readcontext-50k/comparison.csv`.

Conclusion: this stage closes all three goals: table recent-block reuse, index-seek coverage caching, and a thread-local read context for consecutive API-level single-key point gets. Pure random hits are still limited by in-block seek / decode cost; locality and burst workloads now remain above the 50% comparison line.
### Block seek anchor experiment conclusion

- This stage formalizes the restart key/offset data built when opening a Block as the lightweight in-memory seek index; `Block.seek` first positions through a restart anchor, then linearly scans only within that restart range, avoiding full-entry indexes and cold-Block predecode.
- In the 50k gate, `readrandom_hit` reached 139,821 ops/s with `blockSeekIndexHits=50,000`, `blockSeekIndexMisses=0`, and `blockSeekIndexFallbacks=0`, proving that random point reads use the Block seek index.
- `readrandom_sameblock` reached 298,209 ops/s, `readrandom_burst` 226,772 ops/s, `multiget_mixed` 238,913 ops/s, `multiget_sameblock` 269,404 ops/s, and `scan` 1,450,280 ops/s; scan does not use the block seek index and reports zero counters.
- Conclusion: restart anchors are a low-risk, format-compatible abstraction, but Block-local restart indexing alone is not enough to raise `readrandom_hit` to 50% of RocksDB JNI; the next stage should still reduce random point-read `tableIndexSeeks` and `tableDataBlockOpens`.
### Table point-read array index plus sparse Block anchor conclusion

- Added a Table-open-time array index for the index block, reducing point-read `tableIndexSeeks` from nearly 50,000 to 0 and avoiding repeated index-block decoding on the hot path.
- Added a direct-mapped Table point-read data-block cache to reduce global block-cache traffic when random reads revisit the same data block; in the final 50k gate, `readrandom_hit` reported `tableDataBlockOpens=11,085` and `tableLastBlockHits=38,915`.
- Restored Block-open-time sparse entry anchors, with one anchor per 4 entries inside restart ranges only, avoiding a full-entry index. With this combination, `multiget_mixed` reached 92.15% of RocksDB JNI, `readrandom_sameblock` reached 53.02%, and `readrandom_burst` reached 48.97%.
- The primary gate `readrandom_hit` reached 137,708 ops/s, only 29.48% of RocksDB JNI at 467,188 ops/s, so the remaining bottleneck is no longer table index seek but random point-read data-block hit/decode/compare cost; the P0 50% target remains incomplete.
### Block.seek skipped-value decoding conclusion

- `Block.seek` and `seekFromOffset` now read values only for the matching candidate entry; skipped entries decode only keys and advance with `SliceInput.skipBytes(valueLength)`, reducing value Slice creation and position movement during random point reads.
- In the 50k comparison, `readrandom_hit` improved to 149,953 ops/s, or 35.08% of RocksDB JNI at 427,426 ops/s; `readrandom_sameblock` reached 311,414 ops/s, or 58.57%; `readrandom_burst` reached 320,670 ops/s, or 62.48%.
- `multiget_mixed` reached 217,566 ops/s, or 99.34% of RocksDB JNI, but the batch path still reports `tableIndexSeeks=25,207`, so the next step should reuse the Table point-read array index and direct cache in MultiGet grouping.
- The primary `readrandom_hit` gate is still below 50%, but this stage proves that the remaining useful work is data-block seek decoding cost and batch-path index reuse, not adding a full-entry in-memory index.
### Reverted follow-up experiments to protect `readrandom_hit`

- A heavier hash/mix strategy for `pointGetBlockCacheSlot` reduced `readrandom_hit` data-block opens, but the 50k `readrandom_hit` result dropped from the prior 149,953 ops/s to 143,843 ops/s, with `readrandom_sameblock` and `readrandom_burst` also regressing. The current release path does not keep this strategy because the hot-path hash cost outweighed the collision reduction.
- A 4-way set-associative point data-block cache reduced `readrandom_hit` `tableDataBlockOpens` to 1,416, but 50k `readrandom_hit` reached only 139,300 ops/s. This proves that block-open counters alone are not sufficient; random-hit throughput is now highly sensitive to per-lookup CPU probing cost.
- Removing the defensive candidate comparison after `Table.get` / MultiGet seek dropped 50k `readrandom_hit` to 102,426 ops/s. Even though `Block.seek` should return the first entry `>= target`, the check is not removed in the current path.
- Reusing the Table-open-time array index in MultiGet grouping reduced `multiget_mixed` `tableIndexSeeks` to 0, but 50k `multiget_mixed` dropped to 224,801 ops/s, below the known result with `indexBlock.seek`. The batch path therefore keeps per-key index-block seek plus same-block data-block reuse.
- Conclusion: the retained path is restart/sparse anchors, the Table single-key array index, the direct-mapped data-block cache, and `Block.seek` skip-value decoding. Further progress toward the `readrandom_hit` 50% target should focus on lower-CPU data-block key decoding and comparison, not more complex runtime cache probing.
### Block.readKey direct-copy 50k acceptance conclusion

- Shared-prefix key reconstruction in `Block.readKey` no longer creates a temporary `SliceOutput` wrapper. It now copies the shared prefix with `Slice.setBytes` and writes the non-shared suffix with `SliceInput.readBytes`; the file format, restart/sparse anchors, and seek semantics are unchanged.
- In the 50k `read_optimized` gate, `readrandom_hit` reached 161,956 ops/s, a small improvement over the previous final guard at 155,081 ops/s. The main path still reports `blockSeekIndexHits=50,000`, `blockSeekIndexMisses=0`, and `blockSeekIndexFallbacks=0`.
- In the same-host RocksDB JNI comparison, `readrandom_hit` reached 441,936 ops/s, so the current LDB/RocksDB JNI ratio is 36.65%; the primary 50% target remains incomplete.
- The surrounding workloads did not show a blocking regression: `readrandom_sameblock` reached 315,204 ops/s, or 54.94%; `readrandom_burst` reached 381,084 ops/s, or 78.60%; `multiget_mixed` reached 294,708 ops/s, or 87.45%; `scan` reached 1,519,138 ops/s.
- Conclusion: reducing temporary objects during key reconstruction is a positive direction, but the remaining gap is still data-block key decoding and comparison cost. The next step should keep looking for lower-CPU paths without adding full-entry indexes or more complex runtime cache probing.
### Reverted Block.seek scratch-key reuse experiment

- The single-call `Block.seek` scratch-buffer reuse experiment for shared-key reconstruction has been reverted: 50k `readrandom_hit` dropped from 161,956 ops/s in the direct-copy version to 153,643 ops/s, failing the primary-gate-first rule.
- Although the experiment showed short-run improvements in `multiget_mixed`, `multiget_sameblock`, and `scan`, the objective uses `readrandom_hit` as the primary acceptance metric, so scratch-key reuse is not kept in the current version.
- The retained positive change remains `Block.readKey` direct-copy: it removes the temporary `SliceOutput` wrapper without reusing a cross-entry key buffer, preserving the hotter point-read shape under JIT/escape analysis.
### InternalUserComparator slice-level comparison 50k acceptance conclusion

- `InternalUserComparator.compare(Slice, Slice)` no longer constructs two `InternalKey` objects for every comparison and no longer unpacks `ValueType`. It now compares the user-key Slice portions directly and reads the sequence number from the trailing packed sequence/type word, preserving user-key ascending and sequence descending ordering.
- In the 50k `read_optimized` gate, `readrandom_hit` reached 174,459 ops/s. The same-host RocksDB JNI result was 396,410 ops/s, so LDB/RocksDB JNI reached 44.01%; the primary 50% target is closer but still incomplete.
- Surrounding workloads: `readrandom_sameblock` reached 322,833 ops/s, or 61.81%; `readrandom_burst` reached 311,130 ops/s, or 63.67%; `multiget_mixed` reached 277,267 ops/s, or 136.68%; `multiget_sameblock` reached 373,474 ops/s; `scan` reached 1,254,277 ops/s.
- The stats path remains correct: `readrandom_hit` reports `blockSeekIndexHits=50,000`, `blockSeekIndexMisses=0`, `blockSeekIndexFallbacks=0`, and `tableIndexSeeks=0`; scan still does not trigger the block seek index.
- Conclusion: reducing comparator object construction is one of the most effective current directions. The next step should keep optimizing slice-level internal-key/user-key comparison, for example by reducing user-key `slice(...)` wrapper allocation, while still avoiding full-entry indexes and complex runtime cache probing.
### Reverted bytewise raw-array user-key comparison experiment

- The default-`BytewiseComparator` raw-array user-key comparison experiment inside `InternalUserComparator` has been reverted. It avoided user-key `slice(...)` wrappers, but 50k `readrandom_hit` dropped from 174,459 ops/s in the slice-level internal-key comparison version to 151,875 ops/s, with visible regressions in `readrandom_sameblock` and `readrandom_burst`.
- This shows that under the current JIT/call shape, a handwritten raw-array fast path is not necessarily better than the existing `Slice.compareTo`/`BytewiseComparator` path. The current version keeps only the proven optimization that avoids constructing `InternalKey` objects.
### Reverted InternalUserComparator length-guard removal experiment

- Removing the internal-key length `checkArgument` guards at the beginning of `InternalUserComparator.compare(Slice, Slice)` dropped the 50k `readrandom_hit` result from the validated 174,459 ops/s slice-level internal-key comparison baseline to 132,221 ops/s, so the experiment has been reverted under the primary-gate-first rule.
- Although the checks look defensive, removing them did not improve the current JIT/call shape and clearly polluted the random-hit baseline. The current version restores the explicit length guards and keeps only the proven optimization that avoids constructing `InternalKey` objects.
- Further comparator work should look for stable Slice range-comparison support rather than deleting boundary guards or adding handwritten raw-array fast paths.
### Reverted Slice range-comparison fast path experiment

- This experiment added an offset/length range comparison to `Slice` and used it from `InternalUserComparator.compare` for the default `BytewiseComparator` path, avoiding `left.slice(...)` / `right.slice(...)` wrapper objects while preserving the original slice-based path for custom comparators.
- The 50k `read_optimized` short benchmark dropped the primary `readrandom_hit` metric to 141,790 ops/s, clearly below the validated 174,459 ops/s slice-level internal-key comparison baseline. The current version therefore does not keep the `Slice.compareTo(offset,length,...)` fast path.
- This matches the earlier raw-array user-key fast-path result: under the current JIT/call shape, avoiding `slice(...)` wrappers does not automatically improve throughput. Future comparator work should require stronger hotspot evidence before spending more effort on handwritten user-key bytewise paths.
### Reverted sequence-unpack inlining experiment

- This experiment inlined `SequenceNumber.unpackSequenceNumber(...)` as `packed >>> 8` inside `InternalUserComparator.compare` to remove one static method call from the comparator hot path. The change is semantically equivalent, but it changes the call shape visible to the JIT.
- The 50k `read_optimized` short benchmark dropped `readrandom_hit` to 164,108 ops/s, below the 171,696 ops/s restored-guard run and the 174,459 ops/s historical slice-level internal-key comparison baseline. `readrandom_sameblock`, `readrandom_burst`, and `scan` also weakened, so the experiment has been reverted.
- The current version keeps `SequenceNumber.unpackSequenceNumber` to express the packed sequence/type format semantics and leaves this tiny inlining decision to the JVM rather than expanding it manually in the business comparator.
### Reverted Block sparse-anchor interval=2 experiment

- This experiment tightened the Block open-time sparse seek-anchor interval from one anchor per 4 entries to one anchor per 2 entries. It still indexed only sparse anchors inside restart regions, did not change the SST file format, and was not a full-entry index.
- The 50k `read_optimized` short benchmark dropped the primary `readrandom_hit` metric to 146,226 ops/s, below both the 171,696 ops/s restored-guard run and the 174,459 ops/s historical baseline. `readrandom_sameblock`, `readrandom_burst`, and `multiget_mixed` also weakened.
- Conclusion: denser in-memory anchors add open-time decoding and hot-path anchor binary-search/object pressure. The current version keeps the balanced one-anchor-per-4-entries setting. Future work to shorten in-block scans should focus on persisted compact block-local-index admission/layout rather than simply increasing current in-memory anchor density.
### Reverted Block sparse-anchor interval=8 experiment

- This experiment relaxed the Block open-time sparse seek-anchor interval from one anchor per 4 entries to one anchor per 8 entries. It still narrowed scans through sparse anchors inside restart regions, did not change the SST file format, and was not a full-entry index.
- In the first 50k `read_optimized` short run, `readrandom_hit` reached 177,100 ops/s, above the 171,696 ops/s restored-guard run and the 174,459 ops/s historical baseline. However, `readrandom_sameblock`, `readrandom_burst`, `multiget_mixed`, and `scan` were all below the restored baseline, so the gain was not workload-general.
- In the same-parameter rerun, `readrandom_hit` dropped to 157,745 ops/s; `readrandom_sameblock` was 322,031 ops/s, `readrandom_burst` was 343,650 ops/s, `multiget_mixed` was 297,009 ops/s, and `scan` was 1,313,819 ops/s. Because the primary metric was unstable and the surrounding paths did not win overall, interval=8 is not kept in the current version.
- Conclusion: simple tuning of the current in-memory sparse-anchor density is near its useful boundary. Interval=4 remains the stable compromise; future work should move to clearer admission/layout design or finer-grained hotspot evidence instead of continuing blind anchor-interval tuning.
### Reverted exact-hit return from Block sparse anchors

- This experiment added value offset/length to Block open-time sparse anchors and returned the anchor value directly only when `targetKey` exactly matched the sparse anchor key. Non-exact hits still used the original scan path from the anchor offset. The experiment still covered only sparse anchors, was not a full-entry index, and did not change the SST file format.
- The 50k `read_optimized` short benchmark dropped `readrandom_hit` to 143,404 ops/s, clearly below the current restored baseline; `multiget_mixed` and `scan` also weakened. Although `readrandom_sameblock` reached 333,930 ops/s in this short run, the primary gate comes first and the gain was not overall.
- Conclusion: adding more fields and an extra exact comparison to the current in-memory sparse anchors increases hot-path object/compare cost, and sparse exact-anchor hits do not offset it. The current version keeps anchors limited to key, entry offset, and previous key for narrowing scans, not for directly returning candidate entries.
### Confirmed blockSeekIndex stats semantics boundary

- This experiment tried to distinguish `blockSeekIndexHits/Misses` by whether `Block.seek` returned a candidate entry. It could classify the rare “index available but no candidate entry” case as a miss, but it added a result branch to the `Table.seekWithBlockSeekIndex` hot path.
- In the 50k `read_optimized` validation run, `readrandom_hit` dropped to 130,404 ops/s. Also, logical misses in miss/mixed workloads are already represented by `candidateEntryMisses`, `bloomFalsePositives`, and Bloom skip stats. For Bloom false positives, `Block.seek` usually still returns the first candidate entry `>= target`, so `blockSeekIndexMisses` is not equivalent to a business key miss.
- The current version reverts that hot-path branch. `blockSeekIndexHits` continues to mean “the Block open-time seek index was used”, `blockSeekIndexFallbacks` means no seek index was available, and `blockSeekIndexMisses` remains reserved by `SeekResult` rather than being used as a logical-miss metric. Release and performance evidence should use `candidateEntryHits/Misses`, `filterSkips`, and `bloomFalsePositives` for business hit/miss interpretation.
### Reverted Block.readKey shared-key null-guard experiment

- This experiment replaced the Guava `checkState(previousKey != null, ...)` in the shared-prefix path of `Block.readKey` with a handwritten `if (previousKey == null) throw new IllegalStateException(...)`, hoping to reduce generic precondition helper cost on the hot path. It did not change the file format, anchors, key reconstruction algorithm, or exception-protection semantics.
- The 50k `read_optimized` short benchmark dropped `readrandom_hit` to 128,680 ops/s; `readrandom_sameblock` reached 274,510 ops/s and `scan` reached 1,184,321 ops/s, all weaker than the restored baseline.
- Conclusion: this guard shape is not the current bottleneck, and the original `checkState` call shape is likely handled better by the JIT. The current version restores `checkState` and keeps the useful work focused on the validated direct-copy key reconstruction rather than handwritten micro-branches.
### Reverted exact restart-key starting-point experiment

- This experiment changed `Block.restartIndexBefore` from `restartKey < target` to `restartKey <= target`, so a target exactly equal to a restart key would scan from that restart region instead of the previous one. It did not change the file format, did not add index entries, and still relied only on restart/anchor data to narrow scans.
- The 50k `read_optimized` short benchmark reached 164,277 ops/s for `readrandom_hit`, below the restored baseline and the 174,459 ops/s historical baseline. `readrandom_sameblock` reached 285,996 ops/s, `readrandom_burst` reached 309,092 ops/s, and `multiget_mixed` reached 309,195 ops/s, so the surrounding workloads did not win overall either.
- Conclusion: exact restart-key starts either trigger too rarely or change the call shape enough that the cost outweighs the benefit under the current workload. The current version restores the original `restartKey < target` strategy and continues to use sparse anchors to narrow scans inside restart regions.
### Reverted Block sparse-anchor interval=3/5 experiments and current boundary

To verify whether the current one-anchor-per-4-entries setting was merely a local accident, this round also tested `SEEK_ANCHOR_INTERVAL=3` and `SEEK_ANCHOR_INTERVAL=5`. Both experiments affected only the lightweight in-memory index built when a `Block` is opened. They still indexed only sparse anchors inside restart regions, did not change the SST file format, and did not introduce a full-entry index.

The 50k `read_optimized` results are listed below. The baseline is the restored interval=4 result: `readrandom_hit=179,215.195 ops/s`; the same-host RocksDB JNI `readrandom_hit` result was `369,105.296 ops/s`, so the current ratio is `48.55%`.

| Configuration | readrandom_hit | readrandom_sameblock | readrandom_burst | multiget_mixed | scan | Conclusion |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| interval=4 current stable baseline | 179,215.195 | 369,652.697 | 329,674.387 | 335,029.251 | 2,412,568.517 | retained |
| interval=3 | 159,506.271 | 395,670.416 | 366,295.268 | 280,144.532 | 1,680,175.276 | primary metric, MultiGet, and scan regressed; reverted |
| interval=5 | 151,884.968 | 400,606.679 | 393,660.182 | 309,080.221 | 1,835,044.206 | primary metric, MultiGet, and scan regressed; reverted |

Evidence reports:
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k/ldb-db-bench-summary.json`
- `build/reports/rocksdbjni-comparison-current-50k/comparison.csv`
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k-anchor3/ldb-db-bench-summary.json`
- `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-50k-anchor5/ldb-db-bench-summary.json`

Conclusion: simple interval tuning for the current open-time sparse entry anchors has reached its useful boundary. Interval=4 is the current stable compromise across `readrandom_hit`, locality reads, MultiGet, and scan. Testing `2/3/5/8` did not produce a retainable overall win. To move `readrandom_hit` beyond the current `48.55%` line, the next stage should persist a file-format-level sparse entry-anchor index with previous-key or equivalent recovery information, or find new data-block key decoding/comparison hotspots, rather than continuing to tune the current in-memory anchor density.
### Next file-format index design entry point

Because the default `readrandom_hit` path has not been proven stable above 50%, intervals `2/3/5/8` did not produce retainable wins, and forcing the v3 restart-anchor local index into single-key gets regressed, the next file-format optimization should not reuse the v3 `block.local_index.v1` restart-anchor semantics. It now has a separate sparse entry-anchor index design. See `docs/storage-format-0.12-entry-anchor-index-design.md` and the English copy at `docs/storage-format-0.12-entry-anchor-index-design.en.md`.
## Additional update: default MultiGet same-block seekAll wiring

This round does not change the file format. It wires the existing `seekDenseBlock` batch path into `Table.get(List<Slice>)`: when a group has enough lookups in the same data block and the SST does not declare the opt-in `block_local_index`, the batch path sorts the keys and calls `Block.seekAll` to avoid repeating per-key restart/anchor seeks for dense same-block MultiGet. If the table declares the v3 block-local index, the existing explicit opt-in local-index branch remains in use, so the format policy is unchanged.

Validation: `compileJava` passed; `LdbCoreBehaviorTest`, `LdbObservabilityTest`, and `TablePropertiesTest` passed. In the 50k read-optimized quick gate, `multiget_sameblock` reached `323,423.424 ops/s`, `multiget_mixed` reached `330,660.713 ops/s`, and `scan` reached `2,166,809.676 ops/s`. A separate `readrandom_hit` rerun reached `195,440.834 ops/s`; the stats still show `blockSeekIndexHits=50000` and `mayContainRequests=13`, confirming that this MultiGet wiring does not change the point-read Block seek-index hot path.

Boundary: this round does not strictly prove `readrandom_hit` above 50% of RocksDB JNI. It is retained as a MultiGet-side regression guard and dense-batch improvement. To keep pushing the P0 target, the next highest-value work is still the remaining point-hit cost in table/block cache and InternalKey decoding, not forcing the v4 entry-anchor index back into the default hot path.
## Plan update: lightweight InternalKey decoding after hit

The next optimization does not change the Block/SST format and does not introduce a full-entry index. After table direct get returns a candidate entry, `Level` / `Level0` currently construct a full `InternalKey` object for every hit in order to compare the user key and read sequence/type. This cost is shared by `readrandom_hit` and MultiGet hit paths.

This round will add lightweight encoded-key readers to `InternalKey`: compare the user-key prefix directly from the encoded `Slice`, and read sequence/type from the trailer. `Level` / `Level0` will use those helpers to avoid allocating a full `InternalKey` on the hot path. The change only affects in-memory decoding; it does not change visible semantics, file format, or comparator ordering.
### Lightweight InternalKey decoding experiment result: not retained

This round tried to let `Level` / `Level0` compare the user key and read sequence/type directly from the encoded internal key after a candidate entry was found, avoiding full `InternalKey` allocation. The code compiled and the targeted read-path tests passed; a standalone `readrandom_hit` run reached `216,060.839 ops/s`. However, side benchmarks were unstable and `multiget_mixed` repeatedly fell below the previous safe range. Under this workstream's requirement to watch sameblock/burst/scan/MultiGet regressions, the experiment code has been reverted and is not retained in the default hot path.

Retained conclusion: post-hit object allocation may still be a future direction, but it needs finer-grained profiling or stable repeated benchmarks proving that MultiGet is not depressed. This version does not keep the change.
## Plan update: reuse table-open-time point-get index for MultiGet

Single-key `Table.get(Slice)` already decodes the index block at table-open time into `pointGetIndexLimitKeys` / `pointGetIndexBlockHandles`, then uses binary search to locate the data block without constructing an index iterator or decoding an index entry per lookup. The batch `Table.get(List<Slice>)` path still calls `indexBlock.seek` for each key and parses the block handle from the index entry.

This experiment changes batch data-block routing to reuse `findPointGetBlockHandle`, allowing MultiGet to share the same table-open-time lightweight index as point reads. It does not change the Block/SST format, does not add a full-entry index, and does not change the in-block seek-anchor policy. Acceptance still uses `readrandom_hit` as the primary signal while watching `readrandom_sameblock`, `readrandom_burst`, `scan`, `multiget_mixed`, and `multiget_sameblock`; if MultiGet or sameblock shows a stable regression, the change must be reverted.
### MultiGet point-get index reuse experiment result: not retained

This round tried to make `Table.get(List<Slice>)` use `findPointGetBlockHandle` and reuse the table-open-time point-get index arrays instead of calling `indexBlock.seek` for each key. The code compiled and targeted read-path tests passed. The stats showed `tableIndexSeeks` dropping from `25207` to `0` in `multiget_mixed`, replaced by `tableIndexCacheHits=25207`.

However, performance did not improve: the 50k `multiget_mixed` run reached only `242,850.954 ops/s`, below the previous safe range. This suggests the current mixed batch bottleneck is not index-block seek itself, or that array binary search / last-index reuse does not improve overall locality. The experiment code has been reverted; the default batch path continues to use the original `indexBlock.seek` grouping strategy.
## Plan update: MultiGet data-block grouping observability

The previous two experiments showed that blindly optimizing post-hit decoding or batch index routing does not reliably improve `multiget_mixed`. The next step is observability rather than another hot-path change: `Table.get(List<Slice>)` will report how many data-block groups each batch direct get creates, how many keys participate in those groups, how many groups/keys reach the dense threshold, and how often `seekDenseBlock` is actually used.

This change only extends `readStats`; it does not change read semantics, the Block/SST format, or introduce a full-entry index. The acceptance goal is to make `multiget_mixed` explainable: whether low results come from nearly every key landing in a different data block, dense groups not triggering, block cache/open cost, or another path. Follow-up optimizations must be chosen from these fields.
### MultiGet data-block grouping observability result

The new observability fields explain the difference between `multiget_mixed` and `multiget_sameblock`. In the 50k `multiget_mixed` run, `directGetBatchKeys=25207` but `tableBatchDataBlockGroups=24917`, or about `1.01` key per data-block group; `tableBatchDenseBlockGroups=0` and `tableBatchSeekAllCount=0`. This means mixed is effectively a sparse random point-get collection, so the same-block `seekAll` optimization does not trigger and low mixed results should not be attributed to the dense batch path.

In the 50k `multiget_sameblock` run, `directGetBatchKeys=50000`, `tableBatchDataBlockGroups=2158`, `tableBatchDenseBlockGroups=1857`, `tableBatchDenseBlockKeys=48805`, and `tableBatchSeekAllCount=1857`. This proves the same-block batch optimization covers dense batch workloads; the `blockSeekIndexHits=1195` counter corresponds only to sparse tail keys that did not enter dense `seekAll`.

Conclusion: further `multiget_mixed` work should not keep guessing around same-block `seekAll` or batch index routing. The workload is closer to many random point gets. The next useful work should return to point-hit data-block open/cache hits, candidate-entry decoding, and benchmark stability.
## Plan update: split direct-read data-block cache observability

The current `tableDataBlockOpens` field means that the Table layer requested a data block on the point-get/MultiGet path, but it does not distinguish whether a local point-get block-cache miss then hit the global `BlockCache`, or whether the SST block was actually read and decoded. This makes the remaining `readrandom_hit` block-open cost hard to attribute.

This round only adds observability fields: for direct-read data-block opens, record global block-cache hits/misses and actual read/decode count. The change does not alter cache policy, the Block/SST format, or introduce a full-entry index. The goal is to decide whether the next optimization should target the point-get local cache, global block cache, or block decode/open cost.
### direct-read data-block cache observability result

The new direct-read cache fields explain the remaining block-open cost in `readrandom_hit`. In the 50k `readrandom_hit` run, `tableDataBlockOpens=11085`, `tableDirectReadBlockCacheHits=9696`, `tableDirectReadBlockCacheMisses=1389`, and `tableDirectReadBlockReads=1389`. This means that after the Table-local point-get block cache misses, most requests do not re-read or re-decode the block; they hit the global `BlockCache`. Actual read/decode happens for only about `2.78%` of point gets.

In the 50k `multiget_mixed` run, `tableDataBlockOpens=24917`, `tableDirectReadBlockCacheHits=23528`, `tableDirectReadBlockCacheMisses=1389`, and `tableDirectReadBlockReads=1389`, matching the sparse data-block grouping conclusion: mixed behaves more like many random point gets, and its main cost is many direct-read data-block lookups rather than dense `seekAll`.

Conclusion: the next promising optimization is not reducing actual block reads further, nor adding another file-format index; real read/decode is already small. The more promising direction is reducing global `BlockCache` synchronized lookups caused by Table-local direct-map point-cache conflicts, for example with a small set-associative point cache or a safer local-hit policy, but it must be validated across `readrandom_hit`, sameblock/burst, scan, and MultiGet.
## Plan update: Table-local point-get block cache 2-way experiment

Based on the direct-read cache observability, most Table-local point-get block cache misses in `readrandom_hit` then hit the global `BlockCache`, so actual read/decode is already small. The remaining cost may come from direct-map local-cache conflicts and synchronized global-cache lookups. This experiment expands the Table-local point-get block cache from one entry per slot to two recent entries, primary and secondary: primary hits return directly; secondary hits are promoted to primary; on miss, the new block becomes primary and the old primary moves to secondary.

This only changes in-process Table cache policy. It does not change the Block/SST format or add a full-entry index. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, `multiget_mixed`, and the direct-read cache hit/read fields; if locality worsens or MultiGet regresses, the change must be reverted.
### Table-local point-get block cache 2-way experiment result: not retained

The 2-way local point-get block cache experiment proved that the observability direction is useful: a standalone 50k `readrandom_hit` rerun reached `222,654.270 ops/s`; `tableDataBlockOpens` dropped from `11085` in the direct-map observation to `2110`, and global `BlockCache` hits dropped from `9696` to `721`. However, sameblock side results were unstable and consistently low: two standalone `readrandom_sameblock` reruns reached `313,327.184 ops/s` and `302,395.456 ops/s`, below the previous safe range.

Under this workstream's requirement to watch sameblock/burst/scan/MultiGet together, the 2-way cache logic has been reverted and is not retained in the default path. The retained conclusion is that local point-cache conflicts are real, but a simple 2-way MRU policy changes sameblock locality. Future work should either use an adaptive policy for sparse random mode only, or first add finer local-cache collision/promote statistics.
## Plan update: Table-local point-cache hit/collision observability

The 2-way local cache experiment proved that direct-map slot conflicts push many requests back to the global `BlockCache`, but a simple primary/secondary MRU policy depressed `readrandom_sameblock`. The next step is finer observability rather than another policy change: distinguish `lastPointGetBlock` hits, direct-map slot hits, slot misses, and collisions where the slot already contains another block.

This only extends `readStats`; it does not change the local cache replacement policy, the Block/SST format, or introduce a full-entry index. The goal is to determine whether collisions mostly come from wide random-hit jumps or from sameblock/burst locality patterns. Any later adaptive policy must use these fields to constrain where it is enabled.
### Table-local point-cache hit/collision observability result

The new detailed fields explain why a fixed 2-way cache hurts sameblock. In the 50k `readrandom_hit` run, `tablePointGetLastBlockHits=33`, `tablePointGetSlotHits=38882`, `tablePointGetSlotMisses=11085`, and `tablePointGetSlotCollisions=9974`. Random hit has almost no consecutive last-block hits and very high slot collisions, so many requests fall back to the global `BlockCache`.

In the 50k `readrandom_sameblock` run, `tablePointGetLastBlockHits=47839`, `tablePointGetSlotHits=916`, `tablePointGetSlotMisses=1245`, and `tablePointGetSlotCollisions=313`. Sameblock mostly depends on `lastPointGetBlock`, with very few slot collisions. In the 50k `readrandom_burst` run, `tablePointGetLastBlockHits=14153`, `tablePointGetSlotHits=34613`, `tablePointGetSlotMisses=1234`, and `tablePointGetSlotCollisions=309`; burst sits between the two, but is also not a high-collision mode.

Conclusion: if local-cache optimization continues, it should not replace direct-map with a fixed 2-way policy. A secondary cache should be enabled only adaptively for random-hit mode. A feasible trigger should look like: very low last-hit ratio and high slot-collision ratio. Sameblock/burst already have high last-hit or slot-hit rates, so they should keep the current direct-map plus last-block fast path.
## Plan update: adaptive secondary point-cache for random collision mode

Based on the point-cache hit/collision observability, random `readrandom_hit` has almost no `lastPointGetBlock` hits and high slot collisions, while sameblock/burst already have high last-hit or slot-hit rates. The next experiment will not enable a fixed 2-way cache. Instead, Table will enable secondary-cache behavior adaptively: only after enough slot-miss samples, high slot-collision ratio, and extremely low last-block hit ratio will it check the secondary slot and maintain secondary entries on misses.

This policy only targets random collision mode. Sameblock/burst should keep the existing direct-map plus last-block fast path. The change does not alter the Block/SST format and does not add a full-entry index. Acceptance still uses `readrandom_hit` as the primary signal, while requiring no stable regression in `readrandom_sameblock`, `readrandom_burst`, `scan`, or `multiget_mixed`.
### Adaptive secondary point-cache experiment result: not retained

This round implemented an adaptive secondary point-cache for random collision mode: it only enabled secondary behavior after enough slot misses, high collision ratio, and very low last-block hit ratio. The stats confirmed that it reduced random-hit local-cache misses: in the 50k `readrandom_hit` run, `tableDataBlockOpens` dropped from the direct-map observation of `11085` to `3127`, and `tableDirectReadBlockCacheHits` dropped from `9696` to `1738`. However, the primary metric did not improve reliably: the `secondary128` version reached only `154,794.976 ops/s`, and the conservative-threshold version reached only `195,141.218 ops/s` in a standalone rerun. The extra branches, array accesses, and replacement maintenance outweighed the reduction in global-cache lookups.

Under this workstream's `readrandom_hit` primary acceptance criterion, the adaptive secondary-cache code has been reverted and is not retained in the default path. The retained conclusion is that local slot collision is real, but solving it with a Java-level second array is too expensive. Future work should look for lower-cost hash/slot strategies or more direct per-get cost reductions rather than adding another cache layer.
## Plan update: point-get local block cache slot by index position

The previous observability showed high direct-map point-cache slot collisions in random `readrandom_hit`, while adding a secondary cache layer introduced enough branch/replacement cost to offset the benefit. The next experiment does not add another cache layer. It only changes how single-key `Table.get(Slice)` chooses the local block-cache slot: reuse the table-open-time `pointGetIndex` data-block index position as the slot input, instead of hashing only block offset/dataSize.

This does not change the Block/SST format, does not add a full-entry index, and does not affect the batch `Table.get(List<Slice>)` grouping strategy. The goal is to reduce random-hit slot collisions and global `BlockCache` fallbacks while preserving the `lastPointGetBlock` fast path for sameblock/burst. Acceptance still uses `readrandom_hit` as the primary signal and watches sameblock, burst, scan, and MultiGet regressions.
### point-cache slot by index position experiment result: not retained

This round tried to make single-key `Table.get(Slice)` use the table-open-time index position as the local point-cache slot input. The stats eliminated collisions completely: in the 50k `readrandom_hit`, `readrandom_sameblock`, and `readrandom_burst` runs, `tablePointGetSlotCollisions` was `0`; actual block reads stayed around `1389`, `1103`, and `1095` respectively.

However, performance regressed across the board: `readrandom_hit=146,626.491 ops/s`, `readrandom_sameblock=217,524.089 ops/s`, and `readrandom_burst=196,089.197 ops/s`. This shows that eliminating collisions is not sufficient; the index-position slot likely disrupts locality provided by the original offset/dataSize hash or adds path cost. The experiment code has been reverted; the default path keeps the original blockHandle hash slot.
## Plan update: complete blockSeekIndex miss semantics

`blockSeekIndexHits/Fallbacks` already prove that default data blocks use the lightweight seek index built at block-open time, but `blockSeekIndexMisses` is not yet meaningful enough. The next change only adjusts the counter semantics: when a data block has a seek index, execute `Block.seek` once; if it returns a candidate entry, count `blockSeekIndexHits`; if it returns `null`, count `blockSeekIndexMisses`; when the data block has no seek index, keep counting `blockSeekIndexFallbacks`.

This does not change seek behavior, does not add an extra block seek, does not change the Block/SST format, and does not introduce a full-entry index. The goal is to make hit/miss/fallback all usable as release-time read-path evidence.
### blockSeekIndex miss counter validation result

After the adjustment, `blockSeekIndexHits/Misses/Fallbacks` have clearer semantics: `Hits` means the data block has a seek index built at block-open time and `Block.seek` returned a candidate entry; `Misses` means the block has a seek index but there is no candidate entry inside that block; `Fallbacks` means the data block has no seek index. The 50k `readrandom_hit` run reported `blockSeekIndexHits=50000`, `blockSeekIndexMisses=0`, and `blockSeekIndexFallbacks=0`, proving that the default hit path uses the block seek index.

The 50k `readrandom_miss` run reported `blockSeekIndexHits=48561`, `blockSeekIndexMisses=0`, and `blockSeekIndexFallbacks=0`, while `candidateEntryMisses=48561`. This means a logical read miss is not the same as a block seek-index miss: the in-block seek still returns the first candidate entry greater than or equal to the target internal key, and the upper layer later rejects it by user-key/sequence/type semantics. Future reports should not interpret `blockSeekIndexMisses` as readrandom logical misses.
## Current retained-state 50k quick-gate baseline

This round ran the current default retained path through the 50k `read_optimized` quick gate, covering `readrandom_hit`, `readrandom_sameblock`, `readrandom_burst`, `multiget_mixed`, `multiget_sameblock`, and `scan`. No code was changed in this run. The purpose is to prove whether the lightweight seek index built when opening a `Block` is still active on the default hot path, and to check sameblock, burst, scan, and MultiGet for visible regressions.

| Scenario | ops/s | Key stats |
| --- | ---: | --- |
| `readrandom_hit` | 173,332.155 | `blockSeekIndexHits=50000`, `blockSeekIndexMisses=0`, `blockSeekIndexFallbacks=0`, `tableDataBlockOpens=11085`, `tablePointGetSlotCollisions=9974` |
| `readrandom_sameblock` | 347,573.416 | `blockSeekIndexHits=50000`, `tablePointGetLastBlockHits=47839`, `tableDataBlockOpens=1245` |
| `readrandom_burst` | 333,896.282 | `blockSeekIndexHits=50000`, `tablePointGetLastBlockHits=14153`, `tablePointGetSlotHits=34613` |
| `multiget_mixed` | 353,465.053 | `directGetBatchKeys=25207`, `tableBatchDataBlockGroups=24917`, `tableBatchDenseBlockGroups=0`, `blockSeekIndexHits=25207` |
| `multiget_sameblock` | 413,432.248 | `tableBatchDenseBlockGroups=1857`, `tableBatchDenseBlockKeys=48805`, `tableBatchSeekAllCount=1857`, `blockSeekIndexHits=1195` |
| `scan` | 1,265,646.556 | The scan path does not trigger point-get/block-seek stats, so `blockSeekIndexHits=0` is expected |

Evidence file: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-current-retained-gate-50k/ldb-db-bench-summary.json`.

Conclusion: the functional goal is closed for the current default path. Hit point reads and sparse/dense MultiGet all report no seek-index fallback, proving that the lightweight restart/anchor seek index built at block-open time participates in the default read path. Dense same-block MultiGet also continues to exercise the `seekAll` batch path. The performance goal is not closed yet: this round's `readrandom_hit=173,332.155 ops/s` is lower than several earlier 50k samples and has not been paired with a same-run RocksDB JNI comparison, so the P0 target of "at least 50%" cannot be claimed yet. The next step should be either a same-configuration RocksDB JNI comparison or repeated quick gates to quantify variance before deciding whether to continue point-hit optimization or move into release closure.
## Same-run RocksDB JNI 50k comparison conclusion

This round used `scripts/run-rocksdbjni-comparison.ps1` to compare the current LDB summary against RocksDB JNI 10.10.1 with the same basic parameters. The RocksDB JNI runner currently does not support `multiget_sameblock` or `scan`, so those two scenarios remain LDB-only regression gates. The comparable scenarios are `readrandom_hit`, `readrandom_sameblock`, `readrandom_burst`, and `multiget_mixed`.

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Conclusion |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 173,332.155 | 516,413.693 | 0.3356 | Primary acceptance is still below 50%; keep this as the next optimization focus |
| `readrandom_sameblock` | 347,573.416 | 634,482.339 | 0.5478 | Locality point reads are above 50%, so the current recent block/index path is retainable |
| `readrandom_burst` | 333,896.282 | 596,462.975 | 0.5598 | Burst locality is above 50%, with no visible side regression in this gate |
| `multiget_mixed` | 353,465.053 | 464,217.209 | 0.7614 | Sparse mixed MultiGet is above 50%; the current batch direct-get and Bloom path is retainable |

Evidence file: `build/reports/rocksdbjni-comparison-current-retained-gate-50k-supported4/comparison.csv`.

Conclusion: the current version has closed the functional work around the lightweight Block seek index, seek counters, and MultiGet/dense same-block regression observation, but the P0 primary performance target is not complete because pure random `readrandom_hit` is only `33.56%` of RocksDB JNI. The next highest-value work should focus on per-key random hit-path cost around `Block.seek`, candidate-key decoding, and upper-layer InternalKey validation. MultiGet and dense same-block should not be the next priority because they are already above 50% in this gate or are LDB-only regression guards.
## Plan update: lightweight InternalKey validation for single-key get only

The same-run RocksDB JNI comparison shows that `readrandom_sameblock`, `readrandom_burst`, and `multiget_mixed` are already above 50%, while pure random `readrandom_hit` is still only `33.56%`. The next step should not prioritize MultiGet or dense same-block paths. Instead, it should focus on the per-hit `InternalKey` allocation and user-key/type/sequence validation cost after `Level.get` / `Level0.get` receives a table candidate.

This experiment adds lightweight encoded-internal-key readers only to the single-key get hot path: compare the encoded key's user-key prefix directly and read sequence/type from the trailer. The `get(List)` MultiGet post-processing path is intentionally left unchanged to avoid repeating the previous lightweight-decoding experiment's mixed-MultiGet instability. This does not change the Block/SST format, comparator ordering, full-entry index policy, range-delete behavior, snapshot semantics, or deletion/value semantics. Acceptance still uses `readrandom_hit` as the primary signal and reruns sameblock, burst, scan, and MultiGet; if the primary metric does not improve reliably or side paths regress, the change must be reverted.
### Single-key-only lightweight InternalKey validation result: not retained

This round limited lightweight encoded-internal-key validation to single-key `Level.get` / `Level0.get`, while leaving MultiGet post-processing unchanged. Compilation passed, and `LdbCoreBehaviorTest`, `LdbObservabilityTest`, and `TablePropertiesTest` passed. However, the 50k `read_optimized` quick gate did not improve: `readrandom_hit=155,714.149 ops/s`, below the current retained baseline of `173,332.155 ops/s`; `multiget_mixed=252,269.161 ops/s` was also below the previous safe range. sameblock, burst, sameblock MultiGet, and scan did not provide a stable enough gain to offset the primary-metric drop.

Therefore this experiment is reverted and not retained in the default path. The retained conclusion is that post-hit `InternalKey` allocation may still be a cost, but replacing full object construction with Java-level raw-array user-key comparison and trailer helpers does not reliably improve the primary metric under the current JIT and data distribution. The next step should not keep tuning this implementation detail; it should return to `Block.seek` scan length, candidate-entry decoding count, or more direct block-local positioning cost.
## Plan update: Block.seek restart-region scan-boundary experiment

The current `Block.seek` already narrows the starting point to a restart region by using restart-key cache and sparse anchors, but the scan loop can still continue until the end of the data block. Given the restart binary-search semantics, if the target key falls between the current restart key and the next restart key, the candidate entry is either inside the current restart region or the first entry of the next restart region. There is no need to keep scanning later restart regions.

This experiment only tightens the linear scan boundary for one `Block.seek`: scan from the selected restart/anchor start to the end of the current restart region; if no candidate is found and a next restart exists, read at most the first entry of that next restart as the candidate. It does not change restart/anchor density, does not introduce a full-entry index, does not change the SST file format, and does not affect the dense-batch `seekAll` strategy. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet; if the primary metric does not improve or side paths regress, the change must be reverted.
### Block.seek restart-region scan-boundary experiment result: retainable candidate, primary target not complete

After this implementation, `Block.seek` scans from the selected restart/anchor start only to the end of the current restart region; if no candidate is found and a next restart exists, it reads at most the first entry of that next restart. This does not increase index density, does not introduce a full-entry index, and does not change the SST file format. `compileJava` passed, and `LdbCoreBehaviorTest`, `LdbObservabilityTest`, and `TablePropertiesTest` passed.

50k `read_optimized` LDB quick-gate result:

| Scenario | ops/s | Key observation |
| --- | ---: | --- |
| `readrandom_hit` | 184,356.673 | Above the current retained baseline of `173,332.155`; `blockSeekIndexHits=50000`, `Fallbacks=0` |
| `readrandom_sameblock` | 456,277.234 | Locality point reads stay strong; `tablePointGetLastBlockHits=47839` |
| `readrandom_burst` | 386,821.158 | No visible burst-locality regression |
| `multiget_mixed` | 370,280.406 | Sparse mixed MultiGet is above the previous retained baseline; `tableBatchDenseBlockGroups=0` as expected |
| `multiget_sameblock` | 386,844.502 | Dense batch still triggers `tableBatchSeekAllCount=1857`, but is lower than the previous `413,432.248`, so it remains a regression-watch item |
| `scan` | 1,730,636.770 | Scan does not depend on this seek optimization and shows no negative signal |

Same-run RocksDB JNI 10.10.1 comparison for supported scenarios:

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI |
| --- | ---: | ---: | ---: |
| `readrandom_hit` | 184,356.673 | 490,988.399 | 0.3755 |
| `readrandom_sameblock` | 456,277.234 | 603,847.961 | 0.7556 |
| `readrandom_burst` | 386,821.158 | 561,305.192 | 0.6891 |
| `multiget_mixed` | 370,280.406 | 472,288.474 | 0.7840 |

Evidence files: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-regionlimit-50k/ldb-db-bench-summary.json` and `build/reports/rocksdbjni-comparison-blockseek-regionlimit-50k-supported4/comparison.csv`.

Conclusion: this change is a retainable candidate for the current stage because it improves the primary `readrandom_hit` metric while sameblock, burst, mixed MultiGet, and scan remain healthy. However, the P0 primary target is still not complete: `readrandom_hit` is only `37.55%` of RocksDB JNI. Follow-up work still needs to reduce per-random-point-get candidate-entry decoding and comparison cost inside the block, while continuing to watch whether `multiget_sameblock` becomes a stable regression.
## Plan update: Block.seek anchor-subrange scan-boundary experiment

The previous restart-region scan-boundary experiment showed that tightening the linear scan endpoint in `Block.seek` can reduce unnecessary candidate-entry decoding. Today, after `Block.seek` lands on a sparse anchor, it can still scan from that anchor to the end of the current restart region. Given the ordered anchor keys, if the target key falls between the current anchor and the next anchor, the candidate entry is either inside the current anchor subrange or the first entry represented by the next anchor.

This experiment does not increase anchor density and does not introduce a full-entry index. It only reuses the existing `seekAnchors`: when seek starts from an anchor, tighten the scan limit to the next anchor offset; if no candidate is found in the current subrange, read at most the first entry at the next anchor as the candidate. If there is no next anchor, keep using the previous restart-region boundary. Acceptance continues to use `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet; if the primary metric does not improve or side paths regress, the change must be reverted.
### Block.seek anchor-subrange scan-boundary experiment result: not retained

This round built on the restart-region boundary change and tried to tighten the scan limit after an anchor hit to the next anchor offset, then read only the first entry at the next anchor when the current subrange had no candidate. Compilation and targeted tests passed, but the 50k `read_optimized` quick gate did not beat the previous restart-region version: `readrandom_hit=180,902.748 ops/s`, below the previous `184,356.673 ops/s`; `readrandom_sameblock=369,043.727 ops/s`, `readrandom_burst=342,644.351 ops/s`, and `multiget_mixed=301,320.991 ops/s` were also below the previous safe range. `multiget_sameblock=419,345.938 ops/s` was high, but not enough to offset the primary and side-path declines.

Therefore the anchor-subrange limit code is reverted and not retained in the default path. The retained conclusion is that for the current sparse-anchor interval, the extra next-anchor candidate branch and selection-object cost can outweigh the small reduction in linear scanning. The default path keeps the previous restart-region boundary optimization without further splitting anchor subranges.
## Plan update: observe actual entry scans in Block.seek

The restart-region scan-boundary optimization is now a retainable candidate, but `readrandom_hit` is still below 50% of RocksDB JNI. Before adding another optimization, we need to prove whether the remaining cost really comes from in-block linear scanning, rather than comparator cost, prefix-key reconstruction, Table-local cache misses, or upper-layer candidate validation.

This round only adds observability and does not change seek decisions: `Block.seekWithIndex` returns the number of entries decoded by this seek, and `Table` aggregates it as `blockSeekEntryScans` and `blockSeekMaxEntryScans` through `ldb.sstReadStats`. This does not change the Block/SST format, does not add a full-entry index, does not change anchor density, and does not change `readrandom_hit` or MultiGet semantics. The acceptance goal is to explain the average scan length per block seek in current `readrandom_hit`, guiding whether the next step should keep reducing scans or move to key reconstruction, comparator, or cache costs.
### Block.seek actual entry-scan observation result

After adding `blockSeekEntryScans` and `blockSeekMaxEntryScans`, the 50k `read_optimized` quick gate reported:

| Scenario | ops/s | block seeks | total entry scans | average scans per seek | max scans |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 190,046.504 | 50,000 | 169,512 | 3.39 | 5 |
| `readrandom_sameblock` | 355,809.588 | 50,000 | 169,404 | 3.39 | 5 |
| `readrandom_burst` | 385,418.240 | 50,000 | 169,236 | 3.38 | 5 |
| `multiget_mixed` | 409,824.308 | 25,207 | 85,725 | 3.40 | 5 |
| `multiget_sameblock` | 458,997.733 | 1,195 | 3,561 | 2.98 | 5 |
| `scan` | 0 | 0 | 0 | 0 | 0 |

Evidence file: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-scanstats-50k/ldb-db-bench-summary.json`.

Conclusion: the current open-time restart/anchor seek index and restart-region scan boundary already keep linear scanning very short. `readrandom_hit` decodes only about `3.39` entries per block seek on average, with a max of `5`. Further adding finer anchor branches, full-entry indexes, or more complex subrange positioning is unlikely to pay off; the previous anchor-subrange experiment already showed that extra branch cost can outweigh the small scan reduction. The next valuable direction should move from "reduce scan count" to "reduce fixed cost per decoded candidate entry": prefix-key reconstruction, comparator calls, `SliceInput` / `BlockEntry` object creation, or the Table-local/global BlockCache lookup path.
## Plan update: Block.seek offset-reader fixed-cost experiment

`blockSeekEntryScans` shows that the current seek path decodes only about 3.39 entries per seek on average, leaving limited room for further scan-count reduction. The next experiment targets fixed cost per decoded entry: today `Block.seek` creates a `SliceInput` for each seek and uses generic input methods to read varints, keys, and values. This round changes only the `Block.seek` hot path to an integer-offset based lightweight decode loop, reading varints, keys, and values directly from `Slice` by offset and avoiding per-point-get `SliceInput` construction.

This experiment does not change restart/anchor selection, scan boundaries, full-entry index policy, Block/SST format, `BlockIterator`, `seekAll`, or the offline v4 entry-anchor diagnostic path. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet; if the primary metric does not improve or side paths regress, the change must be reverted.
### Block.seek offset-reader experiment result: not retained, scan stats retained

This round tried to replace the `SliceInput`-based `Block.seek` hot path with an integer-offset direct decode loop. Compilation and targeted tests passed, but the 50k `read_optimized` quick gate did not produce a retainable gain: `readrandom_hit=190,243.695 ops/s`, essentially the same as the scan-stats version's `190,046.504 ops/s` and within noise; `readrandom_sameblock=324,110.657 ops/s`, clearly below the scan-stats version's `355,809.588 ops/s`. `readrandom_burst`, `multiget_sameblock`, and `scan` were high, but not enough to offset the sameblock regression and the lack of primary-metric gain.

Therefore the offset-reader code is reverted and not retained in the default path; the `blockSeekEntryScans` / `blockSeekMaxEntryScans` observability fields are retained. The retained conclusion is that `SliceInput` object cost is not a clearly dominant bottleneck, or the extra static helpers, bit operations, and bounds checks in the offset reader offset the allocation reduction. The next step should not keep tuning the varint reader; it should focus on `BlockEntry` / key/value Slice object creation, comparator calls, or the Table-local/global BlockCache lookup path.
## Plan update: remove redundant candidate compare in single-key Table.get

`Block.seek` already returns the first candidate entry greater than or equal to the target internal key, and the previous scan-stats run proves that the default hit path uses `blockSeekIndexHits` with no fallback. `Table.get(Slice)` still performs another `comparator.compare(candidate.getKey(), internalKey) < 0` check after receiving the candidate. For the default single-key hot path, this comparison should be redundant and is paid on every `readrandom_hit` operation.

This experiment removes only that candidate compare in single-key `Table.get(Slice)`. MultiGet, block-local index, and entry-anchor diagnostic paths keep their candidate compares to avoid widening semantic risk. The experiment does not change the Block/SST format, seek index, full-entry index policy, or the `Block.seek` return contract. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet; if the primary metric does not improve or side paths regress, the change must be reverted.
### Single-key Table.get candidate-compare removal result: not retained

This round removed only the `comparator.compare(candidate.getKey(), internalKey) < 0` check in single-key `Table.get(Slice)` after `Block.seek`; MultiGet and other diagnostic/index paths stayed unchanged. `compileJava`, `LdbCoreBehaviorTest`, `LdbObservabilityTest`, and `TablePropertiesTest` passed, but the 50k `read_optimized` quick gate did not improve: `readrandom_hit=179,478.951 ops/s`, below the scan-stats version's `190,046.504 ops/s`; `multiget_mixed=299,264.468 ops/s` was also clearly below the scan-stats version's `409,824.308 ops/s`. `readrandom_sameblock=402,920.692 ops/s` and `multiget_sameblock=451,382.902 ops/s` were not enough to offset the primary metric and mixed-MultiGet regressions.

Therefore this experiment is reverted and not retained in the default path. The retained conclusion is that, although the single-key candidate compare looks redundant, removing it does not produce a stable gain under the current JIT, branch layout, and call path; instead it lowers `readrandom_hit` and mixed MultiGet. Future work should not keep micro-tuning this single compare. The more valuable direction is broader observability around fixed per-random-point-get cost, such as candidate-entry object creation, key/value `Slice` construction, comparator-call distribution, and the Table-local cache / global `BlockCache` lookup path.
## Plan update: observe fixed Table candidate-compare cost

The previous experiment showed that directly removing the candidate compare from single-key `Table.get(Slice)` does not reliably improve `readrandom_hit`, but that does not prove the upper-layer candidate validation cost is irrelevant. The next step is to expose this cost explicitly instead of guessing or removing the safety check again.

This round adds observability only and does not change read semantics or cache policy: `tableCandidateCompares` counts how often the `Table` layer runs the upper-layer comparator validation against a candidate entry returned by a block; `tableCandidateRejects` counts how many of those candidates are rejected by that validation. Single-key get, sparse MultiGet direct-get, and dense `seekAll` paths all contribute to these fields. This does not change the Block/SST format, restart/anchor seek index, full-entry index policy, or the `Block.seek` return contract. The next gate should use `readrandom_hit`, sameblock, burst, scan, and MultiGet to determine whether candidate comparison is paid on almost every hit, whether the rejection ratio proves the guard still has semantic value, and whether future optimization should focus on candidate validation rather than in-block scanning.
### Table candidate-compare fixed-cost observation result

After adding `tableCandidateCompares` / `tableCandidateRejects`, the 50k `read_optimized` quick gate passed across `readrandom_hit`, `readrandom_sameblock`, `readrandom_burst`, `multiget_mixed`, `multiget_sameblock`, and `scan`. This run shows that hit-style point reads and MultiGet consistently pay the upper-layer candidate-compare cost, but this data distribution produced no candidate rejects.

| Scenario | ops/s | tableCandidateCompares | tableCandidateRejects | blockSeekIndexHits | blockSeekEntryScans |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 196,089.812 | 50,000 | 0 | 50,000 | 169,512 |
| `readrandom_sameblock` | 421,025.686 | 50,000 | 0 | 50,000 | 169,404 |
| `readrandom_burst` | 472,053.044 | 50,000 | 0 | 50,000 | 169,236 |
| `multiget_mixed` | 314,795.053 | 25,207 | 0 | 25,207 | 85,725 |
| `multiget_sameblock` | 384,311.778 | 50,000 | 0 | 1,195 | 3,561 |
| `scan` | 1,668,168.018 | 0 | 0 | 0 | 0 |

Evidence file: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-candidate-compare-stats-50k/ldb-db-bench-summary.json`.

Conclusion: candidate comparison is a stable fixed cost for point-hit and MultiGet paths, especially because `readrandom_hit` performs 50k compares for 50k operations. However, `tableCandidateRejects=0` in this hit-only distribution also means that simply removing the guard may look semantically tempting but has already been rejected by the previous performance experiment. Future work should not remove the compare again. It should either reduce the cost of constructing compare inputs without changing branch layout or semantic protection, or shift focus to candidate-entry key/value `Slice` construction and in-block object creation.
## Plan update: observe Block.seek key/value/entry fixed cost

`blockSeekEntryScans` already shows that the current `Block.seekWithIndex` path scans only about three to four entries per seek on average, leaving limited room for further scan-count reduction. The previous `tableCandidateCompares` field also proved that upper-layer candidate comparison is a stable fixed cost, but directly removing the compare was not retained. The next step is to split the in-block fixed cost into key, value, and returned-entry work.

This round adds observability only and does not change seek decisions or read semantics: `blockSeekKeyReads` counts how many candidate keys are read/reconstructed in the `Block.seekWithIndex` hot path; `blockSeekValueReads` counts how often the matching value slice is read; `blockSeekEntryCreations` counts how many `BlockEntry` objects are created to return candidates. These fields cover only the `seekWithIndex` path, do not change the dense-batch `seekAll` path, do not change the Block/SST format, do not add a full-entry index, and do not change restart/anchor index density. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet for regressions.
### Block.seek key/value/entry fixed-cost observation result

After adding `blockSeekKeyReads`, `blockSeekValueReads`, and `blockSeekEntryCreations`, the 50k `read_optimized` quick gate passed. The result shows that `blockSeekKeyReads` exactly matches `blockSeekEntryScans`, meaning every scanned candidate entry reads/reconstructs one key. `blockSeekValueReads` and `blockSeekEntryCreations` match the number of returned candidates, meaning the value slice and `BlockEntry` object are created only once for the final candidate.

| Scenario | ops/s | blockSeekEntryScans | blockSeekKeyReads | blockSeekValueReads | blockSeekEntryCreations |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 194,961.265 | 169,512 | 169,512 | 50,000 | 50,000 |
| `readrandom_sameblock` | 365,184.214 | 169,404 | 169,404 | 50,000 | 50,000 |
| `readrandom_burst` | 448,251.997 | 169,236 | 169,236 | 50,000 | 50,000 |
| `multiget_mixed` | 396,244.867 | 85,725 | 85,725 | 25,207 | 25,207 |
| `multiget_sameblock` | 522,063.990 | 3,561 | 3,561 | 1,195 | 1,195 |
| `scan` | 1,503,673.474 | 0 | 0 | 0 | 0 |

Evidence file: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-object-stats-50k/ldb-db-bench-summary.json`.

Conclusion: future optimization should not primarily target value-slice or returned-`BlockEntry` creation because those happen only once per point get. The more promising cost is key reconstruction and comparator-input construction for each scanned entry, about 3.39 times per `readrandom_hit` seek on average. If optimization continues, it should reduce full key materialization for scanned non-candidate entries without adding a full-entry index or changing the file format, for example with a lightweight compare view, scratch-key reuse, or constructing the full returned key only for the final candidate. These directions have had regressions in earlier experiments, so they must still be gated by `readrandom_hit` while watching sameblock, burst, scan, and MultiGet.
## Plan update: observe Block.seek shared/unshared key reads

The previous round showed that `blockSeekKeyReads` matches `blockSeekEntryScans`, but it still does not tell whether key reconstruction cost mostly comes from shared-prefix stitching/copying or from full-key slice reads and comparator-input fixed cost. Because LevelDB block prefix compression depends on the previous key, if most scanned entries have `sharedKeyLength > 0`, follow-up optimization should focus on reducing stitching/copying or carefully revisiting scratch-key reuse. If unshared keys dominate, the better target is likely compare-path and object fixed cost.

This round adds observability only: `blockSeekSharedKeyReads` counts key reads where `sharedKeyLength > 0`, and `blockSeekUnsharedKeyReads` counts key reads where `sharedKeyLength == 0`. This does not change the Block/SST format, restart/anchor seek index, full-entry index policy, `Block.seek` semantics, or the dense-batch `seekAll` path. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet for regressions.
### Block.seek shared/unshared key-read observation result

After adding `blockSeekSharedKeyReads` / `blockSeekUnsharedKeyReads`, the 50k `read_optimized` quick gate passed. The result shows that most key reads in random point reads and sparse MultiGet come from shared-prefix reconstruction rather than unshared restart-entry reads.

| Scenario | ops/s | keyReads | sharedKeyReads | unsharedKeyReads | sharedRatio |
| --- | ---: | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 196,737.618 | 169,512 | 150,139 | 19,373 | 88.57% |
| `readrandom_sameblock` | 396,646.906 | 169,404 | 149,802 | 19,602 | 88.43% |
| `readrandom_burst` | 387,488.463 | 169,236 | 149,682 | 19,554 | 88.45% |
| `multiget_mixed` | 356,246.642 | 85,725 | 76,031 | 9,694 | 88.69% |
| `multiget_sameblock` | 439,475.442 | 3,561 | 2,541 | 1,020 | 71.36% |
| `scan` | 1,339,663.691 | 0 | 0 | 0 | 0% |

Evidence file: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-blockseek-sharedkey-stats-50k/ldb-db-bench-summary.json`.

Conclusion: the in-block fixed cost for `readrandom_hit` is concentrated in shared-prefix key reconstruction: about `88.57%` of scanned keys require stitching a full key from the previous key. If optimization continues, it should prioritize reducing shared-key stitching/copying cost or avoiding full key construction for non-final candidates. However, because earlier scratch-key/raw-compare style experiments regressed, the next round must remain a small experiment gated by `readrandom_hit` while watching sameblock, burst, scan, and MultiGet.
## Plan update: Block.seek two-buffer scratch shared-key experiment

The shared/unshared key-read observation shows that about `88.57%` of scanned keys in `readrandom_hit` come from shared-prefix reconstruction. Directly bypassing the comparator with composite comparison would be risky for custom comparators and internal-key semantics, so this round does not change comparison semantics: it still constructs a full key and still calls the original comparator. The experiment only tries to reduce allocation cost during shared-key reconstruction. Within one `Block.seekWithIndex` hot path, it maintains two growable byte[] scratch buffers and alternates between them for shared-key reconstruction, avoiding a fresh backing byte[] for every scanned entry. When the final candidate must be returned in a `BlockEntry`, a scratch key is copied into a stable key so the returned object cannot be overwritten by a later seek.

This experiment applies only to the `seekWithIndex` / `seekWithValueOnMatch` hot path. It does not change dense-batch `seekAll`, iterators, floor, block-open anchor construction, the Block/SST file format, full-entry index policy, restart/anchor index density, or comparator semantics. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet; if the primary metric does not improve reliably or side paths regress, the change must be reverted.
### Block.seek two-buffer scratch shared-key experiment result: not retained

This round implemented two-buffer scratch shared-key reconstruction only for the `seekWithIndex` hot path: shared keys alternated between two growable byte[] buffers, comparison still used the original comparator, and the final returned candidate was copied into a stable key. Compilation and core tests passed, but the 50k `read_optimized` quick gate did not improve: `readrandom_hit=165,874.450 ops/s`, clearly below the previous shared-key observation version's `196,737.618 ops/s`; `multiget_mixed=326,004.959 ops/s` was also below the previous `356,246.642 ops/s`. `readrandom_burst=488,150.155 ops/s` was locally high, but not enough to offset the primary regression.

Therefore this experiment is reverted and not retained in the default path. The retained conclusion is that shared-prefix key reconstruction is the main repeated cost, but Java-level two-buffer scratch reuse adds extra branches, Slice wrappers, and final-candidate copying; those costs offset or exceed the allocation reduction. Future work should not keep micro-tuning scratch-buffer reuse. If shared-key cost is optimized further, it should use a more structural approach, such as reducing full materialization for non-candidate keys only when comparator specialization is safe, or reducing how often the upper layers enter block seek.
## Plan update: observe where single-key get enters block seek from

The scratch-key experiment proved that Java-level shared-key allocation reduction does not produce a stable gain. The next question is whether there is still a high-value entry-path optimization: does single-key `Table.get(Slice)` enter `Block.seekWithIndex` mostly after `lastPointGetBlock` hits, direct-map slot hits, or slot misses that open data blocks? If most seeks already come from last-block/slot-hit paths, further cache-entry optimization has limited value. If many seeks still follow slot misses, then local point-cache or upper-layer seek reduction remains worth investigating.

This round adds observability only and does not change cache policy or read semantics: `tablePointGetSeekAfterLastBlockHits` counts single-key gets that enter block seek after a `lastPointGetBlock` hit; `tablePointGetSeekAfterSlotHits` counts block seeks after a direct-map slot hit; `tablePointGetSeekAfterSlotMisses` counts block seeks after a slot miss/open-data-block path. This does not change the Block/SST format, full-entry index policy, restart/anchor seek index, or the MultiGet path. Acceptance still uses `readrandom_hit` as the primary signal while watching sameblock, burst, scan, and MultiGet.
### Single-key block-seek source observation result: fields not retained

This round temporarily added `tablePointGetSeekAfterLastBlockHits`, `tablePointGetSeekAfterSlotHits`, and `tablePointGetSeekAfterSlotMisses` to identify which point-cache path a single-key get used before entering `Block.seekWithIndex`. The 50k `read_optimized` quick gate passed, but these fields were implemented through before/after counter deltas, adding extra long reads and branches to the single-key hot path. They are therefore not suitable for long-term retention.

Observation result:

| Scenario | tableDataBlockSeeks | afterLastBlock | afterSlotHit | afterSlotMiss |
| --- | ---: | ---: | ---: | ---: |
| `readrandom_hit` | 50,000 | 33 | 38,882 | 11,085 |
| `readrandom_sameblock` | 50,000 | 47,839 | 916 | 1,245 |
| `readrandom_burst` | 50,000 | 14,153 | 34,613 | 1,234 |
| `multiget_mixed` | 25,207 | 0 | 0 | 0 |
| `multiget_sameblock` | 50,000 | 0 | 0 | 0 |
| `scan` | 0 | 0 | 0 | 0 |

Evidence file: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-pointget-seek-source-stats-50k/ldb-db-bench-summary.json`.

Conclusion: this observation exactly matches the existing `tablePointGetLastBlockHits`, `tablePointGetSlotHits`, and `tablePointGetSlotMisses` fields, so the existing stats are already sufficient to understand entry source. The temporary fields are reverted and not retained in the default path. If future work tries to reduce how often block seek is entered, random-hit work should focus on the direct-map slot-hit path that still has to seek, while sameblock is already dominated by the last-block fast path. Further point-cache replacement changes have limited value and are likely to hurt locality.
## Release-baseline closure: remove non-essential hot-path diagnostic scaffolding

Earlier investigation temporarily added fine-grained diagnostic fields such as `blockSeekEntryScans`, `blockSeekKeyReads`, `blockSeekSharedKeyReads`, `blockSeekValueReads`, `blockSeekEntryCreations`, and `tableCandidateCompares` to locate the `readrandom_hit` bottleneck. These fields helped prove that in-block scan length is already low, the repeated cost is concentrated in shared-prefix key reconstruction, scratch-key reuse is not an effective solution, and point-cache entry source can already be inferred from existing fields.

However, these fields live in the `Block.seekWithIndex` or `Table.get` hot path and add integer increments, branches, and wider `SeekResult` payloads. The default release path keeps only the fields explicitly required by this work item: `blockSeekIndexHits`, `blockSeekIndexMisses`, and `blockSeekIndexFallbacks`, along with existing table/cache path stats. Other fine-grained diagnostic fields are removed from code and `readStats`; their historical conclusions remain documented here. This closure does not change the Block/SST format, restart/anchor seek index, full-entry index policy, or read semantics.
### Release-baseline closure gate result

After removing non-essential hot-path diagnostic scaffolding, the 50k `read_optimized` quick gate passed and the same-run RocksDB JNI 10.10.1 comparison was completed for supported scenarios. The default path now keeps only the fields explicitly required by this work item: `blockSeekIndexHits`, `blockSeekIndexMisses`, and `blockSeekIndexFallbacks`; exploratory fields such as `blockSeekEntryScans`, key-read counters, and candidate-compare counters are no longer retained.

| Scenario | LDB ops/s | RocksDB JNI ops/s | LDB/RocksDB JNI | Conclusion |
| --- | ---: | ---: | ---: | --- |
| `readrandom_hit` | 185,760.216 | 508,764.486 | 36.51% | Primary target still below 50% |
| `readrandom_sameblock` | 380,087.663 | 620,748.648 | 61.23% | Locality point reads are above 50% |
| `readrandom_burst` | 432,627.746 | 600,666.981 | 72.02% | Burst locality is above 50% |
| `multiget_mixed` | 339,170.917 | 429,020.306 | 79.06% | Sparse mixed MultiGet is above 50% |
| `multiget_sameblock` | 510,305.622 | unsupported | - | LDB-only regression gate, healthy |
| `scan` | 1,897,230.802 | unsupported | - | LDB-only regression gate, healthy |

Evidence files: `ldb-longrun/ldb-longrun/build/reports/ldb-db-bench-release-baseline-no-diagnostics-50k/ldb-db-bench-summary.json` and `build/reports/rocksdbjni-comparison-release-baseline-no-diagnostics-50k-supported4/comparison.csv`.

Conclusion: the current release baseline has removed diagnostic scaffolding while preserving the functional goal: default hit-style point reads still use the block-open-time restart/anchor seek index and report `blockSeekIndexFallbacks=0`. However, the P0 primary performance goal is still not complete because `readrandom_hit` is only `36.51%` of RocksDB JNI. If work continues toward 50%, future changes should avoid retaining new hot-path diagnostic fields by default and should focus on more structural design changes rather than Java-level shared-key allocation or point-cache replacement micro-tuning.
## Plan update: InternalUserComparator default bytewise compare fast path

The release baseline has removed non-essential diagnostic scaffolding and keeps the lightweight restart/anchor seek index built when a Block is opened, but the 50k comparison still shows `readrandom_hit` at only `36.51%` of RocksDB JNI, so the primary target is not complete. Earlier observations show that the average in-block scan count is already low, and further scan-range trimming, denser anchor subranges, or Java-level scratch-key reuse did not produce stable gains. The next step should therefore reduce the fixed cost of comparing each scanned key rather than changing the file format or adding a full-entry index.

This round only targets the default `leveldb.BytewiseComparator` case. `InternalUserComparator.compare` gets a bytewise user-key fast path: when the user comparator is the built-in table `BytewiseComparator`, it compares the left and right user-key byte ranges directly through raw array/offset data and avoids creating two user-key `Slice` inputs. Custom comparators keep the existing `userComparator.compare(left.slice(...), right.slice(...))` path. The sequence/type trailer ordering remains unchanged, and this does not change custom-comparator behavior, the Block/SST file format, the restart/anchor seek index, full-entry index policy, or the `Block.seek` return contract.

Acceptance still uses `readrandom_hit` as the primary metric while watching `readrandom_sameblock`, `readrandom_burst`, `scan`, `multiget_mixed`, and `multiget_sameblock` for regressions. If the primary metric does not improve reliably or nearby scenarios regress materially, this fast path should be reverted and only the experiment conclusion retained.

### InternalUserComparator default bytewise compare fast-path result: not retained

This round implemented a raw-byte user-key compare fast path only for the built-in table `BytewiseComparator`; custom comparators still used the original Slice-based path. `compileJava`, `LdbCoreBehaviorTest`, `LdbObservabilityTest`, and `TablePropertiesTest` passed. The 50k `read_optimized` quick gate also passed, but the result is not strong enough to retain: `readrandom_hit=186,439.370 ops/s`, only slightly above the release baseline `185,760.216 ops/s`, which is noise-level improvement. In the same-run RocksDB JNI comparison, RocksDB JNI reached `493,383.237 ops/s`, so LDB/RocksDB JNI was only `37.79%`, still far below the 50% primary target.

Nearby scenarios were mixed. `readrandom_sameblock=598,217.074 ops/s` and `readrandom_burst=599,872.107 ops/s` were strong, and `multiget_mixed=382,956.593 ops/s` was above the release baseline. However, `multiget_sameblock=384,730.508 ops/s` was clearly below the release baseline `510,305.622 ops/s`, and `scan=1,778,840.338 ops/s` was below the release baseline `1,897,230.802 ops/s`. Combined with the earlier Slice-offset/raw-array compare experiment that regressed clearly, this confirms that handwritten user-key bytewise fast paths do not reliably turn into primary `readrandom_hit` gains under the current JIT and call shape, and they can introduce surrounding-path variance.

Therefore the code is reverted and not retained in the default path. The retained conclusion is that future work should not keep micro-tuning `left.slice(...)/right.slice(...)` removal inside `InternalUserComparator.compare`; to continue toward 50%, the more valuable directions are structurally reducing how often reads enter block seek, or redesigning the next file-format version around a compact block-local index.

## Plan update: Table point-cache slot holder reuse experiment

The earlier point-get seek-source observation showed that pure random `readrandom_hit` often enters `Block.seekWithIndex` after a direct-map slot hit: in the 50k sample, `tablePointGetSlotHits` was about 38,882. Today `Table.getPointGetDataBlock` creates a new immutable holder for `lastPointGetBlock` on every slot hit, even though the slot already contains the same block handle and block object. That adds object allocation and publication cost on the random-hit hot path.

This experiment does not change cache policy, block-seek behavior, or the Block/SST file format. It merges the point-get direct cache's parallel `BlockHandle[]` / `Block[]` arrays into a `LastPointGetBlock[]`: on slot miss, opening a block creates one holder and stores it both in the slot and in `lastPointGetBlock`; on slot hit, the existing slot holder is assigned directly to `lastPointGetBlock`, avoiding another holder allocation. Concurrent reads still observe immutable holders, preventing handle/block mismatches, and slot-collision stats continue to use holder presence.

Acceptance still uses `readrandom_hit` as the primary metric while watching `readrandom_sameblock`, `readrandom_burst`, `scan`, `multiget_mixed`, and `multiget_sameblock` for regressions. If the primary metric does not improve reliably or nearby scenarios regress materially, this holder-reuse implementation should be reverted.

### Table point-cache slot holder reuse result: not retained

This round changed the point-get direct cache from parallel `BlockHandle[]` / `Block[]` arrays to a `LastPointGetBlock[]` holder array, reusing the holder on slot hits to avoid repeated `lastPointGetBlock` holder allocation. `compileJava`, targeted core tests, and document encoding checks passed, but the 50k `read_optimized` quick gate regressed broadly: `readrandom_hit=174,039.623 ops/s`, below the release baseline `185,760.216 ops/s`; `readrandom_sameblock=116,216.178 ops/s`, `readrandom_burst=173,682.031 ops/s`, and `multiget_mixed=173,418.965 ops/s` were all clearly below the safe range; `multiget_sameblock=308,690.947 ops/s` and `scan=1,339,222.340 ops/s` also weakened.

Therefore the implementation is reverted and not retained in the default path. The retained conclusion is that repeated holder allocation on slot hits looks like a fixed random-hit cost, but replacing the direct cache with a holder array changes array access, object layout, and JIT shape enough to erase any benefit and severely hurt locality scenarios. Future work should not keep micro-tuning `LastPointGetBlock` holder reuse. To continue toward 50%, the higher-value direction is a more structural block-local-index/file-format design, or a way to reduce `Block.seek` calls without changing the current hot-path object layout.
