package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginCapability;
import net.xdob.vexra.ldb.LdbPluginDescriptor;
import net.xdob.vexra.ldb.LdbPluginFailurePolicy;
import net.xdob.vexra.ldb.WriteEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * longrun 内置轻量诊断插件。
 *
 * <p>该插件只记录回调次数，用于验证 longrun 插件配置、排序和报告链路。
 */
public final class LongRunDiagnosticPlugin implements LdbPlugin {
  private final AtomicLong beforeWrites = new AtomicLong();
  private final AtomicLong afterWrites = new AtomicLong();

  @Override
  public LdbPluginDescriptor descriptor() {
    return new LdbPluginDescriptor(
        "diagnostic",
        "longrun",
        0,
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
