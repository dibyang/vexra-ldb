package net.xdob.vexra.ldb.longrun.workload;

import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.WriteOptions;
import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.longrun.config.LongRunConfig;
import net.xdob.vexra.ldb.longrun.config.LongRunPluginResolver;
import net.xdob.vexra.ldb.longrun.fault.FaultInjector;
import net.xdob.vexra.ldb.longrun.fault.FaultResult;
import net.xdob.vexra.ldb.longrun.instance.WorkDirLock;
import net.xdob.vexra.ldb.longrun.metrics.MetricsWriter;
import net.xdob.vexra.ldb.longrun.metrics.RunStats;
import net.xdob.vexra.ldb.longrun.model.CommittedState;
import net.xdob.vexra.ldb.longrun.model.Ledger;
import net.xdob.vexra.ldb.longrun.model.ValueModel;
import net.xdob.vexra.ldb.longrun.report.ReportAnalyzer;
import net.xdob.vexra.ldb.longrun.report.ReportSummary;
import net.xdob.vexra.ldb.longrun.util.LongRunBuildInfo;
import net.xdob.vexra.ldb.longrun.verify.ConsistencyVerifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Properties;

/**
 * smoke profile 前台运行器。
 */
public final class SmokeRunner {
  private final ConsistencyVerifier verifier = new ConsistencyVerifier();
  private final FaultInjector faultInjector = new FaultInjector();

