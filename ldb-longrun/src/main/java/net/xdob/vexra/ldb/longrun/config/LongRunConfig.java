package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.longrun.util.DurationParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * longrun profile 的强类型配置。
 *
 * <p>配置合并顺序为：内置默认值、profile 文件、系统属性、命令行覆盖。
 */
public final class LongRunConfig {
  private final Properties properties;

  private LongRunConfig(Properties properties) {
    this.properties = properties;
  }

  /**
   * 从命令行参数加载配置。
   *
   * @param args 不包含顶层命令名的参数
   * @return 配置对象
   * @throws IOException profile 读取失败时抛出
   */
  public static LongRunConfig load(String[] args) throws IOException {
    Map<String, String> cli = parseCli(args);
    Properties merged = defaults();
    String profile = cli.get("config");
    if (profile == null) {
      profile = cli.get("profile");
    }
    if (profile != null) {
      loadProfile(merged, profile);
    }
    for (String name : merged.stringPropertyNames()) {
      String systemValue = System.getProperty(name);
      if (systemValue != null) {
        merged.setProperty(name, systemValue);
      }
    }
    for (Map.Entry<String, String> entry : cli.entrySet()) {
      if (!"profile".equals(entry.getKey()) && !"config".equals(entry.getKey())) {
        merged.setProperty(entry.getKey(), entry.getValue());
      }
    }
    String instanceAlias = cli.get("instance");
    if (instanceAlias != null && cli.get("run.instance") == null) {
      merged.setProperty("run.instance", instanceAlias);
    }
    return new LongRunConfig(merged);
  }

