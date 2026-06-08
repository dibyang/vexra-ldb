package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginContext;
import net.xdob.vexra.ldb.LdbPluginDescriptor;
import net.xdob.vexra.ldb.LdbPluginLoader;
import net.xdob.vexra.ldb.LdbPluginProvider;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.WriteEvent;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * longrun 插件解析器。
 *
 * <p>解析顺序为：内置名称、ServiceLoader provider 名称、classpath 显式类名。
 */
public final class LongRunPluginResolver {
  /**
   * 根据 longrun 配置创建插件实例。
   *
   * @param config longrun 配置
   * @return 插件实例列表
   */
  public List<LdbPlugin> resolve(LongRunConfig config) {
    List<LdbPlugin> plugins = new ArrayList<>();
    ProviderRegistry registry = providersByName(config);
    try {
      for (String name : config.pluginNames()) {
        if (!config.pluginEnabled(name)) {
          continue;
        }
        LdbPlugin plugin = createPlugin(name, registry, config.pluginConfig(name));
        if (!config.pluginEnabled(plugin.descriptor().name())) {
          closePluginQuietly(plugin);
          continue;
        }
        plugins.add(applyOrderOverride(config, name, plugin));
      }
      registry.closeUnusedHandles();
      return plugins;
    } catch (RuntimeException e) {
      for (LdbPlugin plugin : plugins) {
        closePluginQuietly(plugin);
      }
      registry.closeUnusedHandles();
      throw e;
    }
  }

  /**
   * 返回可发现 provider 名称，用于启动诊断。
   */
  public List<String> discoveredProviderNames() {
    return new ArrayList<>(providersByName().keySet());
  }

  private static LdbPlugin createPlugin(String name, ProviderRegistry registry,
                                        Map<String, String> config) {
    if ("diagnostic".equals(name) || "longrun-diagnostic".equals(name)) {
      return new LongRunDiagnosticPlugin();
    }
    ProviderRegistration provider = registry.get(name);
    if (provider != null) {
      ensureProviderVersion(name, provider, config.get("versionRange"));
      LdbPlugin plugin = provider.provider.create(config);
      return provider.closeHandle == null ? plugin
          : new ManagedLongRunPlugin(plugin, provider.closeHandle.acquire(), provider.source, provider.isolated);
    }
    try {
      Class<?> type = Class.forName(name);
      if (!LdbPlugin.class.isAssignableFrom(type)) {
        throw new IllegalArgumentException("configured plugin is not an LdbPlugin: " + name);
      }
      return (LdbPlugin) type.getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("configured plugin not found: " + name, e);
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("failed to construct configured plugin: " + name, e);
    }
  }

  private static ProviderRegistry providersByName() {
    ProviderRegistry providers = new ProviderRegistry();
    for (LdbPluginProvider provider : LdbPluginLoader.discoverProviders()) {
      providers.put(new ProviderRegistration(provider, null, false, "classpath"));
    }
    return providers;
  }

  private static ProviderRegistry providersByName(LongRunConfig config) {
    ProviderRegistry providers = new ProviderRegistry();
    if (config.pluginDiscoveryEnabled()) {
      mergeProviders(providers, LdbPluginLoader.discoverProviders(), null, false, "classpath");
    }
    if (config.pluginExternalEnabled()) {
      LdbPluginLoader.Discovery discovery = LdbPluginLoader.discoverProvidersWithHandle(config.pluginDir());
      SharedCloseHandle closeHandle = discovery.isolated() && !discovery.providers().isEmpty()
          ? new SharedCloseHandle(discovery)
          : null;
      mergeProviders(providers, discovery.providers(), closeHandle, discovery.isolated(), discovery.source());
      if (closeHandle == null) {
        closeQuietly(discovery);
      }
    }
    return providers;
  }

  private static void mergeProviders(ProviderRegistry providers, List<LdbPluginProvider> discovered,
                                     SharedCloseHandle closeHandle, boolean isolated, String source) {
    for (LdbPluginProvider provider : discovered) {
      providers.put(new ProviderRegistration(provider, closeHandle, isolated, source));
    }
  }

