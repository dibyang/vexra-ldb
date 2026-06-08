# LDB 插件加载与发现设计

[English](ldb-plugin-loading-design.en.md) | 中文

## 背景

LDB 已经支持通过 `Options.addPlugin` 手工注册插件，`ldb-longrun` 也需要通过 profile 配置启用诊断插件或上层扩展插件。P4 的目标不是引入第三方插件市场，而是在保持可信内部扩展定位的前提下，降低插件接入成本，并让加载结果可观测、可禁用、可回归测试。

## 目标

- 支持三种插件来源：内置名称、显式 classpath 类名、`ServiceLoader` provider 名称。
- provider 只负责发现和创建，默认不改变写入路径。
- longrun 通过 `ldb.plugins` 显式选择启用插件。
- `ldb.plugin.discovery.enabled=false` 时不启用 provider 名称解析。
- 启动失败要包含插件名称、加载路径和原因。

## 非目标

- 不扫描外部目录。
- 不引入独立 classloader。
- 不实现安全沙箱或第三方插件市场。
- 不自动启用 classpath 上发现到的插件。

## 现状

| 模块 | 已有能力 |
| --- | --- |
| 核心 LDB | `Options.addPlugin` 手工注册插件 |
| 插件 API | `LdbPluginDescriptor` 暴露名称、版本、顺序、能力、失败策略 |
| 发现 API | `LdbPluginProvider` 和 `LdbPluginLoader.discoverProviders()` |
| longrun | `LongRunConfig` 解析 `ldb.plugins`、`ldb.plugin.<name>.*` 和 `ldb.plugin.discovery.enabled` |

## 接口设计

### Provider API

```java
public interface LdbPluginProvider {
  String name();
  String version();
  LdbPlugin create(Map<String, String> config);
}
```

`name()` 是 profile 中使用的稳定名称。`create(config)` 只接收该插件私有配置，避免 provider 直接读取完整全局配置。

### longrun 配置

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `ldb.plugins` | 空 | 逗号分隔的插件名称或类名 |
| `ldb.plugin.discovery.enabled` | `false` | 是否允许按 provider 名称解析 |
| `ldb.plugin.<name>.enabled` | `true` | 是否启用单个插件 |
| `ldb.plugin.<name>.*` | 空 | 传递给 provider 的私有配置 |

## 解析顺序

1. 内置名称，例如 `diagnostic`。
2. 当 `ldb.plugin.discovery.enabled=true` 时，按 provider 名称解析。
3. 按显式 classpath 类名解析，并要求类型实现 `LdbPlugin` 且存在无参构造。

## 异常处理

| 场景 | 行为 |
| --- | --- |
| provider 重名 | 启动失败，提示重复名称 |
| provider 创建失败 | 启动失败，保留原始异常 cause |
| 类名不存在 | 启动失败，提示 configured plugin not found |
| 类不是 `LdbPlugin` | 启动失败，提示类型不匹配 |
| 插件被禁用 | 跳过，不注册到 `Options` |

## 观测

- `LongRunPluginResolver.discoveredProviderNames()` 可列出 classpath provider。
- LDB 打开后通过 `ldb.plugins` 查看最终执行顺序、order 和 capabilities。
- longrun 启动日志打印 `PLUGIN list`、`PLUGIN stats`、`PLUGIN executionPolicy`。
- longrun report 写入 `plugins`、`pluginStats`、`pluginLastFailure`、`pluginExecutionPolicy`。

## 兼容性

- 现有 `Options.addPlugin` 不变。
- 默认 `ldb.plugins=` 为空，现有 profile 行为不变。
- 默认禁用 provider 名称解析，避免 classpath 增加 provider 后隐式改变测试路径。
- 新增 property 字段属于追加观测字段，调用方不得依赖字段顺序。

## 回滚策略

回滚后失去 provider 发现和 longrun 配置启用能力，但不影响磁盘数据。显式手工注册插件的核心 API 可以继续保留。

## 测试方案

| 测试 | 覆盖 |
| --- | --- |
| 内置插件解析 | `diagnostic` 可启用 |
| 显式类名解析 | classpath 插件可实例化 |
| 默认禁用发现 | provider 名称在 discovery disabled 时不可用 |
| 显式启用发现 | provider 名称在 discovery enabled 时可用 |
| 插件禁用 | `ldb.plugin.<name>.enabled=false` 跳过注册 |
| report 字段 | summary 能读取插件状态 |

## 当前结论

P4 已按“发现但不默认启用”的边界落地。后续如果需要独立 classloader 或外部目录扫描，应作为新的设计阶段处理。