  /**
   * 执行 smoke workload。
   *
   * @param config 配置
   * @param out 标准输出
   * @return 进程退出码
   * @throws Exception 打开、写入或校验失败时抛出
   */
  public int run(LongRunConfig config, PrintStream out) throws Exception {
    File workDir = config.workDir();
    File dbDir = new File(workDir, "db");
    File stateDir = new File(workDir, "state");
    try (WorkDirLock ignored = WorkDirLock.acquire(workDir)) {
      out.println(LongRunBuildInfo.testedComponentLine());
      if (!config.resume()) {
        prepareFreshRun(workDir, out);
      }
      try (MetricsWriter metrics = new MetricsWriter(config)) {
      LDB db = openDb(dbDir, config, true);
      printPluginState(out, db);
      long reopenChecks = 0;
      long recoveryChecks = 0;
      out.println("START run=" + config.runName()
          + " instance=" + config.instance()
          + " workDir=" + workDir.getPath()
          + " durationMillis=" + config.durationMillis());
      printConfig(out, config);
      metrics.event("run", "STARTED", config.runName());
      try {
        CommittedState state = CommittedState.load(stateDir);
        Ledger ledger = new Ledger(1024);
        if (config.resume()) {
          reconcileRecoveredState(db, state, stateDir, out);
          verifier.verifyActive(db, state);
          recoveryChecks++;
          metrics.event("recovery", "PASS", "recovery check " + recoveryChecks);
        }
        Random random = new Random(config.seed() ^ state.lastSequence());
        long startMillis = System.currentTimeMillis();
        long deadline = startMillis + config.durationMillis();
        long nextSave = System.currentTimeMillis() + Math.min(config.stateIntervalMillis(), Math.max(1_000L, config.durationMillis()));
        long nextMetrics = System.currentTimeMillis();
        long reopenInterval = config.reopenIntervalMillis();
        long nextReopen = reopenInterval > 0 ? System.currentTimeMillis() + reopenInterval : Long.MAX_VALUE;
        long faultInterval = config.faultEnabled() ? Math.max(1, config.faultIntervalMillis()) : 0;
        long nextFault = faultInterval > 0 ? System.currentTimeMillis() + faultInterval : Long.MAX_VALUE;
        long faultEvents = 0;
        long commitEveryOps = Math.max(1, config.commitEveryOps());
        ProgressStats progressStats = new ProgressStats(startMillis, state.operations());
        while (System.currentTimeMillis() < deadline) {
          oneOperation(config, db, state, ledger, random);
          if (state.operations() % commitEveryOps == 0 || System.currentTimeMillis() >= nextSave) {
            state.recordCommit();
            state.save(stateDir);
            nextSave = System.currentTimeMillis() + config.stateIntervalMillis();
          }
          if (System.currentTimeMillis() >= nextReopen) {
            state.recordCommit();
            state.save(stateDir);
            verifier.verifyActive(db, state);
            verifier.verifyLedger(db, state, ledger);
            db.close();
            db = openDb(dbDir, config, false);
            verifier.verifyActive(db, state);
            verifier.verifyLedger(db, state, ledger);
            reopenChecks++;
            metrics.event("reopen", "PASS", "reopen check " + reopenChecks);
            nextReopen = System.currentTimeMillis() + reopenInterval;
          }
          if (System.currentTimeMillis() >= nextFault) {
            state.recordCommit();
            state.save(stateDir);
            verifier.verifyActive(db, state);
            verifier.verifyLedger(db, state, ledger);
            db.close();
            FaultResult result = faultInjector.inject(config, dbDir, state, ++faultEvents, random);
            metrics.fault(result.eventId(), result.kind().text(), result.status(), result.message(),
                result.offset(), result.length(), result.beforeSize(), result.afterSize(), result.filePath());
            metrics.event("fault", result.status(), result.kind().text());
            db = openDb(dbDir, config, false);
            verifier.verifyActive(db, state);
            verifier.verifyLedger(db, state, ledger);
            nextFault = System.currentTimeMillis() + faultInterval;
          }
          if (System.currentTimeMillis() >= nextMetrics) {
            metrics.sample(RunStats.fromState(state, reopenChecks, recoveryChecks));
            printProgress(out, startMillis, config.durationMillis(), state,
                reopenChecks, recoveryChecks, faultEvents, progressStats, db);
            nextMetrics = System.currentTimeMillis() + config.metricsIntervalMillis();
          }
        }
        state.recordCommit();
        state.save(stateDir);
        metrics.sample(RunStats.fromState(state, reopenChecks, recoveryChecks));
        ProgressSnapshot finalProgress = printProgress(out, startMillis, config.durationMillis(), state,
            reopenChecks, recoveryChecks, faultEvents, progressStats, db);
        printWorkloadResult(out, state, finalProgress);
        out.println("FINAL phase=verify enabled=" + config.finalVerifyEnabled());
        if (config.finalVerifyEnabled()) {
          verifier.verifyActive(db, state);
          verifier.verifyLedger(db, state, ledger);
          metrics.event("verify", "PASS", "final verify succeeded");
        } else {
          metrics.event("verify", "SKIPPED", "final verify disabled");
        }
        out.println("FINAL phase=resource");
        long physicalSize = directorySize(dbDir);
        long liveDataBytes = parseLong(db.getProperty("ldb.liveDataBytes"));
        writeResourceState(stateDir, physicalSize, liveDataBytes);
        metrics.reclamation("success", "resource sample", physicalSize, physicalSize, liveDataBytes);
        out.println("FINAL phase=report");
        writeRunState(stateDir, config);
        writePluginState(stateDir, db);
        ReportSummary summary = new ReportAnalyzer().analyze(workDir);
        printSummary(out, summary);
        out.println("PASS " + config.runName() + " operations=" + state.operations()
            + " reads=" + state.reads()
            + " writes=" + state.writes()
            + " removes=" + state.removes()
            + " activeKeys=" + state.active().size()
            + " reopenChecks=" + reopenChecks
            + " recoveryChecks=" + recoveryChecks
            + " reportStatus=" + summary.get("status"));
        return 0;
      } finally {
        db.close();
      }
      }
    }
  }

  private void oneOperation(LongRunConfig config, LDB db, CommittedState state,
                            Ledger ledger, Random random) {
    long keyId = Math.floorMod(random.nextLong(), Math.max(1, config.keySpace()));
    double decision = random.nextDouble();
    if (decision < config.writeRatio()) {
      write(config, db, state, ledger, random, keyId);
    } else if (decision < config.writeRatio() + config.removeRatio()) {
      remove(config, db, state, ledger, keyId);
    } else {
      read(db, state, ledger, keyId);
    }
  }

