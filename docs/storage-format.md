# LDB 存储文件格式参考

[English](storage-format.en.md) | 中文

## 目的

本文记录当前 LDB 存储目录中各类持久化文件的实际格式，作为 `0.8.0-SNAPSHOT` 文件格式演进的基线参考。本文只描述当前事实和兼容边界；新的 v2 table properties、feature set、`storageFormatGates` 等设计见 `storage-format-0.8-design.md`。

## 文件命名

| 文件 | 命名 | 类型 | 说明 |
| --- | --- | --- | --- |
| WAL | `%06d.log` | LOG | 追加写入 write batch record，用于恢复 MemTable |
| SST/table | `%06d.sst` | TABLE | 持久化排序 key/value block |
| MANIFEST | `MANIFEST-%06d` | DESCRIPTOR | 复用 log record 容器，内容为 VersionEdit record |
| CURRENT | `CURRENT` | CURRENT | UTF-8 文本，指向当前 MANIFEST 文件名 |
| LOCK | `LOCK` | DB_LOCK | 进程锁文件 |
| INFO LOG | `LOG` / `LOG.old` | INFO_LOG | 运行日志文件 |
| TEMP | `%06d.dbtmp` | TEMP | 临时文件 |
| Column families | `COLUMN-FAMILIES` | registry | 列族注册表文本文件 |
| Backup manifest | `BACKUP-MANIFEST.json` | backup metadata | 单个备份目录的元数据 |
| Backup report | `BACKUP-REPORT.json` | backup report | 备份执行报告 |
| Restore report | `RESTORE-REPORT.json` | restore report | 恢复执行报告 |
| Object refs | `OBJECT-REFS.json` | backup root metadata | 备份对象仓库引用计数 |
| Object store | `objects/<objectId>` | backup object | 备份根目录下的复用对象文件 |

文件编号必须为非负数，数字文件名使用 6 位补零。`CURRENT` 只能指向同目录内合法 `MANIFEST-[0-9]{6,}` 文件名，不能包含路径分隔符。

## 字节序和基础编码

| 项目 | 当前规则 |
| --- | --- |
| 整数写入 | 代码中的 `SliceOutput.writeInt/writeLong` 作为当前实现事实；本文不额外声明跨语言字节序契约 |
| varint | `VariableLengthQuantity` 编码，用于 block handle、VersionEdit tag/value 的部分字段 |
| length-prefixed bytes | 先写 varint 长度，再写原始 bytes |
| 字符串 | 文件名、列族名、JSON、部分 manifest 字段使用 UTF-8 |
| checksum | WAL chunk 使用 CRC32C；SST block trailer 使用 masked CRC32C，覆盖 block content 和 compression type |

## WAL 物理格式

WAL 文件名为 `%06d.log`。物理层按固定 32KB block 切分，每个 chunk 不跨 block 边界。

| 字段 | 长度 | 说明 |
| --- | ---: | --- |
| checksum | 4 bytes | chunk type + payload 的 CRC32C |
| length | 2 bytes | payload 长度，低字节在前、高字节在后 |
| type | 1 byte | chunk type persistent id |
| payload | length bytes | record 全部或片段 |

chunk type：

| id | 名称 | 说明 |
| ---: | --- | --- |
| 0 | ZERO_TYPE | padding/空记录，读取时跳过 |
| 1 | FULL | 一个完整 logical record |
| 2 | FIRST | 分片 record 的第一片 |
| 3 | MIDDLE | 分片 record 的中间片 |
| 4 | LAST | 分片 record 的最后一片 |

如果 block 剩余空间不足 7 字节 header，写入端用 0 填充剩余空间并从下一个 32KB block 开始。读取端可从 initial offset 跳到下一个可解析 block；checksum、length、unknown type 或不完整分片会通过 LogMonitor 报告 corruption，并跳过损坏片段。

## WAL logical record 格式

WAL payload 是一个 write batch record：

| 字段 | 长度/编码 | 说明 |
| --- | --- | --- |
| sequenceBegin | 8 bytes | 本 batch 第一条操作的 sequence number |
| updateSize | 4 bytes | batch 内操作数量 |
| entries | repeated | 每条写操作 |

每条 entry：

