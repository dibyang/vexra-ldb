# LDB 插件隔离与异步执行评估

[English](ldb-plugin-isolation-async-design.en.md) | 中文

## 背景

插件当前与 LDB 同 JVM、同线程同步执行。这个模型简单、可预测，也最符合当前可信内部扩展定位。P5 的目标是固化哪些阶段必须保持同步 fail-fast，哪些阶段未来可以评估异步化，并定义慢插件的观测与降级边界。

## 目标

- 固化生命周期阶段的同步、失败和降级策略。
- 明确第一阶段不启用异步执行。
- 暴露 `ldb.plugin.executionPolicy`，让工具和 longrun 能确认当前策略。
- 给后续异步或隔离实现留下可测试的进入条件。

## 非目标

- 不在当前阶段实现线程池异步回调。
- 不实现 classloader 隔离。
- 不把插件机制作为安全边界。
- 不改变 `beforeWrite`、`beforeCheckpoint`、`onOpen` 的 fail-fast 语义。

## 当前策略

| 阶段 | 当前执行 | 是否允许异步 | 失败策略 |
| --- | --- | --- | --- |
| `configure` | 同步 | 否 | fail-fast |
| `onOpen` | 同步 | 否 | fail-fast |
| `beforeWrite` | 同步 | 否 | fail-fast，无部分提交 |
| `afterWrite` | 同步 | 候选 | post-commit，可按策略记录或抛出 |
| `beforeCheckpoint` | 同步 | 否 | fail-fast |
| `afterCheckpoint` | 同步 | 候选 | post-commit，可按策略记录或抛出 |
| `beforeClose` | 同步 | 后续评估 | 记录并继续关闭 |
| `close` | 同步 | 后续评估 | 记录并继续关闭 |

当前 property：

```text
ldb.plugin.executionPolicy=asyncEnabled=false,configure=syncFailFast,onOpen=syncFailFast,beforeWrite=syncFailFast,afterWrite=syncPostCommitCandidate,beforeCheckpoint=syncFailFast,afterCheckpoint=syncPostCommitCandidate,beforeClose=syncRecordAndClose,close=syncRecordAndClose
```

## 慢插件观测

已落地观测字段：

| 字段 | 含义 |
| --- | --- |
| `ldb.pluginStats` | 聚合 callbacks、failures、maxMicros、lastFailure |
| `ldb.plugin.<index>.stats` | 单插件 order、capabilities、failurePolicy、phase count/failure |
| `ldb.plugin.lastFailure` | 最近失败阶段、插件名、是否已提交、消息 |
| longrun `plugin.properties` | 将插件状态落盘供 report 使用 |

后续可扩展字段：

| 字段 | 用途 |
| --- | --- |
| `timeoutCount` | 统计慢插件超时 |
| `disabled` | 标记插件是否被自动禁用 |
| `degradationReason` | 最近一次降级原因 |
| `queueDepth` | 异步候选阶段的队列深度 |

## 异步进入条件

只有同时满足以下条件，才建议实现异步：

- 至少一个真实插件或 longrun profile 证明同步回调成本显著。
- 已定义队列容量、丢弃策略、关闭等待时间和超时处理。
- `afterWrite` 异步化不会让调用方误判提交边界。
- property 和日志能识别插件是否降级或被禁用。
- 正确性敏感阶段仍保持同步 fail-fast。

## 降级策略

| 策略 | 适用阶段 | 说明 |
| --- | --- | --- |
| record-only | 提交后通知 | 记录失败，不影响已提交数据 |
| skip-notification | 提交后通知 | 后续通知可跳过，但需记录 |
| disable-plugin | 慢或失败频繁插件 | 需要明确配置或未来熔断条件 |
| fail-fast | 正确性敏感阶段 | `beforeWrite`、`beforeCheckpoint`、`onOpen` 固定使用 |

## 兼容性

- 当前实现不改变提交顺序和线程模型。
- `RECORD_AND_CONTINUE` 仍只适用于提交后通知阶段。
- 新增 `ldb.plugin.executionPolicy` 是只读观测属性。
- 回滚不会影响磁盘数据。

## 测试方案

| 测试 | 覆盖 |
| --- | --- |
| post-commit failure | `afterWrite`/`afterCheckpoint` 失败不回滚数据或 checkpoint |
| fail-fast phase | 正确性敏感阶段失败阻止核心操作 |
| execution policy property | property 和 longrun report 能读取策略 |
| plugin stats | 慢/失败插件能在 per-plugin stats 中定位 |

## 当前结论

P5 当前完成的是策略固化和观测落地，不启用异步。后续真正实现异步或隔离时，应以本文件的进入条件作为门槛。
