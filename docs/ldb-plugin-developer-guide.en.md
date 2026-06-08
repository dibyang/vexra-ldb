# LDB Plugin Developer Guide

English | [中文](ldb-plugin-developer-guide.md)

## Scope

This guide is for engineers writing LDB plugins. It follows the practical order: implement a plugin, register a provider, configure longrun, and verify reports. The plugin documentation entry point is [Plugin Documentation Index](ldb-plugin-docs-index.en.md).

## 1. Implement A Plugin

A plugin implements `net.xdob.vexra.ldb.LdbPlugin`. The minimal implementation declares a descriptor:

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

Descriptor fields:

| Field | Meaning | Recommendation |
| --- | --- | --- |
| `name` | Stable plugin name | Use kebab-case, for example `my-audit`. |
| `version` | Plugin version | Used for diagnostics and versionRange checks. |
| `order` | Execution order | Smaller numbers run earlier. |
| `failurePolicy` | Synchronous callback failure policy | Observability plugins should prefer `RECORD_AND_CONTINUE`. |
| `capabilities` | Capability declarations | Follow least privilege. |

Normal plugins do not need to override `unwrap()`. Only ordering, isolation, or governance wrappers should override it and return the real delegate so the LDB runtime can detect which lifecycle methods the real plugin implements.

## 2. Declare Capabilities

| Capability | Use case |
| --- | --- |
| `OBSERVE_WRITE` | Observe write events without mutating write batches. |
| `MUTATE_WRITE_BATCH` | Mutate write batches before commit. Required when capability enforcement is enabled. |
| `CHECKPOINT_HOOK` | Participate in checkpoint callbacks. |
| `METADATA_READ` | Read config views, properties, column-family metadata, or directory metadata. |

In compatibility mode, capabilities are mainly observable metadata. With `ldb.plugin.capability.enforcement=true`, plugins without `MUTATE_WRITE_BATCH` cannot mutate write batches, plugins without `METADATA_READ` cannot read context metadata, and plugins without `CHECKPOINT_HOOK` cannot participate in checkpoint callbacks.

## 2.1 Column Family id/name Constraints

Plugins that create or depend on column families must document the required id/name pairs before integration.

| Item | Requirement |
| --- | --- |
| id range | Reserve a stable local range for each plugin or business module. Avoid `1`, which is the default column family. |
| name format | Use stable kebab-case names with a plugin or module prefix, for example `sample-audit-events`. |
| registration | Register required column families in `configure(Options)` or document the caller-side `Options.addColumnFamily` requirement. |
| conflict handling | Treat id/name conflicts as startup errors. Do not silently reuse an id for a different semantic meaning. |
| deprecation | Deprecated column-family ids must not be reused. Keep the old id/name in upgrade notes until data is migrated or dropped by an explicit upper-layer migration. |

Recommended plugin declaration template:

| Field | Example |
| --- | --- |
| Plugin | `sample-audit` |
| Column family id | `2001` |
| Column family name | `sample-audit-events` |
| Purpose | Append-only audit event index |
| Deprecation policy | Do not reuse `2001`; add a new id for incompatible semantics |

## 3. Register A Provider

Production plugins should implement `net.xdob.vexra.ldb.LdbPluginProvider` and use ServiceLoader discovery:

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

Add this service file inside the jar:

```text
META-INF/services/net.xdob.vexra.ldb.LdbPluginProvider
```

The file content is the provider class name:

```text
com.example.MyAuditPluginProvider
```

## 4. Enable In longrun

Classpath providers require both discovery and explicit selection:

```properties
ldb.plugins=my-audit
ldb.plugin.discovery.enabled=true
```

Provider-private config uses the `ldb.plugin.<provider-name>.*` prefix:

```properties
ldb.plugin.my-audit.version=trial
ldb.plugin.my-audit.order=10
ldb.plugin.my-audit.versionRange=[1.0.0,2.0.0)
```

Config meanings:

| Config | Meaning |
| --- | --- |
| `ldb.plugin.<name>.version` | Provider-private version or marker; plugin-specific behavior decides how it is used. |
| `ldb.plugin.<name>.order` | longrun-side order override without requiring plugin setters. |
| `ldb.plugin.<name>.versionRange` | Requires provider `version()` to fall within the range; mismatch fails startup. |

External jar directories must be enabled explicitly:

