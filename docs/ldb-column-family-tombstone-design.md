# LDB 非空列族 Drop/Rename 设计

[English](ldb-column-family-tombstone-design.en.md) | 中文

## 背景

LDB 当前已支持运行时 `list/create/drop` 列族生命周期，并通过 `COLUMN-FAMILIES` 注册表和 tombstone 历史让 runtime column family 能随 reopen、backup、checkpoint、check 和 repair 恢复。非空列族 drop 与 rename 已形成最小实现：drop 是逻辑删除，rename 保持 cfId 不变；后续重点转向物理 GC、迁移策略和更大规模运维证明。

## 目标

- 固化已实现的非空列族 drop tombstone 语义：逻辑删除、拒绝新访问、历史 cfId 可解释且不可复用。
- 固化 rename 的持久化身份模型：cfId 不变、name 可变。
- 明确注册表、WAL/SST、check/repair/backup/checkpoint 的一致性规则。
- 为后续 dropped CF 物理 GC、迁移策略和旧版本兼容证据提供验收门禁。

## 非目标

- 不在当前阶段实现列族级 WAL。
- 不支持复用已 drop 的 cfId，除非未来引入 epoch/generation。
- 不保证旧版本能读取包含列族 tombstone 的新库；旧版本应明确拒绝或只读降级。

## 现状/已有流程

| 能力 | 当前状态 |
| --- | --- |
| Runtime create | 已通过 `COLUMN-FAMILIES` 持久化 id/name |
| Runtime drop | 支持非 default 列族逻辑 drop；活动视图移除，dropped cfId 不复用 |
| Rename | 支持 rename，cfId 保持不变 |
| MANIFEST/Registry | 文件变更继续记录 cfId，注册表保留 active/dropped 历史用于解释生命周期 |
| Check/Repair | 已识别注册表和 dropped 历史，并校验 WAL/MANIFEST 中 cfId 是否可解释 |

## 核心约束

- 非空 drop 必须是逻辑删除，不能直接删除 SST 文件。
- cfId 是持久化身份，rename 只能修改 name，不能改变 cfId。
- tombstone 必须写入 MANIFEST 或等价强一致元数据，否则 crash recovery 无法证明 drop 已提交。
- backup/checkpoint 必须保留 tombstone 能力，restore 后不得复活已 drop 列族。
- repair 必须能解释 dropped CF 的历史 SST/WAL，并在报告中区分 active/dropped。

## 接口设计

| API | 当前行为 | 后续目标 |
| --- | --- | --- |
| `dropColumnFamily(cf)` | 写入 drop tombstone，阻止新写入，历史数据进入 GC 候选 | 补充更完整物理 GC 和长链运维证明 |
| `renameColumnFamily(cf, newName)` | cfId 不变，注册表 name 更新 | 补充迁移策略和批量列族运维报告 |
| `listColumnFamilies()` | active 列族 | 默认只列 active，可扩展 includeDropped 诊断视图 |
| `getProperty("ldb.columnFamilies")` | active 列族 | 增加 dropped/renamed 诊断属性 |

## 数据结构

当前最小实现将 active/dropped 历史保存在列族注册表视图中，并通过 cfId 保证 WAL/SST 历史记录可解释。后续如果要把生命周期事件提升为 MANIFEST edit，可采用如下结构：

| 字段 | 含义 |
| --- | --- |
| `cfId` | 列族持久化 id |
| `operation` | `CREATE` / `DROP` / `RENAME` |
| `name` | create/rename 的目标 name |
| `sequence` | 生命周期事件对应的全局 sequence |
| `timestampMillis` | 诊断字段，不参与一致性判断 |

`COLUMN-FAMILIES` 可继续作为 active registry，并保留 dropped history：

```text
active:<cfId>\t<name>
dropped:<cfId>\t<name>\t<dropSequence>
```

若未来为了更强兼容性拆分历史文件，也可以新增 `COLUMN-FAMILIES-HISTORY`，但必须先补兼容测试和旧版本 fail-fast 证据。

## 状态机

