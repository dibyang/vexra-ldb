package net.xdob.vexra.ldb.longrun.crash;

import net.xdob.vexra.ldb.longrun.LongRunMain;
import net.xdob.vexra.ldb.longrun.config.LongRunConfig;
import net.xdob.vexra.ldb.longrun.instance.InstancePaths;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * crash/recovery 容错系统管理器。
 */
public final class CrashSupervisor {
  private volatile Process activeProcess;

  /**
   * 执行 crash/recovery profile。
   *
   * @param config 配置
   * @param originalArgs 原始 profile 参数
   * @param out 控制台输出
   * @return 返回码
   * @throws Exception 运行异常或恢复失败将抛出。
   */
  public int run(LongRunConfig config, String[] originalArgs, PrintStream out) throws Exception {
    Thread cleanup = new Thread(new Runnable() {
      @Override
      public void run() {
        destroyActiveProcess();
      }
    }, "longrun-crash-cleanup");
    Runtime.getRuntime().addShutdownHook(cleanup);
    try {
      int cycles = Math.max(1, config.crashCycles());
      long interval = Math.max(1, config.crashIntervalMillis());
      File workerLog = workerLogFile(config);
      long recoveryTimeoutMillis = recoveryTimeoutMillis(config);
      printCrashProgress(out, 0, cycles, "start");
      out.println("crash workerLog=" + workerLog.getPath());
      for (int i = 0; i < cycles; i++) {
        printCrashProgress(out, i, cycles, "worker");
        Process worker = startWorker(config, originalArgs, false, interval * 10, workerLog);
        out.println("CRASH EVENT worker-start cycle=" + (i + 1)
            + " run.duration=" + (interval * 10) + "ms workerLog=" + workerLog.getPath());
        Thread.sleep(interval);
        worker.destroyForcibly();
        worker.waitFor();
        activeProcess = null;
        out.println("crash cycle " + (i + 1) + " killed worker");
        printCrashProgress(out, i + 0.5D, i, cycles, "recovery");
        long recoveryStart = System.currentTimeMillis();
        Process recovery = startWorker(config, originalArgs, true, Math.min(200L, interval), workerLog);
        int recoveryExit = waitForWithHeartbeat(out, recovery, i + 0.5D, i, cycles,
            "recovery", workerLog, recoveryTimeoutMillis);
        activeProcess = null;
        if (recoveryExit != 0) {
          if (recoveryExit == -1) {
            out.println("FAIL crash recovery timeout=" + recoveryTimeoutMillis
                + "ms workerLog=" + workerLog.getPath());
          } else {
            out.println("FAIL crash recovery workerExit=" + recoveryExit
                + " workerLog=" + workerLog.getPath());
          }
          return recoveryExit == -1 ? 4 : recoveryExit;
        }
        out.println("CRASH EVENT recovery-complete cycle=" + (i + 1)
            + " elapsedMillis=" + (System.currentTimeMillis() - recoveryStart)
            + " workerLog=" + workerLog.getPath());
        printCrashProgress(out, i + 1, cycles, "recovered");
      }
      printCrashProgress(out, cycles, cycles, "final-verify");
      out.println("CRASH EVENT final-verify-start cycles=" + cycles
          + " workerLog=" + workerLog.getPath());
      Process finalWorker = startWorker(config, originalArgs, true, 0, workerLog);
      int finalExit = waitForWithHeartbeat(out, finalWorker, (double) cycles, cycles, cycles,
          "final-verify", workerLog, recoveryTimeoutMillis);
      activeProcess = null;
      if (finalExit == 0) {
        out.println("CRASH EVENT final-verify-complete workerLog=" + workerLog.getPath());
        printCrashProgress(out, cycles, cycles, "done");
        out.println("PASS crash recovery cycles=" + cycles);
      } else {
        if (finalExit == -1) {
          out.println("FAIL crash finalWorker timeout=" + recoveryTimeoutMillis
              + "ms workerLog=" + workerLog.getPath());
        } else {
          out.println("FAIL crash finalWorkerExit=" + finalExit
              + " workerLog=" + workerLog.getPath());
        }
      }
      return finalExit == -1 ? 4 : finalExit;
    } finally {
      destroyActiveProcess();
      try {
        Runtime.getRuntime().removeShutdownHook(cleanup);
      } catch (IllegalStateException ignored) {
      }
    }
  }

