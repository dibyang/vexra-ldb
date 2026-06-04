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
        "-e", "true",
        "-z", "1s",
        "-l", "3",
        "-f", "false",
        "-g", "7s",
        "-j", "copy-based",
        "-b", "65536",
        "-h", "9",
        "-L", "30",
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
    assertEquals("true", values.get("crash.enabled"));
    assertEquals("1s", values.get("crash.interval"));
    assertEquals("3", values.get("crash.cycles"));
    assertEquals("false", values.get("fault.enabled"));
    assertEquals("7s", values.get("fault.interval"));
    assertEquals("copy-based", values.get("fault.kinds"));
    assertEquals("65536", values.get("fault.maxBytes"));
    assertEquals("9", values.get("fault.retainedCopies"));
    assertEquals("30", values.get("limits.maxDbSizeGb"));
    assertEquals("smoke", values.get("profile"));
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
  void loadsPerformanceProfilesWithDifferentDurabilityMode() throws Exception {
    LongRunConfig performance = LongRunConfig.load(new String[] {
        "-c", "performance"
    });
    LongRunConfig durable = LongRunConfig.load(new String[] {
        "-c", "performance-durable"
    });

    assertEquals("performance", performance.runName());
    assertEquals(false, performance.syncWrites());
    assertEquals("performance-durable", durable.runName());
    assertEquals(true, durable.syncWrites());
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
