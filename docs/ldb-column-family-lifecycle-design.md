# LDB 列族生命周期设计

[English](ldb-column-family-lifecycle-design.en.md) | 中文

## 背景

LDB 已支持打开前通过 `Options.addColumnFamily` 注册列族，并支持列族级读写、snapshot cursor、诊断属性和 `compactRange(cf, begin, end)`。运行时列族生命周期已经从最初的 list/create/drop-empty 扩展到非空 drop tombstone 与 rename；本文保留最小生命周期设计基线，非空 drop/rename 的细化规则见 `docs/ldb-column-family-tombstone-design.md`。

## 目标

- 提供最小运行时 `list/create/drop` 能力，并说明其与后续 tombstone 设计的关系。
- 保持 WAL、SST 和 MANIFEST 现有格式兼容。
- 让 runtime column family 能随 reopen、backup、restore、checkpoint、check 和 WAL-only repair 恢复。
- 对列族 id/name 采用持久化身份模型，已 drop 的 id 不得被复用。

## 非目标

- 本文不展开非空 drop/rename 的完整 tombstone 协议；该部分由 `ldb-column-family-tombstone-design.*` 维护。
- 不引入列族级 WAL。
- 不承诺 RocksDB 完整列族生态，例如 per-CF option 热更新、动态插件隔离或复杂迁移策略。

## 现状/已有流程

| 流程 | 现状 |
| --- | --- |
| 打开 | `Options.getColumnFamilies()` 至少包含 default，并由 `LDbImpl` 创建 `ColumnFamilyState` |
| 写入 | WAL record 内包含列族 id，恢复时通过 resolver 查找列族 |
| SST/MANIFEST | `VersionEdit` 和 `FileMetaData` 记录 cf id，但不记录 cf name |
| 维护工具 | check/repair/backup/checkpoint 以文件集合为边界工作 |

## 核心约束

- JDK8 兼容。
- `COLUMN-FAMILIES` 是补充元数据文件，不改变现有磁盘格式。
- 运行时创建先更新内存状态，再持久化注册表；持久化失败会回滚内存状态。
- 运行时删除当前通过 tombstone 逻辑删除非 default 列族；历史数据保留给 recovery、repair、backup、snapshot 和后续 GC 解释。
- `backup`、`restore`、`checkpoint` 必须携带 `COLUMN-FAMILIES`。
- `check` 和 `repair` 必须读取注册表后再解释 runtime CF WAL。

## 接口设计

| API | 语义 |
| --- | --- |
| `LDB#listColumnFamilies()` | 返回按 id 升序排列的不可变列族快照 |
| `LDB#createColumnFamily(int cfId, String name)` | 在写实例中创建列族并持久化注册表；id/name 冲突失败 |
| `LDB#renameColumnFamily(LdbColumnFamily cf, String newName)` | 重命名活动列族；cfId 保持不变，newName 不能与其他活动列族冲突 |
| `LDB#dropColumnFamily(LdbColumnFamily cf)` | 逻辑删除非 default 列族；活动列表移除，历史 cfId 不复用 |
| `LDB#getColumnFamily(int cfId)` | 返回已注册列族；未知 id 失败 |

## 数据结构

新增根目录文件 `COLUMN-FAMILIES`：

```text
<cfId>\t<escapedName>\n
```

