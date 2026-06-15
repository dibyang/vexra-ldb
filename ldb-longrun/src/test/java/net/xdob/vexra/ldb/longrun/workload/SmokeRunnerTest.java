package net.xdob.vexra.ldb.longrun.workload;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginDescriptor;
import net.xdob.vexra.ldb.LdbPluginFailurePolicy;
import net.xdob.vexra.ldb.longrun.config.LongRunConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SmokeRunnerTest {
  @Test
  void runsShortSmokeAndPersistsState() throws Exception {
    File workDir = Files.createTempDirectory("longrun-smoke").toFile();
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties",
        "--run.duration=50",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64"
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode = new SmokeRunner().run(config, new PrintStream(out, true, StandardCharsets.UTF_8.name()));

    assertTrue(exitCode == 0);
    assertTrue(new File(workDir, "state/committed-state.properties").isFile());
    assertTrue(new File(workDir, "metrics/ops.csv").isFile());
    assertTrue(new File(workDir, "state/resource.properties").isFile());
    assertTrue(new File(workDir, "state/run.properties").isFile());
    assertTrue(new File(workDir, "report/summary.md").isFile());
    assertTrue(new File(workDir, "report/summary.properties").isFile());
    String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(output.contains("START run=smoke"));
    assertTrue(output.contains("CONFIG workload.keySpace=100"));
    assertTrue(output.contains("CONFIG check.finalVerify=true"));
    assertTrue(output.contains("CONFIG ldb.writeBufferSizeMb=64"));
    assertTrue(output.contains("CONFIG ldb.groupCommit.enabled=false"));
    assertTrue(output.contains("CONFIG ldb.groupCommit.maxDelayNanos=200000"));
    assertTrue(output.contains("CONFIG ldb.groupCommit.maxBatchBytes=1048576"));
    assertTrue(output.contains("CONFIG ldb.plugins="));
    assertTrue(output.contains("PLUGIN list="));
    assertTrue(output.contains("PLUGIN stats="));
    assertTrue(output.contains("PLUGIN executionPolicy="));
    assertTrue(output.contains("PLUGIN asyncStats="));
    assertTrue(output.contains("PLUGIN degraded=false"));
    assertTrue(output.contains("PLUGIN disabled="));
    assertTrue(output.contains("PLUGIN sandbox=false"));
    assertTrue(output.contains("CONFIG ldb.plugin.capability.enforcement=false"));
    assertTrue(output.contains("CONFIG ldb.plugin.callbackTimeoutMillis=0"));
    assertTrue(output.contains("CONFIG ldb.plugin.async.enabled=false"));
    assertTrue(output.contains("CONFIG ldb.plugin.maxTotalCallbackMillis=0"));
    assertTrue(output.contains("CONFIG ldb.plugin.external.enabled=false"));
    assertTrue(output.contains("CONFIG ldb.plugin.dir="));
    assertTrue(output.contains("PROGRESS timeMillis="));
    assertTrue(output.contains("progressPercent="));
    assertTrue(output.contains("windowOpsPerSecond="));
    assertTrue(output.contains("avgOpsPerSecond="));
    assertTrue(output.contains("minOpsPerSecond="));
    assertTrue(output.contains("maxOpsPerSecond="));
    assertTrue(output.contains("ldbWriteSlowdownCount="));
    assertTrue(output.contains("ldbCompactionRunCount="));
    assertTrue(output.contains("RESULT phase=workload"));
    assertTrue(output.contains("FINAL phase=verify enabled=true"));
    assertTrue(output.contains("FINAL PROGRESS phase=verify"));
    assertTrue(output.contains("verified="));
    assertTrue(output.contains("total="));
    assertTrue(output.contains("SUMMARY status="));
    assertTrue(output.contains("warmupSamples="));
    assertTrue(output.contains("measuredSamples="));
    assertTrue(output.contains("p50OpsPerSecond="));
    assertTrue(output.contains("workloadSyncWrites=true"));
    assertTrue(output.contains("ldbGroupCommitEnabled=false"));
    assertTrue(output.contains("ldbPluginAsyncEnabled=false"));
    assertTrue(output.contains("PASS smoke"));
  }

  @Test
  void runsWithDiagnosticPluginAndReportsPluginState() throws Exception {
    File workDir = Files.createTempDirectory("longrun-plugin").toFile();
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties",
        "--run.duration=50",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--metrics.interval=20",
        "-P", "diagnostic"
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode = new SmokeRunner().run(config, new PrintStream(out, true, StandardCharsets.UTF_8.name()));

    Properties summary = new Properties();
    try (FileInputStream in = new FileInputStream(new File(workDir, "report/summary.properties"))) {
      summary.load(in);
    }
    Properties runState = new Properties();
    try (FileInputStream in = new FileInputStream(new File(workDir, "state/run.properties"))) {
      runState.load(in);
    }
    String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(exitCode == 0);
    assertTrue(output.contains("PLUGIN list=0:diagnostic:order=0:capabilities=METADATA_READ|OBSERVE_WRITE"));
    assertTrue(output.contains("CONFIG ldb.plugins=diagnostic"));
    assertTrue(summary.getProperty("plugins").contains("diagnostic"));
    assertTrue(summary.getProperty("pluginStats").contains("count=1"));
    assertTrue(summary.getProperty("pluginExecutionPolicy").contains("asyncEnabled=false"));
    assertTrue(summary.getProperty("workloadSyncWrites").equals("true"));
    assertTrue(summary.getProperty("ldbGroupCommitEnabled").equals("false"));
    assertTrue(summary.getProperty("ldbPluginAsyncEnabled").equals("false"));
    assertTrue(runState.getProperty("ldbGroupCommitMaxBatchBytes").equals("1048576"));
  }

  @Test
  void reportsPluginCompositionWithAsyncCapabilityAndGroupCommitSettings() throws Exception {
    File workDir = Files.createTempDirectory("longrun-plugin-composition").toFile();
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "plugin-sample",
        "--run.duration=50",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--metrics.interval=20",
        "--ldb.plugin.capability.enforcement=true",
        "--ldb.plugin.async.enabled=true",
        "--ldb.plugin.async.queueCapacity=8",
        "--ldb.plugin.async.closeTimeoutMillis=1000",
        "--ldb.plugin.maxTotalCallbackMillis=10000",
        "--ldb.groupCommit.enabled=true",
        "--ldb.groupCommit.maxDelayNanos=1000",
        "--ldb.groupCommit.maxBatchBytes=8192"
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode = new SmokeRunner().run(config, new PrintStream(out, true, StandardCharsets.UTF_8.name()));

    Properties summary = new Properties();
    try (FileInputStream in = new FileInputStream(new File(workDir, "report/summary.properties"))) {
      summary.load(in);
    }
    String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(exitCode == 0);
    assertTrue(output.contains("CONFIG ldb.plugin.capability.enforcement=true"));
    assertTrue(output.contains("CONFIG ldb.plugin.async.enabled=true"));
    assertTrue(output.contains("CONFIG ldb.plugin.maxTotalCallbackMillis=10000"));
    assertTrue(output.contains("CONFIG ldb.groupCommit.enabled=true"));
    assertTrue(output.contains("PLUGIN list=0:sample-audit:order=5"));
    assertTrue(summary.getProperty("plugins").contains("sample-audit"));
    assertTrue(summary.getProperty("pluginExecutionPolicy").contains("asyncEnabled=true"));
    assertTrue(summary.getProperty("pluginAsyncStats").contains("enabled=true"));
    assertTrue(summary.getProperty("ldbGroupCommitEnabled").equals("true"));
    assertTrue(summary.getProperty("ldbGroupCommitMaxDelayNanos").equals("1000"));
    assertTrue(summary.getProperty("ldbGroupCommitMaxBatchBytes").equals("8192"));
    assertTrue(summary.getProperty("ldbPluginAsyncEnabled").equals("true"));
    assertTrue(summary.getProperty("ldbPluginMaxTotalCallbackMillis").equals("10000"));
  }

  @Test
  void runsWithPackagedSamplePluginProfile() throws Exception {
    File workDir = Files.createTempDirectory("longrun-plugin-sample").toFile();
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "plugin-sample",
        "--run.duration=50",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--metrics.interval=20"
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode = new SmokeRunner().run(config, new PrintStream(out, true, StandardCharsets.UTF_8.name()));

    Properties summary = new Properties();
    try (FileInputStream in = new FileInputStream(new File(workDir, "report/summary.properties"))) {
      summary.load(in);
    }
    String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(exitCode == 0);
    assertTrue(output.contains("CONFIG ldb.plugins=sample-audit"));
    assertTrue(output.contains("CONFIG ldb.plugin.discovery.enabled=true"));
    assertTrue(output.contains("PLUGIN list=0:sample-audit:order=5"));
    assertTrue(summary.getProperty("plugins").contains("sample-audit"));
    assertTrue(summary.getProperty("pluginStats").contains("count=1"));
  }

  @Test
  void appliesLongRunPluginOrderOverrideToOpenedDb() throws Exception {
    File workDir = Files.createTempDirectory("longrun-plugin-order").toFile();
    String pluginClass = OrderOverridePlugin.class.getName();
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties",
        "--run.duration=50",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--metrics.interval=20",
        "-P", "diagnostic," + pluginClass,
        "--ldb.plugin.diagnostic.order=10",
        "--ldb.plugin." + pluginClass + ".order=-10"
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exitCode = new SmokeRunner().run(config, new PrintStream(out, true, StandardCharsets.UTF_8.name()));

    String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(exitCode == 0);
    assertTrue(output.contains("PLUGIN list=0:order-override-plugin:order=-10"));
    assertTrue(output.contains("1:diagnostic:order=10"));
  }

  @Test
  void runsReopenChecks() throws Exception {
    File workDir = Files.createTempDirectory("longrun-reopen").toFile();
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/reopen.properties",
        "--run.duration=120",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--check.reopenInterval=30",
        "--metrics.interval=20"
    });

    int exitCode = new SmokeRunner().run(config,
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

    Properties summary = new Properties();
    try (FileInputStream in = new FileInputStream(new File(workDir, "report/summary.properties"))) {
      summary.load(in);
    }
    assertTrue(exitCode == 0);
    assertTrue(Long.parseLong(summary.getProperty("reopenChecks")) > 0);
    assertTrue("PASS".equals(summary.getProperty("status")));
  }

  @Test
  void freshRunCleansPreviousWorkData() throws Exception {
    File workDir = Files.createTempDirectory("longrun-fresh").toFile();
    LongRunConfig first = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties",
        "--run.duration=50",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--metrics.interval=20"
    });
    new SmokeRunner().run(first, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));
    File stale = new File(workDir, "db/stale.marker");
    Files.write(stale.toPath(), "stale".getBytes(StandardCharsets.UTF_8));

    LongRunConfig second = LongRunConfig.load(new String[] {
        "--config", "config/smoke.properties",
        "--run.duration=50",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--metrics.interval=20"
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode = new SmokeRunner().run(second, new PrintStream(out, true, StandardCharsets.UTF_8.name()));

    String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(exitCode == 0);
    assertTrue(output.contains("FRESH resume=false"));
    assertTrue(output.contains("cleaned=db,state,metrics,report"));
    assertTrue(!stale.isFile());
  }

  @Test
  void resumeRunsRecoveryCheck() throws Exception {
    File workDir = Files.createTempDirectory("longrun-recovery").toFile();
    LongRunConfig first = LongRunConfig.load(new String[] {
        "--config", "config/crash.properties",
        "--run.duration=80",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--crash.enabled=false",
        "--metrics.interval=20"
    });
    new SmokeRunner().run(first, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

    LongRunConfig resume = LongRunConfig.load(new String[] {
        "--config", "config/crash.properties",
        "--run.duration=0",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--resume=true",
        "--crash.enabled=false",
        "--metrics.interval=20"
    });

    int exitCode = new SmokeRunner().run(resume,
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

    Properties summary = new Properties();
    try (FileInputStream in = new FileInputStream(new File(workDir, "report/summary.properties"))) {
      summary.load(in);
    }
    assertTrue(exitCode == 0);
    assertTrue(Long.parseLong(summary.getProperty("recoveryChecks")) > 0);
  }

  @Test
  void runsFaultInjectionAndRetainsCopies() throws Exception {
    File workDir = Files.createTempDirectory("longrun-fault").toFile();
    LongRunConfig config = LongRunConfig.load(new String[] {
        "--config", "config/fault-injection.properties",
        "--run.duration=180",
        "--run.workDir=" + workDir.getAbsolutePath(),
        "--workload.keySpace=100",
        "--workload.valueSizeMin=32",
        "--workload.valueSizeMax=64",
        "--fault.interval=40",
        "--fault.kinds=bit-flip,zero-range",
        "--fault.retainedCopies=1",
        "--metrics.interval=20"
    });

    int exitCode = new SmokeRunner().run(config,
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

    Properties summary = new Properties();
    try (FileInputStream in = new FileInputStream(new File(workDir, "report/summary.properties"))) {
      summary.load(in);
    }
    File[] copies = new File(workDir, "fault").listFiles(file -> file.isDirectory() && file.getName().startsWith("fault-"));
    assertTrue(exitCode == 0);
    assertTrue(Long.parseLong(summary.getProperty("faultInjectionEvents")) > 0);
    assertTrue(copies != null && copies.length <= 1);
  }

  public static final class OrderOverridePlugin implements LdbPlugin {
    @Override
    public LdbPluginDescriptor descriptor() {
      return new LdbPluginDescriptor("order-override-plugin", "test", 0,
          LdbPluginFailurePolicy.FAIL_FAST);
    }
  }
}
