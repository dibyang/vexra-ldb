package net.xdob.vexra.ldb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LDB 插件兼容性检查工具。
 *
 * <p>该工具面向插件开发者和接入方，用于在正式注册插件前做轻量契约检查。
 * 它不打开数据库、不执行真实写入，只验证 provider、descriptor、capability 和
 * failurePolicy 是否满足 LDB 插件运行时的最低兼容要求。
 */
public final class LdbPluginCompatibility {
  private LdbPluginCompatibility() {
  }

  /**
   * 检查 provider 是否能创建出满足最低契约的插件。
   *
   * @param provider 插件 provider
   * @param config 传给 provider 的私有配置
   * @return 兼容性检查报告
   */
  public static Report verifyProvider(LdbPluginProvider provider, Map<String, String> config) {
    List<String> failures = new ArrayList<>();
    if (provider == null) {
      failures.add("provider must not be null");
      return new Report(false, failures);
    }
    requireText(failures, "provider.name", provider.name());
    requireText(failures, "provider.version", provider.version());
    try {
      LdbPlugin plugin = provider.create(config == null
          ? Collections.<String, String>emptyMap()
          : Collections.unmodifiableMap(config));
      failures.addAll(verifyPlugin(plugin).failures());
    } catch (RuntimeException e) {
      failures.add("provider.create failed: " + e.getClass().getName() + ':' + safeMessage(e));
    }
    return new Report(failures.isEmpty(), failures);
  }

  /**
   * 检查插件 descriptor 是否稳定且满足最低契约。
   *
   * @param plugin 插件实例
   * @return 兼容性检查报告
   */
  public static Report verifyPlugin(LdbPlugin plugin) {
    List<String> failures = new ArrayList<>();
    if (plugin == null) {
      failures.add("plugin must not be null");
      return new Report(false, failures);
    }
    LdbPluginDescriptor first = descriptor(failures, plugin, "first");
    LdbPluginDescriptor second = descriptor(failures, plugin, "second");
    if (first != null) {
      verifyDescriptor(failures, first);
    }
    if (first != null && second != null) {
      if (!first.name().equals(second.name())) {
        failures.add("descriptor.name must be stable");
      }
      if (!first.version().equals(second.version())) {
        failures.add("descriptor.version must be stable");
      }
      if (first.order() != second.order()) {
        failures.add("descriptor.order must be stable");
      }
      if (first.failurePolicy() != second.failurePolicy()) {
        failures.add("descriptor.failurePolicy must be stable");
      }
      if (!first.capabilities().equals(second.capabilities())) {
        failures.add("descriptor.capabilities must be stable");
      }
    }
    return new Report(failures.isEmpty(), failures);
  }

  private static LdbPluginDescriptor descriptor(List<String> failures, LdbPlugin plugin, String phase) {
    try {
      LdbPluginDescriptor descriptor = plugin.descriptor();
      if (descriptor == null) {
        failures.add("descriptor must not be null: " + phase);
      }
      return descriptor;
    } catch (RuntimeException e) {
      failures.add("descriptor failed at " + phase + ": " + e.getClass().getName() + ':' + safeMessage(e));
      return null;
    }
  }

  private static void verifyDescriptor(List<String> failures, LdbPluginDescriptor descriptor) {
    requireText(failures, "descriptor.name", descriptor.name());
    if (descriptor.version() == null) {
      failures.add("descriptor.version must not be null");
    }
    if (descriptor.failurePolicy() == null) {
      failures.add("descriptor.failurePolicy must not be null");
    }
    if (descriptor.capabilities() == null) {
      failures.add("descriptor.capabilities must not be null");
    }
  }

  private static void requireText(List<String> failures, String name, String value) {
    if (value == null || value.trim().isEmpty()) {
      failures.add(name + " must not be empty");
    }
  }

  private static String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    return message == null ? "" : message.replace(',', ';');
  }

  /**
   * 插件兼容性检查报告。
   *
   * <p>报告只包含通过状态和失败原因，便于不同测试框架直接转换为断言信息。
   */
  public static final class Report {
    private final boolean compatible;
    private final List<String> failures;

    private Report(boolean compatible, List<String> failures) {
      this.compatible = compatible;
      this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
    }

    /**
     * 返回是否通过最低兼容性检查。
     */
    public boolean compatible() {
      return compatible;
    }

    /**
     * 返回失败原因快照。
     */
    public List<String> failures() {
      return failures;
    }

    /**
     * 未通过时抛出 IllegalArgumentException，方便测试中一行调用。
     */
    public void throwIfIncompatible() {
      if (!compatible) {
        throw new IllegalArgumentException(String.join("; ", failures));
      }
    }
  }
}
