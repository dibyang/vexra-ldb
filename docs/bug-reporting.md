# Bug 报告指南

[English](bug-reporting.en.md) | 中文

感谢你帮助改进 `vexra-ldb`。本指南用于普通缺陷报告；如果问题可能导致数据泄露、越权访问、任意文件访问、可被远程触发的拒绝服务、可利用的反序列化、权限绕过或依赖供应链风险，请不要提交公开 issue，改用 [SECURITY.md](../SECURITY.md) 中的私下报告方式。

## 提交位置

- 普通 bug：使用 GitHub 的 `Bug report` issue form。
- 安全问题：按 [SECURITY.md](../SECURITY.md) 私下报告。
- 贡献修复：先阅读 [CONTRIBUTING.md](../CONTRIBUTING.md)，并在 PR 中关联对应 issue。

## 必填信息

请尽量提供以下信息，让维护者可以直接复现和定位：

- 版本、提交号或依赖坐标，例如 `net.xdob:vexra-ldb:<version>`。
- 受影响模块，例如 WAL/recovery、SSTable、compaction、列族、snapshot cursor、check/repair/backup/restore、CLI 或插件。
- 使用路径：直接使用 Java API、通过 `LdbTool`、longrun 插件、测试夹具或上层应用集成。
- 期望行为和实际行为。
- 最小复现，优先提供可运行 JUnit 测试、最小代码片段或命令序列。
- 首个失败堆栈、`INFO_LOG` 摘要、`LdbTool` JSON 输出或 `getProperty` 诊断属性。
- 运行环境：JDK、操作系统、文件系统、磁盘类型、Gradle/IDE、容器或 CI 环境。

## 存储、恢复和并发问题

如果问题涉及 WAL、MANIFEST、SSTable、compaction、checkpoint、repair、backup、snapshot cursor、列族或插件，请额外说明：

- 数据库目录初始状态，以及是否由旧版本升级而来。
- 触发问题前的关键操作顺序，例如 `put`、`write`、`deleteRange`、`compactRange`、重启、`check`、`repair`、`backup` 或 `restore`。
- 线程数、迭代次数、失败频率、首次失败时间和是否可稳定复现。
- 是否启用自定义 `Options`、列族、Bloom filter、插件、限速或故障注入。
- 相关诊断属性，例如 `ldb.recoveryEvidence`、`ldb.fileCounts`、`ldb.compactionStats`、`ldb.snapshotCursorStats`、`ldb.storageFormat` 或 `ldb.sstReadStats`。

## 最小复现建议

维护者最容易处理以下形式的报告：

```java
@Test
public void reproducesIssue() throws Exception {
  Options options = new Options()
      .createIfMissing(true)
      .verifyChecksums(true);

  try (LDB db = LDBFactory.factory.open(tempDir, options)) {
    // Arrange: 写入最小数据集。
    // Act: 触发问题的最短操作序列。
    // Assert: 说明期望值和实际失败。
  }
}
```

如果无法提供测试，请提供完整命令序列、输入数据规模、目录结构摘要和可公开的日志片段。请在粘贴日志前移除业务数据、访问凭据、本机私有路径和其他敏感信息。

## 不适合公开 issue 的内容

以下内容请改走安全报告或先脱敏：

- 可直接利用的漏洞细节、攻击载荷或绕过步骤。
- 未脱敏的业务 key/value、数据库文件、备份文件或日志。
- 密钥、令牌、签名文件、私有仓库地址或内部服务地址。
- 会暴露用户、客户或生产环境的信息。
