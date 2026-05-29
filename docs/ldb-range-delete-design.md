# LDB Range Delete 设计方案

## 背景

`LdbWriteBatch` 已经暴露 `deleteRange` API，`ValueType` 也预留了 `DELETE_RANGE(0x02)`，但当前写入前会在 `LdbWriteBatchImpl.validateForWrite()` 中拒绝该操作。第九阶段的目标是把这个 API 从“提前拒绝”推进到可恢复、可持久化、可压缩合并的 range tombstone 能力。

Range Delete 会改变 WAL/SST 的可见语义，并影响 snapshot、iterator 和 compaction，因此必须先完成独立设计，再进入代码实现。

## 目标

- 支持 `LdbWriteBatch.deleteRange(cf, beginKey, endKey)`，删除半开区间 `[beginKey, endKey)` 内的可见 point key。
- WAL replay 能恢复 range tombstone，进程崩溃后语义保持一致。
- MemTable、SST、snapshot cursor 和 point get 都能按 sequence 正确遮蔽被删除的 key。
- compaction 能合并 tombstone 与 point key，并在安全条件满足时丢弃被覆盖数据。
- 明确磁盘格式兼容、降级限制和回滚策略。

## 非目标

- 不引入列族级 WAL；第九阶段继续沿用全局共享 WAL。
- 不实现运行时 create/drop column family。
- 不引入 PrefixExtractor、MergeOperator 或 RocksDB 完整 tombstone fragmentation 策略。
- 不改变现有 `PUT`、`DELETE`、`ADD_LONG` 的编码语义。

## 现状/已有流程

- `LdbWriteBatchImpl` 能收集 `DELETE_RANGE` 操作，但写入前显式抛出 `DBException("deleteRange is not supported yet")`。
- `LdbWriteBatchLog` 只解码 `VALUE`、`DELETION` 和 `ADD_LONG`。
- MemTable 当前按 `InternalKey -> Slice` 保存 point entry，读路径只处理 point value 和 point deletion。
- SST 表格式可以保存任意 `InternalKey` 与 value，但当前 reader/iterator 不理解 `DELETE_RANGE` 遮蔽语义。
- `DbSnapshotCursor` 和 `SnapshotSeekingIterator` 只把 `DELETION` 当作 tombstone，不会检查区间删除。

## 核心约束

- 保持 JDK8 兼容。
- 继续使用全局 sequence 与全局 WAL，保证跨列族 batch 的原子顺序。
- 新版本必须读取没有 range tombstone 的旧库。
- 一旦新版本写入 `DELETE_RANGE` WAL/SST，旧版本不保证可读；降级前必须使用备份或导出后的干净数据。
- range tombstone 的可见性必须受 snapshot sequence 约束，不能删除 snapshot 仍应看到的旧版本。

## 接口设计

| 接口 | 行为 |
| --- | --- |
| `LdbWriteBatch.deleteRange(cf, beginKey, endKey)` | 写入列族级 range tombstone，区间为 `[beginKey, endKey)` |
| `LdbWriteBatch.deleteRange(beginKey, endKey)` | 等价于 default column family 的 range delete |
| `LDB.write(batch, options)` | 对 `DELETE_RANGE` 分配 sequence、写 WAL、应用 MemTable |
| `LDB.getSnapshot()` | snapshot sequence 约束 range tombstone 可见性 |
| `LDB.getProperty("ldb.rangeDelete.enabled")` | 可选诊断属性，返回实现是否已启用 range delete |

校验规则：

- `cf`、`beginKey`、`endKey` 不允许为 `null`。
- `beginKey` 必须小于 `endKey`；空区间或反向区间抛出 `DBException`。
- 未注册列族仍按现有列族校验失败。

## 数据结构

### WAL 编码

沿用现有 write batch record 框架，新增 `ValueType.DELETE_RANGE(0x02)` 的 entry：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `valueType` | byte | `DELETE_RANGE` 的 persistent id |
| `cfId` | int | 列族 id |
| `beginKey` | length-prefixed bytes | 半开区间起点 |
| `endKey` | length-prefixed bytes | 半开区间终点 |

### 内存模型

新增 `RangeTombstone`：

| 字段 | 说明 |
| --- | --- |
| `cf` | 所属列族 |
| `beginKey` | 区间起点，包含 |
| `endKey` | 区间终点，不包含 |
| `sequence` | tombstone 写入 sequence |

MemTable 保留现有 point entry 结构，并为每个列族维护 range tombstone 集合。第一版可以使用按 beginKey 排序的列表实现，后续再根据压测结果升级为 interval index。

### SST 持久化

SST 中用普通 table entry 保存 range tombstone：

- key：`InternalKey(beginKey, sequence, DELETE_RANGE)`
- value：`endKey`

这样可以复用 block/index/filter 编码与校验逻辑。读路径必须识别 `DELETE_RANGE`，并将其纳入 tombstone 遮蔽判断。

## 状态机

`deleteRange` 跟随现有写入状态机：

`VALIDATING -> BEFORE_WRITE -> WAL_APPENDED -> MEMTABLE_APPLIED -> AFTER_WRITE -> COMMITTED`