  private void write(LongRunConfig config, LDB db, CommittedState state,
                     Ledger ledger, Random random, long keyId) {
    long sequence = state.nextSequence();
    int min = config.valueSizeMin();
    int max = Math.max(min, config.valueSizeMax());
    int size = min + random.nextInt(max - min + 1);
    db.put(ValueModel.key(keyId), ValueModel.encode(config.seed(), keyId, sequence, size),
        new WriteOptions().sync(config.syncWrites()));
    state.recordWrite(keyId, sequence);
    ledger.add(Ledger.Kind.WRITE, keyId, sequence);
  }

  private void remove(LongRunConfig config, LDB db, CommittedState state,
                      Ledger ledger, long keyId) {
    state.nextSequence();
    db.delete(ValueModel.key(keyId), new WriteOptions().sync(config.syncWrites()));
    state.recordRemove(keyId);
    ledger.add(Ledger.Kind.REMOVE, keyId, -1);
  }

  private void read(LDB db, CommittedState state, Ledger ledger, long keyId) {
    state.recordRead();
    Long sequence = state.active().get(keyId);
    byte[] value = db.get(ValueModel.key(keyId));
    if (sequence == null) {
      if (value != null) {
        throw new IllegalStateException("unexpected value for inactive key " + keyId);
      }
    } else {
      ValueModel.verify(value, keyId, sequence);
    }
    ledger.add(Ledger.Kind.READ, keyId, sequence == null ? -1 : sequence);
  }

  private void reconcileRecoveredState(LDB db, CommittedState state,
                                       File stateDir, PrintStream out) throws Exception {
    long maxSequence = state.lastSequence();
    int activeKeys = 0;
    state.active().clear();
    try (SnapshotCursor cursor = db.newSnapshotCursor()) {
      cursor.seekToFirst();
      while (cursor.isValid()) {
        ValueModel.Decoded decoded = ValueModel.decode(cursor.value());
        byte[] expectedKey = ValueModel.key(decoded.keyId());
        if (!Arrays.equals(expectedKey, cursor.key())) {
          throw new IllegalStateException("key/value id mismatch for recovered key "
              + decoded.keyId());
        }
        state.active().put(decoded.keyId(), decoded.sequence());
        maxSequence = Math.max(maxSequence, decoded.sequence());
        activeKeys++;
        cursor.next();
      }
    }
    state.advanceLastSequence(maxSequence);
    state.save(stateDir);
    out.println("RECOVERY reconciled activeKeys=" + activeKeys
        + " lastSequence=" + state.lastSequence());
  }