  private static void printCrashProgress(PrintStream out, int completedCycles,
                                         int totalCycles, String phase) {
    printCrashProgress(out, completedCycles, completedCycles, totalCycles, phase, null);
  }

  private static void printCrashProgress(PrintStream out, double progressCycles,
                                         int completedCycles, int totalCycles, String phase) {
    printCrashProgress(out, progressCycles, completedCycles, totalCycles, phase, null);
  }

  private static void printCrashProgress(PrintStream out, double progressCycles,
                                         int completedCycles, int totalCycles,
                                         String phase, String details) {
    double percent = totalCycles <= 0
        ? 100.0D : progressCycles * 100.0D / totalCycles;
    percent = Math.max(0.0D, Math.min(100.0D, percent));
    StringBuilder line = new StringBuilder("CRASH PROGRESS progressPercent="
        + String.format(java.util.Locale.ROOT, "%.2f", percent)
        + " completedCycles=" + completedCycles
        + " totalCycles=" + totalCycles
        + " phase=" + phase);
    if (details != null && !details.trim().isEmpty()) {
      line.append(' ').append(details);
    }
    out.println(line);
  }

  private static int waitForWithHeartbeat(PrintStream out, Process process, double progressCycles,
                                         int completedCycles, int totalCycles, String phase,
                                         File workerLog, long timeoutMillis) throws Exception {
    long start = System.currentTimeMillis();
    long deadline = start + Math.max(timeoutMillis, 10_000L);
    while (true) {
      long now = System.currentTimeMillis();
      long remaining = deadline - now;
      if (remaining <= 0L) {
        process.destroyForcibly();
        printCrashProgress(out, progressCycles, completedCycles, totalCycles, phase + "-timedout",
            "elapsedMillis=" + (now - start) + " workerLog=" + workerLog.getPath());
        return -1;
      }
      if (process.waitFor(Math.min(5_000L, Math.max(1L, remaining)), TimeUnit.MILLISECONDS)) {
        return process.exitValue();
      }
      printCrashProgress(out, progressCycles, completedCycles, totalCycles, phase,
          "elapsedMillis=" + (now - start) + " workerLog=" + workerLog.getPath());
    }
  }

  private static long recoveryTimeoutMillis(LongRunConfig config) {
    long base = Math.max(30_000L, config.durationMillis());
    long fromCrashInterval = Math.min(3_600_000L, config.crashIntervalMillis() * 4L);
    return Math.max(base, fromCrashInterval);
  }

  private Process startWorker(LongRunConfig config, String[] originalArgs,
                              boolean resume, long durationMillis, File workerLog) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(LongRunMain.class.getName());
    command.add("worker");
    command.addAll(Arrays.asList(originalArgs));
    command.add("--resume=" + resume);
    command.add("--run.duration=" + durationMillis);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(workerLog));
    Process process = builder.start();
    activeProcess = process;
    return process;
  }

  private static File workerLogFile(LongRunConfig config) {
    InstancePaths paths = new InstancePaths(config);
    paths.ensureDirs();
    File parentLog = paths.logFile();
    return new File(parentLog.getParentFile(), config.instance() + "-worker.out");
  }

  private void destroyActiveProcess() {
    Process process = activeProcess;
    if (process != null) {
      process.destroyForcibly();
    }
  }

  private static String javaBinary() {
    String javaHome = System.getProperty("java.home");
    File java = new File(new File(javaHome, "bin"), isWindows() ? "java.exe" : "java");
    return java.isFile() ? java.getAbsolutePath() : "java";
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
