package net.xdob.vexra.ldb.longrun.crash;

import net.xdob.vexra.ldb.longrun.LongRunMain;
import net.xdob.vexra.ldb.longrun.config.LongRunConfig;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * crash/recovery 父进程调度器。
 */
public final class CrashSupervisor {
  /**
   * 执行 crash/recovery profile。
   *
   * @param config 配置
   * @param originalArgs 原始 profile 参数
   * @param out 标准输出
   * @return 退出码
   * @throws Exception 子进程启动或等待失败时抛出
   */
  public int run(LongRunConfig config, String[] originalArgs, PrintStream out) throws Exception {
    int cycles = Math.max(1, config.crashCycles());
    long interval = Math.max(1, config.crashIntervalMillis());
    for (int i = 0; i < cycles; i++) {
      Process worker = startWorker(config, originalArgs, false, interval * 10);
      Thread.sleep(interval);
      worker.destroyForcibly();
      worker.waitFor();
      out.println("crash cycle " + (i + 1) + " killed worker");
      Process recovery = startWorker(config, originalArgs, true, Math.min(200L, interval));
      int recoveryExit = recovery.waitFor();
      if (recoveryExit != 0) {
        return recoveryExit;
      }
    }
    Process finalWorker = startWorker(config, originalArgs, true, 0);
    int finalExit = finalWorker.waitFor();
    if (finalExit == 0) {
      out.println("PASS crash recovery cycles=" + cycles);
    }
    return finalExit;
  }

  private Process startWorker(LongRunConfig config, String[] originalArgs,
                              boolean resume, long durationMillis) throws Exception {
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
    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    return builder.start();
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
