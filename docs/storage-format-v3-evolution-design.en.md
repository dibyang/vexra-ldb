# LDB v3 Storage Format Evolution Design

[中文](storage-format-v3-evolution-design.md) | English

## Background

The 0.11.0 line has moved SST block-local indexes to v3 opt-in. Writers can generate per-data-block local indexes, a `block_local_index` directory, and the `block.local_index.v1` incompatible feature when `tableFormatVersion(3)` and `writeBlockLocalIndex(true)` are enabled. Readers can recognize the directory and use the local index on demand for MultiGet groups that contain multiple keys in the same data block, reducing the in-block linear decode window.

The next important step is no longer only making the format writable, readable, and benchmarkable. v3 needs to become a long-term evolvable, diagnosable, rollback-aware, release-governed format layer. Otherwise future Bloom/filter extensions, partitioned index/filter, compression block extensions, range tombstone format work, or deeper block seek optimizations will scatter compatibility, corruption diagnostics, and release gates across unrelated code paths.

## Goals

| Goal | Meaning |
| --- | --- |
| Freeze the v3 compatibility matrix | Define the read/write boundary between readers, v1/v2/v3 SSTs, and opt-in features. |
| Standardize the meta block registry | Give `properties`, `block_local_index`, and future meta blocks a single naming, requiredness, missing-behavior, and feature model. |
| Add format self-checking | Provide machine-readable evidence for release gates, user diagnostics, and corruption classification. |
| Support default-enable decisions | Require performance, space, scan-regression, and mixed-format evidence before block-local indexes can become a default candidate. |
| Keep no-downgrade explainable | Once users write v3 incompatible features, docs and diagnostics must explain the rollback boundary. |

## Non Goals

- Do not change the current v3 on-disk layout in this document.
- Do not promise old readers can open v3 SSTs containing `block.local_index.v1`.
- Do not enable `writeBlockLocalIndex` by default.
- Do not introduce partitioned index/filter or a new compression-block layout in this phase.
- Do not make repair rewrite SSTs or rebuild block-local indexes by default.

## v3 Compatibility Matrix

| Reader | SST Format | Behavior |
| --- | --- | --- |
| New reader | v1 legacy SST | Must remain readable by default. |
| New reader | v2 properties SST | Must remain readable and keep unknown incompatible feature fail-fast. |
| New reader | v3 without `block.local_index.v1` | Readable; v3 properties are interpreted, while reads may fall back to existing `Block.seek`. |
| New reader | v3 with `block.local_index.v1` | Readable only when the reader supports the feature and directory/properties are consistent. |
| Old reader | v1/v2 SST | Keeps old behavior. |
| Old reader | v3 SST | Not promised; version/feature mechanisms must prevent silent misreads. |
| New reader | mixed v1/v2/v3 DB | Required; different SSTs in the same DB may use different formats. |

## Meta Block Registry

All future meta blocks must be registered in one model instead of scattering hard-coded rules across writer, reader, and check paths.

| Name | Status | Feature | Required When | Missing Behavior | Notes |
| --- | --- | --- | --- | --- | --- |
| `properties` | stable | `table.properties` | v2/v3 SST | v2/v3 open fails or check reports properties missing | Records format version, feature set, and format evidence. |
| `filter.<policy>` | stable | filter policy name | Bloom/filter is configured | Missing filter falls back; declared mismatch fails fast | Used to skip candidate SSTs on random reads. |
| `block_local_index` | v3 opt-in | `block.local_index.v1` | `block.local_index.v1` is declared | Open fails; check reports `BLOCK_LOCAL_INDEX_DIRECTORY_MISSING` | Maps data block handles to local index block handles. |
| `partitioned_index` | reserved | future incompatible feature | Disabled | Ignored when undeclared; fail-fast when declared but unsupported | Reserved for later RocksDB gap closure. |
| `partitioned_filter` | reserved | future incompatible feature | Disabled | Ignored when undeclared; fail-fast when declared but unsupported | Reserved for later filter extensions. |

### Registry Rules

