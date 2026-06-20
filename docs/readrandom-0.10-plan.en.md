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

## Follow-up candidates

- File-format improvements: block-level key index, filter/layout metadata, and format-version capabilities.
- Deeper block lookup optimization: reduce linear scans inside restart regions.
- Cache policy optimization: separate block cache admission for random reads and scans.
- Long-run benchmarks: random reads, mixed read/write workloads, and MultiGet hotspot distributions.
