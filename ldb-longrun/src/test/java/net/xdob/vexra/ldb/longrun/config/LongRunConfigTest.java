package net.xdob.vexra.ldb.longrun.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LongRunConfigTest {
  @Test
  void parsesCliOverrides() {
    Map<String, String> values = LongRunConfig.parseCli(new String[] {
        "--config", "config/smoke.properties",
        "--run.duration=1s",
        "--run.instance", "a"
    });

    assertEquals("config/smoke.properties", values.get("config"));
    assertEquals("1s", values.get("run.duration"));
    assertEquals("a", values.get("run.instance"));
  }

  @Test
  void parsesConfigShorthand() {
    Map<String, String> values = LongRunConfig.parseCli(new String[] {
        "-c", "smoke",
        "--run.instance", "a"
    });

    assertEquals("smoke", values.get("config"));
    assertEquals("a", values.get("run.instance"));
  }

  @Test
  void parsesCommonOptionShorthands() {
    Map<String, String> values = LongRunConfig.parseCli(new String[] {
        "-i", "worker-a",
        "-I", "smoke-a",
        "-w", "work/cli",
        "-W", "work/cli-run",
        "-d", "20m",
        "-r", "true",
        "-k", "123",
        "-m", "mixed",
        "-a", "100",
        "-S", "false",
        "-q", "0.55",
        "-y", "0.35",
        "-x", "0.1",
        "-t", "12s",
        "-u", "20s",
        "-o", "30s",
        "-O", "false",
        "-e", "true",
        "-z", "1s",
        "-l", "3",
        "-f", "false",
        "-g", "7s",
        "-j", "copy-based",
        "-b", "65536",
        "-h", "9",
        "-L", "30",
        "-M", "512",
        "-P", "diagnostic,com.example.Plugin",
        "-p", "smoke"
    });

    assertEquals("worker-a", values.get("instance"));
    assertEquals("smoke-a", values.get("run.instance"));
    assertEquals("work/cli", values.get("workDir"));
    assertEquals("work/cli-run", values.get("run.workDir"));
    assertEquals("20m", values.get("run.duration"));
    assertEquals("true", values.get("resume"));
    assertEquals("123", values.get("workload.keySpace"));
    assertEquals("mixed", values.get("workload.mode"));
    assertEquals("100", values.get("workload.commitEveryOps"));
    assertEquals("false", values.get("workload.syncWrites"));
    assertEquals("0.55", values.get("workload.readRatio"));
    assertEquals("0.35", values.get("workload.writeRatio"));
    assertEquals("0.1", values.get("workload.removeRatio"));
    assertEquals("12s", values.get("metrics.interval"));
    assertEquals("20s", values.get("state.interval"));
    assertEquals("30s", values.get("check.reopenInterval"));
    assertEquals("false", values.get("check.finalVerify"));
    assertEquals("true", values.get("crash.enabled"));
    assertEquals("1s", values.get("crash.interval"));
    assertEquals("3", values.get("crash.cycles"));
    assertEquals("false", values.get("fault.enabled"));
    assertEquals("7s", values.get("fault.interval"));
    assertEquals("copy-based", values.get("fault.kinds"));
    assertEquals("65536", values.get("fault.maxBytes"));
    assertEquals("9", values.get("fault.retainedCopies"));
    assertEquals("30", values.get("limits.maxDbSizeGb"));
    assertEquals("512", values.get("ldb.writeBufferSizeMb"));
    assertEquals("diagnostic,com.example.Plugin", values.get("ldb.plugins"));
    assertEquals("smoke", values.get("profile"));
  }

  @Test
  void parsesPluginConfig() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "diagnostic,provider-a",
        "--ldb.plugin.discovery.enabled=true",
        "--ldb.plugin.diagnostic.enabled=false",
        "--ldb.plugin.provider-a.sampleMillis=1000",
        "--ldb.plugin.provider-a.order=-5",
        "--ldb.plugin.provider-a.versionRange=[1.0.0,2.0.0)",
        "--ldb.plugin.capability.enforcement=true",
        "--ldb.plugin.callbackTimeoutMillis=7",
        "--ldb.plugin.autoDisableOnTimeout=true",
        "--ldb.plugin.autoDisableFailureThreshold=3",
        "--ldb.groupCommit.enabled=true",
        "--ldb.groupCommit.maxDelayNanos=123456",
        "--ldb.groupCommit.maxBatchBytes=65536",
        "--ldb.plugin.async.enabled=true",
        "--ldb.plugin.async.queueCapacity=8",
        "--ldb.plugin.async.closeTimeoutMillis=9",
        "--ldb.plugin.maxTotalCallbackMillis=10",
        "--ldb.plugin.external.enabled=true",
        "--ldb.plugin.dir=plugins"
    });

    assertEquals("diagnostic", config.pluginNames().get(0));
    assertEquals("provider-a", config.pluginNames().get(1));
    assertEquals(false, config.pluginEnabled("diagnostic"));
    assertEquals(true, config.pluginDiscoveryEnabled());
    assertEquals("1000", config.pluginConfig("provider-a").get("sampleMillis"));
    assertEquals(Integer.valueOf(-5), config.pluginOrder("provider-a"));
    assertEquals("[1.0.0,2.0.0)", config.pluginVersionRange("provider-a"));
    assertEquals(true, config.pluginCapabilityEnforcement());
    assertEquals(7L, config.pluginCallbackTimeoutMillis());
    assertEquals(true, config.pluginAutoDisableOnTimeout());
    assertEquals(3, config.pluginAutoDisableFailureThreshold());
    assertEquals(true, config.ldbGroupCommitEnabled());
    assertEquals(123456L, config.ldbGroupCommitMaxDelayNanos());
    assertEquals(65536L, config.ldbGroupCommitMaxBatchBytes());
    assertEquals(true, config.pluginAsyncEnabled());
    assertEquals(8, config.pluginAsyncQueueCapacity());
    assertEquals(9L, config.pluginAsyncCloseTimeoutMillis());
    assertEquals(10L, config.pluginMaxTotalCallbackMillis());
    assertEquals(true, config.pluginExternalEnabled());
    assertEquals("plugins", config.pluginDir().getPath());
  }

  @Test
  void loadsClasspathProfileAndCliOverride() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties",
        "--run.duration=1s",
        "--run.instance=test-a"
    });

    assertEquals("smoke", config.runName());
    assertEquals("test-a", config.instance());
    assertEquals(1000L, config.durationMillis());
    assertEquals(true, config.finalVerifyEnabled());
  }

  @Test
  void loadsProfileByShorthandName() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "--run.duration=1s"
    });

    assertEquals("smoke", config.runName());
    assertEquals(1000L, config.durationMillis());
  }

  @Test
  void loadsProductionGateProfile() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "production-gate",
        "--run.duration=1s"
    });

    assertEquals("production-gate", config.runName());
    assertEquals(true, config.finalVerifyEnabled());
    assertEquals(true, config.syncWrites());
    assertEquals(true, config.ldbGroupCommitEnabled());
    assertEquals(1000L, config.durationMillis());
  }

  @Test
  void loadsPerformanceProfilesWithDifferentDurabilityMode() throws Exception {
    LongRunConfig performance = LongRunConfig.load(new String[] {
        "-c", "performance"
    });
    LongRunConfig durable = LongRunConfig.load(new String[] {
        "-c", "performance-durable"
    });

    assertEquals("performance", performance.runName());
    assertEquals(false, performance.syncWrites());
    assertEquals(false, performance.finalVerifyEnabled());
    assertEquals(512L, performance.ldbWriteBufferSizeMb());
    assertEquals(false, performance.ldbGroupCommitEnabled());
    assertEquals(200000L, performance.ldbGroupCommitMaxDelayNanos());
    assertEquals(1048576L, performance.ldbGroupCommitMaxBatchBytes());
    assertEquals("performance-durable", durable.runName());
    assertEquals(true, durable.syncWrites());
    assertEquals(false, durable.finalVerifyEnabled());
    assertEquals(512L, durable.ldbWriteBufferSizeMb());
  }

  @Test
  void loadsSpecializedPerformanceProfiles() throws Exception {
    assertEquals("performance-write", LongRunConfig.load(new String[] {
        "-c", "performance-write"
    }).runName());
    assertEquals("performance-read", LongRunConfig.load(new String[] {
        "-c", "performance-read"
    }).runName());
    assertEquals("performance-large-value", LongRunConfig.load(new String[] {
        "-c", "performance-large-value"
    }).runName());
  }

  @Test
  void defaultProfileInstanceUsesTestType() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties"
    });

    assertEquals("smoke", config.instance());
  }

  @Test
  void instanceAliasOverridesRunInstance() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties",
        "--instance", "smoke-a"
    });

    assertEquals("smoke-a", config.instance());
  }

  @Test
  void rejectsInvalidArgument() {
    assertThrows(IllegalArgumentException.class, () -> LongRunConfig.parseCli(new String[] {"run.duration=1s"}));
  }
}