  private static LdbPlugin applyOrderOverride(LongRunConfig config, String configuredName, LdbPlugin plugin) {
    Integer order = config.pluginOrder(configuredName);
    if (order == null) {
      order = config.pluginOrder(plugin.descriptor().name());
    }
    return order == null ? plugin : new OrderedLongRunPlugin(plugin, order);
  }

  private static void ensureProviderVersion(String name, ProviderRegistration provider, String range) {
    if (range == null || range.trim().isEmpty()) {
      return;
    }
    if (!VersionRange.parse(range).contains(provider.provider.version())) {
      throw new IllegalArgumentException("configured plugin provider version mismatch: name=" + name
          + ",version=" + provider.provider.version() + ",range=" + range);
    }
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (IOException ignored) {
      // discovery close failure must not hide the real plugin resolution error path
    }
  }

  private static void closePluginQuietly(LdbPlugin plugin) {
    if (plugin == null) {
      return;
    }
    try {
      plugin.close();
    } catch (RuntimeException ignored) {
      // keep the original resolution failure visible to callers
    }
  }

  private static final class ProviderRegistry {
    private final Map<String, ProviderRegistration> providers = new LinkedHashMap<>();
    private final List<SharedCloseHandle> handles = new ArrayList<>();

    private void put(ProviderRegistration registration) {
      ProviderRegistration previous = providers.put(registration.provider.name(), registration);
      if (previous != null) {
        throw new IllegalStateException("duplicate LDB plugin provider name: " + registration.provider.name());
      }
      if (registration.closeHandle != null && !handles.contains(registration.closeHandle)) {
        handles.add(registration.closeHandle);
      }
    }

    private ProviderRegistration get(String name) {
      return providers.get(name);
    }

    private List<String> keySet() {
      return new ArrayList<>(providers.keySet());
    }

    private void closeUnusedHandles() {
      for (SharedCloseHandle handle : handles) {
        handle.closeIfUnused();
      }
    }
  }

  private static final class ProviderRegistration {
    private final LdbPluginProvider provider;
    private final SharedCloseHandle closeHandle;
    private final boolean isolated;
    private final String source;

    private ProviderRegistration(LdbPluginProvider provider, SharedCloseHandle closeHandle,
                                 boolean isolated, String source) {
      this.provider = provider;
      this.closeHandle = closeHandle;
      this.isolated = isolated;
      this.source = source;
    }
  }

  private static final class SharedCloseHandle {
    private final Closeable delegate;
    private final AtomicInteger references = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    private SharedCloseHandle(Closeable delegate) {
      this.delegate = delegate;
    }

    private Closeable acquire() {
      references.incrementAndGet();
      return new Closeable() {
        @Override
        public void close() throws IOException {
          if (references.decrementAndGet() <= 0) {
            closeOnce();
          }
        }
      };
    }

    private void closeIfUnused() {
      if (references.get() == 0) {
        closeQuietly(new Closeable() {
          @Override
          public void close() throws IOException {
            closeOnce();
          }
        });
      }
    }

    private void closeOnce() throws IOException {
      if (closed.compareAndSet(false, true)) {
        delegate.close();
      }
    }
  }

  private static final class ManagedLongRunPlugin implements LdbPlugin {
    private final LdbPlugin delegate;
    private final Closeable closeHandle;
    private final String source;
    private final boolean isolated;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ManagedLongRunPlugin(LdbPlugin delegate, Closeable closeHandle, String source, boolean isolated) {
      this.delegate = delegate;
      this.closeHandle = closeHandle;
      this.source = source;
      this.isolated = isolated;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      return delegate.descriptor();
    }

    @Override
    public LdbPlugin unwrap() {
      return delegate.unwrap();
    }

    @Override
    public void configure(Options options) {
      delegate.configure(options);
    }

    @Override
    public void onOpen(LdbPluginContext context) {
      delegate.onOpen(context);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      delegate.beforeWrite(event);
    }

    @Override
    public void afterWrite(WriteEvent event) {
      delegate.afterWrite(event);
    }