| 字段 | 长度/编码 | 说明 |
| --- | --- | --- |
| valueType | 1 byte | `DELETION=0`、`VALUE=1`、`DELETE_RANGE=2`、`ADD_LONG=3` |
| cfId | 4 bytes | 列族 id |
| key/beginKey | length-prefixed bytes | PUT/DELETE/ADD_LONG 的 key，或 DELETE_RANGE 的 beginKey |
| value/endKey/delta | length-prefixed bytes | PUT value、DELETE_RANGE endKey、ADD_LONG 8-byte delta；DELETE 无该字段 |

恢复时要求实际 entry 数等于 `updateSize`，否则 record 视为损坏。

## InternalKey 格式

InternalKey = user key bytes + 8-byte packed sequence/type。

| 部分 | 说明 |
| --- | --- |
| userKey | 用户 key 原始 bytes |
| packed sequence/type | 高 56 bit 为 sequence number，低 8 bit 为 ValueType persistent id |

`MAX_SEQUENCE_NUMBER = 2^56 - 1`。该格式同时用于 SST key、MANIFEST 的 file boundary key 和 compaction pointer。

## MANIFEST 格式

MANIFEST 文件名为 `MANIFEST-%06d`。MANIFEST 物理层复用 WAL log container：32KB block、7 字节 chunk header、FULL/FIRST/MIDDLE/LAST 分片和 CRC32C 校验。

MANIFEST logical record 是 `VersionEdit.encode()` 的输出，由多个 tag/value 顺序组成。每个 tag 先写 varint persistent id，再写对应 value。

| tag id | 名称 | value 格式 |
| ---: | --- | --- |
| 1 | COMPARATOR | varint length + UTF-8 comparator name bytes |
| 2 | LOG_NUMBER | varint64 log number |
| 3 | NEXT_FILE_NUMBER | varint64 next file number |
| 4 | LAST_SEQUENCE | varint64 last sequence number |
| 5 | COMPACT_POINTER | varint cfId, varint level, length-prefixed InternalKey |
| 6 | DELETED_FILE | varint cfId, varint level, varint64 file number |
| 7 | NEW_FILE | varint cfId, varint level, varint64 file number, varint64 file size, length-prefixed smallest InternalKey, length-prefixed largest InternalKey |
| 9 | PREVIOUS_LOG_NUMBER | varint64 previous log number |

tag id `8` 已废弃。读取遇到未知 tag 会失败。恢复要求 descriptor 至少能提供 next file number、log number 和 last sequence number 等 meta 信息。

## CURRENT 格式

`CURRENT` 是 UTF-8 文本文件，内容为当前 MANIFEST 文件名加换行，例如：

```text
MANIFEST-000123
```

校验规则：