  /**
   * 解析 `--key=value` 和 `--profile file` 参数。
   *
   * @param args 命令行参数
   * @return 按出现顺序保存的覆盖项
   */
  public static Map<String, String> parseCli(String[] args) {
    if (args == null || args.length == 0) {
      return Collections.emptyMap();
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      String option;
      if (arg.startsWith("--")) {
        option = arg.substring(2);
      } else if (arg.startsWith("-") && arg.length() == 2) {
        option = resolveShortOption(arg.substring(1));
      } else {
        throw new IllegalArgumentException("invalid argument: " + arg);
      }
      int eq = option.indexOf('=');
      if (eq >= 0) {
        values.put(option.substring(0, eq), option.substring(eq + 1));
      } else {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("missing value for argument: " + arg);
        }
        values.put(option, args[++i]);
      }
    }
    return values;
  }

  private static String resolveShortOption(String option) {
    if (option == null || option.length() != 1) {
      return option;
    }

    switch (option) {
      case "c":
        return "config";
      case "p":
        return "profile";
      case "i":
        return "instance";
      case "I":
        return "run.instance";
      case "w":
        return "workDir";
      case "W":
        return "run.workDir";
      case "d":
        return "run.duration";
      case "n":
        return "run.name";
      case "s":
        return "run.seed";
      case "r":
        return "resume";
      case "k":
        return "workload.keySpace";
      case "m":
        return "workload.mode";
      case "v":
        return "workload.valueSizeMin";
      case "V":
        return "workload.valueSizeMax";
      case "a":
        return "workload.commitEveryOps";
      case "q":
        return "workload.readRatio";
      case "y":
        return "workload.writeRatio";
      case "x":
        return "workload.removeRatio";
      case "t":
        return "metrics.interval";
      case "u":
        return "state.interval";
      case "o":
        return "check.reopenInterval";
      case "e":
        return "crash.enabled";
      case "z":
        return "crash.interval";
      case "l":
        return "crash.cycles";
      case "f":
        return "fault.enabled";
      case "g":
        return "fault.interval";
      case "j":
        return "fault.kinds";
      case "b":
        return "fault.maxBytes";
      case "h":
        return "fault.retainedCopies";
      case "L":
        return "limits.maxDbSizeGb";
      default:
        return option;
    }
  }

  private static Properties defaults() {
    Properties p = new Properties();
    p.setProperty("run.name", "smoke");
    p.setProperty("run.instance", "");
    p.setProperty("run.duration", "5m");
    p.setProperty("run.seed", "1");
    p.setProperty("run.workDir", "work/smoke");
    p.setProperty("workload.mode", "mixed");
    p.setProperty("workload.keySpace", "100000");
    p.setProperty("workload.valueSizeMin", "64");
    p.setProperty("workload.valueSizeMax", "4096");
    p.setProperty("workload.readRatio", "0.55");
    p.setProperty("workload.writeRatio", "0.35");
    p.setProperty("workload.removeRatio", "0.10");
    p.setProperty("workload.commitEveryOps", "1000");
    p.setProperty("metrics.interval", "10s");
    p.setProperty("state.interval", "30s");
    p.setProperty("check.reopenInterval", "0");
    p.setProperty("crash.enabled", "false");
    p.setProperty("crash.interval", "0");
    p.setProperty("crash.cycles", "0");
    p.setProperty("fault.enabled", "false");
    p.setProperty("fault.interval", "0");
    p.setProperty("fault.kinds", "");
    p.setProperty("fault.maxBytes", "4096");
    p.setProperty("fault.retainedCopies", "5");
    p.setProperty("limits.maxDbSizeGb", "20");
    return p;
  }

  private static String normalizeProfile(String profile) {
    String raw = profile == null ? "" : profile.trim();
    if (raw.isEmpty()) {
      return raw;
    }

    String withExt = raw.endsWith(".properties") ? raw : raw + ".properties";
    String[] candidates = {
        raw,
        withExt,
        "config/" + raw,
        "config/" + withExt,
        "config\\" + raw,
        "config\\" + withExt
    };
    for (String path : candidates) {
      if (new File(path).isFile()) {
        return path;
      }
    }
    return withExt;
  }

  private static String normalizeClasspathProfile(String profile) {
    if (profile == null || profile.isEmpty()) {
      return profile;
    }
    String fileName = profile;
    int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
    if (slash >= 0) {
      fileName = fileName.substring(slash + 1);
    }
    if (!fileName.endsWith(".properties")) {
      fileName = fileName + ".properties";
    }
    return fileName;
  }

  private static void loadProfile(Properties target, String profile) throws IOException {
    File file = new File(normalizeProfile(profile));
    InputStream in = null;
    if (file.isFile()) {
      in = new FileInputStream(file);
    } else {
      String name = normalizeClasspathProfile(profile);
      in = LongRunConfig.class.getResourceAsStream("/profiles/" + name);
      if (in == null) {
        throw new IOException("profile not found: " + profile);
      }
    }
    try {
      target.load(in);
    } finally {
      in.close();
    }
  }

  public String runName() {
    return get("run.name");
  }

  public String instance() {
    String configured = get("run.instance");
    return configured == null || configured.trim().isEmpty() ? runName() : configured.trim();
  }

  public long durationMillis() {
    return DurationParser.parseMillis(get("run.duration"));
  }

  public long seed() {
    return Long.parseLong(get("run.seed"));
  }

  public File workDir() {
    return new File(get("run.workDir"));
  }

  public long keySpace() {
    return Long.parseLong(get("workload.keySpace"));
  }

  public int valueSizeMin() {
    return Integer.parseInt(get("workload.valueSizeMin"));
  }

  public int valueSizeMax() {
    return Integer.parseInt(get("workload.valueSizeMax"));
  }

  public double readRatio() {
    return Double.parseDouble(get("workload.readRatio"));
  }

  public double writeRatio() {
    return Double.parseDouble(get("workload.writeRatio"));
  }

  public double removeRatio() {
    return Double.parseDouble(get("workload.removeRatio"));
  }

  public int commitEveryOps() {
    return Integer.parseInt(get("workload.commitEveryOps"));
  }

  public long metricsIntervalMillis() {
    return DurationParser.parseMillis(get("metrics.interval"));
  }

  public long stateIntervalMillis() {
    return DurationParser.parseMillis(get("state.interval"));
  }

  public long reopenIntervalMillis() {
    return DurationParser.parseMillis(get("check.reopenInterval"));
  }

  public boolean crashEnabled() {
    return Boolean.parseBoolean(get("crash.enabled"));
  }

  public long crashIntervalMillis() {
    return DurationParser.parseMillis(get("crash.interval"));
  }

  public int crashCycles() {
    return Integer.parseInt(get("crash.cycles"));
  }

  public boolean resume() {
    return Boolean.parseBoolean(properties.getProperty("resume",
        properties.getProperty("run.resume", "false")));
  }

  public boolean faultEnabled() {
    return Boolean.parseBoolean(get("fault.enabled"));
  }

  public long faultIntervalMillis() {
    return DurationParser.parseMillis(get("fault.interval"));
  }

  public String faultKinds() {
    return get("fault.kinds");
  }

  public int faultMaxBytes() {
    return Integer.parseInt(get("fault.maxBytes"));
  }

  public int faultRetainedCopies() {
    return Integer.parseInt(get("fault.retainedCopies"));
  }

  public String get(String key) {
    return properties.getProperty(key);
  }

  public Properties asProperties() {
    Properties copy = new Properties();
    copy.putAll(properties);
    return copy;
  }
}
