package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginDescriptor;
import net.xdob.vexra.ldb.LdbPluginFailurePolicy;
import net.xdob.vexra.ldb.LdbPluginProvider;

import java.util.Map;

public final class TestLongRunPluginProvider implements LdbPluginProvider {
  @Override
  public String name() {
    return "test-provider";
  }

  @Override
  public String version() {
    return "1.2.0";
  }

  @Override
  public LdbPlugin create(Map<String, String> config) {
    return new ProviderPlugin(config.get("version"));
  }

  private static final class ProviderPlugin implements LdbPlugin {
    private final String version;

    private ProviderPlugin(String version) {
      this.version = version == null ? "test" : version;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      return new LdbPluginDescriptor("test-provider-plugin", version, 0, LdbPluginFailurePolicy.FAIL_FAST);
    }
  }
}
