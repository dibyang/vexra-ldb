package net.xdob.vexra.ldb.longrun.report;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;

/**
 * longrun 报告分析器。
 */
public final class ReportAnalyzer {
  /**
   * 重新分析 workDir 并写出报告。
   *
   * @param workDir 工作目录
   * @return 报告摘要
   * @throws IOException 读取 metrics 或写报告失败时抛出
   */
  public ReportSummary analyze(File workDir) throws IOException {
    ReportSummary summary = new ReportSummary();
    File ops = new File(workDir, "metrics/ops.csv");
    List<String> failures = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    long operations = 0;
    long reads = 0;
    long writes = 0;
    long removes = 0;
    long commits = 0;
    long reopenChecks = 0;
    long recoveryChecks = 0;
    String runId = "";
    FaultStats faultStats = faultStats(new File(workDir, "metrics/fault.csv"));
    int samples = 0;
    List<Double> allOpsPerSecondSamples = new ArrayList<>();
    List<Double> allReadsPerSecondSamples = new ArrayList<>();
    List<Double> allWritesPerSecondSamples = new ArrayList<>();
    List<Double> allRemovesPerSecondSamples = new ArrayList<>();
    List<Long> allSampleElapsedMillis = new ArrayList<>();
    if (ops.isFile()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(ops))) {
        String line = reader.readLine();
        while ((line = reader.readLine()) != null) {
          String[] parts = line.split(",", -1);
          if (parts.length >= 12) {
            runId = parts[1];
            operations = parseLong(parts[4]);
            double opsPerSecond = parseDouble(parts[5]);
            allOpsPerSecondSamples.add(opsPerSecond);
            reads = parseLong(parts[6]);
            writes = parseLong(parts[7]);
            removes = parseLong(parts[8]);
            commits = parseLong(parts[9]);
            reopenChecks = parseLong(parts[10]);
            recoveryChecks = parseLong(parts[11]);
            if (parts.length >= 15) {
              allReadsPerSecondSamples.add(parseDouble(parts[12]));
              allWritesPerSecondSamples.add(parseDouble(parts[13]));
              allRemovesPerSecondSamples.add(parseDouble(parts[14]));
            }
            if (parts.length >= 16) {
              allSampleElapsedMillis.add(parseLong(parts[15]));
            }
            samples++;
          }
        }
      }
    } else {
      warnings.add("metrics/ops.csv is missing");
    }
    int warmupSamples = allOpsPerSecondSamples.size() > 2 ? 2 : Math.max(0, allOpsPerSecondSamples.size() - 1);
    int measuredEnd = measuredEndIndex(allOpsPerSecondSamples, allSampleElapsedMillis, warmupSamples);
    int trailingPartialSamples = allOpsPerSecondSamples.size() - measuredEnd;
    List<Double> measuredOpsPerSecondSamples = allOpsPerSecondSamples.subList(warmupSamples, measuredEnd);
    List<Double> measuredReadsPerSecondSamples = measuredSamples(allReadsPerSecondSamples, warmupSamples, measuredEnd);
    List<Double> measuredWritesPerSecondSamples = measuredSamples(allWritesPerSecondSamples, warmupSamples, measuredEnd);
    List<Double> measuredRemovesPerSecondSamples = measuredSamples(allRemovesPerSecondSamples, warmupSamples, measuredEnd);
    DoubleSummaryStatistics opsStats = new DoubleSummaryStatistics();
    for (Double opsPerSecond : measuredOpsPerSecondSamples) {
      opsStats.accept(opsPerSecond);
    }
    DoubleSummaryStatistics readStats = stats(measuredReadsPerSecondSamples);
    DoubleSummaryStatistics writeStats = stats(measuredWritesPerSecondSamples);
    DoubleSummaryStatistics removeStats = stats(measuredRemovesPerSecondSamples);
    long finalSizeBytes = directorySize(workDir);
    ResourceStats resourceStats = resourceStats(new File(workDir, "state/resource.properties"));
    PluginState pluginState = pluginState(new File(workDir, "state/plugin.properties"));
    RunState runState = runState(new File(workDir, "state/run.properties"));
    ReclamationStats reclamationStats = reclamationStats(new File(workDir, "metrics/reclamation.csv"));
    if (resourceStats.physicalSizeBytes > 0) {
      finalSizeBytes = resourceStats.physicalSizeBytes;
    }
    double avgOps = samples == 0 ? 0 : opsStats.getAverage();
    double minOps = samples == 0 ? 0 : opsStats.getMin();
    double maxOps = samples == 0 ? 0 : opsStats.getMax();
    double p05Ops = percentile(measuredOpsPerSecondSamples, 0.05D);
    double p50Ops = percentile(measuredOpsPerSecondSamples, 0.50D);
    double p95Ops = percentile(measuredOpsPerSecondSamples, 0.95D);
    double throughputDropRatio = p50Ops <= 0.0D ? 0.0D : Math.max(0.0D, 1.0D - p05Ops / p50Ops);
    long suspicious = suspiciousLines(new File(workDir, "logs"));
    if (suspicious > 0) {
      failures.add("suspicious log lines: " + suspicious);
    }
    if ("reopen".equals(runId) && reopenChecks == 0) {
      failures.add("reopen profile requires reopenChecks > 0");
    }
    if ("crash".equals(runId) && recoveryChecks == 0) {
      failures.add("crash profile requires recoveryChecks > 0");
    }
    if ("fault-injection".equals(runId) && faultStats.events == 0) {
      failures.add("fault-injection profile requires fault events > 0");
    }
    if (faultStats.unexpected > 0) {
      failures.add("fault injection unexpected events: " + faultStats.unexpected);
    }
    double sizeAmplification = resourceStats.liveDataBytes <= 0 ? 0.0 : finalSizeBytes * 1.0 / resourceStats.liveDataBytes;
    if (sizeAmplification > 5.0) {
      failures.add("size amplification too high: " + String.format(java.util.Locale.ROOT, "%.3f", sizeAmplification));
    }
    summary.put("status", failures.isEmpty() ? (warnings.isEmpty() ? "PASS" : "WARN") : "FAIL");
    summary.put("operations", operations);
    summary.put("commits", commits);
    summary.put("reads", reads);
    summary.put("writes", writes);
    summary.put("removes", removes);
    summary.put("reopenChecks", reopenChecks);
    summary.put("recoveryChecks", recoveryChecks);
    summary.put("finalSizeBytes", finalSizeBytes);
    summary.put("metricSamples", samples);
    summary.put("warmupSamples", warmupSamples);
    summary.put("trailingPartialSamples", trailingPartialSamples);
    summary.put("measuredSamples", measuredOpsPerSecondSamples.size());
    summary.put("avgOpsPerSecond", String.format(java.util.Locale.ROOT, "%.3f", avgOps));
    summary.put("minOpsPerSecond", String.format(java.util.Locale.ROOT, "%.3f", minOps));
    summary.put("maxOpsPerSecond", String.format(java.util.Locale.ROOT, "%.3f", maxOps));
    summary.put("p05OpsPerSecond", String.format(java.util.Locale.ROOT, "%.3f", p05Ops));
    summary.put("p50OpsPerSecond", String.format(java.util.Locale.ROOT, "%.3f", p50Ops));
    summary.put("p95OpsPerSecond", String.format(java.util.Locale.ROOT, "%.3f", p95Ops));
    putRateSummary(summary, "Read", readStats, measuredReadsPerSecondSamples);
    putRateSummary(summary, "Write", writeStats, measuredWritesPerSecondSamples);
    putRateSummary(summary, "Remove", removeStats, measuredRemovesPerSecondSamples);
    summary.put("throughputDropRatio", String.format(java.util.Locale.ROOT, "%.3f", throughputDropRatio));
    summary.put("reclamationEvents", reclamationStats.events);
    summary.put("reclamationSuccessEvents", reclamationStats.success);
    summary.put("reclamationBackoffEvents", reclamationStats.backoff);
    summary.put("reclamationShrinkBytes", reclamationStats.shrinkBytes);
    summary.put("finalSizeGb", String.format(java.util.Locale.ROOT, "%.6f", finalSizeBytes / 1024.0 / 1024.0 / 1024.0));
    summary.put("sizePerMillionOpsGb", operations == 0 ? "0.000000"
        : String.format(java.util.Locale.ROOT, "%.6f", (finalSizeBytes / 1024.0 / 1024.0 / 1024.0) / (operations / 1_000_000.0)));
    summary.put("sizeAmplification", String.format(java.util.Locale.ROOT, "%.3f", sizeAmplification));
    summary.put("faultInjectionEvents", faultStats.events);
    summary.put("faultInjectionRecoveredEvents", faultStats.recovered);
    summary.put("faultInjectionDetectedEvents", faultStats.detected);
    summary.put("faultInjectionUnexpectedEvents", faultStats.unexpected);
    summary.put("faultInjectionStatusCounts", faultStats.statusCounts);
    summary.put("faultInjectionKindCounts", faultStats.kindCounts);
    summary.put("suspiciousLogLines", suspicious);
    summary.put("plugins", pluginState.plugins);
    summary.put("pluginStats", pluginState.pluginStats);
    summary.put("pluginLastFailure", pluginState.pluginLastFailure);
    summary.put("pluginExecutionPolicy", pluginState.pluginExecutionPolicy);
    summary.put("pluginAsyncStats", pluginState.pluginAsyncStats);
    summary.put("pluginDegraded", pluginState.pluginDegraded);
    summary.put("pluginDisabled", pluginState.pluginDisabled);
    summary.put("pluginSandbox", pluginState.pluginSandbox);
    summary.put("workloadSyncWrites", runState.workloadSyncWrites);
    summary.put("ldbGroupCommitEnabled", runState.ldbGroupCommitEnabled);
    summary.put("ldbGroupCommitMaxDelayNanos", runState.ldbGroupCommitMaxDelayNanos);
    summary.put("ldbGroupCommitMaxBatchBytes", runState.ldbGroupCommitMaxBatchBytes);
    summary.put("ldbPluginAsyncEnabled", runState.ldbPluginAsyncEnabled);
    summary.put("ldbPluginMaxTotalCallbackMillis", runState.ldbPluginMaxTotalCallbackMillis);
    summary.put("failures", failures.size());
    summary.put("warnings", warnings.size());
    summary.put("recentEvents", recentEvents(new File(workDir, "metrics/events.log")));
    write(workDir, summary, failures, warnings);
    return summary;
  }

  private static void write(File workDir, ReportSummary summary,
                            List<String> failures, List<String> warnings) throws IOException {
    File reportDir = new File(workDir, "report");
    if (!reportDir.exists() && !reportDir.mkdirs()) {
      throw new IOException("failed to create report dir: " + reportDir);
    }
    try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
        new FileOutputStream(new File(reportDir, "summary.properties")), StandardCharsets.UTF_8))) {
      for (Map.Entry<String, String> entry : summary.values().entrySet()) {
        out.println(entry.getKey() + "=" + entry.getValue());
      }
    }
    try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
        new FileOutputStream(new File(reportDir, "summary.md")), StandardCharsets.UTF_8))) {
      out.println("# Longrun Summary");
      out.println();
      for (Map.Entry<String, String> entry : summary.values().entrySet()) {
        out.println("- " + title(entry.getKey()) + ": " + entry.getValue());
      }
      if (!failures.isEmpty()) {
        out.println();
        out.println("## Failures");
        for (String failure : failures) {
          out.println("- " + failure);
        }
      }
      if (!warnings.isEmpty()) {
        out.println();
        out.println("## Warnings");
        for (String warning : warnings) {
          out.println("- " + warning);
        }
      }
    }
  }

  private static String title(String key) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (i == 0) {
        out.append(Character.toUpperCase(c));
      } else if (Character.isUpperCase(c)) {
        out.append(' ').append(c);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String recentEvents(File file) throws IOException {
    if (!file.isFile()) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    int start = Math.max(0, lines.size() - 5);
    return lines.subList(start, lines.size()).toString();
  }

  private static FaultStats faultStats(File file) throws IOException {
    FaultStats stats = new FaultStats();
    if (!file.isFile()) {
      return stats;
    }
    java.util.Map<String, Integer> status = new java.util.LinkedHashMap<>();
    java.util.Map<String, Integer> kind = new java.util.LinkedHashMap<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(",", -1);
        if (parts.length >= 4) {
          stats.events++;
          increment(kind, parts[2]);
          increment(status, parts[3]);
          if ("RECOVERED".equals(parts[3])) {
            stats.recovered++;
          } else if ("DETECTED".equals(parts[3]) || "DETECTED_BY_VERIFY".equals(parts[3])) {
            stats.detected++;
          } else if (parts[3].startsWith("UNEXPECTED")) {
            stats.unexpected++;
          }
        }
      }
    }
    stats.statusCounts = status.toString();
    stats.kindCounts = kind.toString();
    return stats;
  }

  private static ResourceStats resourceStats(File file) throws IOException {
    ResourceStats stats = new ResourceStats();
    if (!file.isFile()) {
      return stats;
    }
    java.util.Properties properties = new java.util.Properties();
    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
      properties.load(in);
    }
    stats.physicalSizeBytes = Long.parseLong(properties.getProperty("physicalSizeBytes", "0"));
    stats.liveDataBytes = Long.parseLong(properties.getProperty("liveDataBytes", "0"));
    return stats;
  }

  private static PluginState pluginState(File file) throws IOException {
    PluginState state = new PluginState();
    if (!file.isFile()) {
      return state;
    }
    java.util.Properties properties = new java.util.Properties();
    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
      properties.load(in);
    }
    state.plugins = properties.getProperty("plugins", "");
    state.pluginStats = properties.getProperty("pluginStats", "");
    state.pluginLastFailure = properties.getProperty("pluginLastFailure", "");
    state.pluginExecutionPolicy = properties.getProperty("pluginExecutionPolicy", "");
    state.pluginAsyncStats = properties.getProperty("pluginAsyncStats", "");
    state.pluginDegraded = properties.getProperty("pluginDegraded", "");
    state.pluginDisabled = properties.getProperty("pluginDisabled", "");
    state.pluginSandbox = properties.getProperty("pluginSandbox", "");
    return state;
  }

  private static RunState runState(File file) throws IOException {
    RunState state = new RunState();
    if (!file.isFile()) {
      return state;
    }
    java.util.Properties properties = new java.util.Properties();
    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
      properties.load(in);
    }
    state.workloadSyncWrites = properties.getProperty("workloadSyncWrites", "");
    state.ldbGroupCommitEnabled = properties.getProperty("ldbGroupCommitEnabled", "");
    state.ldbGroupCommitMaxDelayNanos = properties.getProperty("ldbGroupCommitMaxDelayNanos", "");
    state.ldbGroupCommitMaxBatchBytes = properties.getProperty("ldbGroupCommitMaxBatchBytes", "");
    state.ldbPluginAsyncEnabled = properties.getProperty("ldbPluginAsyncEnabled", "");
    state.ldbPluginMaxTotalCallbackMillis = properties.getProperty("ldbPluginMaxTotalCallbackMillis", "");
    return state;
  }

  private static ReclamationStats reclamationStats(File file) throws IOException {
    ReclamationStats stats = new ReclamationStats();
    if (!file.isFile()) {
      return stats;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(",", -1);
        if (parts.length >= 12) {
          stats.events++;
          if ("success".equals(parts[1])) {
            stats.success++;
          } else if ("backoff".equals(parts[1])) {
            stats.backoff++;
          }
          stats.shrinkBytes += parseLong(parts[5]);
        }
      }
    }
    return stats;
  }

  private static final class ResourceStats {
    private long physicalSizeBytes;
    private long liveDataBytes;
  }

  private static final class PluginState {
    private String plugins = "";
    private String pluginStats = "";
    private String pluginLastFailure = "";
    private String pluginExecutionPolicy = "";
    private String pluginAsyncStats = "";
    private String pluginDegraded = "";
    private String pluginDisabled = "";
    private String pluginSandbox = "";
  }

  private static final class RunState {
    private String workloadSyncWrites = "";
    private String ldbGroupCommitEnabled = "";
    private String ldbGroupCommitMaxDelayNanos = "";
    private String ldbGroupCommitMaxBatchBytes = "";
    private String ldbPluginAsyncEnabled = "";
    private String ldbPluginMaxTotalCallbackMillis = "";
  }

  private static final class ReclamationStats {
    private long events;
    private long success;
    private long backoff;
    private long shrinkBytes;
  }

  private static void increment(java.util.Map<String, Integer> map, String key) {
    Integer count = map.get(key);
    map.put(key, count == null ? 1 : count + 1);
  }

  private static final class FaultStats {
    private long events;
    private long recovered;
    private long detected;
    private long unexpected;
    private String statusCounts = "{}";
    private String kindCounts = "{}";
  }

  private static long suspiciousLines(File logDir) throws IOException {
    if (!logDir.isDirectory()) {
      return 0;
    }
    long count = 0;
    File[] files = logDir.listFiles();
    if (files == null) {
      return 0;
    }
    for (File file : files) {
      if (file.isFile()) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.contains("ERROR") || line.contains("Corruption")
                || line.contains("Checksum") || line.contains("panic")
                || line.contains("leak") || line.contains("Exception")) {
              count++;
            }
          }
        }
      }
    }
    return count;
  }

  private static long directorySize(File dir) {
    if (!dir.exists()) {
      return 0;
    }
    if (dir.isFile()) {
      return dir.length();
    }
    long total = 0;
    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        total += directorySize(file);
      }
    }
    return total;
  }

  private static long parseLong(String value) {
    return Long.parseLong(value.trim());
  }

  private static double parseDouble(String value) {
    return Double.parseDouble(value.trim());
  }

  private static List<Double> measuredSamples(List<Double> samples, int warmupSamples, int measuredEnd) {
    if (samples.isEmpty()) {
      return Collections.emptyList();
    }
    int end = Math.min(Math.max(0, measuredEnd), samples.size());
    int start = Math.min(Math.max(0, warmupSamples), end);
    return samples.subList(start, end);
  }

  private static int measuredEndIndex(List<Double> samples, List<Long> elapsedMillis, int warmupSamples) {
    if (samples.size() <= warmupSamples + 1 || elapsedMillis.size() != samples.size()) {
      return samples.size();
    }
    int last = samples.size() - 1;
    List<Long> baseline = new ArrayList<>(elapsedMillis.subList(warmupSamples, last));
    if (baseline.isEmpty()) {
      return samples.size();
    }
    Collections.sort(baseline);
    long medianElapsed = baseline.get(baseline.size() / 2);
    long lastElapsed = elapsedMillis.get(last);
    if (medianElapsed > 0 && lastElapsed * 2 < medianElapsed) {
      return last;
    }
    return samples.size();
  }

  private static DoubleSummaryStatistics stats(List<Double> values) {
    DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
    for (Double value : values) {
      stats.accept(value);
    }
    return stats;
  }

  private static void putRateSummary(ReportSummary summary, String name,
                                     DoubleSummaryStatistics stats,
                                     List<Double> samples) {
    summary.put("avg" + name + "OpsPerSecond", formatStats(stats, "avg"));
    summary.put("min" + name + "OpsPerSecond", formatStats(stats, "min"));
    summary.put("max" + name + "OpsPerSecond", formatStats(stats, "max"));
    summary.put("p50" + name + "OpsPerSecond", formatDouble(percentile(samples, 0.50D)));
    summary.put("p95" + name + "OpsPerSecond", formatDouble(percentile(samples, 0.95D)));
  }

  private static String formatStats(DoubleSummaryStatistics stats, String kind) {
    if (stats.getCount() == 0) {
      return "0.000";
    }
    if ("avg".equals(kind)) {
      return formatDouble(stats.getAverage());
    }
    if ("min".equals(kind)) {
      return formatDouble(stats.getMin());
    }
    if ("max".equals(kind)) {
      return formatDouble(stats.getMax());
    }
    return "0.000";
  }

  private static String formatDouble(double value) {
    return String.format(java.util.Locale.ROOT, "%.3f", value);
  }

  private static double percentile(List<Double> values, double percentile) {
    if (values.isEmpty()) {
      return 0.0D;
    }
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    index = Math.max(0, Math.min(sorted.size() - 1, index));
    return sorted.get(index);
  }
}