  private static void printConfig(PrintStream out, LongRunConfig config) {
    out.println("CONFIG run.seed=" + config.seed());
    out.println("CONFIG workload.keySpace=" + config.keySpace());
    out.println("CONFIG workload.valueSizeMin=" + config.valueSizeMin());
    out.println("CONFIG workload.valueSizeMax=" + config.valueSizeMax());
    out.println("CONFIG workload.readRatio=" + config.readRatio());
    out.println("CONFIG workload.writeRatio=" + config.writeRatio());
    out.println("CONFIG workload.removeRatio=" + config.removeRatio());
    out.println("CONFIG workload.syncWrites=" + config.syncWrites());
    out.println("CONFIG metrics.intervalMillis=" + config.metricsIntervalMillis());
    out.println("CONFIG state.intervalMillis=" + config.stateIntervalMillis());
    out.println("CONFIG check.reopenIntervalMillis=" + config.reopenIntervalMillis());
    out.println("CONFIG check.finalVerify=" + config.finalVerifyEnabled());
    out.println("CONFIG crash.enabled=" + config.crashEnabled());
    out.println("CONFIG crash.intervalMillis=" + config.crashIntervalMillis());
    out.println("CONFIG crash.cycles=" + config.crashCycles());
    out.println("CONFIG fault.enabled=" + config.faultEnabled());
    out.println("CONFIG fault.intervalMillis=" + config.faultIntervalMillis());
    out.println("CONFIG fault.kinds=" + config.faultKinds());
    out.println("CONFIG fault.retainedCopies=" + config.faultRetainedCopies());
    out.println("CONFIG ldb.writeBufferSizeMb=" + config.ldbWriteBufferSizeMb());
    out.println("CONFIG ldb.groupCommit.enabled=" + config.ldbGroupCommitEnabled());
    out.println("CONFIG ldb.groupCommit.maxDelayNanos=" + config.ldbGroupCommitMaxDelayNanos());
    out.println("CONFIG ldb.groupCommit.maxBatchBytes=" + config.ldbGroupCommitMaxBatchBytes());
    out.println("CONFIG ldb.plugins=" + join(config.pluginNames()));
    out.println("CONFIG ldb.plugin.discovery.enabled=" + config.pluginDiscoveryEnabled());
    out.println("CONFIG ldb.plugin.capability.enforcement=" + config.pluginCapabilityEnforcement());
    out.println("CONFIG ldb.plugin.callbackTimeoutMillis=" + config.pluginCallbackTimeoutMillis());
    out.println("CONFIG ldb.plugin.autoDisableOnTimeout=" + config.pluginAutoDisableOnTimeout());
    out.println("CONFIG ldb.plugin.autoDisableFailureThreshold=" + config.pluginAutoDisableFailureThreshold());
    out.println("CONFIG ldb.plugin.async.enabled=" + config.pluginAsyncEnabled());
    out.println("CONFIG ldb.plugin.async.queueCapacity=" + config.pluginAsyncQueueCapacity());
    out.println("CONFIG ldb.plugin.async.closeTimeoutMillis=" + config.pluginAsyncCloseTimeoutMillis());
    out.println("CONFIG ldb.plugin.maxTotalCallbackMillis=" + config.pluginMaxTotalCallbackMillis());
    out.println("CONFIG ldb.plugin.external.enabled=" + config.pluginExternalEnabled());
    out.println("CONFIG ldb.plugin.dir=" + (config.pluginDir() == null ? "" : config.pluginDir().getPath()));
  }

  private static ProgressSnapshot printProgress(PrintStream out, long startMillis, long durationMillis,
                                                CommittedState state, long reopenChecks,
                                                long recoveryChecks, long faultEvents,
                                                ProgressStats progressStats, LDB db) {
    long now = System.currentTimeMillis();
    ProgressSnapshot snapshot = progressStats.sample(now, state.operations());
    out.println("PROGRESS timeMillis=" + now
        + " progressPercent=" + progressPercent(now, startMillis, durationMillis)
        + " operations=" + state.operations()
        + " windowOpsPerSecond=" + formatDouble(snapshot.windowOpsPerSecond)
        + " avgOpsPerSecond=" + formatDouble(snapshot.avgOpsPerSecond)
        + " minOpsPerSecond=" + formatDouble(snapshot.minOpsPerSecond)
        + " maxOpsPerSecond=" + formatDouble(snapshot.maxOpsPerSecond)
        + " reads=" + state.reads()
        + " writes=" + state.writes()
        + " removes=" + state.removes()
        + " commits=" + state.commits()
        + " activeKeys=" + state.active().size()
        + " reopenChecks=" + reopenChecks
        + " recoveryChecks=" + recoveryChecks
        + " faultEvents=" + faultEvents
        + " ldbMemTableBytes=" + property(db, "ldb.memTableBytes")
        + " ldbCompactionBacklog=" + property(db, "ldb.compactionBacklog")
        + " ldbCompactionPendingBytes=" + property(db, "ldb.compactionPendingBytes")
        + " ldbCompactionRunning=" + property(db, "ldb.compaction.running")
        + " ldbCompactionRunCount=" + property(db, "ldb.compaction.runCount")
        + " ldbCompactionOutputBytes=" + property(db, "ldb.compaction.outputBytes")
        + " ldbWriteSlowdownCount=" + property(db, "ldb.writeStall.slowdownCount")
        + " ldbWriteSlowdownMicros=" + property(db, "ldb.writeStall.slowdownMicros")
        + " ldbImmutableWaitCount=" + property(db, "ldb.writeStall.immutableWaitCount")
        + " ldbImmutableWaitMicros=" + property(db, "ldb.writeStall.immutableWaitMicros")
        + " ldbLevel0StopWaitCount=" + property(db, "ldb.writeStall.level0StopWaitCount")
        + " ldbLevel0StopWaitMicros=" + property(db, "ldb.writeStall.level0StopWaitMicros"));
    return snapshot;
  }

