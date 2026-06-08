package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginProvider;

import java.util.Map;

/**
 * longrun 样例插件 provider。
 *
 * <p>该 provider 通过 ServiceLoader 暴露稳定名称 {@code sample-audit}，
 * 用于演示外部调用方如何用 {@code ldb.plugins} 选择插件，并用
 * {@code ldb.plugin.sample-audit.*} 传入插件私有配置。</p>
 */
public final class LongRunSamplePluginProvider implements LdbPluginProvider {
  @Override
  public String name() {
    return "sample-audit";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public LdbPlugin create(Map<String, String> config) {
    String version = config == null ? null : config.get("version");
    return new LongRunSampleAuditPlugin(version);
  }
}
