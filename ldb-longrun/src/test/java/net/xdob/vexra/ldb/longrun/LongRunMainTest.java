package net.xdob.vexra.ldb.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class LongRunMainTest {
  @Test
  void helpPrintsUsage() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exitCode = LongRunMain.run(new String[] {"--help"},
        new PrintStream(out, true, StandardCharsets.UTF_8.name()),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

    assertEquals(0, exitCode);
    assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("Usage: longrun"));
    assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("watch"));
  }

  @Test
  void unknownCommandFails() throws Exception {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exitCode = LongRunMain.run(new String[] {"unknown"},
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()),
        new PrintStream(err, true, StandardCharsets.UTF_8.name()));

    assertEquals(1, exitCode);
    assertTrue(new String(err.toByteArray(), StandardCharsets.UTF_8).contains("Unknown command"));
  }

  @Test
  void reportCommandAnalyzesWorkDir() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-command").toFile();
    File metrics = new File(workDir, "metrics");
    assertTrue(metrics.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,smoke,a,0,10,100.0,5,4,1,1,0,0\n").getBytes(StandardCharsets.UTF_8));
    }
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    int exitCode = LongRunMain.run(new String[] {"report", "--workDir", workDir.getAbsolutePath()},
        new PrintStream(stdout, true, StandardCharsets.UTF_8.name()),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

    assertTrue(exitCode == 0);
    assertTrue(new File(workDir, "report/summary.properties").isFile());
    assertTrue(new String(stdout.toByteArray(), StandardCharsets.UTF_8).contains("Report status=PASS"));
  }

  @Test
  void reportCommandParsesShortWorkDir() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-short-workdir").toFile();
    File metrics = new File(workDir, "metrics");
    assertTrue(metrics.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,smoke,a,0,10,100.0,5,4,1,1,0,0\n").getBytes(StandardCharsets.UTF_8));
    }
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    int exitCode = LongRunMain.run(new String[] {"report", "-w", workDir.getAbsolutePath()},
        new PrintStream(stdout, true, StandardCharsets.UTF_8.name()),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

    assertTrue(exitCode == 0);
    assertTrue(new File(workDir, "report/summary.properties").isFile());
    assertTrue(new String(stdout.toByteArray(), StandardCharsets.UTF_8).contains("Report status=PASS"));
  }
}
