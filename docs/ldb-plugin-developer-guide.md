# LDB 插件开发手册

[English](ldb-plugin-developer-guide.en.md) | 中文

## 适用范围

本文面向需要开发 LDB 插件的工程同学，按“实现插件 -> 注册 provider -> 配置 longrun -> 验证报告”的顺序说明。插件化文档总入口见 [插件化文档索引](ldb-plugin-docs-index.md)。

## 1. 实现插件

插件实现 `net.xdob.vexra.ldb.LdbPlugin`。最小实现需要声明 descriptor：

```java
public final class MyAuditPlugin implements LdbPlugin {
  @Override
  public LdbPluginDescriptor descriptor() {
    return new LdbPluginDescriptor(
        "my-audit",
        "1.0.0",
        50,
        LdbPluginFailurePolicy.RECORD_AND_CONTINUE,
        LdbPluginCapability.OBSERVE_WRITE,
        LdbPluginCapability.METADATA_READ);
  }
}
```

descriptor 字段含义：

| 字段 | 含义 | 建议 |
| --- | --- | --- |
| `name` | 稳定插件名 | 用短横线命名，例如 `my-audit`。 |
| `version` | 插件版本 | 用于诊断和 versionRange 检查。 |
| `order` | 执行顺序 | 数字越小越先执行。 |
| `failurePolicy` | 同步回调失败策略 | 观察型插件优先使用 `RECORD_AND_CONTINUE`。 |
| `capabilities` | 能力声明 | 遵循最小权限原则。 |

普通插件不需要覆盖 `unwrap()`。只有排序、隔离或治理类包装器需要覆盖它并返回真实 delegate，便于 LDB 运行时判断真实插件实现了哪些生命周期方法。

## 2. 声明能力

| 能力 | 适用场景 |
| --- | --- |
| `OBSERVE_WRITE` | 只观察写入事件，不修改写批次。 |
| `MUTATE_WRITE_BATCH` | 需要在提交前修改写批次。开启 capability enforcement 时必须声明。 |
| `CHECKPOINT_HOOK` | 需要参与 checkpoint 前后回调。 |
| `METADATA_READ` | 需要读取配置视图、属性、列族或目录元数据。 |

默认兼容模式下 capability 主要用于观测。配置 `ldb.plugin.capability.enforcement=true` 后，未声明 `MUTATE_WRITE_BATCH` 的插件不能修改写批次，未声明 `METADATA_READ` 的插件不能读取上下文元数据，未声明 `CHECKPOINT_HOOK` 的插件不能参与 checkpoint 回调。

## 2.1 Column family id/name 约束

需要创建或依赖 column family 的插件，接入前必须声明所需 id/name。

| 项目 | 要求 |
| --- | --- |
| id 范围 | 每个插件或业务模块保留稳定的本地范围。避免使用默认列族 id `1`。 |
| name 格式 | 使用稳定的短横线命名，并带插件或模块前缀，例如 `sample-audit-events`。 |
| 注册方式 | 在 `configure(Options)` 中注册，或在接入文档中声明调用方必须通过 `Options.addColumnFamily` 注册。 |
| 冲突处理 | id/name 冲突必须作为启动错误处理，不能静默复用为不同语义。 |
| 废弃策略 | 已废弃的 column family id 不得复用；在数据迁移或显式上层清理前，升级说明仍需保留旧 id/name。 |

推荐声明模板：

| 字段 | 示例 |
| --- | --- |
| 插件 | `sample-audit` |
| Column family id | `2001` |
| Column family name | `sample-audit-events` |
| 用途 | 追加式审计事件索引 |
| 废弃策略 | 不复用 `2001`，语义不兼容时新增 id |

## 3. 注册 provider

生产插件推荐实现 `net.xdob.vexra.ldb.LdbPluginProvider`，由 ServiceLoader 发现：

```java
public final class MyAuditPluginProvider implements LdbPluginProvider {
  @Override
  public String name() {
    return "my-audit";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public LdbPlugin create(Map<String, String> config) {
    return new MyAuditPlugin();
  }
}
```

jar 内增加服务文件：

```text
META-INF/services/net.xdob.vexra.ldb.LdbPluginProvider
```

文件内容为 provider 类名：

```text
com.example.MyAuditPluginProvider
```

## 4. 在 longrun 中启用

classpath provider 需要同时开启发现和选择插件：

```properties
ldb.plugins=my-audit
ldb.plugin.discovery.enabled=true
```

插件私有配置使用 `ldb.plugin.<provider-name>.*` 前缀：

```properties
ldb.plugin.my-audit.version=trial
ldb.plugin.my-audit.order=10
ldb.plugin.my-audit.versionRange=[1.0.0,2.0.0)
```

配置含义：