- Meta block names must be stable, case-sensitive, and documented in both Chinese and English format documents.
- The relationship between incompatible features and meta blocks must be explicit. If a feature is declared, a missing required meta block must fail fast.
- Compatible meta blocks may fall back when absent, but must have diagnostic counters or check classifications.
- Properties fields must use the `ldb.table.<domain>.<field>` namespace.
- Any new meta block must update reader behavior, check/repair/report, releaseGate documentation gates, and no-downgrade notes.

## Format Self-Check Design

Add lightweight SST/table self-checking as the shared base for release gates and user diagnostics. The first phase can be an internal checker or an extension to `check` reports; a new CLI is not required immediately.

| Check | Evidence Field | Failure Class |
| --- | --- | --- |
| Footer/metaindex is readable | `footerReadable`, `metaindexReadable` | `TABLE_FOOTER_CORRUPT`, `TABLE_METAINDEX_CORRUPT` |
| Properties exist and parse | `propertiesReadable`, `tableFormatVersion` | `TABLE_PROPERTIES_MISSING`, `TABLE_PROPERTIES_CORRUPT` |
| Feature set matches meta blocks | `features`, `metaBlocks` | `TABLE_FEATURE_META_MISMATCH` |
| `block_local_index` directory exists | `blockLocalIndexDirectoryPresent` | `BLOCK_LOCAL_INDEX_DIRECTORY_MISSING` |
| Directory handle is valid | `blockLocalIndexDirectoryHandle` | `BLOCK_LOCAL_INDEX_HANDLE_OUT_OF_RANGE` |
| Local index blocks are readable | `blockLocalIndexBlocksReadable` | `BLOCK_LOCAL_INDEX_BLOCK_CORRUPT` |
| Covered block count matches | `blockLocalIndexCoveredBlocks` | `BLOCK_LOCAL_INDEX_COVERAGE_MISMATCH` |
| Scan/iterator does not load local indexes | `iteratorLoadsBlockLocalIndex=false` | `BLOCK_LOCAL_INDEX_SCAN_POLICY_VIOLATION` |

## Evidence Required Before Default Enablement

Before `writeBlockLocalIndex` can become a default candidate, all of the following evidence is required:

| Dimension | Required Evidence |
| --- | --- |
| cold readrandom | At least 200k scale, repeated runs, and v3 opt-in no worse than the stable v1/v2 baseline. |
| sparse MultiGet | Random sparse batches do not regress, with the policy explained for low same-block hit rates. |
| dense same-block MultiGet | Clear benefit when multiple keys hit the same data block. |
| scan/iterator | Default iterators/scans do not load local indexes, and scan benchmarks do not meaningfully regress. |
| space amplification | Record index bytes, covered blocks, raw data-block bytes, and release-gate upper bounds. |
| mixed-format | v1/v2/v3 mixed flush/compaction/get/MultiGet/check passes in the same DB. |
| no-downgrade | Release docs clearly state that old versions are not guaranteed to open v3 data. |

## Current 0.11 Evidence Archive

The current 200k comparison shows that an eager strategy cannot be enabled by default. When every point get proactively read the local index, `cold_readrandom` regressed from the baseline `180,908.754 ops/s` to `158,232.367 ops/s`, only `87.47%` of baseline; `multiget_random` reached `157,482.882 ops/s`, or `97.23%` of baseline.

After switching to the smart policy, point get stays on the existing `Block.seek` path and does not proactively read local indexes. MultiGet uses local indexes only when the same data-block group contains more than one key. With that policy, v3 opt-in `cold_readrandom` reached `204,225.049 ops/s`, or `112.89%` of baseline; `multiget_random` reached `171,515.540 ops/s`, or `105.89%` of baseline.

Evidence reports:

- `ldb-longrun/build/reports/ldb-db-bench-bi05-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-bi05-v3bli-smart-200k/ldb-db-bench-summary.json`

Conclusion: the current v3 block-local index can remain an opt-in capability; default enablement still requires repeated runs, dense same-block MultiGet, scan-regression, and space-amplification evidence.

## Release Gate Recommendations