  private static LDB openDb(File dbDir, LongRunConfig config, boolean createIfMissing)
      throws Exception {
    Options options = new Options()
        .createIfMissing(createIfMissing)
        .verifyChecksums(true)
        .writeBufferSize(config.ldbWriteBufferSizeBytes())
        .groupCommitEnabled(config.ldbGroupCommitEnabled())
        .groupCommitMaxDelayNanos(config.ldbGroupCommitMaxDelayNanos())
        .groupCommitMaxBatchBytes(config.ldbGroupCommitMaxBatchBytes())
        .pluginCapabilityEnforcement(config.pluginCapabilityEnforcement())
        .pluginCallbackTimeoutMillis(config.pluginCallbackTimeoutMillis())
        .pluginAutoDisableOnTimeout(config.pluginAutoDisableOnTimeout())
        .pluginAutoDisableFailureThreshold(config.pluginAutoDisableFailureThreshold())
        .pluginAsyncEnabled(config.pluginAsyncEnabled())
        .pluginAsyncQueueCapacity(config.pluginAsyncQueueCapacity())
        .pluginAsyncCloseTimeoutMillis(config.pluginAsyncCloseTimeoutMillis())
        .pluginMaxTotalCallbackMillis(config.pluginMaxTotalCallbackMillis());
    List<LdbPlugin> plugins = new LongRunPluginResolver().resolve(config);
    for (LdbPlugin plugin : plugins) {
      options.addPlugin(plugin);
    }
    return LDBFactory.factory.open(dbDir, options);
  }

  private static void printPluginState(PrintStream out, LDB db) {
    out.println("PLUGIN list=" + property(db, "ldb.plugins"));
    out.println("PLUGIN stats=" + property(db, "ldb.pluginStats"));
    out.println("PLUGIN executionPolicy=" + property(db, "ldb.plugin.executionPolicy"));
    out.println("PLUGIN asyncStats=" + property(db, "ldb.plugin.asyncStats"));
    out.println("PLUGIN degraded=" + property(db, "ldb.plugin.degraded"));
    out.println("PLUGIN disabled=" + property(db, "ldb.plugin.disabled"));
    out.println("PLUGIN sandbox=" + property(db, "ldb.plugin.sandbox"));
  }

  private static void writeRunState(File stateDir, LongRunConfig config) throws Exception {
    if (!stateDir.exists() && !stateDir.mkdirs()) {
      throw new IllegalStateException("failed to create state dir: " + stateDir);
    }
    Properties properties = new Properties();
    properties.setProperty("workloadSyncWrites", Boolean.toString(config.syncWrites()));
    properties.setProperty("ldbGroupCommitEnabled", Boolean.toString(config.ldbGroupCommitEnabled()));
    properties.setProperty("ldbGroupCommitMaxDelayNanos", Long.toString(config.ldbGroupCommitMaxDelayNanos()));
    properties.setProperty("ldbGroupCommitMaxBatchBytes", Long.toString(config.ldbGroupCommitMaxBatchBytes()));
    properties.setProperty("ldbPluginAsyncEnabled", Boolean.toString(config.pluginAsyncEnabled()));
    properties.setProperty("ldbPluginMaxTotalCallbackMillis", Long.toString(config.pluginMaxTotalCallbackMillis()));
    try (FileOutputStream out = new FileOutputStream(new File(stateDir, "run.properties"))) {
      properties.store(out, "longrun run state");
    }
  }

