package net.xdob.vexra.ldb;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * LDB 插件描述信息，用于稳定暴露插件名称、版本、执行顺序、能力和失败策略。
 *
 * 描述信息只参与运行时观测和插件调度，不写入 WAL、MANIFEST 或 SST。
 */
public final class LdbPluginDescriptor {
  private final String name;
  private final String version;
  private final int order;
  private final LdbPluginFailurePolicy failurePolicy;
  private final Set<LdbPluginCapability> capabilities;

  public LdbPluginDescriptor(String name, String version, int order, LdbPluginFailurePolicy failurePolicy) {
    this(name, version, order, failurePolicy, Collections.<LdbPluginCapability>emptySet());
  }

  public LdbPluginDescriptor(String name, String version, int order, LdbPluginFailurePolicy failurePolicy,
                             LdbPluginCapability... capabilities) {
    this(name, version, order, failurePolicy,
        capabilities == null ? Collections.<LdbPluginCapability>emptySet() : Arrays.asList(capabilities));
  }

  public LdbPluginDescriptor(String name, String version, int order, LdbPluginFailurePolicy failurePolicy,
                             Iterable<LdbPluginCapability> capabilities) {
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalArgumentException("plugin name must not be empty");
    }
    this.name = name;
    this.version = version == null ? "" : version;
    this.order = order;
    this.failurePolicy = failurePolicy == null ? LdbPluginFailurePolicy.FAIL_FAST : failurePolicy;
    EnumSet<LdbPluginCapability> values = EnumSet.noneOf(LdbPluginCapability.class);
    if (capabilities != null) {
      for (LdbPluginCapability capability : capabilities) {
        if (capability != null) {
          values.add(capability);
        }
      }
    }
    this.capabilities = Collections.unmodifiableSet(values);
  }

  public static LdbPluginDescriptor of(String name) {
    return new LdbPluginDescriptor(name, "", 0, LdbPluginFailurePolicy.FAIL_FAST);
  }

  public String name() {
    return name;
  }

  public String version() {
    return version;
  }

  public int order() {
    return order;
  }

  public LdbPluginFailurePolicy failurePolicy() {
    return failurePolicy;
  }

  public Set<LdbPluginCapability> capabilities() {
    return capabilities;
  }
}