| Gate | Requirement |
| --- | --- |
| `storageFormatDocs` | Chinese/English format references, v3 evolution design, block-local index design, and release docs record v3/no-downgrade boundaries. |
| `tableFormatPolicyCoverage` | `ldb.tableFormatPolicy` exposes v3 opt-in/default state and rollback action. |
| `blockLocalIndexFormatCoverage` | Options, properties, metaindex, reader, and check/report fully cover `block.local_index.v1`. |
| `blockLocalIndexBenchmarkEvidence` | Archive cold_readrandom, multiget_random, dense same-block MultiGet, and scan comparisons. |
| `gitReleaseTraceability` | Formal releases still require local commit, tag, push, and user-managed staging for human confirmation. |

## Phased Plan

| Phase | Deliverable | Acceptance |
| --- | --- | --- |
| V3E-01 | This document and its Chinese copy | Compatibility matrix, registry, self-checking, and default-enable evidence are clear. |
| V3E-02 | check/report extension design | Corruption classes and evidence fields are stable. |
| V3E-03 | Registry constants and self-check skeleton | No default read/write behavior changes; old SST regressions pass. |
| V3E-04 | releaseGate integration | Documentation, format coverage, and benchmark evidence are gate-checkable. |
| V3E-05 | Default-enable review | Default enablement is discussed only after all evidence prerequisites are satisfied. |
## V3E-02 Current Landing Boundary

The current implementation first wires v3/block-local-index evidence into the offline `check` and `repair` report skeletons without changing default read/write behavior. Reports now archive the following fields through `storageFormat` and `tableFormats`:

- `v3Tables`
- `blockLocalIndexTables`
- `blockLocalIndexBytes`
- `blockLocalIndexCoveredBlocks`
- Per-SST `blockLocalIndex`, `blockLocalIndexPolicy`, `blockLocalIndexInterval`, `blockLocalIndexBytes`, and `blockLocalIndexCoveredBlocks`

This step only proves that readable SST properties/features can be summarized consistently by check/repair. Deeper corruption classes such as out-of-range directory handles, per-block coverage mismatch, and local-index-block checksum failures remain V3E-03/V3E-04 work.

## V3E-03 Current Landing Boundary

The current implementation wires the block-local-index self-check skeleton into offline `check` and `repair` paths. For readable SSTs declaring `block.local_index.v1`, the diagnostic path reads the directory and verifies:

- the directory exists and is non-empty
- `ldb.table.block_local_index.covered_blocks` matches the number of directory entries
- local-index block handles are inside the SST file boundary
- local-index blocks can be read through the existing block trailer/checksum path

The `blockLocalIndexEvidence` report fragment archives `coverageMatches`, `handlesInRange`, `blocksReadable`, and `failureCount`; failures are reported with classes such as `BLOCK_LOCAL_INDEX_DIRECTORY_MISSING`, `BLOCK_LOCAL_INDEX_COVERAGE_MISMATCH`, `BLOCK_LOCAL_INDEX_HANDLE_OUT_OF_RANGE`, and `BLOCK_LOCAL_INDEX_BLOCK_CORRUPT`. This self-check still runs only on offline paths and does not change normal open/get/iterator policy.

## V3E-04 Current Landing Boundary

Gradle `releaseGate` now adds a dedicated `blockLocalIndexFormatCoverage` gate, and the gate participates in the overall PASS/FAILED decision through `storageFormatGates`. The gate requires:

- Chinese and English block-local index design documents exist and record `block.local_index.v1`
- Chinese and English v3 storage-format evolution documents exist and record `blockLocalIndexFormatCoverage` plus `BLOCK_LOCAL_INDEX_COVERAGE_MISMATCH`
- `Table` exposes offline `getBlockLocalIndexFormatEvidence` and `getBlockLocalIndexFormatFailures`
- `LDBFactory` check/repair reports include `blockLocalIndexEvidence` and `blockLocalIndexFailures`
- `TablePropertiesTest` covers `BLOCK_LOCAL_INDEX_FEATURE`
- `LdbVerifyCheckTest` covers v3 block-local-index check reports
- `LdbRepairTest` covers v3 block-local-index repair reports