- 每行一条列族定义。
- 空行和 `#` 开头的注释行会被忽略。
- name 对 `\`、tab、换行和回车做转义。
- 打开时先加载 `Options` 静态列族，再合并注册表；id 相同但 name 不同视为损坏。

## 状态机

| 状态 | 触发 | 可转移到 |
| --- | --- | --- |
| `ABSENT` | 未注册 | `ACTIVE` |
| `ACTIVE_EMPTY` | 已注册且无 MemTable/SST 数据 | `DROPPED` |
| `ACTIVE_NON_EMPTY` | 已注册且存在 MemTable、immutable MemTable 或 SST | `RENAMED` 或 `DROPPED` |
| `RENAMED` | name 变更，cfId 不变 | `ACTIVE_*` |
| `DROPPED` | tombstone 已提交 | 不允许复用同 cfId |

## 时序流程

### 创建列族

1. 校验数据库可写、后台无异常。
2. 校验 id/name 合法且无冲突。
3. 在互斥锁内创建 `ColumnFamilyState` 并加入 `cfs`。
4. 写 `COLUMN-FAMILIES.tmp`，fsync 后替换为 `COLUMN-FAMILIES`。
5. 注册表写入失败时移除新状态并抛出 `DBException`。

### 删除列族

1. 校验数据库可写、目标非 default。
2. 在互斥锁内写入列族 tombstone 元数据并阻止新写入。
3. 从活动 `cfs` 视图移除并重写注册表，历史记录保留用于 recovery、repair、backup 和 snapshot。
4. 注册表写入失败时按 tombstone 设计的失败语义回滚或交由 check/repair 解释。

### 重命名列族

1. 校验数据库可写、目标 active、newName 非空且不冲突。
2. 在互斥锁内保持 cfId 不变，仅更新 name。
3. 重写注册表；失败时保留旧 name。

## 异常处理

- 注册表格式错误：`check` 报告 `COLUMN-FAMILIES` 失败，`verifyOnOpen(true)` 拒绝打开。
- 注册表缺失且 WAL 或 MANIFEST 引用 runtime CF：`check` 报告未知列族 id。
- 备份源库注册表损坏：backup 不发布备份目录。
- 备份目录注册表损坏：restore 在创建目标目录前失败。
- repair 遇到 runtime CF WAL：优先用注册表解释列族；缺少注册表时按未知列族 WAL 隔离。

## 幂等性

- `listColumnFamilies` 无副作用。
- create 对已存在 id/name 显式失败，不做静默复用。
- drop 已删除或未知列族失败，避免调用方误以为完成；已 drop 的 cfId 不允许复用。

## 回滚策略

`COLUMN-FAMILIES` 不改变 WAL/SST/MANIFEST 格式。若回滚到旧版本，静态 `Options.addColumnFamily` 仍可打开已知列族；旧版本不会自动读取 runtime 注册表，因此 runtime CF 需要由调用方显式注册。

## 兼容性

- 旧库没有 `COLUMN-FAMILIES` 时继续按 `Options` 打开。
- 新库首次可写打开会写出注册表，便于 backup/checkpoint 携带完整列族定义。
- 非空 drop 和 rename 已由 tombstone 设计接管；旧版本如不能识别相关元数据，应 fail-fast 或按发布说明给出降级限制。

## 测试方案

- runtime CF create/list/reopen/read。
- 空列族和非空列族 drop 后 reopen 不再可见，cfId 不可复用。
- rename 后 cfId 稳定，reopen、backup、restore、checkpoint 和 repair 保留新 name。
- default drop 失败。
- backup、restore、checkpoint 携带 runtime CF 注册表。
- 损坏注册表、缺失注册表导致 WAL/MANIFEST 无法解释、坏 CURRENT、坏备份注册表和 runtime CF WAL-only repair 的损坏注入矩阵。

## 风险点

| 风险 | 缓解 |
| --- | --- |
| 注册表与 WAL/MANIFEST 非原子 | create/drop/rename 失败由 check/verifyOnOpen/repair 暴露；非空 drop 通过 tombstone 保留历史解释能力 |
| 非空列族删除后历史文件仍存在 | 先保证逻辑删除和恢复正确，物理 GC 作为后续生产硬化项 |
| SST 无法从文件自身反推出 cf name | repair runtime CF 依赖注册表或调用方 Options；文档中保留限制 |

## 分阶段实施计划

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 新增 `COLUMN-FAMILIES` 注册表和 runtime list/create/drop-empty API | 已完成 |
| 2 | backup/checkpoint/check/repair 识别注册表 | 已完成 |
| 3 | 损坏注入矩阵覆盖注册表、WAL、CURRENT、备份恢复 | 已完成 |
| 4 | 设计并实现非空 drop tombstone 与 rename 最小闭环 | 已完成 |
| 5 | dropped CF 物理 GC、迁移策略和大规模多列族运维报告 | 后续生产硬化 |
