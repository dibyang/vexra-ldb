# LDB 长稳压测与故障注入测试工具设计

[English](ldb-longrun-test-tool-design.en.md) | 中文

## 背景

当前 LDB 已具备 WAL、SSTable、checkpoint、backup、repair、check、compaction 观测和进程级 crash 测试能力，但这些能力主要分布在单元测试、工具命令和可靠性回归中，缺少一套可独立发布、可长时间运行、可注入真实文件损坏、可复核报告的长稳压测工具。

本设计新增独立测试工具 `ldb-longrun`，用于真实业务压力模拟、长时间稳定性验证、故障恢复验证、空间/资源回收观察和发布前验收。

实施跟踪文档：`docs/ldb-longrun-test-tool-implementation-plan.md`。

## 目标

- 以独立 Gradle 子项目构建，不进入主产品默认运行路径。
- 独立发布 `tar.gz` / `zip`，包含 `lib/`、`bin/`、`config/`、`README.md` 和 `README.en.md`。
- 支持 `smoke`、`nightly`、`soak`、`reopen`、`crash/recovery`、`fault-injection`、`comprehensive` 运行模式。
- 通过可校验数据模型验证已提交数据不损坏、不丢失。
- 支持父子进程 crash/recovery，周期性强杀 worker 后恢复同一 `workDir`。
- 支持 copy-based 真实文件损坏注入，首版复制整个 DB 目录，只破坏副本。
- 周期性输出机器可读 metrics，并在正常结束或手动 `report` 时生成可判定报告。
- 通过 LDB 暴露的 live data bytes 计算空间放大率。

## 非目标

- 不把长测、30 天 soak、故障注入加入默认 CI。
- 不让 longrun 子项目反向影响主产品 API、启动路径或默认发布物。
- 首版不支持 Windows crash 强杀脚本；Linux 为唯一正式运行平台。
- 首版 `comprehensive` 默认不启用 fault injection，避免普通综合压测混入文件破坏语义。
- 首版不提供外部可视化平台，只输出 CSV、properties 和 Markdown 报告。

## 现状/已有流程

- 根项目当前是 Gradle Java 项目，`settings.gradle` 仅包含根项目。
- 已有可靠性计划位于 `docs/ldb-reliability-plan.md` 和英文副本。
- 已有若干恢复、损坏、压缩、观测、backup、repair、tool 相关测试。
- `LDBFactory.check`、工具命令、compaction 属性、容量水位属性等能力可作为 longrun 观测和报告基础。

## 核心约束

- 保持 JDK8 兼容。
- profile 首版使用 `properties`。
- 默认输出目录为 `work/`，禁止使用 `build/` 作为运行数据目录。
- 每个 profile 必须有独立 `workDir`。
- Java 层必须对 `workDir` 加独占文件锁。
- 同一 `instance` 默认禁止重复启动。
- 故障注入必须使用独立 profile，不默认混入 `smoke` 或 `comprehensive`。
- metrics resume 后必须重置 baseline，避免 crash/recovery 后出现虚假吞吐尖峰。
- 设计文档、README 和 profile 说明必须维护中英双份，文件使用 UTF-8。

## 总体架构

```text
ldb-longrun
  CLI / Scripts
    start / run / stop / status / logs / restart / report
  Profile Loader
    properties 配置解析和 CLI 覆盖
  Instance Manager
    instance、pid、log、workDir lock
  Workload Engine
    读、写、删除、commit、scan、reopen
  Consistency Model
    checksum、sequence、version、counters、ledger
  Crash Supervisor
    parent / worker 模型
  Fault Injector
    copy-based 文件损坏注入
  Metrics Reporter
    ops、reclamation、fault、event metrics
  Report Analyzer
    summary.md / summary.properties
  Packaging
    独立 tar.gz / zip
```

## 目录结构

推荐将主产品保留在根项目，新增 Gradle 子项目：