This gate proves format coverage and the offline self-check loop only. It does not mean default enablement is ready; `blockLocalIndexBenchmarkEvidence` still requires dense same-block MultiGet, scan regression, repeated 200k comparisons, and space-amplification evidence.

## V3E-05 Current Landing Boundary

`ldbDbBenchReport` now adds two benchmark names for block-local-index default-enable review:

- `multiget_sameblock`: builds batches from consecutive keys to increase the chance that multiple keys hit the same data block, so local-index benefit can be observed on dense same-block MultiGet.
- `scan`: scans through `SnapshotCursor`, confirming that v3/block-local indexes do not make iterator/scan paths load extra indexes or meaningfully regress.

Gradle `releaseGate` adds `blockLocalIndexBenchmarkEvidence`, requiring the v3 evolution documents and `LdbDbBenchMain`/`LdbDbBenchMainTest` to cover `cold_readrandom`, sparse `multiget_random`, dense same-block MultiGet, scan regression, and v3 opt-in parameters. The gate currently proves that benchmark entry points and evidence archival are available; it does not claim the performance threshold is satisfied. Default enablement still requires formal repeated 200k results and a space-amplification upper bound.

## V3E-05 200k Benchmark Archive and Default-Enable Conclusion

This round adds the formal 200k comparison used to decide whether v3 block-local indexes are ready for default enablement. The comparison covers `cold_readrandom`, sparse `multiget_random`, dense same-block `multiget_sameblock`, and `scan`.

| Scenario | baseline | v3 block-local index opt-in | v3 / baseline |
| --- | ---: | ---: | ---: |
| `cold_readrandom` | `194,755.962 ops/s` | `211,992.343 ops/s` | `108.85%` |
| `multiget_random` | `167,724.829 ops/s` | `173,929.302 ops/s` | `103.70%` |
| `multiget_sameblock` | `208,109.117 ops/s` | `458,276.345 ops/s` | `220.21%` |
| `scan` | `3,463,269.430 ops/s` | `2,912,335.781 ops/s` | `84.09%` |

Evidence reports:

- `ldb-longrun/build/reports/ldb-db-bench-v3e05-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e05-v3bli-200k/ldb-db-bench-summary.json`

Conclusion: v3 block-local indexes already show opt-in gains for `cold_readrandom`, sparse MultiGet, and dense same-block MultiGet, with `multiget_sameblock` reaching `220.21%` of baseline. However, this run shows `scan` at only `84.09%` of baseline, a regression of about `15.91%`; therefore `writeBlockLocalIndex` must not be enabled by default in the current version. Default enablement still requires repeated 200k stability runs, scan-regression root-cause removal or mitigation, and a space-amplification upper bound for block-local indexes.
## V3E-06 Scan-Path Decoupling and Observability

For the V3E-05 regression where `scan` reached only `84.09%` of baseline, this phase lands a low-risk fix first. Opening a v3 SST that declares `block.local_index.v1` still verifies that the `block_local_index` metaindex handle exists, but it no longer eagerly reads the directory block content. The directory is loaded on demand only for same-data-block MultiGet groups or offline check/repair self-checking.

This keeps iterator/scan paths from reading block-local-index directory contents just because a v3 SST was opened. To make future benchmark decisions traceable, `ldb.sstReadStats` now also archives:

- `blockLocalIndexTables`
- `blockLocalIndexDirectoryLoadedTables`
- `blockLocalIndexDirectoryEntries`
- `blockLocalIndexSeekCount`
- `blockLocalIndexHitCount`
- `blockLocalIndexFallbackCount`

The release conclusion remains unchanged: this fix reduces the risk that scan is coupled to block-local-index metadata, but default enablement still requires a fresh 200k comparison proving no meaningful scan regression plus a space-amplification upper bound.
## V3E-06 200k Threshold-Policy Rerun

After directory lazy-loading, MultiGet now enables block-local indexes only when the same data-block group has at least `8` lookups. This avoids loading the directory/local-index path for sparse random MultiGet batches that only accidentally contain a few same-block keys, while preserving the optimization path for dense same-block batches.

The adjacent 200k comparison for this round is:

