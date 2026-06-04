package net.xdob.vexra.ldb.tool;

import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.LDBFactory;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LDB 最小命令行工具入口。
 *
 * 当前只开放无破坏性的 `check` 和 `properties` 命令，避免 repair/compact/restore
 * 等有副作用命令在缺少完整命令测试前被误用。该类不持有全局状态，`run` 方法可由测试直接调用。
 */
public final class LdbTool {
  private static final int EXIT_OK = 0;
  private static final int EXIT_USAGE = 1;
  private static final int EXIT_CHECK_FAILED = 2;
  private static final int EXIT_INTERNAL_ERROR = 4;

  private static final List<String> DEFAULT_PROPERTIES = Arrays.asList(
      "ldb.api.compatibility",
      "ldb.api.optionsMapping",
      "ldb.api.supportedFeatures",
      "ldb.api.unsupportedFeatures",
      "ldb.api.ecosystemGaps",
      "ldb.api.optionValues",
      "ldb.operationStats",
      "ldb.blockCacheStats",
      "ldb.compactionStats",
      "ldb.walPolicy",
      "ldb.snapshotCursorStats");

  private LdbTool() {
  }

  /**
   * 命令行进程入口；实际逻辑委托给 `run`，便于测试覆盖退出码和输出内容。
   */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * 执行 LDB 工具命令。
   *
   * @param args 命令行参数，首个参数为命令名
   * @param out 标准输出，承载 JSON 或 property 键值
   * @param err 错误输出，承载参数错误和异常摘要
   * @return 进程退出码，遵循第十七阶段计划中的命令语义
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args == null || args.length == 0) {
      printUsage(err);
      return EXIT_USAGE;
    }

    String command = args[0];
    try {
      if ("check".equals(command)) {
        return runCheck(args, out, err);
      }
      if ("properties".equals(command)) {
        return runProperties(args, out, err);
      }
      if ("repair".equals(command)) {
        return runRepair(args, out, err);
      }
      if ("repair-plan".equals(command)) {
        return runRepairPlan(args, out, err);
      }
      if ("backup".equals(command)) {
        return runBackup(args, out, err);
      }
      if ("incremental-backup".equals(command)) {
        return runIncrementalBackup(args, out, err);
      }
      if ("check-backup".equals(command)) {
        return runCheckBackup(args, out, err);
      }
      if ("restore".equals(command)) {
        return runRestore(args, out, err);
      }
      if ("checkpoint".equals(command)) {
        return runCheckpoint(args, out, err);
      }
      err.println("Unknown command: " + command);
      printUsage(err);
      return EXIT_USAGE;
    } catch (Exception e) {
      err.println("LDB tool failed: " + e.getClass().getName() + ": " + e.getMessage());
      return EXIT_INTERNAL_ERROR;
    }
  }

  private static int runCheck(String[] args, PrintStream out, PrintStream err) {
    if (args.length != 2) {
      err.println("Usage error: check requires exactly one database directory");
      printUsage(err);
      return EXIT_USAGE;
    }

    LDBFactory.CheckReport report = LDBFactory.factory.check(
        new File(args[1]),
        new Options().createIfMissing(false));
    out.print(report.toJson());
    return report.isOk() ? EXIT_OK : EXIT_CHECK_FAILED;
  }

  private static int runCheckpoint(String[] args, PrintStream out, PrintStream err) throws Exception {
    if (args.length != 3) {
      err.println("Usage error: checkpoint requires a database directory and a target directory");
      printUsage(err);
      return EXIT_USAGE;
    }

    File targetDir = new File(args[2]);
    try (LDB db = LDBFactory.factory.open(
        new File(args[1]),
        new Options().createIfMissing(false))) {
      db.checkpoint(targetDir.getAbsolutePath());
    }
    File report = new File(targetDir, "CHECKPOINT-REPORT.json");
    if (report.isFile()) {
      out.print(new String(Files.readAllBytes(report.toPath()), "UTF-8"));
    } else {
      out.println("{");
      out.println("  \"databaseDir\": \"" + escape(targetDir.getAbsolutePath()) + "\",");
      out.println("  \"checkpointReport\": \"missing\"");
      out.println("}");
    }
    return EXIT_OK;
  }

  private static int runRepair(String[] args, PrintStream out, PrintStream err) throws Exception {
    if (args.length != 2) {
      err.println("Usage error: repair requires exactly one database directory");
      printUsage(err);
      return EXIT_USAGE;
    }

    File databaseDir = new File(args[1]);
    LDBFactory.factory.repair(databaseDir, new Options().createIfMissing(false));
    File report = new File(databaseDir, "REPAIR-REPORT.json");
    if (report.isFile()) {
      out.print(new String(Files.readAllBytes(report.toPath()), "UTF-8"));
    } else {
      out.println("{");
      out.println("  \"databaseDir\": \"" + escape(databaseDir.getAbsolutePath()) + "\",");
      out.println("  \"repairReport\": \"missing\"");
      out.println("}");
    }
    return EXIT_OK;
  }

  private static int runRepairPlan(String[] args, PrintStream out, PrintStream err) throws Exception {
    if (args.length != 2) {
      err.println("Usage error: repair-plan requires exactly one database directory");
      printUsage(err);
      return EXIT_USAGE;
    }

    String report = LDBFactory.factory.planRepair(
        new File(args[1]),
        new Options().createIfMissing(false));
    out.print(report);
    return EXIT_OK;
  }

  private static int runBackup(String[] args, PrintStream out, PrintStream err) throws Exception {
    if (args.length != 3) {
      err.println("Usage error: backup requires a database directory and a backup root");
      printUsage(err);
      return EXIT_USAGE;
    }

    LDBFactory.BackupReport report = LDBFactory.factory.createBackup(
        new File(args[1]),
        new File(args[2]),
        new Options().createIfMissing(false));
    out.print(report.toJson());
    return report.isOk() ? EXIT_OK : EXIT_CHECK_FAILED;
  }

  private static int runIncrementalBackup(String[] args, PrintStream out, PrintStream err) throws Exception {
    if (args.length != 3) {
      err.println("Usage error: incremental-backup requires a database directory and a backup root");
      printUsage(err);
      return EXIT_USAGE;
    }

    LDBFactory.BackupReport report = LDBFactory.factory.createIncrementalBackup(
        new File(args[1]),
        new File(args[2]),
        new Options().createIfMissing(false));
    out.print(report.toJson());
    return report.isOk() ? EXIT_OK : EXIT_CHECK_FAILED;
  }

  private static int runCheckBackup(String[] args, PrintStream out, PrintStream err) {
    if (args.length != 2) {
      err.println("Usage error: check-backup requires exactly one backup directory");
      printUsage(err);
      return EXIT_USAGE;
    }

    LDBFactory.CheckReport report = LDBFactory.factory.checkBackup(
        new File(args[1]),
        new Options().createIfMissing(false));
    out.print(report.toJson());
    return report.isOk() ? EXIT_OK : EXIT_CHECK_FAILED;
  }

  private static int runRestore(String[] args, PrintStream out, PrintStream err) throws Exception {
    if (args.length != 3) {
      err.println("Usage error: restore requires a backup directory and a target directory");
      printUsage(err);
      return EXIT_USAGE;
    }

    LDBFactory.BackupReport report = LDBFactory.factory.restoreBackup(
        new File(args[1]),
        new File(args[2]),
        new Options().createIfMissing(false));
    out.print(report.toJson());
    return report.isOk() ? EXIT_OK : EXIT_CHECK_FAILED;
  }

  private static int runProperties(String[] args, PrintStream out, PrintStream err) throws Exception {
    if (args.length < 2) {
      err.println("Usage error: properties requires a database directory");
      printUsage(err);
      return EXIT_USAGE;
    }

    File databaseDir = new File(args[1]);
    List<String> propertyNames = new ArrayList<>();
    if (args.length == 2) {
      propertyNames.addAll(DEFAULT_PROPERTIES);
    } else {
      propertyNames.addAll(Arrays.asList(args).subList(2, args.length));
    }

    try (LDB db = LDBFactory.factory.open(databaseDir,
        new Options().createIfMissing(false).readOnly(true))) {
      List<String> values = new ArrayList<>();
      for (String name : propertyNames) {
        String value = db.getProperty(name);
        if (value == null) {
          err.println("Unknown property: " + name);
          return EXIT_USAGE;
        }
        values.add(value);
      }

      out.println("{");
      for (int i = 0; i < propertyNames.size(); i++) {
        String name = propertyNames.get(i);
        out.print("  \"");
        out.print(escape(name));
        out.print("\": \"");
        out.print(escape(values.get(i)));
        out.print("\"");
        if (i + 1 < propertyNames.size()) {
          out.print(',');
        }
        out.println();
      }
      out.println("}");
    }
    return EXIT_OK;
  }

  private static void printUsage(PrintStream err) {
    err.println("Usage:");
    err.println("  ldb check <db>");
    err.println("  ldb properties <db> [property...]");
    err.println("  ldb repair-plan <db>");
    err.println("  ldb repair <db>");
    err.println("  ldb backup <db> <backupRoot>");
    err.println("  ldb incremental-backup <db> <backupRoot>");
    err.println("  ldb check-backup <backupDir>");
    err.println("  ldb restore <backupDir> <targetDir>");
    err.println("  ldb checkpoint <db> <targetDir>");
  }

  private static String escape(String value) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        builder.append('\\').append(c);
      } else if (c == '\n') {
        builder.append("\\n");
      } else if (c == '\r') {
        builder.append("\\r");
      } else if (c == '\t') {
        builder.append("\\t");
      } else {
        builder.append(c);
      }
    }
    return builder.toString();
  }
}
