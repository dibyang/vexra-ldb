package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginCapability;
import net.xdob.vexra.ldb.LdbPluginDescriptor;
import net.xdob.vexra.ldb.LdbPluginFailurePolicy;
import net.xdob.vexra.ldb.WriteEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * longrun 插件开发样例。
 *
 * <p>该插件只观察写入事件，不修改用户写批次，适合作为 provider、capability、
 * order 和 longrun 配置串联的最小开发模板。</p>
 */
public final class LongRunSampleAuditPlugin implements LdbPlugin {
  private final String version;
  private final AtomicLong beforeWrites = new AtomicLong();
  private final AtomicLong afterWrites = new AtomicLong();

  /**
   * 创建使用默认版本号的样例插件。
   */
  public LongRunSampleAuditPlugin() {
    this("longrun-sample");
  }

  /**
   * 创建使用指定版本号的样例插件。
   *
   * @param version 暴露到插件描述和 longrun 报告中的样例版本
   */
  public LongRunSampleAuditPlugin(String version) {
    this.version = version == null || version.trim().isEmpty() ? "longrun-sample" : version.trim();
  }

  @Override
  public LdbPluginDescriptor descriptor() {
    return new LdbPluginDescriptor(
        "sample-audit",
        version,
        50,
        LdbPluginFailurePolicy.RECORD_AND_CONTINUE,
        LdbPluginCapability.OBSERVE_WRITE,
        LdbPluginCapability.METADATA_READ);
  }

  @Override
  public void beforeWrite(WriteEvent event) {
    beforeWrites.incrementAndGet();
  }

  @Override
  public void afterWrite(WriteEvent event) {
    afterWrites.incrementAndGet();
  }

  public long beforeWrites() {
    return beforeWrites.get();
  }

  public long afterWrites() {
    return afterWrites.get();
  }
}