| Scenario | baseline | v3 lazy + threshold8 | v3 / baseline |
| --- | ---: | ---: | ---: |
| `cold_readrandom` | `232,465.180 ops/s` | `212,625.059 ops/s` | `91.47%` |
| `multiget_random` | `188,663.529 ops/s` | `170,094.033 ops/s` | `90.16%` |
| `multiget_sameblock` | `237,016.924 ops/s` | `254,079.856 ops/s` | `107.20%` |
| `scan` | `1,267,053.752 ops/s` | `2,724,543.230 ops/s` | `215.03%` |

Key read-path evidence:

- `cold_readrandom`: `blockLocalIndexDirectoryLoadedTables=0`, `blockLocalIndexSeekCount=0`
- `multiget_random`: `blockLocalIndexDirectoryLoadedTables=0`, `blockLocalIndexSeekCount=0`, proving sparse random batches no longer load local indexes
- `multiget_sameblock`: `blockLocalIndexDirectoryLoadedTables=12`, `blockLocalIndexSeekCount=195282`, `blockLocalIndexHitCount=190315`, proving dense same-block batches still use local indexes
- `scan`: `blockLocalIndexDirectoryLoadedTables=0`, `blockLocalIndexSeekCount=0`, proving iterator/scan paths did not load block-local indexes

Evidence reports:

- `ldb-longrun/build/reports/ldb-db-bench-v3e06-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e06-v3bli-threshold8-200k/ldb-db-bench-summary.json`

Conclusion: lazy directory loading and the threshold8 policy remove scan-path coupling and reduce sparse MultiGet misuse of local indexes. However, `cold_readrandom` and `multiget_random` remain below baseline, so the current version keeps `writeBlockLocalIndex` as explicit opt-in and does not move it to the default-enable candidate state. Before default enablement, follow-up work must further reduce v3 metadata/file-size impact on cold reads and sparse MultiGet, and must add a space-amplification upper bound.
## V3E-07 block-local-index interval4 trade-off validation

On top of the V3E-06 lazy-directory and threshold8 policy, this round validates `blockLocalIndexInterval=4`. This is the current API default and is intended to reduce local-index anchor density, lowering v3 metadata/space cost for cold reads and sparse MultiGet.

Compared with the same baseline and `interval=1` run:

| Scenario | baseline | interval=1 | interval=4 |
| --- | ---: | ---: | ---: |
| `cold_readrandom` | `232,465.180 ops/s` | `212,625.059 ops/s` (`91.47%`) | `211,307.754 ops/s` (`90.90%`) |
| `multiget_random` | `188,663.529 ops/s` | `170,094.033 ops/s` (`90.16%`) | `189,448.836 ops/s` (`100.42%`) |
| `multiget_sameblock` | `237,016.924 ops/s` | `254,079.856 ops/s` (`107.20%`) | `225,199.916 ops/s` (`95.01%`) |
| `scan` | `1,267,053.752 ops/s` | `2,724,543.230 ops/s` (`215.03%`) | `3,272,417.286 ops/s` (`258.27%`) |

The `interval=4` read-path evidence shows:

- `cold_readrandom` and `scan` both record `blockLocalIndexDirectoryLoadedTables=0` and `blockLocalIndexSeekCount=0`
- `multiget_random` records `blockLocalIndexDirectoryLoadedTables=0` and `blockLocalIndexSeekCount=0`, and returns to `100.42%` of baseline
- `multiget_sameblock` still loads local indexes, but its benefit drops from `107.20%` with `interval=1` to `95.01%`

Evidence reports:

- `ldb-longrun/build/reports/ldb-db-bench-v3e06-baseline-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e06-v3bli-threshold8-200k/ldb-db-bench-summary.json`
- `ldb-longrun/build/reports/ldb-db-bench-v3e06-v3bli-threshold8-interval4-200k/ldb-db-bench-summary.json`

Conclusion: `interval=4` is safer for sparse MultiGet, but it weakens dense same-block benefits; `interval=1` preserves dense benefits but carries higher cold/sparse cost. The current version still must not move to the default-enable candidate state. Next work should reduce v3 block-local-index space/metadata cost or introduce clearer workload admission: sparse random reads avoid local indexes by default, while dense batches or explicit profiles can use denser anchors.
### V3E-08 Writer-side admission: skip single-anchor local-index blocks

