package net.xdob.vexra.ldb.longrun.instance;

import net.xdob.vexra.ldb.longrun.config.LongRunConfig;

import java.io.File;

/**
 * longrun instance 的 pid 和日志路径。
 */
public final class InstancePaths {
  private final File runDir;
  private final File logsDir;
  private final String instance;

  public InstancePaths(LongRunConfig config) {
    File base = config.workDir().getParentFile() == null
        ? new File(".") : config.workDir().getParentFile().getParentFile();
    if (base == null) {
      base = new File(".");
    }
    this.runDir = new File(base, "run");
    this.logsDir = new File(base, "logs");
    this.instance = config.instance();
  }

  public File pidFile() {
    return new File(runDir, instance + ".pid");
  }

  public File logFile() {
    return new File(logsDir, instance + ".out");
  }

  public void ensureDirs() {
    runDir.mkdirs();
    logsDir.mkdirs();
  }
}
