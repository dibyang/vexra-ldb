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
