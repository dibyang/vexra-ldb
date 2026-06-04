package net.xdob.vexra.ldb.longrun.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ReportAnalyzerTest {
  @Test
  void analyzesOpsMetrics() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report").toFile();
    File metrics = new File(workDir, "metrics");
    File state = new File(workDir, "state");
    assertTrue(metrics.mkdirs());
    assertTrue(state.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,smoke,a,0,10,100.0,5,4,1,1,0,0\n"
          + "2,smoke,a,0,20,200.0,10,8,2,2,0,0\n").getBytes(StandardCharsets.UTF_8));
    }
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "reclamation.csv"))) {
      out.write(("timeMillis,status,message,beforeFileSize,afterFileSize,shrinkBytes,fillRate,estimatedReclaimedBytes,candidateChunks,backoffCount,noProgressCount,successCount\n"
          + "1,success,sample,100,90,10,0,80,0,0,0,1\n").getBytes(StandardCharsets.UTF_8));
    }
    try (FileOutputStream out = new FileOutputStream(new File(state, "resource.properties"))) {
      out.write(("physicalSizeBytes=100\nliveDataBytes=50\n").getBytes(StandardCharsets.UTF_8));
    }

    ReportSummary summary = new ReportAnalyzer().analyze(workDir);

    assertEquals("PASS", summary.get("status"));
    assertEquals("20", summary.get("operations"));
    assertEquals("2", summary.get("metricSamples"));
    assertEquals("2.000", summary.get("sizeAmplification"));
    assertEquals("1", summary.get("reclamationEvents"));
    assertTrue(new File(workDir, "report/summary.md").isFile());
    assertTrue(new File(workDir, "report/summary.properties").isFile());
  }

  @Test
  void warnsWhenMetricsMissing() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-missing").toFile();

    ReportSummary summary = new ReportAnalyzer().analyze(workDir);

    assertEquals("WARN", summary.get("status"));
    assertEquals("1", summary.get("warnings"));
  }

  @Test
  void failsOnSuspiciousLogLine() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-log").toFile();
    File metrics = new File(workDir, "metrics");
    File logs = new File(workDir, "logs");
    assertTrue(metrics.mkdirs());
    assertTrue(logs.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,smoke,a,0,10,100.0,5,4,1,1,0,0\n").getBytes(StandardCharsets.UTF_8));
    }
    try (FileOutputStream out = new FileOutputStream(new File(logs, "smoke.out"))) {
      out.write("ERROR something happened\n".getBytes(StandardCharsets.UTF_8));
    }

    ReportSummary summary = new ReportAnalyzer().analyze(workDir);

    assertEquals("FAIL", summary.get("status"));
    assertEquals("1", summary.get("suspiciousLogLines"));
  }

  @Test
  void failsReopenProfileWithoutChecks() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-reopen").toFile();
    File metrics = new File(workDir, "metrics");
    assertTrue(metrics.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,reopen,a,0,10,100.0,5,4,1,1,0,0\n").getBytes(StandardCharsets.UTF_8));
    }

    ReportSummary summary = new ReportAnalyzer().analyze(workDir);

    assertEquals("FAIL", summary.get("status"));
  }

  @Test
  void failsCrashProfileWithoutRecoveryChecks() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-crash").toFile();
    File metrics = new File(workDir, "metrics");
    assertTrue(metrics.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,crash,a,0,10,100.0,5,4,1,1,0,0\n").getBytes(StandardCharsets.UTF_8));
    }

    ReportSummary summary = new ReportAnalyzer().analyze(workDir);

    assertEquals("FAIL", summary.get("status"));
  }

  @Test
  void failsOnUnexpectedFaultEvent() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-fault").toFile();
    File metrics = new File(workDir, "metrics");
    assertTrue(metrics.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,fault-injection,a,0,10,100.0,5,4,1,1,0,0\n").getBytes(StandardCharsets.UTF_8));
    }
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "fault.csv"))) {
      out.write(("timeMillis,eventId,kind,status,message,offset,length,beforeSize,afterSize,filePath\n"
          + "1,1,bit-flip,UNEXPECTED_MAIN_DB_DAMAGE,bad,0,1,10,10,x\n").getBytes(StandardCharsets.UTF_8));
    }

    ReportSummary summary = new ReportAnalyzer().analyze(workDir);

    assertEquals("FAIL", summary.get("status"));
    assertEquals("1", summary.get("faultInjectionUnexpectedEvents"));
  }

  @Test
  void failsWhenSizeAmplificationTooHigh() throws Exception {
    File workDir = Files.createTempDirectory("longrun-report-size").toFile();
    File metrics = new File(workDir, "metrics");
    File state = new File(workDir, "state");
    assertTrue(metrics.mkdirs());
    assertTrue(state.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(metrics, "ops.csv"))) {
      out.write(("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks\n"
          + "1,smoke,a,0,10,100.0,5,4,1,1,0,0\n").getBytes(StandardCharsets.UTF_8));
    }
    try (FileOutputStream out = new FileOutputStream(new File(state, "resource.properties"))) {
      out.write(("physicalSizeBytes=1000\nliveDataBytes=100\n").getBytes(StandardCharsets.UTF_8));
    }

    ReportSummary summary = new ReportAnalyzer().analyze(workDir);

    assertEquals("FAIL", summary.get("status"));
  }

  @Test
  void longrunDocsAreUtf8() throws Exception {
    assertUtf8(Paths.get("..", "docs", "ldb-longrun-test-tool-design.md"));
    assertUtf8(Paths.get("..", "docs", "ldb-longrun-test-tool-design.en.md"));
    assertUtf8(Paths.get("..", "docs", "ldb-longrun-test-tool-implementation-plan.md"));
    assertUtf8(Paths.get("..", "docs", "ldb-longrun-test-tool-implementation-plan.en.md"));
  }

  private static void assertUtf8(Path path) throws Exception {
    try {
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(Files.readAllBytes(path)));
    } catch (CharacterCodingException e) {
      throw new AssertionError("not UTF-8: " + path, e);
    }
  }
}
