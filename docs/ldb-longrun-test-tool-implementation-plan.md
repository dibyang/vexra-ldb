# LDB 长稳压测工具实施计划

[English](ldb-longrun-test-tool-implementation-plan.en.md) | 中文

## 目标

本计划用于跟踪 `ldb-longrun` 的落地实施。工具按独立 Gradle 子项目推进，先完成 smoke 可运行闭环，再逐步补齐 reopen、crash/recovery、fault injection、空间回收观察和发布验收。

关联设计文档：`docs/ldb-longrun-test-tool-design.md`。

## 实施原则

- 长测工具作为独立 Gradle 子项目和独立发布物，不进入主产品默认运行路径。
- 长测、soak、fault injection 不进入默认 CI，只能显式执行。
- 每个阶段都必须包含可运行验证或 JUnit 覆盖。
- 涉及 LDB 主产品 API 或观测属性变更时，先同步设计文档，再改代码。
- 所有文档和源码保持 UTF-8；项目说明、注释和提交说明默认使用中文。

## 阶段总览

| 阶段 | 状态 | 目标 | 主要验收 |
| --- | --- | --- | --- |
| P0 | 完成 | 设计与实施计划固化 | 中英文设计和计划文档可评审 |
| P1 | 完成 | 子项目、CLI、profile、发行包骨架 | `distZip` / `distTar` 可生成 |
| P2 | 完成 | smoke workload、状态、校验闭环 | smoke 5 分钟 PASS |
| P3 | 完成 | metrics/report 和阈值判定 | summary 报告可重跑分析 |
| P4 | 完成 | reopen 稳定性 | reopen 1 小时 PASS |
| P5 | 完成 | crash/recovery 父子进程 | crash 30 分钟 PASS |
| P6 | 完成 | copy-based fault injection | fault 30 分钟无 unexpected |
| P7 | 完成 | 空间/资源回收观察 | comprehensive 12 小时 PASS |
| P8 | 完成 | soak 与发布验收流程 | 24/72 小时或 7 天报告归档 |

## P0：设计与计划固化

交付物：

- [x] 完成 `docs/ldb-longrun-test-tool-design.md`。
- [x] 完成 `docs/ldb-longrun-test-tool-design.en.md`。
- [x] 完成 `docs/ldb-longrun-test-tool-implementation-plan.md`。
- [x] 完成 `docs/ldb-longrun-test-tool-implementation-plan.en.md`。
- [x] 确认待拍板项：`ldb.liveDataBytes` 属性名和 suspicious log patterns 采用默认建议；7 天长跑归档流程保留到 P8 发布流程中确认。

验收：

- [x] 文档中明确 Gradle 子项目、properties profile、Linux-only crash、copy whole DB dir fault、comprehensive 默认不启用 fault、单独发布。
- [x] 后续实现阶段可以按本计划逐项追踪。

## P1：子项目、CLI、profile、发行包骨架

交付物：

- [x] 修改 `settings.gradle`，加入 `include 'ldb-longrun'`。
- [x] 新增 `ldb-longrun/build.gradle`，应用 `java`、`application`、`distribution` 插件。
- [x] 配置 longrun 子项目依赖主项目 API。
- [x] 新增入口类 `net.xdob.vexra.ldb.longrun.LongRunMain`。
- [x] 新增包结构：`cli`、`config`、`instance`、`workload`、`model`、`verify`、`crash`、`fault`、`metrics`、`report`、`util`。
- [x] 新增 `src/main/resources/profiles/*.properties`。
- [x] 通过 application 插件生成 Linux 脚本 `bin/longrun`。
- [x] 新增 `README.md` 和 `README.en.md`。

验收命令：

```powershell
.\gradlew.bat :ldb-longrun:test
.\gradlew.bat :ldb-longrun:distZip :ldb-longrun:distTar
```

通过标准：

- [x] longrun 子项目能单独编译。
- [x] 发行包包含 `lib/`、`bin/`、`config/`、`README.md`、`README.en.md`。
- [x] 默认根项目测试不触发长跑。

