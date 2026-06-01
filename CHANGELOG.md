# Changelog

本文档记录 `vexra-ldb` 的重要变更。格式遵循 Keep a Changelog 的精神，版本号遵循语义化版本约定。

## [Unreleased]

## [0.2.0] - 2026-06-01

### Added

- 新增中文 README 和英文 README。
- 新增项目整体设计文档及英文副本。
- 新增贡献指南、安全政策、NOTICE、发布说明和 CI 配置。
- 新增 benchmark/soak 回归入口，覆盖写入、随机读、snapshot scan、manual compaction、checkpoint 和 reopen 工作流。
- 新增 compaction 高压可靠性回归，覆盖手动压缩压力和写入突增后的反复压缩恢复。
- 新增 checkpoint、backup/restore、repair 和 check 串联的恢复闭环回归测试。
- 新增后续性能可靠性专项评估文档及英文副本，规划 group commit、增量备份和 range delete 强化路径。
- 新增插件能力增强设计文档及英文副本。
- 新增插件增强 API：`LdbPluginDescriptor`、`LdbPluginFailurePolicy`、`OptionsView`、`WriteEvent` 和 `WriteBatchView`。
- 新增插件观测属性：`ldb.plugins`、`ldb.pluginStats`、`ldb.plugin.<index>.stats` 和 `ldb.plugin.lastFailure`。
- 新增 Windows + Ubuntu JDK 8 CI，以及本地 Maven 发布产物校验任务。

### Changed

- 修正 Maven POM 元数据，使项目名称、描述和主页指向 `vexra-ldb`。
- 优化 range tombstone 读取路径，避免普通 point get 在无关 tombstone 上做不必要扫描。
- 新增 `Options.writeSlowdownDelayNanos`，使 Level-0 slowdown 延迟可配置并可通过诊断属性观测。
- 明确插件 `afterWrite` 和 `afterCheckpoint` 的提交后通知失败语义，避免调用方误以为已提交数据会回滚。
- 将版本号切换为正式发布版本 `0.2.0`。

### Fixed

- 补齐插件提交后失败、checkpoint 后置失败、只读配置视图和只读写入事件的回归覆盖。
- 补齐本地发布产物验证，确认主 jar、sources jar、javadoc jar、POM、module 和对应签名文件可生成。

## [0.1.0]

### Added

- 基础 LDB API、WAL、MemTable、SSTable、MANIFEST/CURRENT、VersionSet 和 compaction。
- 列族、snapshot cursor、checkpoint、离线 check/repair/backup/restore 和工具命令。
- 可靠性、range delete、API 兼容和项目整体设计文档。
