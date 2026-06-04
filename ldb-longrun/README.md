# ldb-longrun

[English](README.en.md) | 中文

`ldb-longrun` 是 LDB 的独立长稳压测与故障注入测试工具。它作为独立 Gradle 子项目构建和发布，不进入主产品默认运行路径，也不进入默认 CI 长跑。

## 构建

```bash
./gradlew :ldb-longrun:distZip
./gradlew :ldb-longrun:distTar
```

构建产物位于 `ldb-longrun/build/distributions/`，包括 `ldb-longrun-<version>.zip` 和 `ldb-longrun-<version>.tar.gz`。两个归档解压后只包含一个顶层目录，固定为 `ldb-longrun/`。

## 命令

```text
longrun run      --config config/smoke.properties
longrun start    --config config/nightly.properties
longrun stop     --instance smoke-1
longrun status   --instance smoke-1
longrun logs     --instance smoke-1
longrun restart  --config config/reopen.properties
longrun watch    --config config/smoke.properties
longrun report   --workDir work/smoke
longrun worker   --config config/crash.properties --resume=true
```

`--profile` 仍作为兼容别名保留，推荐新命令使用 `--config` 或 `-c`。

## 更方便的输入

`--config` 支持 profile 简写名，会自动补齐 `.properties` 并从本地 `config/` 或打包内置 profiles 中解析：

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

也可以使用短参数：

```bash
./bin/longrun watch -c smoke -d 5m
```

常用短参数：

```text
-c/--config                 => config
-p/--profile                => profile（兼容别名）
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir（兼容）
-W/--run.workDir            => run.workDir
-d/--run.duration           => run.duration
-n/--run.name               => run.name
-s/--run.seed               => run.seed
-r/--resume                 => resume
-k/--workload.keySpace      => workload.keySpace
-m/--workload.mode          => workload.mode
-v/--workload.valueSizeMin  => workload.valueSizeMin
-V/--workload.valueSizeMax  => workload.valueSizeMax
-a/--workload.commitEveryOps=> workload.commitEveryOps
-q/--workload.readRatio     => workload.readRatio
-y/--workload.writeRatio    => workload.writeRatio
-x/--workload.removeRatio   => workload.removeRatio
-t/--metrics.interval       => metrics.interval
-u/--state.interval         => state.interval
-o/--check.reopenInterval   => check.reopenInterval
-O/--check.finalVerify      => check.finalVerify
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
-M/--ldb.writeBufferSizeMb  => ldb.writeBufferSizeMb
```

`report` 命令支持 `-w <dir>`，等价于 `--workDir <dir>`。

## Bash 自动补全

Linux/bash 下可以安装补全脚本：

```bash
bash ./install-completion.bash
```

安装后新开一个 shell 即可生效。当前 shell 想立即生效，可以执行安装脚本输出的 `source ...` 命令。

补全效果：

```text
./bin/longrun <TAB>                 => run start watch ...
./bin/longrun watch <TAB>           => 常用参数
./bin/longrun watch -c <TAB>        => profile 名称或 config 路径
./bin/longrun watch --config <TAB>  => profile 名称或 config 路径
```

补全同时注册 `longrun`、`bin/longrun` 和 `./bin/longrun`。注意 `./bin/longrun<TAB>` 没有空格时，bash 仍在补可执行文件路径；需要输入 `./bin/longrun <TAB>` 才会补 longrun 的 command。

## Profiles

默认 profile 位于发行包 `config/`：

- `smoke.properties`：默认 5 分钟快速验证。
- `performance.properties`：默认 3 分钟轻量混合性能压测，小 value，`workload.syncWrites=false`，默认 `check.finalVerify=false`，用于观察引擎综合吞吐。
- `performance-write.properties`：默认 3 分钟写入主导性能压测，小 value，`workload.syncWrites=false`，默认 `check.finalVerify=false`，用于观察写路径吞吐。
- `performance-read.properties`：默认 3 分钟读取主导性能压测，小 value，`workload.syncWrites=false`，默认 `check.finalVerify=false`，用于观察读路径吞吐。
- `performance-large-value.properties`：默认 3 分钟大 value 混合性能压测，`workload.syncWrites=false`，默认 `check.finalVerify=false`，用于观察大 value 带宽和 IO 压力。
- `performance-durable.properties`：默认 3 分钟同步落盘性能压测，`workload.syncWrites=true`，默认 `check.finalVerify=false`，用于观察 durable write/fsync 口径。
- `nightly.properties`：默认 12 小时夜间长跑。
- `soak.properties`：默认 7 天长稳压测，可通过 `--run.duration=30d` 覆盖。
- `reopen.properties`：周期性 close/open，并记录 `reopenChecks`。
- `crash.properties`：父子进程 crash/recovery，并记录 `recoveryChecks`。
- `fault-injection.properties`：copy-based 文件损坏注入，默认不混入其他 profile。
- `comprehensive.properties`：读写压力、reopen、crash/recovery 和空间回收观察，默认不启用 fault。

## 单实例和多实例

同一个 `run.instance` 默认不能重复启动。默认 profile 使用测试类型作为实例名，例如 `smoke`、`reopen`、`crash` 和 `fault-injection`，因此不同测试类型可以并行运行，同一测试类型默认只允许一个实例。

后台启动会写入：

```text
run/<instance>.pid
logs/<instance>.out
```

