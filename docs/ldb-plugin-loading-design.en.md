# LDB Plugin Loading And Discovery Design

English | [中文](ldb-plugin-loading-design.md)

## Background

LDB already supports manual plugin registration through `Options.addPlugin`. `ldb-longrun` also needs profile-based plugin enablement for diagnostics and upper-layer extensions. P4 does not introduce a third-party marketplace. It keeps plugins as trusted internal extensions while reducing integration cost and keeping loading observable, disableable, and testable.

## Goals

- Support built-in names, explicit classpath class names, and `ServiceLoader` provider names.
- Provider discovery must not change the write path by itself.
- longrun explicitly enables plugins through `ldb.plugins`.
- `ldb.plugin.discovery.enabled=false` disables provider-name resolution.
- Startup failures should include the plugin name, loading path, and cause.

## Non-Goals

- No external directory scanning.
- No independent classloader.
- No security sandbox or third-party marketplace.
- No automatic enablement of discovered classpath plugins.

## Current State

| Module | Existing capability |
| --- | --- |
| Core LDB | Manual `Options.addPlugin` registration |
| Plugin API | `LdbPluginDescriptor` exposes name, version, order, capabilities, and failure policy |
| Discovery API | `LdbPluginProvider` and `LdbPluginLoader.discoverProviders()` |
| longrun | `LongRunConfig` parses `ldb.plugins`, `ldb.plugin.<name>.*`, and `ldb.plugin.discovery.enabled` |

## Interface Design

```java
public interface LdbPluginProvider {
  String name();
  String version();
  LdbPlugin create(Map<String, String> config);
}
```

`name()` is the stable profile-facing name. `create(config)` receives only the plugin-private configuration view.

## longrun Config

| Config | Default | Meaning |
| --- | --- | --- |
| `ldb.plugins` | empty | Comma-separated plugin names or class names |
| `ldb.plugin.discovery.enabled` | `false` | Allow provider-name resolution |
| `ldb.plugin.<name>.enabled` | `true` | Enable or disable one plugin |
| `ldb.plugin.<name>.*` | empty | Private provider configuration |

## Resolution Order

1. Built-in names such as `diagnostic`.
2. Provider names when `ldb.plugin.discovery.enabled=true`.
3. Explicit classpath class names implementing `LdbPlugin` with a no-arg constructor.

## Failure Handling

| Scenario | Behavior |
| --- | --- |
| Duplicate provider name | Startup fails with the duplicate name |
| Provider construction failure | Startup fails and preserves the original cause |
| Missing class | Startup fails with configured plugin not found |
| Wrong type | Startup fails with a type mismatch |
| Disabled plugin | Skipped and not registered into `Options` |

## Observability

- `LongRunPluginResolver.discoveredProviderNames()` lists classpath providers.
- `ldb.plugins` shows final execution order, order value, and capabilities after open.
- longrun startup logs print `PLUGIN list`, `PLUGIN stats`, and `PLUGIN executionPolicy`.
- longrun reports persist `plugins`, `pluginStats`, `pluginLastFailure`, and `pluginExecutionPolicy`.

## Compatibility

- Existing `Options.addPlugin` remains unchanged.
- Default `ldb.plugins=` keeps existing profiles unchanged.
- Provider-name resolution is disabled by default so adding a provider to the classpath does not implicitly change workloads.
- New property fields are append-only observability fields.

## Rollback

Rollback removes provider discovery and longrun config-based enablement, but does not affect on-disk data. Manual core plugin registration can remain.

## Tests

| Test | Coverage |
| --- | --- |
| Built-in plugin resolution | `diagnostic` can be enabled |
| Explicit class loading | classpath plugin can be instantiated |
| Discovery disabled by default | provider name is unavailable when disabled |
| Discovery enabled explicitly | provider name is available when enabled |
| Plugin disable flag | `ldb.plugin.<name>.enabled=false` skips registration |
| Report fields | summary reads plugin state |

## Conclusion

P4 is implemented with discover-but-not-enabled-by-default semantics. Independent classloader or external directory scanning should be handled in a later design stage.
