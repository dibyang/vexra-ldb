# LDB 非空列族 Drop/Rename 设计

[English](ldb-column-family-tombstone-design.en.md) | 中文

## 背景

LDB 当前已支持运行时 `list/create/drop-empty` 列族生命周期，并通过 `COLUMN-FAMILIES` 注册表让 runtime column family 能随 reopen、backup、checkpoint、check 和 repair 恢复。剩余硬缺口是非空列族 drop 与 rename：如果只从注册表删除列族，旧 WAL、SST 和 MANIFEST 中仍可能引用该 cfId，恢复、repair 或旧 snapshot 会无法解释数据，甚至误读为其他列族。

## 目标

- 设计可恢复、可校验、可回滚的非空列族 drop 方案。
- 设计 rename 的持久化身份模型，保证 cfId 不变、name 可变。
- 明确 MANIFEST tombstone、注册表、WAL/SST、check/repair/backup 的一致性规则。
- 先形成实现门禁，不在本设计提交中直接改变磁盘格式。

## 非目标

- 不在当前阶段实现列族级 WAL。
- 不支持复用已 drop 的 cfId，除非未来引入 epoch/generation。
- 不保证旧版本能读取包含列族 tombstone 的新库；旧版本应明确拒绝或只读降级。

## 现状/已有流程

| 能力 | 当前状态 |
| --- | --- |
| Runtime create | 已通过 `COLUMN-FAMILIES` 持久化 id/name |
| Runtime drop | 仅允许空列族；非空 drop 抛 `DBException` |
| Rename | 未支持 |
| MANIFEST | 记录文件变更和 cfId，不记录列族生命周期 |
| Check/Repair | 已识别注册表，并校验 WAL/MANIFEST 中 cfId 是否可解释 |

## 核心约束

- 非空 drop 必须是逻辑删除，不能直接删除 SST 文件。
- cfId 是持久化身份，rename 只能修改 name，不能改变 cfId。
- tombstone 必须写入 MANIFEST 或等价强一致元数据，否则 crash recovery 无法证明 drop 已提交。
- backup/checkpoint 必须保留 tombstone 能力，restore 后不得复活已 drop 列族。
- repair 必须能解释 dropped CF 的历史 SST/WAL，并在报告中区分 active/dropped。

## 接口设计

| API | 当前行为 | 后续目标 |
| --- | --- | --- |
| `dropColumnFamily(cf)` | 非空失败 | 写入 drop tombstone，阻止新写入，历史数据进入 GC 候选 |
| `renameColumnFamily(cf, newName)` | 无 | 写入 rename edit，cfId 不变，注册表 name 更新 |
| `listColumnFamilies()` | active 列族 | 默认只列 active，可扩展 includeDropped 诊断视图 |
| `getProperty("ldb.columnFamilies")` | active 列族 | 增加 dropped/renamed 诊断属性 |

## 数据结构

建议新增 MANIFEST edit 类型：

| 字段 | 含义 |
| --- | --- |
| `cfId` | 列族持久化 id |
| `operation` | `CREATE` / `DROP` / `RENAME` |
| `name` | create/rename 的目标 name |
| `sequence` | 生命周期事件对应的全局 sequence |
| `timestampMillis` | 诊断字段，不参与一致性判断 |

`COLUMN-FAMILIES` 可继续作为 active registry，但需要增加：

```text
active:<cfId>\t<name>
dropped:<cfId>\t<name>\t<dropSequence>
```

若为了兼容现有格式，也可以新增 `COLUMN-FAMILIES-HISTORY`，避免破坏旧 registry parser。实现前必须二选一并补兼容测试。

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
2. 阻止目标列族新写入，并 flush 相关 MemTable。
3. 写入 MANIFEST drop edit，记录 cfId 和 drop sequence。
4. 更新 registry，将列族从 active 移入 dropped history。
5. 后台 compaction/obsolete cleanup 在无 snapshot 引用后清理该 cfId 的 SST。
6. `getColumnFamily(cfId)` 默认对 dropped 抛出明确异常。

### Rename

1. 校验 cfId active、newName 非空且不与 active 列族冲突。
2. 写入 MANIFEST rename edit。
3. 更新 registry active name。
4. WAL/SST 中继续使用 cfId，不受 name 变化影响。

## 异常处理

- MANIFEST drop 写入失败：保持 active，registry 不变。
- registry 更新失败：启动 repair/check 必须能从 MANIFEST lifecycle edit 重建 registry。
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
- drop crash：MANIFEST 成功/registry 失败、registry 成功/MANIFEST 失败组合。
- rename 后 reopen、backup/restore、repair 均保留新 name。
- 旧版本兼容门禁：面对新 lifecycle edit 明确失败。

## 风险点

| 风险 | 缓解 |
| --- | --- |
| drop 误删仍被 snapshot 引用的 SST | GC 必须依赖 snapshot/version 引用计数 |
| registry 与 MANIFEST 不一致 | MANIFEST 作为权威日志，check/repair 可重建 registry |
| 旧版本静默误读 | 引入 format marker 或未知 tag fail-fast |

## 分阶段实施计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 1 | 定义 lifecycle MANIFEST edit 和 registry history 格式 | 文档、兼容测试草案完成 |
| 2 | 实现 rename，验证 cfId 稳定 | rename/reopen/backup/repair 测试通过 |
| 3 | 实现非空逻辑 drop，不做物理 GC | drop 后拒绝读写，历史可解释 |
| 4 | 实现 dropped CF 文件 GC | snapshot/compaction/obsolete 测试通过 |
| 5 | 旧版本兼容与 release gate | 新格式旧版本 fail-fast 记录清楚 |