```properties
ldb.plugins=my-audit
ldb.plugin.external.enabled=true
ldb.plugin.dir=/opt/ldb/plugins
```

Note: the external directory only discovers providers. The final enabled plugin list is always controlled by `ldb.plugins`. External directories are discovered through an independent child-first `URLClassLoader`; JDK classes and the `net.xdob.vexra.ldb` API package still come from the parent loader. longrun releases that loader when the managed plugin closes. This provides dependency isolation and handle governance, not a cross-process security sandbox.

## 5. Run The Built-In Sample

`ldb-longrun` ships `sample-audit`:

| File | Purpose |
| --- | --- |
| `LongRunSampleAuditPlugin.java` | Sample plugin implementation. |
| `LongRunSamplePluginProvider.java` | Sample ServiceLoader provider. |
| `plugin-sample.properties` | Sample longrun profile. |

Run:

```bash
./bin/longrun watch -c plugin-sample
```

Important config:

```properties
ldb.plugins=sample-audit
ldb.plugin.discovery.enabled=true
ldb.plugin.sample-audit.version=guide
ldb.plugin.sample-audit.order=5
```

The log should include:

```text
PLUGIN list=0:sample-audit:order=5
SUMMARY status=PASS ...
```

## 6. Governance Config

| Config | Default | Meaning |
| --- | --- | --- |
| `ldb.plugin.capability.enforcement` | `false` | Enforce capability boundaries. |
| `ldb.plugin.callbackTimeoutMillis` | `0` | Callback elapsed-time threshold; 0 disables timeout judgment. |
| `ldb.plugin.autoDisableOnTimeout` | `false` | Disable a plugin after timeout. |
| `ldb.plugin.autoDisableFailureThreshold` | `0` | Consecutive failure threshold; 0 disables it. |
| `ldb.plugin.async.enabled` | `false` | Run post-commit notifications asynchronously. |
| `ldb.plugin.async.queueCapacity` | `1024` | Async notification queue capacity. |
| `ldb.plugin.async.closeTimeoutMillis` | `30000` | close wait time for async tasks. |
| `ldb.plugin.maxTotalCallbackMillis` | `0` | Per-plugin cumulative callback elapsed-time budget; 0 disables it. Exceeding the budget marks the plugin degraded and skips later callbacks. |

Boundaries:

- `beforeWrite` always runs synchronously.
- `afterWrite` and `afterCheckpoint` can run asynchronously.
- Plugins are trusted in-process extensions, not a security sandbox; reports expose `ldb.plugin.sandbox=false`.
- Timeout does not interrupt currently running Java code; it records and affects later degradation.
- The cumulative callback budget also does not interrupt a running callback; it disables later callbacks after the current callback returns.

## 6.1 Compatibility Testkit

Before integration, plugin developers can call `LdbPluginCompatibility` for the minimum contract check:

```java
LdbPluginCompatibility.verifyProvider(provider, config).throwIfIncompatible();
LdbPluginCompatibility.verifyPlugin(plugin).throwIfIncompatible();
```

The check covers provider name/version, `create(config)` result, descriptor stability, failurePolicy, and basic capability validity. It does not open a database and does not replace longrun integration validation.

## 7. Test Recommendations

| Test type | Recommended coverage |
| --- | --- |
| Unit tests | descriptor, capability, provider name/version. |
| Config tests | `ldb.plugins`, `versionRange`, order override, external dir. |
| longrun integration tests | Start a profile and assert `PLUGIN list`, `PLUGIN stats`, and `SUMMARY`. |
| Compatibility tests | Legacy behavior remains unchanged when enforcement is disabled. |
| Governance tests | timeout, auto-disable, async close wait, queue reject. |
| Testkit tests | Use `LdbPluginCompatibility` to pin the minimum provider and descriptor contract. |

## 8. FAQ

| Question | Resolution |
| --- | --- |
| Provider is discovered but plugin does not run | Ensure `ldb.plugins` explicitly lists the provider name. |
| Classpath provider is not discovered | Check `ldb.plugin.discovery.enabled=true` and the ServiceLoader file. |
| External jar is not discovered | Check `ldb.plugin.external.enabled=true`, `ldb.plugin.dir`, and the jar service file. |
| versionRange fails startup | Align provider `version()` with the configured range. |
| Plugin fails after mutating a batch | Declare `MUTATE_WRITE_BATCH` when enforcement is enabled. |
