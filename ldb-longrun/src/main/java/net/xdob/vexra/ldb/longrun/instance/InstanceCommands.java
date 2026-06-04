package net.xdob.vexra.ldb.longrun.instance;

import net.xdob.vexra.ldb.longrun.LongRunMain;
import net.xdob.vexra.ldb.longrun.config.LongRunConfig;

import java.io.File;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * start/status/logs/stop/restart 实例命令。
 */
public final class InstanceCommands {
  public int start(LongRunConfig config, String[] args, PrintStream out) throws Exception {
    InstancePaths paths = new InstancePaths(config);
    paths.ensureDirs();
    if (paths.pidFile().isFile()) {
      String pid = new String(java.nio.file.Files.readAllBytes(paths.pidFile().toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
      if (!pid.isEmpty() && isLongRunProcess(pid)) {
        throw new IllegalStateException("instance already running: " + config.instance());
      }
      paths.pidFile().delete();
    }
    printStartConfig(config, out);
    rotateLog(paths.logFile());
    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(LongRunMain.class.getName());
    command.add("run");
    command.addAll(Arrays.asList(args));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logFile()));
    Process process = builder.start();
    long pid = pid(process);
    java.nio.file.Files.write(paths.pidFile().toPath(), Long.toString(pid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    out.println("started instance=" + config.instance() + " pid=" + pid + " log=" + paths.logFile().getPath());
    return 0;
  }

  private static void printStartConfig(LongRunConfig config, PrintStream out) {
    out.println("config run.name=" + config.runName());
    out.println("config run.instance=" + config.instance());
    out.println("config run.workDir=" + config.workDir().getPath());
    out.println("config run.durationMillis=" + config.durationMillis());
    out.println("config workload.keySpace=" + config.keySpace());
    out.println("config metrics.intervalMillis=" + config.metricsIntervalMillis());
    out.println("config check.reopenIntervalMillis=" + config.reopenIntervalMillis());
    out.println("config crash.enabled=" + config.crashEnabled());
    out.println("config fault.enabled=" + config.faultEnabled());
  }

  public int watch(LongRunConfig config, String[] args, PrintStream out) throws Exception {
    InstancePaths paths = new InstancePaths(config);
    boolean running = false;
    if (paths.pidFile().isFile()) {
      String pid = new String(java.nio.file.Files.readAllBytes(paths.pidFile().toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
      running = !pid.isEmpty() && isLongRunProcess(pid);
      if (!running) {
        paths.pidFile().delete();
      }
    }
    if (!running) {
      start(config, args, out);
    } else {
      out.println("watch existing instance=" + config.instance());
    }
    return logs(config, out);
  }

  private static void rotateLog(File log) {
    if (!log.isFile() || log.length() == 0) {
      return;
    }
    for (int i = 8; i >= 1; i--) {
      File source = new File(log.getPath() + "." + i);
      File target = new File(log.getPath() + "." + (i + 1));
      if (source.isFile()) {
        if (target.isFile()) {
          target.delete();
        }
        source.renameTo(target);
      }
    }
    File first = new File(log.getPath() + ".1");
    if (first.isFile()) {
      first.delete();
    }
    log.renameTo(first);
  }

  public int status(LongRunConfig config, PrintStream out) throws Exception {
    InstancePaths paths = new InstancePaths(config);
    if (!paths.pidFile().isFile()) {
      out.println("STOPPED instance=" + config.instance());
      return 1;
    }
    String pid = new String(java.nio.file.Files.readAllBytes(paths.pidFile().toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
    boolean alive = !pid.isEmpty() && isLongRunProcess(pid);
    if (!alive) {
      paths.pidFile().delete();
    }
    out.println((alive ? "RUNNING" : "STOPPED") + " instance=" + config.instance() + " pid=" + pid);
    return alive ? 0 : 1;
  }

  public int stop(LongRunConfig config, PrintStream out) throws Exception {
    InstancePaths paths = new InstancePaths(config);
    if (!paths.pidFile().isFile()) {
      out.println("STOPPED instance=" + config.instance());
      return 0;
    }
    String pid = new String(java.nio.file.Files.readAllBytes(paths.pidFile().toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
    if (!pid.isEmpty() && isLongRunProcess(pid)) {
      kill(pid);
    }
    paths.pidFile().delete();
    out.println("stopped instance=" + config.instance());
    return 0;
  }

  public int logs(LongRunConfig config, PrintStream out) throws Exception {
    InstancePaths paths = new InstancePaths(config);
    File log = paths.logFile();
    out.println("log=" + log.getPath());
    String pid = paths.pidFile().isFile()
        ? new String(java.nio.file.Files.readAllBytes(paths.pidFile().toPath()), java.nio.charset.StandardCharsets.UTF_8).trim()
        : "";
    boolean follow = !pid.isEmpty() && isLongRunProcess(pid);
    if (!log.isFile()) {
      if (!follow) {
        return 0;
      }
      while (!log.isFile() && isLongRunProcess(pid)) {
        Thread.sleep(500L);
      }
    }
    if (!log.isFile()) {
      return 0;
    }
    ProgressPrinter printer = new ProgressPrinter(out);
    printTail(log, printer, 50);
    if (follow) {
      try (RandomAccessFile reader = new RandomAccessFile(log, "r")) {
        reader.seek(log.length());
        while (isLongRunProcess(pid)) {
          String line = reader.readLine();
          if (line == null) {
            Thread.sleep(500L);
          } else {
            printer.printLine(decodeLogLine(line));
          }
        }
        String line;
        while ((line = reader.readLine()) != null) {
          printer.printLine(decodeLogLine(line));
        }
        printer.finish();
      }
    } else {
      printer.finish();
    }
    return 0;
  }

  private static void printTail(File log, ProgressPrinter printer, int maxLines) throws Exception {
    List<String> lines = java.nio.file.Files.readAllLines(log.toPath(), java.nio.charset.StandardCharsets.UTF_8);
    int start = Math.max(0, lines.size() - maxLines);
    for (String line : lines.subList(start, lines.size())) {
      printer.printLine(line);
    }
  }

  private static String decodeLogLine(String line) {
    return new String(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private static final class ProgressPrinter {
    private final PrintStream out;
    private int progressLength;
    private boolean progressOpen;

    private ProgressPrinter(PrintStream out) {
      this.out = out;
    }

    private void printLine(String line) {
      if (progressIndex(line) >= 0) {
        printProgress(formatProgressLineForConsole(line));
      } else {
        finish();
        out.println(line);
      }
    }

    private void printProgress(String line) {
      String padding = repeatSpaces(Math.max(0, progressLength - line.length()));
      out.print('\r');
      out.print(line);
      out.print(padding);
      out.flush();
      progressLength = line.length();
      progressOpen = true;
    }

    private void finish() {
      if (progressOpen) {
        out.println();
        progressOpen = false;
        progressLength = 0;
      }
    }

    private static String repeatSpaces(int count) {
      if (count <= 0) {
        return "";
      }
      StringBuilder builder = new StringBuilder(count);
      for (int i = 0; i < count; i++) {
        builder.append(' ');
      }
      return builder.toString();
    }
  }

  static String formatProgressLineForConsole(String line) {
    int progress = progressIndex(line);
    if (progress < 0) {
      return line;
    }
    String percentText = fieldValue(line, "progressPercent");
    if (percentText == null) {
      percentText = fieldValue(line, "percent");
    }
    if (percentText == null) {
      return line;
    }
    double percent;
    try {
      percent = Double.parseDouble(percentText);
    } catch (NumberFormatException e) {
      return line;
    }
    percent = Math.max(0.0D, Math.min(100.0D, percent));
    String prefix = line.substring(0, progress);
    String rest = removeField(line.substring(progress + "PROGRESS ".length()), "progressPercent");
    rest = removeField(rest, "percent").trim();
    return prefix + "PROGRESS " + progressBar(percent, 20) + " "
        + String.format(java.util.Locale.ROOT, "%3.0f%%", percent)
        + (rest.isEmpty() ? "" : " " + rest);
  }

  private static int progressIndex(String line) {
    if (line.startsWith("PROGRESS ")) {
      return 0;
    }
    int index = line.indexOf(" PROGRESS ");
    return index < 0 ? -1 : index + 1;
  }

  private static String fieldValue(String line, String name) {
    String token = name + "=";
    int start = line.indexOf(token);
    if (start < 0) {
      return null;
    }
    start += token.length();
    int end = start;
    while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
      end++;
    }
    return line.substring(start, end);
  }

  private static String removeField(String line, String name) {
    String token = name + "=";
    int start = line.indexOf(token);
    if (start < 0) {
      return line;
    }
    int end = start + token.length();
    while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
      end++;
    }
    if (end < line.length()) {
      end++;
    }
    return (line.substring(0, start) + line.substring(end)).trim();
  }

  private static String progressBar(double percent, int width) {
    int filled = (int) Math.round(percent * width / 100.0D);
    filled = Math.max(0, Math.min(width, filled));
    StringBuilder builder = new StringBuilder(width + 2);
    builder.append('[');
    for (int i = 0; i < width; i++) {
      builder.append(i < filled ? '#' : '-');
    }
    builder.append(']');
    return builder.toString();
  }

  private static long pid(Process process) throws Exception {
    try {
      java.lang.reflect.Method method = Process.class.getMethod("pid");
      return ((Number) method.invoke(process)).longValue();
    } catch (Exception ignored) {
    }
    try {
      Field field = process.getClass().getDeclaredField("pid");
      field.setAccessible(true);
      return ((Number) field.get(process)).longValue();
    } catch (Exception e) {
      String text = process.toString();
      int pidIndex = text.indexOf("pid=");
      if (pidIndex >= 0) {
        int start = pidIndex + 4;
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
          end++;
        }
        if (end > start) {
          return Long.parseLong(text.substring(start, end));
        }
      }
      return -1;
    }
  }

  private static boolean isAlive(String pid) throws Exception {
    Process process;
    if (isWindows()) {
      process = new ProcessBuilder("cmd", "/c", "tasklist /FI \"PID eq " + pid + "\" | findstr " + pid).start();
    } else {
      process = new ProcessBuilder("sh", "-c", "kill -0 " + pid).start();
    }
    return process.waitFor() == 0;
  }

  private static boolean isLongRunProcess(String pid) throws Exception {
    if (!isAlive(pid)) {
      return false;
    }
    if (isWindows()) {
      return true;
    }
    File cmdline = new File("/proc/" + pid + "/cmdline");
    if (!cmdline.isFile()) {
      return false;
    }
    String command = new String(java.nio.file.Files.readAllBytes(cmdline.toPath()),
        java.nio.charset.StandardCharsets.UTF_8).replace('\0', ' ');
    return command.contains(LongRunMain.class.getName());
  }

  private static void kill(String pid) throws Exception {
    if (isWindows()) {
      Process process = new ProcessBuilder("cmd", "/c", "taskkill /PID " + pid + " /T /F").start();
      process.waitFor();
    } else {
      killUnixTree(pid);
    }
  }

  private static void killUnixTree(String pid) throws Exception {
    if (!isNumeric(pid)) {
      return;
    }
    for (String child : unixChildren(pid)) {
      killUnixTree(child);
    }
    signalUnix(pid, "TERM");
    Thread.sleep(200L);
    if (isAlive(pid)) {
      for (String child : unixChildren(pid)) {
        killUnixTree(child);
      }
      signalUnix(pid, "KILL");
    }
  }

  private static List<String> unixChildren(String pid) throws Exception {
    File childrenFile = new File("/proc/" + pid + "/task/" + pid + "/children");
    List<String> children = new ArrayList<>();
    if (!childrenFile.isFile()) {
      return children;
    }
    String text = new String(java.nio.file.Files.readAllBytes(childrenFile.toPath()),
        java.nio.charset.StandardCharsets.UTF_8).trim();
    if (text.isEmpty()) {
      return children;
    }
    for (String child : text.split("\\s+")) {
      if (isNumeric(child)) {
        children.add(child);
      }
    }
    return children;
  }

  private static void signalUnix(String pid, String signal) throws Exception {
    Process process = new ProcessBuilder("kill", "-" + signal, pid).start();
    process.waitFor();
  }

  private static boolean isNumeric(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
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
