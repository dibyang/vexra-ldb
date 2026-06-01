package net.xdob.vexra.ldb;

/**
 * LDB 插件描述信息，用于稳定暴露插件名称、版本、执行顺序和失败策略。
 *
 * 描述信息只参与运行时观测和后续插件调度评估，不写入 WAL、MANIFEST 或 SST。
 */
public final class LdbPluginDescriptor {
  private final String name;
  private final String version;
  private final int order;
  private final LdbPluginFailurePolicy failurePolicy;

  public LdbPluginDescriptor(String name, String version, int order, LdbPluginFailurePolicy failurePolicy) {
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalArgumentException("plugin name must not be empty");
    }
    this.name = name;
    this.version = version == null ? "" : version;
    this.order = order;
    this.failurePolicy = failurePolicy == null ? LdbPluginFailurePolicy.FAIL_FAST : failurePolicy;
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
}
