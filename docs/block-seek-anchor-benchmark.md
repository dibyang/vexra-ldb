# Block.seek 内存 seek anchor 基准记录

本文记录 `Block.seek` 内存态 seek anchor 的局部基准结果，用于评估随机点查路径中
`decodedEntries`、`sharedKeyRebuilds`、线程分配与 anchor 内存成本之间的权衡。

## 背景

`Block.seek` 在 prefix-compressed block 内定位目标 key 时，需要从 restart 或 seek anchor
位置开始顺序解码 entry。对于共享前缀 entry，解码下一条 entry 前必须拥有上一条完整 key，
因此无法简单跳过 shared-key rebuild。当前优化方向是通过更密的内存 seek anchor 缩短线性
解码距离。

## 当前主线策略

- `SEEK_ANCHOR_INTERVAL = 2`
- 仅影响 `Block` 构造后的内存态 anchor，不改变磁盘 block 格式。
- `blockSeekMicroBenchReport` 输出：
  - `decodedEntriesPerOp`
  - `sharedKeyRebuildsPerOp`
  - `seekAnchorCount`
  - `seekAnchorRetainedKeyBytes`

## micro-benchmark 对比

配置：

- `entries=8192`
- `reads=200000`
- `valueSize=100`
- `seed=20260623`

### anchor interval 对比

| anchor interval | ops/s | bytes/op | decodedEntriesPerOp | sharedKeyRebuildsPerOp | seekAnchorCount | seekAnchorRetainedKeyBytes |
|---:|---:|---:|---:|---:|---:|---:|
| 2 | 496,848 | 236.964 | 1.623 | 1.499 | 3,584 | 172,032 |
| 1 | 496,472 | 199.047 | 1.062 | 1.000 | 7,680 | 未保留 |

`interval=1` 在 micro 层减少了解码和分配，但真实 `ldbDbBenchReport` 中
`readrandom_mixed` 吞吐与 allocation 均不稳定，且 anchor 数量翻倍，因此未进入主线。

### restart interval 矩阵（anchor interval=2）

| restart interval | ops/s | bytes/op | decodedEntriesPerOp | sharedKeyRebuildsPerOp | seekAnchorCount | seekAnchorRetainedKeyBytes |
|---:|---:|---:|---:|---:|---:|---:|
| 4 | 478,914 | 254.584 | 2.002 | 1.500 | 2,048 | 98,304 |
| 8 | 425,898 | 245.910 | 1.747 | 1.499 | 3,072 | 147,456 |
| 16 | 643,819 | 236.964 | 1.623 | 1.499 | 3,584 | 172,032 |
| 32 | 571,240 | 232.472 | 1.561 | 1.499 | 3,840 | 184,320 |

## 结论

- 当前 `interval=2` 是比 `interval=1` 更稳的主线折中。
- `interval=1` 不建议默认启用：它降低 micro allocation，但真实读场景没有稳定收益，且
  anchor 数量和保留 key 字节显著增加。
- 后续如果继续做自适应 anchor，应以 `seekAnchorRetainedKeyBytes` 和真实 dbBench 的
  `readrandom_mixed` / `multiget_random` 共同作为准入门槛。

## 自适应 anchor 策略

当前内存态策略对普通大 restart 区间继续保留 `interval=2`，但不再对低收益区间付出同样的常驻 key 内存成本：

- entry 数不超过 4 的 restart 区间不构建内存 seek anchor；
- 小 restart 区间或 shared-key 比例较低的区间降为 interval=4；
- 普通 prefix-compressed 区间继续使用 interval=2，保留已验证的 seek 距离缩短收益。

该策略只影响 reader 打开 block 后构建的内存态 anchor，不改变磁盘 block 格式，也不改变持久化 inline seek-index 编码。

`readrandom_mixed` 现在额外输出 `workloadStats`，按 hit/miss 拆分 lookups、found、latency 和线程分配。filter-skip 与 cache-hit/cache-miss 证据继续保留在已有 `sstReadStats` 和 `blockCacheStats` 字段中，后续 mixed 调优可以把调用方 hit/miss 成本与存储引擎计数关联起来看。

## 发布前轻量门禁

`blockSeekPerfGate` 已接入 `releaseGate`。该门禁会先运行 `blockSeekMicroBenchReport`，再检查 JSON 报告中的关键字段是否存在且为正：

- `decodedEntriesPerOp`
- `sharedKeyRebuildsPerOp`
- `seekAnchorCount`
- `seekAnchorRetainedKeyBytes`

该门禁只防止 benchmark 入口、报告结构和核心观测信号退化，不对 `opsPerSecond` 设置硬阈值；吞吐仍需结合真实 `ldbDbBenchReport` 和同机对照判断。