  private static void writePluginState(File stateDir, LDB db) throws Exception {
    if (!stateDir.exists() && !stateDir.mkdirs()) {
      throw new IllegalStateException("failed to create state dir: " + stateDir);
    }
    Properties properties = new Properties();
    properties.setProperty("plugins", property(db, "ldb.plugins"));
    properties.setProperty("pluginStats", property(db, "ldb.pluginStats"));
    properties.setProperty("pluginLastFailure", property(db, "ldb.plugin.lastFailure"));
    properties.setProperty("pluginExecutionPolicy", property(db, "ldb.plugin.executionPolicy"));
    properties.setProperty("pluginAsyncStats", property(db, "ldb.plugin.asyncStats"));
    properties.setProperty("pluginDegraded", property(db, "ldb.plugin.degraded"));
    properties.setProperty("pluginDisabled", property(db, "ldb.plugin.disabled"));
    properties.setProperty("pluginSandbox", property(db, "ldb.plugin.sandbox"));
    try (FileOutputStream out = new FileOutputStream(new File(stateDir, "plugin.properties"))) {
      properties.store(out, "longrun plugin state");
    }
  }

  private static String property(LDB db, String name) {
    try {
      String value = db.getProperty(name);
      return value == null ? "" : value;
    } catch (RuntimeException e) {
      return "unavailable";
    }
  }

  private static void printWorkloadResult(PrintStream out, CommittedState state,
                                          ProgressSnapshot snapshot) {
    out.println("RESULT phase=workload"
        + " operations=" + state.operations()
        + " avgOpsPerSecond=" + formatDouble(snapshot.avgOpsPerSecond)
        + " minOpsPerSecond=" + formatDouble(snapshot.minOpsPerSecond)
        + " maxOpsPerSecond=" + formatDouble(snapshot.maxOpsPerSecond)
        + " reads=" + state.reads()
        + " writes=" + state.writes()
        + " removes=" + state.removes()
        + " commits=" + state.commits()
        + " activeKeys=" + state.active().size());
  }

  private static void printSummary(PrintStream out, ReportSummary summary) {
    out.println("SUMMARY status=" + summary.get("status")
        + " operations=" + summary.get("operations")
        + " metricSamples=" + summary.get("metricSamples")
        + " warmupSamples=" + summary.get("warmupSamples")
        + " trailingPartialSamples=" + summary.get("trailingPartialSamples")
        + " measuredSamples=" + summary.get("measuredSamples")
        + " avgOpsPerSecond=" + summary.get("avgOpsPerSecond")
        + " minOpsPerSecond=" + summary.get("minOpsPerSecond")
        + " maxOpsPerSecond=" + summary.get("maxOpsPerSecond")
        + " p05OpsPerSecond=" + summary.get("p05OpsPerSecond")
        + " p50OpsPerSecond=" + summary.get("p50OpsPerSecond")
        + " p95OpsPerSecond=" + summary.get("p95OpsPerSecond")
        + " p50ReadOpsPerSecond=" + summary.get("p50ReadOpsPerSecond")
        + " p95ReadOpsPerSecond=" + summary.get("p95ReadOpsPerSecond")
        + " p50WriteOpsPerSecond=" + summary.get("p50WriteOpsPerSecond")
        + " p95WriteOpsPerSecond=" + summary.get("p95WriteOpsPerSecond")
        + " p50RemoveOpsPerSecond=" + summary.get("p50RemoveOpsPerSecond")
        + " p95RemoveOpsPerSecond=" + summary.get("p95RemoveOpsPerSecond")
        + " workloadSyncWrites=" + summary.get("workloadSyncWrites")
        + " ldbGroupCommitEnabled=" + summary.get("ldbGroupCommitEnabled")
        + " ldbPluginAsyncEnabled=" + summary.get("ldbPluginAsyncEnabled")
        + " throughputDropRatio=" + summary.get("throughputDropRatio")
        + " finalSizeBytes=" + summary.get("finalSizeBytes")
        + " sizeAmplification=" + summary.get("sizeAmplification"));
  }

