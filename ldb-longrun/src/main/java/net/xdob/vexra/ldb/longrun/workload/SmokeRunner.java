package net.xdob.vexra.ldb.longrun.workload;

import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.WriteOptions;
import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.longrun.config.LongRunConfig;
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
      LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true).verifyChecksums(true));
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
            db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false).verifyChecksums(true));
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
            db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false).verifyChecksums(true));
            verifier.verifyActive(db, state);
            verifier.verifyLedger(db, state, ledger);
            nextFault = System.currentTimeMillis() + faultInterval;
          }
          if (System.currentTimeMillis() >= nextMetrics) {
            metrics.sample(RunStats.fromState(state, reopenChecks, recoveryChecks));
            printProgress(out, startMillis, config.durationMillis(), state, reopenChecks, recoveryChecks, faultEvents);
            nextMetrics = System.currentTimeMillis() + config.metricsIntervalMillis();
          }
        }
        state.recordCommit();
        state.save(stateDir);
        metrics.sample(RunStats.fromState(state, reopenChecks, recoveryChecks));
        printProgress(out, startMillis, config.durationMillis(), state, reopenChecks, recoveryChecks, faultEvents);
        verifier.verifyActive(db, state);
        verifier.verifyLedger(db, state, ledger);
        long physicalSize = directorySize(dbDir);
        long liveDataBytes = parseLong(db.getProperty("ldb.liveDataBytes"));
        writeResourceState(stateDir, physicalSize, liveDataBytes);
        metrics.reclamation("success", "resource sample", physicalSize, physicalSize, liveDataBytes);
        metrics.event("verify", "PASS", "final verify succeeded");
        ReportSummary summary = new ReportAnalyzer().analyze(workDir);
        out.println("PASS smoke operations=" + state.operations()
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
    out.println("CONFIG run.name=" + config.runName());
    out.println("CONFIG run.instance=" + config.instance());
    out.println("CONFIG run.workDir=" + config.workDir().getPath());
    out.println("CONFIG run.durationMillis=" + config.durationMillis());
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
    out.println("CONFIG crash.enabled=" + config.crashEnabled());
    out.println("CONFIG crash.intervalMillis=" + config.crashIntervalMillis());
    out.println("CONFIG crash.cycles=" + config.crashCycles());
    out.println("CONFIG fault.enabled=" + config.faultEnabled());
    out.println("CONFIG fault.intervalMillis=" + config.faultIntervalMillis());
    out.println("CONFIG fault.kinds=" + config.faultKinds());
    out.println("CONFIG fault.retainedCopies=" + config.faultRetainedCopies());
  }

  private static void printProgress(PrintStream out, long startMillis, long durationMillis, CommittedState state,
                                    long reopenChecks, long recoveryChecks, long faultEvents) {
    long now = System.currentTimeMillis();
    out.println("PROGRESS timeMillis=" + now
        + " progressPercent=" + progressPercent(now, startMillis, durationMillis)
        + " operations=" + state.operations()
        + " reads=" + state.reads()
        + " writes=" + state.writes()
        + " removes=" + state.removes()
        + " commits=" + state.commits()
        + " activeKeys=" + state.active().size()
        + " reopenChecks=" + reopenChecks
        + " recoveryChecks=" + recoveryChecks
        + " faultEvents=" + faultEvents);
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
}
