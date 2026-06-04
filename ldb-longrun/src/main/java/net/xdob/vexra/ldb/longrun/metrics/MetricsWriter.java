package net.xdob.vexra.ldb.longrun.metrics;

import net.xdob.vexra.ldb.longrun.config.LongRunConfig;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * longrun CSV metrics 输出器。
 */
public final class MetricsWriter implements Closeable {
  private final String runId;
  private final String instance;
  private final PrintWriter ops;
  private final PrintWriter events;
  private final PrintWriter reclamation;
  private final PrintWriter fault;
  private long lastMillis;
  private long lastOperations;
  private int sampleCount;

  public MetricsWriter(LongRunConfig config) throws IOException {
    File metricsDir = new File(config.workDir(), "metrics");
    if (!metricsDir.exists() && !metricsDir.mkdirs()) {
      throw new IOException("failed to create metrics dir: " + metricsDir);
    }
    this.runId = config.runName();
    this.instance = config.instance();
    File opsFile = new File(metricsDir, "ops.csv");
    File eventsFile = new File(metricsDir, "events.log");
    File reclamationFile = new File(metricsDir, "reclamation.csv");
    File faultFile = new File(metricsDir, "fault.csv");
    boolean opsHeader = !opsFile.isFile() || opsFile.length() == 0;
    boolean reclamationHeader = !reclamationFile.isFile() || reclamationFile.length() == 0;
    boolean faultHeader = !faultFile.isFile() || faultFile.length() == 0;
    this.ops = writer(opsFile);
    this.events = writer(eventsFile);
    this.reclamation = writer(reclamationFile);
    this.fault = writer(faultFile);
    if (opsHeader) {
      this.ops.println("timeMillis,runId,instance,workerEpoch,operations,opsPerSecond,reads,writes,removes,commits,reopenChecks,recoveryChecks");
    }
    if (reclamationHeader) {
      this.reclamation.println("timeMillis,status,message,beforeFileSize,afterFileSize,shrinkBytes,fillRate,estimatedReclaimedBytes,candidateChunks,backoffCount,noProgressCount,successCount");
    }
    if (faultHeader) {
      this.fault.println("timeMillis,eventId,kind,status,message,offset,length,beforeSize,afterSize,filePath");
    }
    this.lastMillis = System.currentTimeMillis();
  }

  private static PrintWriter writer(File file) throws IOException {
    return new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
  }

  /**
   * 记录一条普通操作采样。
   *
   * @param stats 当前统计快照
   */
  public void sample(RunStats stats) {
    long now = System.currentTimeMillis();
    long elapsed = Math.max(1, now - lastMillis);
    long delta = Math.max(0, stats.operations() - lastOperations);
    double opsPerSecond = delta * 1000.0 / elapsed;
    ops.println(now + "," + runId + "," + instance + ",0,"
        + stats.operations() + ","
        + String.format(Locale.ROOT, "%.3f", opsPerSecond) + ","
        + stats.reads() + ","
        + stats.writes() + ","
        + stats.removes() + ","
        + stats.commits() + ","
        + stats.reopenChecks() + ","
        + stats.recoveryChecks());
    ops.flush();
    lastMillis = now;
    lastOperations = stats.operations();
    sampleCount++;
  }

  public int sampleCount() {
    return sampleCount;
  }

  public void event(String type, String status, String message) {
    events.println(System.currentTimeMillis() + "," + type + "," + status + "," + sanitize(message));
    events.flush();
  }

  public void fault(long eventId, String kind, String status, String message,
                    long offset, long length, long beforeSize, long afterSize, String filePath) {
    fault.println(System.currentTimeMillis() + "," + eventId + "," + kind + "," + status + ","
        + sanitize(message) + "," + offset + "," + length + "," + beforeSize + "," + afterSize + ","
        + sanitize(filePath));
    fault.flush();
  }

  public void reclamation(String status, String message, long beforeFileSize,
                          long afterFileSize, long estimatedReclaimedBytes) {
    reclamation.println(System.currentTimeMillis() + "," + status + "," + sanitize(message) + ","
        + beforeFileSize + "," + afterFileSize + "," + Math.max(0, beforeFileSize - afterFileSize)
        + ",0," + estimatedReclaimedBytes + ",0,0,0," + ("success".equals(status) ? 1 : 0));
    reclamation.flush();
  }

  private static String sanitize(String message) {
    return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').replace(',', ';');
  }

  @Override
  public void close() {
    ops.close();
    events.close();
    reclamation.close();
    fault.close();
  }
}