```text
settings.gradle
  include 'ldb-longrun'

ldb-longrun/
  build.gradle
  src/main/java/net/xdob/vexra/ldb/longrun/
    LongRunMain.java
    cli/
    config/
    instance/
    workload/
    model/
    verify/
    crash/
    fault/
    metrics/
    report/
    util/
  src/main/resources/profiles/
    smoke.properties
    nightly.properties
    soak.properties
    reopen.properties
    crash.properties
    fault-injection.properties
    comprehensive.properties
  src/test/java/net/xdob/vexra/ldb/longrun/
  src/dist/bin/
    longrun
  src/dist/config/
    *.properties
  README.md
  README.en.md
```

发布产物：

```text
ldb-longrun-<version>.tar.gz
ldb-longrun-<version>.zip
  lib/
    ldb-longrun.jar
    dependency jars
  bin/
    longrun
  config/
    *.properties
  README.md
  README.en.md
```

运行输出：

```text
work/
  smoke/
  nightly/
  soak/
  reopen/
  crash/
  fault-injection/
  comprehensive/
logs/
run/
```

## 配置文件设计

配置优先级：

```text
内置默认值 < profile 文件 < 系统属性 -Dkey=value < CLI --key=value
```

基础 profile 示例：

```properties
run.name=smoke
run.instance=smoke-1
run.duration=5m
run.seed=20260602
run.workDir=work/smoke

workload.mode=mixed
workload.keySpace=100000
workload.valueSizeMin=64
workload.valueSizeMax=4096
workload.readRatio=0.55
workload.writeRatio=0.35
workload.removeRatio=0.10
workload.commitEveryOps=1000

metrics.interval=10s
state.interval=30s
check.reopenInterval=0

crash.enabled=false
crash.interval=0
crash.cycles=0

fault.enabled=false
fault.interval=0
fault.kinds=
fault.maxBytes=4096
fault.retainedCopies=5

limits.maxDbSizeGb=20
threshold.maxSizeAmplification=5.0
threshold.suspiciousLogLines=0
```

profile 默认规划：

| profile | duration | workDir | reopen | crash | fault |
| --- | ---: | --- | --- | --- | --- |
| smoke | 5m | `work/smoke` | 关闭 | 关闭 | 关闭 |
| nightly | 12h | `work/nightly` | 可选 | 关闭 | 关闭 |
| soak | 7d/30d | `work/soak` | 可选 | 关闭 | 关闭 |
| reopen | 1h+ | `work/reopen` | 开启 | 关闭 | 关闭 |
| crash | 30m+ | `work/crash` | 关闭 | 开启 | 关闭 |
| fault-injection | 30m+ | `work/fault-injection` | 关闭 | 关闭 | 开启 |
| comprehensive | 12h | `work/comprehensive` | 开启 | 开启 | 关闭 |

## CLI 和脚本设计

Java CLI：

```text
longrun run      --config <file> [--key=value]
longrun start    --config <file> [--key=value]
longrun stop     --instance <name>
longrun status   --instance <name>
longrun logs     --instance <name>
longrun restart  --config <file> [--key=value]
longrun report   --workDir <dir>
longrun worker   --config <file> --resume=true|false
```

Linux 脚本语义：

| 命令 | 行为 |
| --- | --- |
| `start` | 默认后台启动，写 `run/<instance>.pid`，日志写 `logs/<instance>.out` |
| `run` | 前台运行 |
| `status` | 根据 pid 文件和进程存活判断 |
| `logs` | 默认 tail `logs/<instance>.out` |
| `stop` | 优雅停止，超时后可选强停 |
| `restart` | 先 stop 后 start |
| `report` | 重新分析已有 metrics、state 和 log |

`instance` 派生规则：

1. CLI `--run.instance`。
2. profile `run.instance`。
3. 默认使用 `run.name`。

`workDir` 锁使用 `FileChannel.tryLock()` 获取 `work/<profile>/.longrun.lock`。锁失败直接退出并标记失败，避免两个进程同时写同一 DB 目录。