非法区间、未知列族、WAL 写入失败或 MemTable 应用失败都保持现有写失败语义。WAL 已成功追加后，崩溃恢复必须 replay tombstone。

## 时序流程

1. `LdbWriteBatchImpl.validateForWrite()` 校验 `DELETE_RANGE` 边界。
2. 写线程获取 sequence，batch 内每个操作按顺序递增 sequence。
3. WAL writer 按新增编码写入 `DELETE_RANGE` entry。
4. MemTable handler 将 tombstone 写入列族 tombstone 集合。
5. point get 先定位候选 point entry，再检查 snapshot 内可见且 sequence 更新的 covering tombstone。
6. iterator/scan 在输出 point key 前检查是否被可见 tombstone 覆盖。
7. flush 将 MemTable tombstone 作为 `InternalKey(begin, seq, DELETE_RANGE) -> end` 写入 Level-0 SST。
8. compaction 同时读取 point entry 与 tombstone，合并并按 snapshot 安全边界清理被覆盖数据。

## 异常处理

- 空 key、空区间或反向区间抛出 `DBException`，错误信息包含 `deleteRange` 与边界原因。
- WAL 中遇到结构不完整的 `DELETE_RANGE`，按现有 WAL corruption 策略处理：完整 record 解码失败则该 record 不应用。
- SST 中遇到 value 无法解析为 `endKey` 或 `beginKey >= endKey`，open/read/verify 应显式失败并保留文件名与 key 信息。

## 幂等性

- WAL replay 使用 record 中的 sequence 重新构造 tombstone，重复 replay 同一个已提交 record 不应改变最终可见结果。
- compaction 是语义等价重写，输出文件记录到 MANIFEST 后才成为新版本；失败输出按现有 pending output/obsolete cleanup 处理。

## 回滚策略

- 代码实现前或未写入 `DELETE_RANGE` 数据前，可以直接回滚二进制。
- 一旦库内出现 `DELETE_RANGE` WAL/SST，不支持用旧版本直接打开该库。
- 需要降级时，先用新版本完成 checkpoint/verify 或逻辑导出，再由旧版本导入到新库。
- 若灰度中发现问题，停止调用 `deleteRange`，保留新版本读取能力，待 compaction/导出清理后再评估降级。

## 兼容性

- 新版本必须兼容旧 WAL/SST。
- 旧版本遇到 `DELETE_RANGE` 可能抛出未知类型或产生错误读结果，因此明确标记为不可安全降级。
- repair、verify、backup 后续阶段必须把 `DELETE_RANGE` 当作一等持久化 entry 处理。

## 灰度/迁移

- 不需要旧数据迁移。
- 第一轮只在单元测试和故障注入测试中启用。
- 进入 ADB 集成前必须完成 crash recovery、snapshot 和 compaction 回归。
- 灰度期间保留 `deleteRange` 使用点审计，避免业务误以为旧版本仍可回滚。

## 测试方案

- `LdbRangeDeleteTest`：覆盖基本 put/deleteRange/get、边界 `[begin,end)`、空区间失败、反向区间失败。
- `LdbRangeDeleteRecoveryTest`：覆盖 WAL replay、flush 后 reopen、SST+WAL 混合恢复。
- `LdbRangeDeleteSnapshotTest`：覆盖 tombstone 前后 snapshot、range delete 后重新 put 的可见性。
- `LdbRangeDeleteIteratorTest`：覆盖 scan 跳过被遮蔽 key、跨 SST/level 遮蔽、列族隔离。
- `LdbRangeDeleteCompactionTest`：覆盖 compaction 后被遮蔽 point key 清理、tombstone 保留和长 snapshot 安全。
- `LdbFaultInjectionTest` 扩展：覆盖含 `DELETE_RANGE` WAL 尾部截断和 checksum 错误。

## 风险点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| tombstone 遮蔽顺序错误 | 读到已删除数据或误删新数据 | 以 sequence 规则为核心编写 snapshot/iterator 矩阵测试 |
| compaction 过早丢弃 tombstone | 下层旧 key 重新可见 | compaction 先保守保留 tombstone，再逐步优化丢弃规则 |
| 旧版本误打开新格式库 | 数据不可预期 | 文档明确不可降级，verify/repair 识别 `DELETE_RANGE` |
| tombstone 扫描过慢 | 大范围删除后读放大 | 第一版先正确，后续用 interval index 和 tombstone fragmentation 优化 |

## 分阶段实施计划

1. 9.1：补齐设计评审、测试类骨架和 `deleteRange` 参数校验，仍不写入磁盘格式。（已完成）
2. 9.2：实现 WAL 编码/解码、MemTable tombstone 和 point get 遮蔽，覆盖 crash recovery。（已完成）
3. 9.3：实现 SST tombstone 持久化与 reopen 后读路径遮蔽，覆盖跨 SST/level。（已完成基础持久化、point get 与 cursor reopen 遮蔽；compaction 合并待 9.5）
4. 9.4：实现 iterator/scan 遮蔽与 snapshot 矩阵。（已完成）
5. 9.5：实现 compaction 合并与保守清理规则，完成 fault injection 与兼容文档更新。（已完成；当前采用保守策略保留 range tombstone，不做激进 point key 清理）