This round adds writer-side block-local-index admission in `TableBuilder`: a data block writes a local-index block only when the configured `blockLocalIndexInterval` would produce at least 2 anchors. Low-value single-anchor blocks no longer emit directory entries. Properties now declare the `block.local_index.v1` incompatible feature and `ldb.table.block_local_index=true` only when the SST actually contains local-index directory entries.

This closes a pre-release format semantics risk. When V3 local-index is enabled but the table is too small, or the interval is too sparse, the format should not claim that a local-index directory exists unless one was actually written. The current rule separates the user option from the physical SST feature: only SSTs that really contain local-index metadata declare the feature, so readers do not treat admitted small tables as corrupt.

Additional 200k read-optimized samples are listed below. The reference baseline is the same local V3E-06 baseline run: cold_readrandom 232,465.180 ops/s, multiget_random 188,663.529 ops/s, multiget_sameblock 237,016.924 ops/s, scan 1,267,053.752 ops/s.

| Configuration | cold_readrandom | multiget_random | multiget_sameblock | scan | Key observation |
| --- | ---: | ---: | ---: | ---: | --- |
| V3 local-index interval=1 + admission(>=2 anchors) | 167,208.911 | 157,276.574 | 255,594.549 | 2,475,633.577 | Dense same-block lookups loaded 12 directories, 5556 entries, 195282 seeks, and 190315 hits; cold/scan did not load directories. |
| V3 local-index interval=4 + admission(>=2 anchors) | 183,962.008 | 168,337.671 | 261,546.185 | 2,483,605.102 | With the default block layout, interval=4 is too sparse and all local-index blocks were admitted out; properties do not declare the local-index feature. |

Conclusion: writer-side admission prevents low-value local-index metadata from polluting the file format and keeps scan from loading directories. `interval=1` still provides hit evidence for dense same-block MultiGet, but cold and sparse MultiGet remain below the bar for default enablement. `interval=4` acts more like a safe skip policy under the default layout. V3 local-index remains opt-in for this release.
### V3E-09 Cold random-read block cache admission

This round adds block-cache admission for direct-read data blocks. `Options.blockCacheAdmissionMinReads(int)` defaults to 1 and preserves the historical behavior. When explicitly set to 2, data blocks opened by direct get or MultiGet are recorded as admission candidates on the first miss and inserted into the block cache only after a second touch. Metadata blocks, iterator/scan-opened blocks, and `blockCacheWarmOnOpen` warmup still use forced caching, so the random-read policy does not leak into scan or format-reading paths.

The policy targets cold_readrandom and sparse MultiGet cache pollution: one-off random block touches no longer occupy the main LRU immediately, while repeatedly touched hot blocks can still be admitted. `BlockCache.stats()` now reports `admissionRequests`, `admissionSkips`, and `admissionAdmits` so the policy can be verified from benchmark output.

Adjacent 200k read-optimized samples are listed below. Both runs use table format v1 with V3 local-index disabled to isolate the cache-admission effect.

| Configuration | cold_readrandom | multiget_random | multiget_sameblock | scan | Key observation |
| --- | ---: | ---: | ---: | ---: | --- |
| admissionMinReads=1 | 177,048.621 | 161,744.394 | 216,031.923 | 2,470,462.532 | Historical behavior: cache immediately after a miss; admission counters stay at 0. |
| admissionMinReads=2 | 187,734.903 | 178,359.627 | 274,764.640 | 2,666,513.787 | Cold path reports `admissionSkips=5556` and `admissionAdmits=5556`; random MultiGet reports `admissionSkips=5564` and `admissionAdmits=5564`; scan does not trigger admission. |

Conclusion: second-touch admission is a low-risk optional policy that reduces one-off cache pollution from cold random reads and sparse MultiGet, with direct observability through cache stats. The default remains 1 for compatibility in this release. After release, read-optimized deployments should collect broader samples before considering a profile-specific default of 2.