  private static String formatDouble(double value) {
    return String.format(java.util.Locale.ROOT, "%.3f", value);
  }

  private static String join(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "";
    }
    return String.join(",", values);
  }

  private static String progressPercent(long now, long startMillis, long durationMillis) {
    if (durationMillis <= 0) {
      return "100.00";
    }
    double percent = Math.max(0.0D, Math.min(100.0D, (now - startMillis) * 100.0D / durationMillis));
    return String.format(java.util.Locale.ROOT, "%.2f", percent);
  }

  private static void prepareFreshRun(File workDir, PrintStream out) throws Exception {
    StringBuilder cleaned = new StringBuilder();
    for (String name : new String[] {"db", "state", "metrics", "report", "fault"}) {
      File target = new File(workDir, name);
      if (target.exists()) {
        deleteRecursively(target);
        if (cleaned.length() > 0) {
          cleaned.append(',');
        }
        cleaned.append(name);
      }
    }
    out.println("FRESH resume=false workDir=" + workDir.getPath()
        + " cleaned=" + (cleaned.length() == 0 ? "none" : cleaned.toString()));
  }

  private static void deleteRecursively(File file) throws Exception {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursively(child);
        }
      }
    }
    if (!file.delete() && file.exists()) {
      throw new java.io.IOException("failed to delete stale longrun path: " + file.getAbsolutePath());
    }
  }

  private static void writeResourceState(File stateDir, long physicalSize, long liveDataBytes)
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty("physicalSizeBytes", Long.toString(physicalSize));
    properties.setProperty("liveDataBytes", Long.toString(liveDataBytes));
    try (FileOutputStream out = new FileOutputStream(new File(stateDir, "resource.properties"))) {
      properties.store(out, "ldb-longrun resource state");
    }
  }

  private static long parseLong(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    return Long.parseLong(value.trim());
  }

  private static long directorySize(File file) {
    if (!file.exists()) {
      return 0;
    }
    if (file.isFile()) {
      return file.length();
    }
    long total = 0;
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        total += directorySize(child);
      }
    }
    return total;
  }

  private static final class ProgressStats {
    private final long startMillis;
    private final long startOperations;
    private long lastMillis;
    private long lastOperations;
    private double minOpsPerSecond = Double.MAX_VALUE;
    private double maxOpsPerSecond;

    private ProgressStats(long startMillis, long startOperations) {
      this.startMillis = startMillis;
      this.startOperations = startOperations;
      this.lastMillis = startMillis;
      this.lastOperations = startOperations;
    }

    private ProgressSnapshot sample(long now, long operations) {
      double windowOpsPerSecond = opsPerSecond(operations - lastOperations, now - lastMillis);
      double avgOpsPerSecond = opsPerSecond(operations - startOperations, now - startMillis);
      minOpsPerSecond = Math.min(minOpsPerSecond, windowOpsPerSecond);
      maxOpsPerSecond = Math.max(maxOpsPerSecond, windowOpsPerSecond);
      lastMillis = now;
      lastOperations = operations;
      return new ProgressSnapshot(windowOpsPerSecond, avgOpsPerSecond,
          minOpsPerSecond == Double.MAX_VALUE ? 0.0D : minOpsPerSecond, maxOpsPerSecond);
    }

    private static double opsPerSecond(long operations, long elapsedMillis) {
      return elapsedMillis <= 0 ? 0.0D : operations * 1000.0D / elapsedMillis;
    }
  }

  private static final class ProgressSnapshot {
    private final double windowOpsPerSecond;
    private final double avgOpsPerSecond;
    private final double minOpsPerSecond;
    private final double maxOpsPerSecond;

    private ProgressSnapshot(double windowOpsPerSecond, double avgOpsPerSecond,
                             double minOpsPerSecond, double maxOpsPerSecond) {
      this.windowOpsPerSecond = windowOpsPerSecond;
      this.avgOpsPerSecond = avgOpsPerSecond;
      this.minOpsPerSecond = minOpsPerSecond;
      this.maxOpsPerSecond = maxOpsPerSecond;
    }
  }
}