- 内容必须以换行结束。
- 文件名不能为空。
- 文件名不能包含 `/` 或 `\`。
- 文件名必须匹配 `MANIFEST-[0-9]{6,}`。
- 指向文件必须存在于同一数据库目录。

更新 CURRENT 时先写 `%06d.dbtmp` 临时文件并 fsync，再 rename 到 `CURRENT`；rename 失败时回退为直接写 CURRENT。

## SST/table 格式 v1

当前 SST/table 格式视为 `table format v1 legacy`。它接近 LevelDB table layout：data blocks、filter block、metaindex block、index block、footer。

### 整体布局

```text
[data block 0]
[data block 1]
...
[optional filter block]
[metaindex block]
[index block]
[footer]
```

### BlockHandle

BlockHandle 使用两个 varint：

| 字段 | 说明 |
| --- | --- |
| offset | block content 起始偏移 |
| dataSize | block content 长度，不含 5 字节 trailer |

完整 block 大小 = `dataSize + 5`。

### Block trailer

每个 data/index/metaindex block 后跟 5 字节 trailer：

| 字段 | 长度 | 说明 |
| --- | ---: | --- |
| compressionType | 1 byte | `NONE=0x00`，`LZ4=0x02` |
| crc32c | 4 bytes | masked CRC32C，覆盖 block content 和 compressionType |

启用 `verifyChecksums` 时，读取 block 会校验 trailer CRC。

### Data block entry 编码

Data block 内部使用 key 前缀压缩和 restart points。

每条 entry：

| 字段 | 编码 | 说明 |
| --- | --- | --- |
| sharedKeyBytes | varint | 与上一 key 共享的前缀长度；restart entry 为 0 |
| nonSharedKeyBytes | varint | 本 entry 独有 key bytes 长度 |
| valueSize | varint | value bytes 长度 |
| key delta | bytes | 从 sharedKeyBytes 之后开始的 key bytes |
| value | bytes | value bytes |

block 末尾：

| 字段 | 说明 |
| --- | --- |
| restartPositions[] | 每个 restart point 在 block content 中的 int offset |
| restartCount | restart point 数量 |

`blockRestartInterval` 控制多少条 entry 后新增 restart point。空 block finish 时只写 `restartCount=0`。

### Compression

TableBuilder 按 block 尝试 LZ4 压缩。压缩后的 block content 格式为：

| 字段 | 说明 |
| --- | --- |
| rawLength | varint 原始 block 长度 |
| compressed bytes | LZ4 压缩数据 |

只有压缩后总大小比原始小至少约 12.5% 时才使用 LZ4，否则保持 NONE。

### Filter block 和 metaindex

如果启用 `FilterPolicy` 且存在 key，TableBuilder 会收集去重 user key，调用 `filterPolicy.createFilter(filterKeys)` 生成 filter bytes，并以 raw block 形式写入。

metaindex block 是普通 block，当前可包含：

| key | value |
| --- | --- |
| `filter.<policyName>` | filter block 的 BlockHandle 编码 |

读取 table 时，如果 Options 中有同名 FilterPolicy，会从 metaindex 找到 `filter.<policyName>` 并读取 filter block；没有 filter 或找不到时 `mayContain` 返回 true。

0.9.0-SNAPSHOT 起，发布门禁通过 `filterBlockCoverage` 要求证明 `BloomFilterPolicy` 能让范围内缺失 key 产生 `mayContainFalse` 与 `filterSkips>0`。该约束只强化验收，不改变磁盘兼容性：filter block 仍是可选 metaindex entry。

### Index block

Index block 是普通 block，key 为相邻 data block 边界上的 shortest separator/successor，value 为 data block handle。读取时 index iterator seek 到目标 internal key，再打开对应 data block。

### Footer

Footer 固定长度：`BlockHandle.MAX_ENCODED_LENGTH * 2 + 8 = 48 bytes`。

| 字段 | 说明 |
| --- | --- |
| metaindexBlockHandle | metaindex block handle |
| indexBlockHandle | index block handle |
| padding | 0 填充到 magic 前 |
| magic | 64-bit table magic number `0xdb4775248b80fb57`，按两个 32-bit int 写入 |

当前 footer 不包含 format version 或 feature set。

## COLUMN-FAMILIES 格式

`COLUMN-FAMILIES` 是 UTF-8 文本文件。没有该文件时，打开逻辑使用 `Options.getColumnFamilies()` 中的静态声明。

当前写入格式每行一条 record：

```text
A	<cfId>	<escapedName>
D	<cfId>	<escapedName>
```

| 字段 | 说明 |
| --- | --- |
| `A` | active column family |
| `D` | dropped tombstone |
| cfId | 十进制整数 |
| escapedName | 列族名，转义 `\`、tab、LF、CR |

兼容读取也支持旧两字段格式：

```text
<cfId>	<escapedName>
```

旧两字段格式按 active record 处理。写入时先写 `COLUMN-FAMILIES.tmp` 并 fsync，再删除旧文件并 rename 发布。

## Backup metadata 格式

备份目录名为 `backup-%06d`。增量备份会尝试复用上一备份中的 SST 硬链接；失败时回退为复制。备份根目录可包含对象仓库：`objects/` 和 `OBJECT-REFS.json`。

### BACKUP-MANIFEST.json

单个备份目录下的 `BACKUP-MANIFEST.json` 当前为手写 JSON，核心字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `formatVersion` | number | 当前为 `1` |
| `backupId` | string | 当前备份目录名，例如 `backup-000001` |
| `parentBackupId` | string | 增量备份父目录名；全量为空字符串 |
| `action` | string | `backup` 或 `incremental-backup` |
| `copiedFiles` | array | 本次复制的文件名 |
| `reusedFiles` | array | 本次复用的文件名 |
| `published` | boolean | 发布完成标记，必须为 true |

`checkBackup` 会要求 manifest 存在、包含 `formatVersion`、backupId 匹配目录名并且 `published=true`。

### OBJECT-REFS.json

备份根目录下的 `OBJECT-REFS.json` 记录对象仓库引用：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `formatVersion` | number | 当前为 `1` |
| `objects` | array | object ref 列表 |
| `objects[].objectId` | string | 对象文件名 |
| `objects[].refCount` | number | 引用该对象的备份数量 |
| `objects[].backups` | array | 引用该对象的 backup id 列表 |

object id 当前由 `fileName-fileLength-crc32` 组成。`checkBackup` 会重建预期引用集合，检查对象文件存在、refCount 正确、无缺失 ref、无 orphan object。

### BACKUP-REPORT.json / RESTORE-REPORT.json

这两个文件是执行报告，不作为恢复所需的核心格式；它们用于审计 action、source/target、copied/reused files、check report 和 failures。

## Check/repair 当前格式行为

| 入口 | 当前行为 |
| --- | --- |
| `check` | 校验 CURRENT、MANIFEST、SST、WAL 和 COLUMN-FAMILIES；CURRENT 必须换行结束并指向合法 manifest |
| `checkBackup` | 校验 BACKUP-MANIFEST、OBJECT-REFS、对象文件引用和孤儿对象 |
| `repairPlan` | 只生成计划，不写 MANIFEST/CURRENT/SST/report 文件 |
| `repair` | 从可读 SST/WAL 重建 MANIFEST/CURRENT；损坏文件移入 `corrupt/`；WAL 重放到 MemTable 后刷成 Level-0 SST |

repair 不会原地修改 SST；恢复 MANIFEST 时写新的 VersionEdit snapshot，并更新 CURRENT。

## 当前格式不足

| 领域 | 不足 |
| --- | --- |
| 全局版本 | WAL、SST、MANIFEST、COLUMN-FAMILIES、backup metadata 缺少统一 format matrix |
| SST 自描述 | v1 footer 无 format version；无 properties block；entry count、block count、filter/compression 参数不落盘 |
| feature set | 缺少 compatible/incompatible feature 列表，未知能力边界不统一 |
| MANIFEST | VersionEdit tag 未预留 feature/version tag；未知 tag 直接失败但错误分类不够产品化 |
| Backup metadata | 0.8 已补充 `schemaVersion`、稳定 `chainId`、`generation` 和对象引用表 schema；保留策略字段仍待后续版本设计 |
| Check/report | 错误能被发现，但格式版本、feature 和损坏分类还未形成统一 release gate 证据 |

## 0.8.0 演进入口

`0.8.0` 应以本文为事实基线推进：

- 新增 table properties block reader。
- 定义 table format v2、compatible/incompatible feature set。
- v2 写入先 opt-in，默认继续读旧库和保留 legacy write mode。
- check/repair/report 增加 storage format 证据。
- backup metadata schema 已补充 `schemaVersion/chainId/generation` 与对象引用表 schema，保留策略字段后续推进。

详细设计见 `docs/storage-format-0.8-design.md`。
## 0.8 备份元数据 schema

0.8 版本在不改变备份目录主体布局的前提下，为备份元数据补充显式 schema 字段，便于发布前检查、跨版本恢复诊断和后续对象存储演进。

- `BACKUP-MANIFEST.json` 保留 `formatVersion=1`，新增 `schemaVersion=backup-metadata-v2`、`chainId`、`generation`。其中 `chainId` 在增量备份链内保持稳定，后续备份优先继承父备份清单中的链标识；`generation` 来自 `backup-000001` 这类目录序号。
- `OBJECT-REFS.json` 保留对象引用数组，新增 `schemaVersion=backup-object-refs-v2`、`objectStoreVersion=1`、`generatedBy=vexra-ldb`，用于区分对象引用表自身 schema 与备份清单 schema。
- 兼容性策略：旧备份仍可检查和恢复；新增字段属于诊断和迁移元数据，不要求旧备份回填。父清单不可读时，`chainId` 会退化为父备份目录名，避免阻断旧备份链操作。
## 0.8 repair 报告格式证据

`REPAIR-REPORT.json` 与 `repair-plan` dry-run 输出现在包含结构化格式证据字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `storageFormat` | string | 汇总 table/WAL/MANIFEST/CURRENT/COLUMN-FAMILIES/backup metadata 的当前格式策略 |
| `tableFormats` | array | 每个可恢复 SST 的 `formatVersion/legacy/compatible/incompatible` 摘要 |
| `legacyTables` | number | repair 输入和 WAL 重放生成 SST 中识别为 legacy v1 的 table 数量 |
| `v2Tables` | number | repair 输入和 WAL 重放生成 SST 中识别为 table format v2 的 table 数量 |
| `incompatibleTables` | number | 包含 incompatible feature 的 table 数量 |

兼容性策略：repair 不会原地重写 v1/v2 SST 格式；它只在报告中解释格式状态。WAL 重放生成的新 SST 按当前 `Options.tableFormatVersion` 策略写出，并在报告中记录最终落盘格式。
### v2 SST repair 格式保持

当 repair 从已有 v2 SST 重建 MANIFEST/CURRENT 时，不会把该 SST 原地改写或降级为 v1。`REPAIR-REPORT.json` 会在 `tableFormats` 中保留 `formatVersion=2`、`compatible=[table.properties,...]` 等证据，并通过 `v2Tables` 计数体现当前 repair 输入中的 v2 table 数量。
### v1/v2 混合格式 check 证据

同一数据库目录可以在 opt-in 迁移期间同时包含 v1 legacy SST 和 v2 properties SST。`LDBFactory.check` 会在 `tableFormats` 中分别列出 `formatVersion=1` 与 `formatVersion=2`，并通过 `legacyTables`、`v2Tables`、`incompatibleTables` 计数暴露混合状态。该行为用于发布门禁 `mixedFormatCheckCoverage`。
## 0.8 Table Format 策略与 Options API 契约

0.8.0-SNAPSHOT 的 table format 策略保持保守默认：新写 SST/table 默认仍为 v1，只有显式设置 `Options.tableFormatVersion(2)` 时才会写入 v2 table properties block。`Options.writeTableProperties(true)` 只在 `tableFormatVersion=2` 时影响落盘，避免默认写路径改变旧库兼容边界。

旧 SST 缺少 properties block 时会被识别为 v1 legacy，`Options.allowLegacyTableFormat(true)` 默认允许读取旧格式，用于保障新版本默认可以打开旧库。若关闭该选项，应只用于发布验证或强制迁移检查。

`Options.failOnUnknownTableFeature(true)` 默认开启，遇到未知不兼容 feature、未来 table format version 或 malformed table format version 时 fail-fast，避免静默误读。关闭该选项只允许用于诊断性读取（diagnostic-only），不作为生产回滚策略；生产回滚应停止 v2 新写入并依赖备份、副本和 check/repair 报告证据。

插件通过 `OptionsView.tableFormatVersion()`、`OptionsView.writeTableProperties()`、`OptionsView.allowLegacyTableFormat()` 和 `OptionsView.failOnUnknownTableFeature()` 只能观察打开数据库时的只读策略快照，不能修改文件格式策略。
## 0.9.0-SNAPSHOT SF-06 table format policy

`ldb.tableFormatPolicy` 是面向生产启用和回滚的运行时属性，和 `ldb.tableFormat` / `ldb.storageFormat` 配套使用。它显式输出 `newWrites`、`configuredTableFormatVersion`、`writeTableProperties`、`legacyReads`、`unknownFeaturePolicy`、`futureVersionPolicy`、`rollback`、`existingV2` 和 `productionState`。

生产启用 v2 时应观察 `newWrites=v2-properties` 与 `productionState=explicit-v2`；回滚新写入时恢复 `Options.tableFormatVersion(1)`，属性会回到 `newWrites=v1` 与 `productionState=default-legacy`。关闭 `failOnUnknownTableFeature` 只允许作为 diagnostic-only 读取，不作为生产回滚策略。

## v3 block-local index 运行时回退约束

v3 `block.local_index.v1` 是 opt-in 的 SST 加速特性。新 reader 默认继续读取 v1/v2；只有新写入显式设置 `tableFormatVersion(3)` 与 `writeBlockLocalIndex(true)` 时才会生成 local-index directory 和 local-index blocks。

运行时读取时，block-local index 只作为 point get/MultiGet 的旁路定位结构。local-index 缺失、损坏、checksum 失败或 anchor 解析异常不会改变 data block 中的真实数据，读路径必须回退普通 data-block seek，并通过 `ldb.sstReadStats` 中的 `blockLocalIndexFallbackCount` 暴露。离线 check/repair 仍负责把损坏分类归档到 `storageFormat`、`tableFormats` 和 block-local-index failure 字段。
## v3 block-local index 空间放大证据

v3 properties 会记录 `ldb.table.block_local_index.space_amplification_ppm`、candidate/skipped block 计数和 admission policy。ppm 以 local-index bytes / data-block bytes * 1,000,000 表示，便于 releaseGate 与 benchmark 报告做整数阈值比较。当前 writer 只过滤极端空间放大 block；默认启用仍需要后续 benchmark 证明。