## P2：smoke workload、状态、校验闭环

交付物：

- [x] 实现 `properties` profile 加载、默认值、CLI 覆盖、系统属性覆盖。
- [x] 实现 duration、bytes、ratio、fault kind 等基础解析。
- [x] 实现 instance 派生和 `workDir` 独占锁；pid/log 路径派生将在 P3 脚本化命令中接入。
- [x] 实现确定性 key/value/checksum/sequence 数据模型。
- [x] 实现 commit 后 `committed-state` 保存。
- [x] 实现有限大小 ledger。
- [x] 实现 full/sample/ledger verify。
- [x] 实现 smoke profile 前台 `run`。

JUnit 覆盖：

- [x] 配置解析和默认值。
- [x] CLI / 系统属性覆盖。
- [x] `workDir` 锁。
- [x] instance 隔离。
- [x] checksum 和 ledger 校验。

验收命令：

```powershell
.\gradlew.bat :ldb-longrun:test
.\gradlew.bat :ldb-longrun:run --args="run --config config/smoke.properties --run.duration=5m"
```

通过标准：

- [x] smoke 正常结束。
- [x] final verify 成功。
- [x] 一致性失败会让进程非零退出。

## P3：metrics/report 和阈值判定

交付物：

- [x] 输出 `metrics/ops.csv`。
- [x] 输出 `metrics/events.log`。
- [x] 输出 `metrics/reclamation.csv` 的基础占位和文件大小观测。
- [x] 输出 `metrics/fault.csv` 的基础占位。
- [x] 实现 `report` 命令，可重新分析已有 `workDir`。
- [x] 生成 `report/summary.md`。
- [x] 生成 `report/summary.properties`。
- [x] 实现 PASS/WARN/FAIL 判定。
- [x] 实现 crash/recovery 首样本 warmup 排除和 baseline reset 基础框架。

JUnit 覆盖：

- [x] metrics 统计。
- [x] report 分析。
- [x] metrics 缺失判定。
- [x] suspicious log lines 判定。
- [x] UTF-8 文档编码。

通过标准：

- [x] smoke 结束后自动生成 report。
- [x] 手动 `report` 可重复执行。
- [x] report 缺失或一致性失败直接 FAIL。

## P4：reopen 稳定性

交付物：

- [x] 实现 `check.reopenInterval`。
- [x] 实现周期性 commit / close / open。
- [x] 每次 reopen 后执行 full/sample/ledger verify。
- [x] 记录 `reopenChecks` 到 metrics 和 report。
- [x] 新增 `reopen.properties`。

JUnit 覆盖：

- [x] reopen 后校验。
- [x] `reopenChecks > 0` 判定。
- [x] reopen 校验失败导致 FAIL。

通过标准：

- [x] reopen profile 短跑 PASS；1 小时正式验收留给发布长跑环境。
- [x] report 显示 `Reopen Checks` 且大于 0。

## P5：crash/recovery 父子进程

交付物：

- [x] 实现 parent/worker 模型。
- [x] 实现 `longrun worker --resume=true|false`。
- [x] parent 周期性强杀 worker。
- [x] worker resume 后先执行 recovery verify。
- [x] checkpoint 与 committed-state 分离。
- [x] 记录 `recoveryChecks` 到 metrics 和 report。
- [x] 新增 `crash.properties`。

JUnit 覆盖：

- [x] crash/recovery 状态恢复。
- [x] 未提交 checkpoint 不误判为数据丢失。
- [x] worker epoch 切换后 baseline reset。

通过标准：

- [x] crash/recovery profile 短跑 PASS；30 分钟正式验收留给发布长跑环境。
- [x] `recoveryChecks > 0`。
- [x] 吞吐统计没有 crash 后虚假尖峰。

## P6：copy-based fault injection

交付物：

