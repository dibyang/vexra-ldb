# LDB 插件能力概览

[English](ldb-plugin-roadmap.en.md) | 中文

## 文档定位

本文记录当前插件能力和边界，并对齐外部承诺中的待完善项。外部承诺的可执行清单见 [vexra-ldb 外部承诺（面向使用方与基于 LDB 插件扩展开发者）](vexra-ldb-external-commitment.md#完善规划)。执行细则见 [插件化完善规划执行手册](ldb-plugin-improvement-execution-plan.md)。插件开发步骤见 [插件开发手册](ldb-plugin-developer-guide.md)，文档总入口见 [插件化文档索引](ldb-plugin-docs-index.md)。

## 当前能力

| 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| 生命周期回调 | 已支持 | `configure`、`onOpen`、`beforeWrite`、`afterWrite`、checkpoint、close。 |
| 插件描述 | 已支持 | `LdbPluginDescriptor` 暴露 name、version、order、failurePolicy、capabilities。 |
| 能力声明 | 已支持 | `LdbPluginCapability` 声明 observe、mutate、checkpoint、metadata read。 |
| 能力强约束 | 可选开启 | `ldb.plugin.capability.enforcement=true` 后限制未授权 batch mutation、metadata 读取和 checkpoint hook。 |
| 插件排序 | 已支持 | 默认按 descriptor order；longrun 可用 `ldb.plugin.<name>.order` 覆盖。 |
| longrun 配置 | 已支持 | `ldb.plugins`、`-P`、`ldb.plugin.<name>.*`。 |
| provider 发现 | 已支持 | `LdbPluginProvider`、`LdbPluginLoader`、`ServiceLoader`，默认不启用。 |
| provider 版本范围 | 已支持 | `ldb.plugin.<name>.versionRange` 不匹配时启动失败。 |
| 外部插件目录 | 可选开启 | `ldb.plugin.external.enabled=true` 和 `ldb.plugin.dir`，外部 provider 使用独立 classloader 并在插件关闭时释放。 |
| 慢插件治理 | 已支持 | timeout、累计回调预算、自动禁用、降级原因和 stats。 |`r`n| 兼容性 testkit | 已支持 | `LdbPluginCompatibility` 可检查 provider/plugin 最低契约。 |
| 提交后异步通知 | 可选开启 | 仅 `afterWrite` 和 `afterCheckpoint` 可异步。 |
| 隔离边界观测 | 已支持 | 当前是可信进程内插件，明确暴露 `ldb.plugin.sandbox=false`。 |
| 示例插件 | 已支持 | `sample-audit` provider 和 `plugin-sample.properties`。 |

## 当前边界

- 插件仍是可信进程内扩展，不是安全沙箱。
- provider 发现和外部插件目录默认关闭，避免 classpath 或目录变化隐式影响测试。
- `beforeWrite`、`beforeCheckpoint`、`onOpen` 保持同步 fail-fast。
- `afterWrite`、`afterCheckpoint` 是提交后通知阶段，失败不回滚已提交数据。
- async 只对提交后通知阶段生效，并且必须显式开启。
- 新增能力不改变 WAL、SST、MANIFEST、CURRENT 等磁盘格式。

## 对齐外部承诺的待完善项（G1-G6）

这些项目不是能力否定，而是把现有承诺落到可执行治理与验收链路；当前 G1-G6 已完成验收。

| 编号 | 待完善项 | 当前状态 |
| --- | --- | --- |
| G1 | 承诺到测试追踪矩阵 | 已验收 |
| G2 | 插件组合场景回归 | 已验收 |
| G3 | column family id/name 命名约束 | 已验收 |
| G4 | counter 兼容证据固化 | 已验收 |
| G5 | 写入策略与性能报告联动 | 已验收 |
| G6 | 版本升级兼容门禁 | 已验收 |

## 核心文档

- [LDB 插件开发手册](ldb-plugin-developer-guide.md)
- [LDB 插件能力增强设计](ldb-plugin-design.md)
- [LDB 插件加载与发现设计](ldb-plugin-loading-design.md)
- [LDB 插件隔离与异步执行评估](ldb-plugin-isolation-async-design.md)
- [vexra-ldb 外部承诺（使用方与基于 LDB 插件扩展开发者）](vexra-ldb-external-commitment.md)

## 后续方向

后续如接入真实业务插件，应优先用 longrun 观察回调耗时、失败策略和吞吐影响。只有真实数据证明同步回调成本明显时，再考虑更强隔离或更复杂异步执行策略。现阶段以承诺完善清单为优先执行入口。