## 数据结构

value 编码：

```text
magic
formatVersion
keyId
sequence
valueVersion
operationType
payloadLength
payloadBytes
checksumCrc32c
```

状态文件：

```text
state/
  committed-state.properties
  counters.csv
  ledger.log
  checkpoint.tmp
  checkpoint.committed
```

关键语义：

- commit 成功后才更新 `committed-state`。
- checkpoint 只表示 worker 进度，不表示业务提交事实。
- crash 恢复时只校验已确认提交的数据。
- ledger 是有限大小环形账本，用于校验最近已提交操作。

## 状态机

```text
NEW
  -> RUNNING
  -> VERIFYING
  -> REOPENING
  -> RECOVERING
  -> FAULT_INJECTING
  -> REPORTING
  -> PASS / WARN / FAIL
```

非法转换：

- `FAULT_INJECTING` 不能直接破坏主库。
- `RECOVERING` 不能把未提交 checkpoint 当成必须存在的数据。
- `REPORTING` 发现一致性失败必须进入 `FAIL`。

## 时序流程

普通运行：

```text
load profile -> acquire workDir lock -> open DB -> workload loop -> periodic metrics/state -> final verify -> report
```

reopen：

```text
commit -> close DB -> open DB -> verify -> reopenChecks++ -> continue workload
```

crash/recovery：

```text
parent start worker -> sleep crash.interval -> kill -9 worker -> restart worker resume=true -> recovery verify -> continue
```

fault injection：

```text
commit -> verify main DB -> close -> copy full DB dir -> corrupt copy -> readonly open copy -> classify -> reopen main DB -> verify main DB
```

## 异常处理

- 一致性失败直接 `FAIL`。
- 主库被 fault 破坏直接 `FAIL`。
- `UNEXPECTED_*` fault 结果直接 `FAIL`。
- profile 要求 reopen/recovery 但检查次数为 0，直接 `FAIL`。
- metrics 缺失在关键 profile 中为 `FAIL`，在短 smoke 中至少为 `WARN`。
- suspicious log patterns 首版可配置，默认包含 `ERROR`、`Corruption`、`Checksum`、`panic`、`leak`、`Exception`；最终列表待发布流程确认。

## 幂等性

- `stop` 多次执行应稳定返回。
- `report` 可重复执行，不修改业务数据。
- fault 副本清理只删除过期副本，不删除历史 metrics。
- worker resume 可重复加载 `committed-state`，不得推进已提交序号。

## 回滚策略

longrun 是独立子项目和独立发布物。若工具出现问题，可从发布流程中移除 longrun 产物或不执行相关 profile，不影响主产品 jar。若新增 LDB live data bytes 属性引发兼容风险，应先保留旧属性并新增只读属性，不改变现有 API 行为。

## 兼容性

- longrun 依赖主产品公开 API 和只读观测属性。
- longrun 子项目不应被主产品依赖。
- 不改变现有磁盘格式。
- 不改变默认 CI。
- 不改变主产品 Maven 发布坐标。

## 灰度/迁移

1. 新增设计文档和子项目骨架。
2. 实现 smoke profile 和 report analyzer。
3. 接入 reopen、crash/recovery、fault injection。
4. 在发布流程中增加显式 longrun 验收，不进入默认 CI。
5. 7 天 soak 报告归档流程待发布负责人确认。

## metrics 格式

`metrics/ops.csv`：

```csv
timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks
```

`metrics/reclamation.csv`：

```csv
timeMillis,status,message,beforeFileSize,afterFileSize,shrinkBytes,fillRate,estimatedReclaimedBytes,candidateChunks,backoffCount,noProgressCount,successCount
```

`metrics/fault.csv`：

```csv
timeMillis,eventId,kind,status,message,offset,length,beforeSize,afterSize,filePath
```

`metrics/events.log`：

```text
timeMillis,type,status,message
```

吞吐统计规则：