    @Override
    public void beforeCheckpoint(File targetDir) {
      delegate.beforeCheckpoint(targetDir);
    }

    @Override
    public void afterCheckpoint(File targetDir) {
      delegate.afterCheckpoint(targetDir);
    }

    @Override
    public void beforeClose() {
      delegate.beforeClose();
    }

    @Override
    public void close() {
      try {
        delegate.close();
      } finally {
        if (closed.compareAndSet(false, true)) {
          closeQuietly(closeHandle);
        }
      }
    }

    @Override
    public String toString() {
      return "ManagedLongRunPlugin{isolated=" + isolated + ",source=" + source
          + ",delegate=" + delegate.getClass().getName() + '}';
    }
  }

  private static final class OrderedLongRunPlugin implements LdbPlugin {
    private final LdbPlugin delegate;
    private final int order;

    private OrderedLongRunPlugin(LdbPlugin delegate, int order) {
      this.delegate = delegate;
      this.order = order;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      LdbPluginDescriptor descriptor = delegate.descriptor();
      return new LdbPluginDescriptor(descriptor.name(), descriptor.version(), order,
          descriptor.failurePolicy(), descriptor.capabilities());
    }

    @Override
    public LdbPlugin unwrap() {
      return delegate.unwrap();
    }

    @Override
    public void configure(Options options) {
      delegate.configure(options);
    }

    @Override
    public void onOpen(LdbPluginContext context) {
      delegate.onOpen(context);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      delegate.beforeWrite(event);
    }

    @Override
    public void afterWrite(WriteEvent event) {
      delegate.afterWrite(event);
    }

    @Override
    public void beforeCheckpoint(File targetDir) {
      delegate.beforeCheckpoint(targetDir);
    }

    @Override
    public void afterCheckpoint(File targetDir) {
      delegate.afterCheckpoint(targetDir);
    }

    @Override
    public void beforeClose() {
      delegate.beforeClose();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static final class VersionRange {
    private final String lower;
    private final boolean lowerInclusive;
    private final String upper;
    private final boolean upperInclusive;

    private VersionRange(String lower, boolean lowerInclusive, String upper, boolean upperInclusive) {
      this.lower = lower;
      this.lowerInclusive = lowerInclusive;
      this.upper = upper;
      this.upperInclusive = upperInclusive;
    }

    private static VersionRange parse(String text) {
      String value = text.trim();
      if (!(value.startsWith("[") || value.startsWith("("))) {
        return new VersionRange(value, true, value, true);
      }
      if (!(value.endsWith("]") || value.endsWith(")"))) {
        throw new IllegalArgumentException("invalid plugin version range: " + text);
      }
      String body = value.substring(1, value.length() - 1);
      String[] parts = body.split(",", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("invalid plugin version range: " + text);
      }
      return new VersionRange(parts[0].trim(), value.startsWith("["),
          parts[1].trim(), value.endsWith("]"));
    }

    private boolean contains(String version) {
      if (lower != null && !lower.isEmpty()) {
        int compared = compareVersions(version, lower);
        if (compared < 0 || (compared == 0 && !lowerInclusive)) {
          return false;
        }
      }
      if (upper != null && !upper.isEmpty()) {
        int compared = compareVersions(version, upper);
        if (compared > 0 || (compared == 0 && !upperInclusive)) {
          return false;
        }
      }
      return true;
    }

    private static int compareVersions(String left, String right) {
      String[] leftParts = left.split("[.-]");
      String[] rightParts = right.split("[.-]");
      int length = Math.max(leftParts.length, rightParts.length);
      for (int i = 0; i < length; i++) {
        String leftPart = i < leftParts.length ? leftParts[i] : "0";
        String rightPart = i < rightParts.length ? rightParts[i] : "0";
        int compared = compareVersionPart(leftPart, rightPart);
        if (compared != 0) {
          return compared;
        }
      }
      return 0;
    }

    private static int compareVersionPart(String left, String right) {
      try {
        return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
      } catch (NumberFormatException ignored) {
        return left.compareTo(right);
      }
    }
  }
}
