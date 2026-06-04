package net.xdob.vexra.ldb.longrun.workload;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
    assertTrue(new File(workDir, "report/summary.md").isFile());
    assertTrue(new File(workDir, "report/summary.properties").isFile());
    String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(output.contains("START run=smoke"));
    assertTrue(output.contains("CONFIG workload.keySpace=100"));
    assertTrue(output.contains("PROGRESS timeMillis="));
    assertTrue(output.contains("progressPercent="));
    assertTrue(output.contains("windowOpsPerSecond="));
    assertTrue(output.contains("avgOpsPerSecond="));
    assertTrue(output.contains("minOpsPerSecond="));
    assertTrue(output.contains("maxOpsPerSecond="));
    assertTrue(output.contains("SUMMARY status="));
    assertTrue(output.contains("p50OpsPerSecond="));
    assertTrue(output.contains("PASS smoke"));
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
}