- `workerEpoch` 变化后重置 baseline。
- crash/recovery 后首个样本标记为 warmup，不进入正式吞吐统计。
- 短跑样本不足时不输出正式性能结论。
- 长跑使用稳定窗口的 5th percentile 与 median 计算 `throughputDropRatio`。

## report 格式

输出文件：

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

必须包含：

- Operations
- Commits
- Reopen Checks
- Recovery Checks
- Final Size Bytes
- Metric Samples
- Avg/Min/Max Ops/s
- Throughput Drop Ratio
- Reclamation Events
- Reclamation Success Events
- Reclamation Backoff Events
- Reclamation Shrink Bytes
- Final Size GB
- Size Per Million Ops GB
- Size Amplification
- Fault Injection Events
- Fault Injection Recovered Events
- Fault Injection Detected Events
- Fault Injection Unexpected Events
- Fault Injection Status Counts
- Fault Injection Kind Counts
- Suspicious Log Lines
- Failures
- Warnings
- Recent Events

报告状态：

| 状态 | 含义 |
| --- | --- |
| `PASS` | 所有硬性条件满足 |
| `WARN` | 存在非阻塞风险，需要人工解释 |
| `FAIL` | 一致性、恢复、报告、fault 或空间放大率硬性条件失败 |

## fault injection 设计

首版采用 copy-based 模式，复制整个 DB 目录：

1. 主库 commit。
2. 主库 full verify。
3. 关闭主库。
4. 复制整个 DB 目录到 `work/<profile>/fault/fault-N/`。
5. 只破坏副本。
6. 只读打开副本。
7. 对副本执行业务 verify。
8. 分类结果。
9. 重新打开主库。
10. 主库 full verify。
11. 记录 fault event。
12. 删除超过 `fault.retainedCopies` 的旧副本。

损坏类型：

| kind | 行为 |
| --- | --- |
| `truncate` | 随机截断文件尾部 |
| `bit-flip` | 随机翻转 bit |
| `zero-range` | 随机范围置零 |
| `random-range` | 随机范围写随机字节 |
| `partial-page` | 破坏部分页或块 |

结果分类：

| 状态 | 含义 | 是否失败 |
| --- | --- | --- |
| `RECOVERED` | 副本可打开且业务校验通过 | 否 |
| `DETECTED` | 引擎打开阶段拒绝损坏文件 | 否 |
| `DETECTED_BY_VERIFY` | 副本能打开，但业务校验发现问题 | 否 |
| `UNEXPECTED_OPEN_ERROR` | 非预期打开错误 | 是 |
| `UNEXPECTED_MAIN_DB_DAMAGE` | 主库被破坏 | 是 |
| `UNEXPECTED_UNCLASSIFIED` | 无法分类 | 是 |

`RECOVERED` 不一定代表失败，因为损坏可能落在未使用区域、旧版本区域或可恢复区域。

## crash/recovery 设计

parent：

```text
load profile
start worker resume=false
sleep crash.interval
kill -9 worker
workerEpoch++
start worker resume=true
repeat until crash.cycles or duration reached
start final worker for verify/report
```

worker：

```text
acquire workDir lock
if resume=true: recovery verify
load committed-state
run workload
periodic commit
after commit: update committed-state
periodic checkpoint
append metrics
```

恢复语义：

- 只要求已确认提交的数据不能损坏、不能丢失。
- checkpoint 中可能包含未提交进度，恢复时不得误判。
- 每次成功 recovery verify 后 `recoveryChecks++`。
- `recoveryChecks` 必须写入 final report。

## reopen 设计

reopen profile：

```text
run workload
commit
close DB
open DB
full/sample/ledger verify
reopenChecks++
continue
```

验收：

- `reopenChecks > 0`。
- reopen 后无一致性失败。
- report 必须显示 `Reopen Checks`。

## 空间/资源回收观察设计

由 LDB 暴露 live data bytes，longrun 计算：

```text
sizeAmplification = physicalSize / liveDataBytes
```

