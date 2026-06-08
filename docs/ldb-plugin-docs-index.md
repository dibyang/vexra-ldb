# LDB 插件化文档索引

[English](ldb-plugin-docs-index.en.md) | 中文

## 快速入口

| 你想做什么 | 推荐文档 |
| --- | --- |
| 了解当前插件化能力和边界 | [插件能力概览](ldb-plugin-roadmap.md) |
| 编写、测试并在 longrun 中启用插件 | [插件开发手册](ldb-plugin-developer-guide.md) |
| 查验外部承诺与可执行补齐项 | [vexra-ldb 外部承诺（面向使用方与基于 LDB 插件扩展开发者）](vexra-ldb-external-commitment.md) |
| 按执行项推进/更新状态 | [插件化完善规划执行手册](ldb-plugin-improvement-execution-plan.md) |
| 查看当前可直接使用的示例 | [插件开发手册](ldb-plugin-developer-guide.md) 的示例章节 |

## 文档分层

| 层级 | 文档 | 维护原则 |
| --- | --- | --- |
| 使用手册 | `ldb-plugin-developer-guide.*.md` | 面向插件作者，保留可执行配置、样例和注意事项。 |
| 能力快照 | `ldb-plugin-roadmap.*.md` | 保留当前能力边界 + 外部承诺待完善项同步状态。 |
| 对外承诺 | `vexra-ldb-external-commitment.*.md` | 固化使用方与插件扩展开发者可依赖的承诺、验收标准和执行改进计划。 |

## 当前完成态（已落地）

插件化缺口 G1-G8 已完成。开发者新增插件时优先参考 [插件开发手册](ldb-plugin-developer-guide.md) 和 `plugin-sample.properties`。

| 编号 | 已补齐能力 | 当前文档位置 |
| --- | --- | --- |
| G1 | capability 可选强约束 | [插件开发手册](ldb-plugin-developer-guide.md) 的能力声明、metadata/checkpoint enforcement、治理配置和 FAQ。 |
| G2 | 提交后通知可选异步 | [插件开发手册](ldb-plugin-developer-guide.md) 的治理配置和边界说明。 |
| G3 | 插件隔离边界明确为非安全沙箱 | [插件开发手册](ldb-plugin-developer-guide.md) 的治理边界说明。 |
| G4 | 慢插件 timeout、累计预算、降级和自动禁用 | [插件开发手册](ldb-plugin-developer-guide.md) 的治理配置和测试建议。 |
| G5 | provider 版本范围校验 | [插件开发手册](ldb-plugin-developer-guide.md) 的 longrun 启用和 FAQ。 |
| G6 | longrun 插件顺序覆盖 | [插件开发手册](ldb-plugin-developer-guide.md) 的 longrun 启用。 |
| G7 | 外部插件目录发现和 classloader 隔离 | [插件开发手册](ldb-plugin-developer-guide.md) 的 longrun 启用和 FAQ。 |
| G8 | `sample-audit` 示例插件和 profile | [插件开发手册](ldb-plugin-developer-guide.md) 的内置样例。 |

## 待推进（能力承诺执行）

外部承诺新增的 G1-G6 改进项当前均已验收。后续如果重新打开某项，每项完成后同步更新以下三类文档：
当前阶段状态约定：`已定义（待执行）` 表示计划已落地；`进行中` 表示有执行动作；`已验收` 表示闭环完成。
1. 外部承诺 `vexra-ldb-external-commitment.*.md`
2. 插件能力概览 `ldb-plugin-roadmap.*.md`
3. 插件开发手册（如涉及配置/测试/示例）

| 编号 | 待推进项 | 对齐文档入口 |
| --- | --- | --- |
| G1 | 承诺到测试追踪矩阵 | [外部承诺 完善规划](vexra-ldb-external-commitment.md#完善规划) / [执行手册](ldb-plugin-improvement-execution-plan.md) |
| G2 | 插件组合场景回归 | [外部承诺 完善规划](vexra-ldb-external-commitment.md#完善规划) / [执行手册](ldb-plugin-improvement-execution-plan.md) |
| G3 | column family id/name 命名约束 | [外部承诺 完善规划](vexra-ldb-external-commitment.md#完善规划) / [执行手册](ldb-plugin-improvement-execution-plan.md) |
| G4 | counter 兼容证据固化 | [外部承诺 完善规划](vexra-ldb-external-commitment.md#完善规划) / [执行手册](ldb-plugin-improvement-execution-plan.md) |
| G5 | 写入策略与性能报告联动 | [外部承诺 完善规划](vexra-ldb-external-commitment.md#完善规划) / [执行手册](ldb-plugin-improvement-execution-plan.md) |
| G6 | 版本升级兼容门禁 | [外部承诺 完善规划](vexra-ldb-external-commitment.md#完善规划) / [执行手册](ldb-plugin-improvement-execution-plan.md) |

## 完成记录

| 阶段 | 内容 | 提交 |
| --- | --- | --- |
| P1-P5 | 插件化基础能力 | `4aeccc4` |
| S1 | order override、provider versionRange | `0a83c18` |
| S2 | capability enforcement | `57c19ea` |
| S3 | 慢插件治理和 sandbox property | `b5abe2e` |
| S4 | async afterWrite/afterCheckpoint | `b32fb97` |
| S5 | 外部插件目录发现 | `3f0264d` |
| S6 | `sample-audit` 示例插件和开发手册 | `bc86be4` |

## 维护约定

- 新增插件能力时，先更新设计或开发手册，再更新代码。
- 新增 longrun 插件配置时，同时更新开发手册和示例 profile。
- 变更完成态/待推进项时，更新本文件与 [外部承诺](vexra-ldb-external-commitment.md)、[能力概览](ldb-plugin-roadmap.md)。
- 中文文档和英文副本保持同等结构。
