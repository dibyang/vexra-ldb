# LDB 长期压测与报告框架设计

[English](ldb-longrun-benchmark-design.en.md) | 中文

## 背景

LDB 已有轻量 benchmark/soak 测试、operation histogram、block cache stats、compaction/write-stall 指标和容量水位属性。这些入口能证明局部行为正确，但还不足以支撑接近 RocksDB 成熟度的发布可信度：缺少统一报告格式、长期趋势、失败归因、发布门禁阈值，以及低磁盘/高并发/反复 crash-reopen 等环境级场景。

## 目标

- 设计可重复执行的长期压测矩阵和机器可读报告。
- 统一写入、读取、snapshot、compaction、backup、repair、crash/reopen 的指标采集。
- 明确发布前必须满足的门禁阈值。
- 保持默认单元测试轻量，长压测作为显式任务运行。

## 非目标

- 不在普通 `test` 中默认运行小时级压测。
- 不引入外部可视化系统；先输出 JSON/Markdown 报告。
- 不以单次本机数据承诺所有生产环境性能。

## 现状/已有流程

| 能力 | 当前状态 |
| --- | --- |
| Micro benchmark | 已覆盖写入、随机读、snapshot scan、manual compaction、checkpoint |
| Soak | 已覆盖 compaction、多列族、write-stall、reopen 等局部场景 |
| Observability | `getProperty` 暴露 operation、block cache、WAL、file bytes、compaction stats |
| 报告 | 主要在测试断言和属性字符串中，尚无统一发布报告 |

## 核心约束

- JDK8 兼容。
- 压测任务必须显式启用，避免拖慢日常 CI。
- 报告必须包含环境信息：OS、JDK、CPU、内存、磁盘路径、LDB 版本、配置。
- 所有 workload 必须记录 seed，便于复现。
- 失败必须保留 DB 目录或最小诊断摘要，便于后续 repair/check。

## 接口设计

建议新增 Gradle 任务或测试 profile：

| 入口 | 语义 |
| --- | --- |
| `longRunTest` | 运行 10-30 分钟级本地长压测 |
| `releaseSoakTest` | 发布前矩阵，允许更长时间 |
| `benchmarkReport` | 运行短基准并输出报告 |
| `-Pldb.longrun.durationMinutes` | 控制时长 |
| `-Pldb.longrun.outputDir` | 报告输出目录 |
| `-Pldb.longrun.seed` | 固定随机种子 |

报告文件：

```text
build/reports/ldb-longrun/
  summary.json
  summary.md
  operations.csv
  failures.json
  properties-before.json
  properties-after.json
```

## 数据结构

`summary.json` 核心字段：

| 字段 | 含义 |
| --- | --- |
| `version` | LDB 项目版本 |
| `commit` | Git commit，可为空 |
| `environment` | OS/JDK/CPU/内存/磁盘信息 |
| `workloads[]` | 每个 workload 的配置、seed、时长 |
| `metrics` | throughput、latency、stall、compaction、cache、file bytes |
| `checks` | reopen/check/backup/restore/repair 结果 |
| `thresholds` | 发布门禁阈值与通过状态 |
| `failures[]` | 异常、失败阶段、保留路径 |

## Workload 矩阵

| Workload | 目标 | 核心指标 |
| --- | --- | --- |
| `write-burst` | 写入高峰和 write-stall | ops/s、p99、stall count、WAL bytes |
| `read-mixed` | 读写混合稳定性 | get p50/p99、cache hit rate、slow ops |
| `snapshot-scan` | 长 snapshot 与 scan | cursor open/close、snapshot 可见性、obsolete 文件 |
| `compaction-heavy` | 长 compaction 正确性 | compaction success/failure/cancel、pending bytes |
| `backup-restore-loop` | 备份恢复闭环 | backup/check/restore/reopen 成功率 |
| `repair-plan-loop` | 损坏前诊断 | repair-plan 可解释性和耗时 |
| `crash-reopen` | 进程级恢复 | 已 sync 数据恢复率、WAL replay 耗时 |
| `low-disk-simulated` | 磁盘异常前置 | 写失败语义、目录清理、报告保留 |

## 状态机

`PREPARE -> WARMUP -> RUNNING -> VERIFYING -> REPORTING -> PASSED/FAILED`

- `PREPARE` 创建隔离工作目录和配置快照。
- `WARMUP` 预热，数据不计入最终指标。
- `RUNNING` 执行 workload 并周期采样 property。
- `VERIFYING` 执行 reopen/check/backup/repair 等闭环。
- `REPORTING` 写 JSON/Markdown/CSV。

## 时序流程

1. 读取 Gradle 参数和默认配置。
2. 为每个 workload 创建独立 DB 目录和 seed。
3. 周期采样 `getProperty`，并记录操作延迟直方图。
4. workload 结束后执行 `check`、reopen 和必要的 backup/restore。
5. 汇总阈值判断，写报告。
6. 若失败，保留失败 DB 路径并记录最小复现命令。

## 异常处理

- workload 异常：停止当前 workload，执行 best-effort check/repair-plan，报告失败。
- JVM crash 子进程异常：父进程记录退出码和 DB 目录。
- 报告写入失败：测试失败，并打印报告目录。
- 阈值未达标：构建可选择失败或只标记 warning，由 `-Pldb.longrun.failOnThreshold` 控制。

## 幂等性

- 每次运行写入独立 timestamp/seed 目录。
- 相同 seed 和配置应产生相同 key 分布。
- 清理任务只删除本次报告目录，不触碰用户数据。

## 回滚策略

该框架默认只新增测试/报告，不影响 LDB runtime。若报告任务不稳定，可从 release gate 中移除，但保留单元测试和现有属性。

## 兼容性

- 不改变磁盘格式。
- 报告 JSON 字段只新增不反转语义。
- 老版本可不支持某些 property，报告应标记 `missingProperty`。

## 发布门禁建议

| 门禁 | 初始阈值 |
| --- | --- |
| 数据正确性 | 所有 workload 结束后 check/reopen 成功 |
| 写入 p99 | 与上一基线相比退化不超过 30% |
| 读 p99 | 与上一基线相比退化不超过 30% |
| compaction failure | 必须为 0 |
| cursor leak | openCount == closeCount |
| backup/restore | 成功率 100% |
| repair-plan | 不修改目录，输出 JSON 成功 |

## 测试方案

- 报告生成单元测试：短时 workload 生成完整 JSON/Markdown。
- 阈值判断测试：构造 pass/fail 两类报告。
- 失败保留测试：workload 抛异常后报告包含 DB 路径。
- 子进程 crash-reopen 测试复用已有 crash 注入入口。

## 风险点

| 风险 | 缓解 |
| --- | --- |
| 本机性能波动导致误判 | 初始采用宽阈值，并记录环境和基线 |
| 长压测拖慢 CI | 默认不运行，发布或 nightly 显式启用 |
| 报告过大 | CSV 分文件，summary 只保留聚合 |

## 分阶段实施计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 1 | 定义报告 JSON/Markdown schema 和短报告生成器 | 单测生成报告 |
| 2 | 接入 write/read/snapshot/compaction workload | 本地 10 分钟报告稳定 |
| 3 | 接入 backup/restore/repair-plan/crash-reopen | 闭环工作流报告通过 |
| 4 | 加入阈值和基线比较 | release gate 可配置失败 |
| 5 | 增加低磁盘/权限失败环境级脚本 | 失败报告可解释 |