采集项：

- DB 目录总物理大小。
- WAL / SST / MANIFEST / LOG 分类大小。
- LDB live data bytes。
- compaction/reclaim 事件。
- before/after file size。
- shrink bytes。
- estimated reclaimed bytes。
- candidate chunks。
- backoff/no-progress/success 计数。

验收：

| 指标 | 默认阈值 |
| --- | --- |
| sizeAmplification | `<= 5x` |
| suspiciousLogLines | `0` |
| faultUnexpectedEvents | `0` |
| long run reclamation events | `> 0`，否则 WARN |
| backoff 比例 | 长期过高 WARN |

## 测试方案

`ldb-longrun` 子项目自身必须有 JUnit 测试，覆盖：

- 配置解析和默认值。
- CLI / 系统属性覆盖。
- `workDir` 锁。
- instance 隔离。
- metrics baseline reset 和 worker epoch。
- report 分析。
- crash/recovery 状态恢复。
- reopen 校验。
- fault kind 解析。
- 文件损坏注入。
- fault 副本保留上限。
- UTF-8 文档编码。
- 发行包构建。

建议 Gradle 任务：

```text
:ldb-longrun:test
:ldb-longrun:distZip
:ldb-longrun:distTar
:ldb-longrun:longrunSmoke
```

`longrunSmoke` 也必须显式执行，不进入默认 CI。

## 风险点

| 风险 | 严重性 | 缓解 |
| --- | --- | --- |
| 长跑工具误破坏主库 | 高 | fault copy-based，主库 verify，独立 fault profile |
| crash metrics 产生虚假尖峰 | 中 | workerEpoch 和 baseline reset |
| live data bytes 估算不准 | 中 | 由 LDB 暴露只读属性，report 标注来源 |
| 长跑产物撑爆磁盘 | 高 | `limits.maxDbSizeGb` 和 `fault.retainedCopies` |
| 默认 CI 被长测拖慢 | 高 | 子项目长跑任务全部显式执行 |
| 日志 suspicious pattern 误报 | 中 | pattern 可配置，发布前确认最终列表 |

## 分阶段实施计划

| 阶段 | 交付物 | 验证 |
| --- | --- | --- |
| P0 | 中英文设计文档 | 人工评审 |
| P1 | Gradle 子项目、CLI、配置、发行包 | `distZip` / `distTar` |
| P2 | workload、数据模型、verify | smoke PASS |
| P3 | metrics、report、阈值 | report 单测 |
| P4 | reopen | reopen 1h PASS |
| P5 | crash/recovery | crash 30m PASS |
| P6 | fault injection | fault 30m 无 unexpected |
| P7 | 空间回收观察 | comprehensive 12h PASS |
| P8 | soak 验收 | 24h/72h/7d PASS |

## 发布验收标准

发布前至少执行：

| profile | 时长 | 标准 |
| --- | ---: | --- |
| smoke | 5m | PASS |
| reopen | 1h | PASS，`reopenChecks > 0` |
| crash/recovery | 30m | PASS，`recoveryChecks > 0` |
| fault-injection | 30m | 无 unexpected |
| comprehensive | 12h | PASS |
| soak | 24h 或 72h | PASS |
| 正式版本 | 7d | 建议至少一轮 PASS |

硬性标准：

- 主库一致性无失败。
- 已提交数据不丢失。
- `reopenChecks > 0`。
- `recoveryChecks > 0`。
- `faultInjectionEvents > 0`。
- `faultInjectionUnexpectedEvents = 0`。
- `suspiciousLogLines = 0`。
- `sizeAmplification <= 5x`。
- report 为 `PASS`；`WARN` 必须有明确合理解释。

## 待人工拍板

- suspicious log patterns 的最终默认列表。
- 7 天长跑执行环境、负责人、报告归档位置和发布门禁流程。
- LDB live data bytes 属性名称和精确语义。
- longrun 子项目是否随主版本号发布，或使用独立 classifier/包名。