| 配置 | 说明 |
| --- | --- |
| `ldb.plugin.<name>.version` | 传给 provider 的私有版本或标识，是否使用由插件自行决定。 |
| `ldb.plugin.<name>.order` | longrun 侧覆盖插件执行顺序，不要求插件实现 setter。 |
| `ldb.plugin.<name>.versionRange` | 要求 provider `version()` 落在指定范围内，不匹配则启动失败。 |

外部 jar 目录需要显式开启：

```properties
ldb.plugins=my-audit
ldb.plugin.external.enabled=true
ldb.plugin.dir=/opt/ldb/plugins
```

注意：外部目录只负责发现 provider，不会自动启用插件。最终启用列表始终由 `ldb.plugins` 决定。外部目录使用独立 child-first `URLClassLoader` 发现 provider，JDK 与 `net.xdob.vexra.ldb` API 包仍由父加载器提供；longrun 会在插件关闭后释放该加载器。这属于依赖隔离和句柄治理，不是跨进程安全沙箱。

## 5. 运行内置样例

`ldb-longrun` 内置 `sample-audit`：

| 文件 | 说明 |
| --- | --- |
| `LongRunSampleAuditPlugin.java` | 示例插件实现。 |
| `LongRunSamplePluginProvider.java` | 示例 ServiceLoader provider。 |
| `plugin-sample.properties` | 示例 longrun profile。 |

运行：

```bash
./bin/longrun watch -c plugin-sample
```

关键配置：

```properties
ldb.plugins=sample-audit
ldb.plugin.discovery.enabled=true
ldb.plugin.sample-audit.version=guide
ldb.plugin.sample-audit.order=5
```

日志中应看到：

```text
PLUGIN list=0:sample-audit:order=5
SUMMARY status=PASS ...
```

## 6. 治理配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `ldb.plugin.capability.enforcement` | `false` | 是否强制 capability 约束。 |
| `ldb.plugin.callbackTimeoutMillis` | `0` | 回调耗时阈值，0 表示不判定超时。 |
| `ldb.plugin.autoDisableOnTimeout` | `false` | timeout 后是否自动禁用插件。 |
| `ldb.plugin.autoDisableFailureThreshold` | `0` | 连续失败多少次后禁用，0 表示关闭。 |
| `ldb.plugin.async.enabled` | `false` | 是否异步执行提交后通知。 |
| `ldb.plugin.async.queueCapacity` | `1024` | 异步通知队列容量。 |
| `ldb.plugin.async.closeTimeoutMillis` | `30000` | close 等待异步任务的时间。 |
| `ldb.plugin.maxTotalCallbackMillis` | `0` | 单插件累计回调耗时预算，0 表示关闭；超过后标记降级并跳过后续回调。 |

边界：

- `beforeWrite` 始终同步执行。
- `afterWrite` 和 `afterCheckpoint` 可以异步。
- 当前插件是进程内可信扩展，不是安全沙箱；报告会暴露 `ldb.plugin.sandbox=false`。
- timeout 不会强行中断正在运行的 Java 代码，只记录并影响后续降级。
- 总回调预算同样不强行中断当前回调，只在回调返回后禁用后续回调。

## 6.1 兼容性 testkit

插件接入前可以调用 `LdbPluginCompatibility` 做最低契约检查：

```java
LdbPluginCompatibility.verifyProvider(provider, config).throwIfIncompatible();
LdbPluginCompatibility.verifyPlugin(plugin).throwIfIncompatible();
```

该检查覆盖 provider name/version、`create(config)` 返回值、descriptor 稳定性、failurePolicy 和 capabilities 基本合法性。它不打开数据库，也不替代 longrun 集成验证。

## 7. 测试建议

| 测试类型 | 建议覆盖 |
| --- | --- |
| 单元测试 | descriptor、capability、provider name/version。 |
| 配置测试 | `ldb.plugins`、`versionRange`、order override、external dir。 |
| longrun 集成测试 | 启动 profile，检查 `PLUGIN list`、`PLUGIN stats`、`SUMMARY`。 |
| 兼容测试 | enforcement 关闭时旧插件行为不变。 |
| 治理测试 | timeout、auto-disable、async close wait、queue reject。 |
| testkit 测试 | 使用 `LdbPluginCompatibility` 固定 provider 和 descriptor 最低契约。 |

## 8. 常见问题

| 问题 | 处理方式 |
| --- | --- |
| provider 能发现但插件没有运行 | 检查 `ldb.plugins` 是否显式列出 provider 名称。 |
| classpath provider 没被发现 | 检查 `ldb.plugin.discovery.enabled=true` 和 ServiceLoader 文件。 |
| 外部 jar 没被发现 | 检查 `ldb.plugin.external.enabled=true`、`ldb.plugin.dir` 和 jar 中服务文件。 |
| versionRange 启动失败 | 对齐 provider `version()` 与配置范围。 |
| 插件修改 batch 后失败 | 开启 enforcement 时需要声明 `MUTATE_WRITE_BATCH`。 |