| 状态 | 触发 | 说明 |
| --- | --- | --- |
| `ACTIVE` | create/open | 可读写 |
| `RENAMING` | rename edit 写入中 | 失败回滚到旧 name |
| `DROPPING` | drop edit 写入中 | 禁止新写入，等待 MANIFEST 持久化 |
| `DROPPED` | drop edit 已提交 | 拒绝新读写，历史文件只供 snapshot/recovery/repair 解释 |
| `GC_ELIGIBLE` | 无活跃 snapshot 且无 live SST 引用 | 可以删除文件和历史元数据 |

## 时序流程

### 非空 Drop

1. 获取 DB 互斥锁，校验目标非 default 且 active。
2. 阻止目标列族新写入，并保证后续 API 不再返回该 active family。
3. 更新 registry，将列族从 active 移入 dropped history。
4. 历史 WAL/SST 继续通过 cfId 解释；新写入和 `getColumnFamily(cfId)` 默认失败。
5. 后台 compaction/obsolete cleanup 在后续物理 GC 阶段再清理该 cfId 的 SST。
6. `getColumnFamily(cfId)` 默认对 dropped 抛出明确异常。

### Rename

1. 校验 cfId active、newName 非空且不与 active 列族冲突。
2. 写入 MANIFEST rename edit。
3. 更新 registry active name。
4. WAL/SST 中继续使用 cfId，不受 name 变化影响。

## 异常处理

- tombstone 写入失败：保持 active，registry 不变。
- registry 更新失败：启动 repair/check 必须能明确报告生命周期元数据不一致，后续如引入 MANIFEST lifecycle edit 再支持重建 registry。
- drop 后仍有 WAL 引用该 cfId：recovery 允许解释历史 record，但不得接受新写入。
- rename 后旧 name 打开：调用方应通过 cfId 解析；按 name 查找必须返回新 name。

## 幂等性

- 同一 cfId 的重复 drop：若已 `DROPPED`，返回已删除状态或抛出可识别异常，不能重复写 tombstone。
- rename 重试：相同 cfId/newName 可视为幂等成功；不同 newName 必须按新事件处理。

## 回滚策略

一旦引入 MANIFEST lifecycle edit，旧版本不应静默打开。回滚前应：

1. 停止写入 drop/rename。
2. 用新版本 repair/check 确认无 `DROPPED` 未 GC 列族。
3. 若必须降级，生成旧格式 registry，并在 release note 中声明限制。

## 兼容性

- 新版本读取旧库：无 lifecycle edit 时按现有 registry 行为。
- 旧版本读取新库：应通过 format marker 或未知 MANIFEST tag 明确失败。
- backup/restore：必须复制 lifecycle 元数据，并在 `CheckReport` 中报告 active/dropped CF 计数。

## 测试方案

- 非空 drop 后 reopen 不可读写目标列族。
- drop 前 snapshot 持有旧视图，drop 后关闭 snapshot 再 GC。
- drop crash：registry 发布前后失败、reopen、repair、backup、checkpoint 组合。
- rename 后 reopen、backup/restore、repair 均保留新 name。
- 旧版本兼容门禁：面对新 lifecycle edit 明确失败。

## 风险点

| 风险 | 缓解 |
| --- | --- |
| drop 误删仍被 snapshot 引用的 SST | GC 必须依赖 snapshot/version 引用计数 |
| registry 与 MANIFEST 不一致 | check/repair 报告生命周期元数据不一致；未来如引入 MANIFEST lifecycle edit，再以 MANIFEST 作为权威日志 |
| 旧版本静默误读 | 保留旧版本升级样本和 release gate fail-fast 证据 |

## 分阶段实施计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 1 | 定义 lifecycle registry history 和兼容边界 | 已完成 |
| 2 | 实现 rename，验证 cfId 稳定 | 已完成：rename/reopen/backup/repair 测试通过 |
| 3 | 实现非空逻辑 drop，不做物理 GC | 已完成：drop 后拒绝新访问，历史可解释 |
| 4 | dropped CF 文件 GC 与迁移策略 | 后续：snapshot/compaction/obsolete 长链测试通过 |
| 5 | 旧版本兼容与 release gate 证据维护 | 持续：新格式旧版本 fail-fast 记录清楚 |
