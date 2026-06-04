package net.xdob.vexra.ldb.longrun;

import net.xdob.vexra.ldb.longrun.config.LongRunConfig;
import net.xdob.vexra.ldb.longrun.crash.CrashSupervisor;
import net.xdob.vexra.ldb.longrun.instance.InstanceCommands;
import net.xdob.vexra.ldb.longrun.report.ReportAnalyzer;
import net.xdob.vexra.ldb.longrun.report.ReportSummary;
import net.xdob.vexra.ldb.longrun.workload.SmokeRunner;

import java.io.File;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * LDB 长稳压测工具入口。
 *
 * <p>该入口属于独立 longrun 子项目，只负责解析顶层命令并分发到后续模块；具体
 * workload、故障注入和报告逻辑在后续阶段逐步接入。
 */
public final class LongRunMain {
  private LongRunMain() {
  }

  /**
   * 启动 longrun 命令行。
   *
   * @param args 命令行参数
   */
  public static void main(String[] args) {
    int exitCode = run(args, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /**
   * 执行命令并返回进程退出码。
   *
   * @param args 命令行参数
   * @param out 标准输出
   * @param err 标准错误
   * @return 退出码，0 表示成功
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args == null || args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
      printUsage(out);
      return 0;
    }

    String command = args[0];
    if ("run".equals(command)) {
      try {
        LongRunConfig config = LongRunConfig.load(tail(args));
        if (config.crashEnabled()) {
          return new CrashSupervisor().run(config, tail(args), out);
        }
        return new SmokeRunner().run(config, out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        e.printStackTrace(err);
        return 3;
      }
    }

    if ("worker".equals(command)) {
      try {
        return new SmokeRunner().run(LongRunConfig.load(tail(args)), out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        e.printStackTrace(err);
        return 3;
      }
    }

    if ("report".equals(command)) {
      try {
        File workDir = reportWorkDir(tail(args));
        ReportSummary summary = new ReportAnalyzer().analyze(workDir);
        out.println("Report status=" + summary.get("status") + " workDir=" + workDir.getPath());
        return "FAIL".equals(summary.get("status")) ? 4 : 0;
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        e.printStackTrace(err);
        return 3;
      }
    }

    if ("start".equals(command)) {
      try {
        return new InstanceCommands().start(LongRunConfig.load(tail(args)), tail(args), out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        e.printStackTrace(err);
        return 3;
      }
    }

    if ("watch".equals(command)) {
      try {
        return new InstanceCommands().watch(LongRunConfig.load(tail(args)), tail(args), out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        e.printStackTrace(err);
        return 3;
      }
    }

    if ("status".equals(command)) {
      try {
        return new InstanceCommands().status(LongRunConfig.load(tail(args)), out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        return 3;
      }
    }

    if ("logs".equals(command)) {
      try {
        return new InstanceCommands().logs(LongRunConfig.load(tail(args)), out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        return 3;
      }
    }

    if ("stop".equals(command)) {
      try {
        return new InstanceCommands().stop(LongRunConfig.load(tail(args)), out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        return 3;
      }
    }

    if ("restart".equals(command)) {
      try {
        InstanceCommands commands = new InstanceCommands();
        LongRunConfig config = LongRunConfig.load(tail(args));
        commands.stop(config, out);
        return commands.start(config, tail(args), out);
      } catch (Exception e) {
        err.println("FAIL " + e.getMessage());
        return 3;
      }
    }

    err.println("Unknown command: " + command);
    printUsage(err);
    return 1;
  }

  private static String[] tail(String[] args) {
    return Arrays.copyOfRange(args, 1, args.length);
  }

  private static File reportWorkDir(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if ("--workDir".equals(args[i]) && i + 1 < args.length) {
        return new File(args[i + 1]);
      }
      if (args[i].startsWith("--workDir=")) {
        return new File(args[i].substring("--workDir=".length()));
      }
      if ("-w".equals(args[i]) && i + 1 < args.length) {
        return new File(args[i + 1]);
      }
    }
    throw new IllegalArgumentException("report requires --workDir/-w <dir>");
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage: longrun <command> [options]");
    out.println();
    out.println("Commands:");
    out.println("  run      Run a profile in the foreground");
    out.println("  start    Start a profile in the background");
    out.println("  watch    Start an instance if needed and follow logs");
    out.println("  stop     Stop an instance");
    out.println("  status   Show instance status");
    out.println("  logs     Tail instance logs");
    out.println("  restart  Restart an instance");
    out.println("  report   Re-analyze an existing workDir");
    out.println("  worker   Internal crash/recovery worker entry");
  }
}
