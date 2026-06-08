# LDB 插件化完善规划执行手册

## 目标

把外部承诺中的 G1-G6 项目从规划项推进为可验证的完成项。本手册记录每项工作的交付物、验收入口和本轮验证结果，便于后续发布或回归时复用。

## 统一执行规则

- 每项都必须提供执行证据，包括日志、报告、测试或文档。
- 如果涉及代码变更，必须同步更新 `vexra-ldb-external-commitment.*.md`、`ldb-plugin-roadmap.*.md` 和 `ldb-plugin-docs-index.*.md`。
- 验证失败时先更新规划证据，再继续实现，确保验收口径和代码行为一致。

## G1-G6 执行卡片

| 编号 | 需要完成的动作 | 当前状态 | 责任范围 | 完成证据 | 验收入口 |
| --- | --- | --- | --- | --- | --- |
| G1 | 建立 A1-A12 到测试类、longrun profile 和日志字段的一致映射。 | 已验收 | 文档 + 测试 | 已在 `vexra-ldb-external-commitment.*.md` 增加验收矩阵执行映射。 | 聚焦 Gradle 测试通过。 |
| G2 | 插件组合场景集中回归，包括 versionRange、外部目录、capability enforcement、兼容性 testkit 和 async 组合。 | 已验收 | longrun + 测试 | 已补 resolver、外部 classloader 句柄、兼容性、capability enforcement、资源预算和 SmokeRunner 组合运行测试。 | `LongRunPluginResolverTest`、`LdbPluginTest`、`SmokeRunnerTest`。 |
| G3 | 完善 column family id/name 约束模板。 | 已验收 | 插件开发手册 | 已补 id 范围、命名、冲突处理和废弃策略模板。 | `ldb-plugin-developer-guide.*.md`。 |
| G4 | 固化 counter 兼容证据。 | 已验收 | 测试 + 文档 | 已补跨列族 counter batch、普通 value 隔离和 reopen 验证。 | `LdbCoreBehaviorTest.shouldPreserveCounterAddLongBatchAcrossColumnFamiliesAndReopen`。 |
| G5 | 固化写入策略与性能报告联动。 | 已验收 | longrun 报告 + 发布记录 | 已将 `workloadSyncWrites`、group commit 和插件 async 配置写入 run state 与 summary report。 | `SmokeRunner`、`ReportAnalyzer`、`CHANGELOG*.md`。 |
| G6 | 固化版本升级兼容门禁。 | 已验收 | 发布检查 | 已补升级兼容门禁表和 release note 边界要求。 | `vexra-ldb-external-commitment.*.md`。 |

## 验证结果

本轮聚焦验证通过：

```bash
.\gradlew.bat test --tests net.xdob.vexra.ldb.LdbCoreBehaviorTest --tests net.xdob.vexra.ldb.longrun.config.LongRunConfigTest --tests net.xdob.vexra.ldb.longrun.config.LongRunPluginResolverTest --tests net.xdob.vexra.ldb.longrun.workload.SmokeRunnerTest --tests net.xdob.vexra.ldb.longrun.report.ReportAnalyzerTest
```

结果：`BUILD SUCCESSFUL`。

## 完成标准

当前执行规划已完成：
- G1-G6 每项都有明确交付物和验收入口。
- 关联文档已经统一回填为已验收状态。
- 新增代码和报告字段已有聚焦测试覆盖。

## 备注

本手册记录本轮完善规划的完成态。后续如果新增插件能力或外部承诺，继续按现有工程流程在代码和设计文档中推进。
