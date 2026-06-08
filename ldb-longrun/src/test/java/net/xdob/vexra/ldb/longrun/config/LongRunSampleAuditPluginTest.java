package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.LdbPluginCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunSampleAuditPluginTest {
  @Test
  void exposesDescriptorAndCountsCallbacks() {
    LongRunSampleAuditPlugin plugin = new LongRunSampleAuditPlugin("unit-test");

    plugin.beforeWrite(null);
    plugin.afterWrite(null);

    assertEquals("sample-audit", plugin.descriptor().name());
    assertEquals("unit-test", plugin.descriptor().version());
    assertEquals(50, plugin.descriptor().order());
    assertTrue(plugin.descriptor().capabilities().contains(LdbPluginCapability.OBSERVE_WRITE));
    assertTrue(plugin.descriptor().capabilities().contains(LdbPluginCapability.METADATA_READ));
    assertEquals(1, plugin.beforeWrites());
    assertEquals(1, plugin.afterWrites());
  }
}