每次 `start` 会把旧日志轮转为 `logs/<instance>.out.1`、`.2` 等，并为新测试创建新的 `logs/<instance>.out`。实例启动后会先输出 `START` 和多行 `CONFIG`，日志文件中的运行期 `PROGRESS` 行会按 `metrics.interval` 保留 `progressPercent`、`windowOpsPerSecond`、`avgOpsPerSecond`、`minOpsPerSecond` 和 `maxOpsPerSecond` 等完整字段。

`logs` 和 `watch` 跟随运行实例时，会把 `PROGRESS` 行在控制台原地刷新成短进度条，例如 `PROGRESS [##########----------]  50% ops=100000 win=68000/s avg=52000/s min=18000/s max=68000/s keys=50000 slow=0 imm=0 l0wait=0 comp=0 backlog=false`，避免终端换行；日志文件仍保留完整逐行历史，并额外记录 LDB write stall 与 compaction 指标。workload 到达 100% 后会先打印 `RESULT phase=workload`，随后用 `FINAL phase=verify/resource/report` 标记最终校验和报告阶段。测试结束后主日志会打印 `SUMMARY` 行，包含总体 avg/min/max、p05/p50/p95、读/写/删各自的 p50/p95、throughputDropRatio、finalSizeBytes 和 sizeAmplification；性能统计默认跳过启动 warmup 采样和明显不足一个采样周期的尾窗口，并通过 `warmupSamples`、`trailingPartialSamples` 和 `measuredSamples` 标明统计口径。

crash/recovery 模式主日志输出 `CRASH PROGRESS`，表示父进程按 `crash.cycles` 统计的整体进度。worker 自身的 `START`、`CONFIG` 和 `PROGRESS` 写入 `logs/<instance>-worker.out`，用于排查单个 worker 阶段。

普通 workload 默认是 fresh run：`resume=false` 时会在获取 workDir 锁后清理 `db/`、`state/`、`metrics/`、`report/` 和 `fault/`，避免复用上一次未完成运行的库文件和状态文件。crash/recovery worker 使用 `--resume=true`，会复用同一个 workDir 执行恢复校验。

单实例：

```bash
./bin/longrun start --config config/smoke.properties
./bin/longrun status --config config/smoke.properties
./bin/longrun logs --config config/smoke.properties
./bin/longrun stop --config config/smoke.properties
```

`watch` 等价于实例未运行时先 `start`，再跟随 `logs`；实例已运行时直接跟随当前日志：

```bash
./bin/longrun watch --config config/smoke.properties
```

在 `watch` 过程中按 `Ctrl+C` 只会停止日志跟随，后台实例仍会继续运行。需要显式停止实例：

```bash
./bin/longrun stop --config config/smoke.properties
```

多实例必须使用不同 instance 和不同 workDir：

```bash
./bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
./bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 故障注入

`fault-injection.properties` 使用 copy-based 模式：

1. commit 并校验主库。
2. 关闭主库。
3. 复制整个 DB 目录到 `work/<profile>/fault/fault-N/`。
4. 只破坏副本。
5. 只读打开副本并分类结果。
6. 重新打开主库并校验。

支持 `truncate`、`bit-flip`、`zero-range`、`random-range` 和 `partial-page`。`fault.retainedCopies` 控制副本保留上限，历史 fault metrics 不会被删除。

## 报告

正常结束会自动生成：

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

也可以手动重新分析：

```bash
./bin/longrun report --workDir work/smoke
```

核心指标包括 Operations、Commits、Reopen Checks、Recovery Checks、Avg/Min/Max Ops/s、P05/P50/P95 Ops/s、Throughput Drop Ratio、Final Size Bytes、Size Amplification、Reclamation Events、Fault Injection Events、Suspicious Log Lines、Failures 和 Warnings。

性能测试建议先区分两个口径：

```bash
./bin/longrun watch -c performance
./bin/longrun watch -c performance-write
./bin/longrun watch -c performance-read
./bin/longrun watch -c performance-large-value
./bin/longrun watch -c performance-durable
```

`performance` 使用异步写和小 value 混合负载，主要看 LDB 综合吞吐；`performance-write` 偏写路径；`performance-read` 偏读路径；`performance-large-value` 保留大 value 压力；`performance-durable` 使用同步写，主要看落盘和 fsync 成本。性能 profiles 默认 `check.finalVerify=false`，避免 100% 后的全量 active key 校验影响性能报告体验；同时默认 `ldb.writeBufferSizeMb=512`、`state.interval=3m`、`workload.commitEveryOps=1000000000`，减少短性能压测中过早 memtable flush 和 longrun 状态全量保存带来的干扰。如需同时做最终一致性校验，可加 `-O true` 或 `--check.finalVerify=true`。

## 发布验收

建议发布前显式执行：

- smoke：5 分钟 PASS。
- reopen：1 小时 PASS，且 `reopenChecks > 0`。
- crash/recovery：30 分钟 PASS，且 `recoveryChecks > 0`。
- fault injection：30 分钟，unexpected 结果为 0。
- comprehensive：12 小时 PASS。
- soak：24 小时或 72 小时 PASS。
- 正式版本建议至少一轮 7 天 PASS。

报告归档建议放在发布流程指定目录，例如 `work/<profile>/report/` 的压缩副本或发布对象存储路径。7 天长跑的最终归档位置由发布负责人确认。

`logs` 在实例运行中会持续跟随日志；实例结束后会打印最近日志并退出。