- [x] 实现复制整个 DB 目录到 `fault/fault-N/`。
- [x] 实现 `truncate`。
- [x] 实现 `bit-flip`。
- [x] 实现 `zero-range`。
- [x] 实现 `random-range`。
- [x] 实现 `partial-page`。
- [x] 实现只破坏副本并只读打开副本。
- [x] 实现 `RECOVERED`、`DETECTED`、`DETECTED_BY_VERIFY`、`UNEXPECTED_*` 分类。
- [x] 实现 `fault.retainedCopies`。
- [x] 新增 `fault-injection.properties`。

JUnit 覆盖：

- [x] fault kind 解析。
- [x] 文件损坏注入。
- [x] fault 副本保留上限。
- [x] 主库 verify 不受副本破坏影响。

通过标准：

- [x] fault-injection profile 短跑无 unexpected；30 分钟正式验收留给发布长跑环境。
- [x] `faultInjectionEvents > 0`。
- [x] 旧副本被清理，历史 metrics 保留。

## P7：空间/资源回收观察

交付物：

- [x] 在 LDB 主产品中新增只读属性 `ldb.liveDataBytes`，或按最终拍板名称实现。
- [x] longrun 读取 `ldb.liveDataBytes`。
- [x] 统计 DB 目录物理大小、WAL/SST/MANIFEST/LOG 分类大小。
- [x] 计算 `sizeAmplification = physicalSize / liveDataBytes`。
- [x] 汇总 compaction/reclamation 事件、backoff、no-progress、success、shrink bytes。
- [x] 新增 `comprehensive.properties`，默认启用读写压力、reopen、crash/recovery，不启用 fault。

JUnit 覆盖：

- [x] live data bytes 属性存在和可解析。
- [x] size amplification 计算。
- [x] reclamation metrics 汇总。
- [x] threshold `<= 5x` 判定。

通过标准：

- [x] comprehensive profile 短跑 PASS；12 小时正式验收留给发布长跑环境。
- [x] `sizeAmplification <= 5x`。
- [x] 长跑存在可解释的 reclamation 事件；若没有则 WARN。

## P8：soak 与发布验收流程

交付物：

- [x] 新增 `nightly.properties`。
- [x] 新增 `soak.properties`。
- [x] README 补充单实例/多实例运行说明。
- [x] README 补充 report 指标解释。
- [x] README 补充 fault injection 说明。
- [x] README 补充发布验收标准。
- [x] 明确 24/72 小时和 7 天报告归档路径。

验收：

- [x] smoke 短跑 PASS；5 分钟正式验收留给发布长跑环境。
- [x] reopen 短跑 PASS；1 小时正式验收留给发布长跑环境。
- [x] crash/recovery 短跑 PASS；30 分钟正式验收留给发布长跑环境。
- [x] fault injection 短跑无 unexpected；30 分钟正式验收留给发布长跑环境。
- [x] comprehensive 短跑 PASS；12 小时正式验收留给发布长跑环境。
- [x] soak profile 已提供；24/72 小时正式验收留给发布长跑环境。
- [x] 正式版本建议至少一轮 7 天 PASS。

## 当前待拍板项

| 问题 | 建议 | 状态 |
| --- | --- | --- |
| live data bytes 属性名 | `ldb.liveDataBytes` | 已确认 |
| suspicious log patterns | `ERROR,Corruption,Checksum,panic,leak,Exception` | 已确认 |
| 7 天长跑归档 | 发布流程指定目录或对象存储，默认归档 `work/<profile>/report/` 压缩副本 | 已给默认方案 |
| longrun 发布版本 | 跟随主版本，独立包名发布 | 已确认 |

## 进度更新规则

- 每完成一个阶段，将“阶段总览”的状态从“待开始”改为“进行中”或“完成”。
- 每完成一个交付物，将对应 checklist 标记为 `[x]`。
- 若实现与设计不一致，先更新设计文档和英文副本，再更新本计划。
- 若某项被推迟，保留 checklist，并在该阶段补充“延期原因”和“后续阶段”。
