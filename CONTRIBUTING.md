# 贡献指南

[English](CONTRIBUTING.en.md) | 中文

感谢你愿意参与 `vexra-ldb`。这是一个 Java 本地存储项目，修改通常会影响磁盘格式、恢复语义、并发控制或运维工具，因此贡献时请优先保持变更小而可验证。

## 开发环境

- JDK 8
- Gradle Wrapper
- UTF-8 编码

运行测试：

```bash
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew.bat test
```

## 提交流程

1. 先创建 issue 或在现有 issue 中说明要解决的问题。
2. 对涉及 API、协议、磁盘格式、状态机、恢复流程、工具副作用或兼容性的修改，先更新 `docs/` 下的设计文档；设计文档需要维护中英文副本。
3. 让代码变更尽量聚焦，避免混入格式化、重命名或无关重构。
4. 为行为变更补充测试，至少覆盖正常路径和关键失败路径。
5. 提交 PR 时说明变更范围、兼容性影响、回滚方式和验证命令。

## 代码规范

- 保持 JDK 8 兼容。
- 公共类、接口和关键方法需要有清晰注释。
- 涉及 WAL、SST、MANIFEST、compaction、checkpoint、repair、backup、snapshot 和并发控制的逻辑，应说明关键约束。
- 不提交本地密钥、签名文件、IDE 私有配置、构建产物或数据库临时文件。

## 测试要求

根据变更范围选择测试：

- API 和兼容性：`ApiTest`、`LdbApiCompatibilityTest`
- 核心读写：`DbImplTest`、`LdbCoreBehaviorTest`
- 恢复和 WAL：`LdbWalLifecycleTest`、`LdbCrashRecoveryTest`、`LdbRecoveryMatrixTest`
- 维护工具：`LdbVerifyCheckTest`、`LdbRepairTest`、`LdbBackupTest`、`LdbToolTest`
- snapshot、compaction 和故障注入：`LdbSnapshotIteratorTest`、`LdbObservabilityTest`、`LdbFaultInjectionTest`

## Pull Request Checklist

- [ ] 代码和文档使用 UTF-8。
- [ ] 已补充或更新相关设计文档。
- [ ] 已运行 `./gradlew test` 或 `.\gradlew.bat test`。
- [ ] 已说明兼容性、回滚和潜在风险。
- [ ] 没有提交敏感信息或本地环境文件。
