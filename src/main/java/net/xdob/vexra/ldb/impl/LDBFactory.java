package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.DBFactory;
import net.xdob.vexra.ldb.DBComparator;
import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.LdbColumnFamily;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.Filename.FileInfo;
import net.xdob.vexra.ldb.impl.Filename.FileType;
import net.xdob.vexra.ldb.table.BytewiseComparator;
import net.xdob.vexra.ldb.table.BlockCache;
import net.xdob.vexra.ldb.table.CustomUserComparator;
import net.xdob.vexra.ldb.table.FileChannelTable;
import net.xdob.vexra.ldb.table.TableBuilder;
import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.FileUtils;
import net.xdob.vexra.ldb.util.InternalTableIterator;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.SliceInput;
import net.xdob.vexra.ldb.util.Slices;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.Map.Entry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;


public class LDBFactory
    implements DBFactory {
  public static final int CPU_DATA_MODEL;

  static {
    boolean is64bit;
    if (System.getProperty("os.name").contains("Windows")) {
      is64bit = System.getenv("ProgramFiles(x86)") != null;
    } else {
      is64bit = System.getProperty("os.arch").contains("64");
    }
    CPU_DATA_MODEL = is64bit ? 64 : 32;
  }

  // We only use MMAP on 64 bit systems since it's really easy to run out of
  // virtual address space on a 32 bit system when all the data is getting mapped
  // into memory.  If you really want to use MMAP anyways, use -Dleveldb.mmap=true
  public static final boolean USE_MMAP = Boolean.parseBoolean(System.getProperty("leveldb.mmap", "" + (CPU_DATA_MODEL > 32)));

  public static final String VERSION;

  static {
    String v = "unknown";
    InputStream is = LDBFactory.class.getResourceAsStream("version.txt");
    try {
      v = new BufferedReader(new InputStreamReader(is, UTF_8)).readLine();
    } catch (Throwable e) {
    } finally {
      try {
        is.close();
      } catch (Throwable e) {
      }
    }
    VERSION = v;
  }

  public static final LDBFactory factory = new LDBFactory();

  @Override
  public LDB open(File path, Options options)
      throws IOException {
    return new LDbImpl(options, path);
  }

  @Override
  public void destroy(File path, Options options)
      throws IOException {
    // TODO: This should really only delete leveldb-created files.
    FileUtils.deleteRecursively(path);
  }

  @Override
  public void repair(File path, Options options)
      throws IOException {
    new Repairer(path, options).repair();
  }

  /**
   * 离线校验 LDB 目录中的 CURRENT、MANIFEST、SST 和 WAL 文件，并返回结构化报告。
   *
   * 该方法不获取数据库锁、不写入文件，也不修改现有恢复流程；调用方可以用报告中的 failures
   * 判断是否需要进一步 repair 或人工介入。
   */
  public CheckReport check(File path, Options options) {
    return new Checker(path, options).check();
  }

  /**
   * 创建离线全量备份。
   *
   * 源目录必须先通过 `check` 校验；备份先写入临时目录，复制完成并二次校验成功后才发布为
   * `backup-000001` 形式的目录，避免半成品备份被误认为可恢复版本。
   */
  public BackupReport createBackup(File sourceDir, File backupRoot, Options options) throws IOException {
    return new BackupEngine(sourceDir, backupRoot, options).createBackup();
  }

  /**
   * 从指定备份目录恢复到空目标目录，并输出恢复报告。
   *
   * 该方法不会覆盖已有目标内容；恢复完成后会对目标库执行 check，失败时返回失败报告并保留诊断信息。
   */
  public BackupReport restoreBackup(File backupDir, File targetDir, Options options) throws IOException {
    return new BackupEngine(backupDir, targetDir, options).restoreBackup();
  }

  /**
   * 清理旧备份版本，只保留最新的 keepLast 个已发布备份目录。
   *
   * 该方法只识别 `backup-000001` 形式的目录，不删除临时目录或其他文件；keepLast 必须大于等于 0。
   */
  public BackupCleanupReport purgeOldBackups(File backupRoot, int keepLast) throws IOException {
    return BackupEngine.purgeOldBackups(backupRoot, keepLast);
  }

  @Override
  public String toString() {
    return String.format("iq80 leveldb version %s", VERSION);
  }

  public static byte[] bytes(String value) {
    return (value == null) ? null : value.getBytes(UTF_8);
  }

  public static String asString(byte[] value) {
    return (value == null) ? null : new String(value, UTF_8);
  }

  /**
   * 全库离线校验报告，记录扫描到的文件、校验失败原因和基础记录计数。
   *
   * 报告对象是只读结果，不持有文件句柄；`ok=false` 表示至少一个文件或目录级约束校验失败。
   */
  public static final class CheckReport {
    private File databaseDir;
    private boolean ok = true;
    private final List<String> checkedFiles = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private long manifestRecords;
    private long walRecords;
    private long sstEntries;

    private void setDatabaseDir(File databaseDir) {
      this.databaseDir = databaseDir;
    }

    private void addCheckedFile(File file) {
      checkedFiles.add(file.getName());
    }

    private void addFailure(File file, String message) {
      ok = false;
      failures.add(file.getName() + ": " + message);
    }

    private void addFailure(String message) {
      ok = false;
      failures.add(message);
    }

    private void addManifestRecord() {
      manifestRecords++;
    }

    private void addWalRecord() {
      walRecords++;
    }

    private void addSstEntry() {
      sstEntries++;
    }

    /**
     * 返回本次校验的数据库目录。
     */
    public File getDatabaseDir() {
      return databaseDir;
    }

    /**
     * 返回全库校验是否通过。
     */
    public boolean isOk() {
      return ok;
    }

    /**
     * 返回成功纳入扫描流程的 LDB 文件名列表。
     */
    public List<String> getCheckedFiles() {
      return Collections.unmodifiableList(checkedFiles);
    }

    /**
     * 返回校验失败详情，包含文件名和失败原因。
     */
    public List<String> getFailures() {
      return Collections.unmodifiableList(failures);
    }

    /**
     * 返回已解析的 MANIFEST 记录数。
     */
    public long getManifestRecords() {
      return manifestRecords;
    }

    /**
     * 返回已解析的 WAL batch 记录数。
     */
    public long getWalRecords() {
      return walRecords;
    }

    /**
     * 返回已遍历的 SST entry 数。
     */
    public long getSstEntries() {
      return sstEntries;
    }

    @Override
    public String toString() {
      return "CheckReport{"
          + "databaseDir=" + databaseDir
          + ", ok=" + ok
          + ", checkedFiles=" + checkedFiles
          + ", failures=" + failures
          + ", manifestRecords=" + manifestRecords
          + ", walRecords=" + walRecords
          + ", sstEntries=" + sstEntries
          + '}';
    }

    public String toJson() {
      StringBuilder builder = new StringBuilder();
      builder.append("{\n");
      appendJsonField(builder, "databaseDir", databaseDir == null ? "" : databaseDir.getAbsolutePath(), true);
      appendJsonField(builder, "ok", ok, true);
      appendJsonField(builder, "checkedFiles", checkedFiles, true);
      appendJsonField(builder, "failures", failures, true);
      appendJsonField(builder, "manifestRecords", manifestRecords, true);
      appendJsonField(builder, "walRecords", walRecords, true);
      appendJsonField(builder, "sstEntries", sstEntries, false);
      builder.append("\n}\n");
      return builder.toString();
    }

    private static void appendJsonField(StringBuilder builder, String name, Object value, boolean comma) {
      builder.append("  \"").append(name).append("\": ");
      if (value instanceof String) {
        builder.append('"').append(escape((String) value)).append('"');
      } else if (value instanceof List) {
        appendJsonList(builder, (List<?>) value);
      } else {
        builder.append(value);
      }
      if (comma) {
        builder.append(',');
      }
      builder.append('\n');
    }

    private static void appendJsonList(StringBuilder builder, List<?> values) {
      builder.append('[');
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        builder.append('"').append(escape(String.valueOf(values.get(i)))).append('"');
      }
      builder.append(']');
    }

    private static String escape(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
  }

  private static final class Checker {
    private final File databaseDir;
    private final Options options;
    private final InternalKeyComparator internalKeyComparator;
    private final CheckReport report = new CheckReport();

    private Checker(File databaseDir, Options options) {
      this.databaseDir = databaseDir;
      this.options = options == null ? new Options() : options;
      DBComparator comparator = this.options.comparator();
      UserComparator userComparator = comparator != null
          ? new CustomUserComparator(comparator)
          : new BytewiseComparator();
      this.internalKeyComparator = new InternalKeyComparator(userComparator);
    }

    private CheckReport check() {
      report.setDatabaseDir(databaseDir);
      if (!databaseDir.exists()) {
        report.addFailure("Database directory does not exist: " + databaseDir);
        return report;
      }
      if (!databaseDir.isDirectory()) {
        report.addFailure("Database path is not a directory: " + databaseDir);
        return report;
      }

      checkCurrentFile();
      for (File file : Filename.listFiles(databaseDir)) {
        FileInfo info;
        try {
          info = Filename.parseFileName(file);
        } catch (RuntimeException e) {
          report.addFailure(file, rootMessage(e));
          continue;
        }
        if (info == null || !file.isFile()) {
          continue;
        }
        switch (info.getFileType()) {
          case DESCRIPTOR:
            checkManifest(file);
            break;
          case TABLE:
            checkTable(file, info.getFileNumber());
            break;
          case LOG:
            checkWal(file);
            break;
          case CURRENT:
            report.addCheckedFile(file);
            break;
          default:
            break;
        }
      }
      return report;
    }

    private void checkCurrentFile() {
      File current = new File(databaseDir, Filename.currentFileName());
      if (!current.exists()) {
        report.addFailure(current, "CURRENT file is missing");
        return;
      }
      try {
        String currentName = com.google.common.io.Files.toString(current, UTF_8);
        if (currentName.isEmpty() || currentName.charAt(currentName.length() - 1) != '\n') {
          report.addFailure(current, "CURRENT file does not end with newline");
          return;
        }
        File manifest = new File(databaseDir, currentName.substring(0, currentName.length() - 1));
        if (!manifest.isFile()) {
          report.addFailure(current, "CURRENT points to missing manifest: " + manifest.getName());
        }
      } catch (IOException e) {
        report.addFailure(current, rootMessage(e));
      }
    }

    private void checkManifest(File manifest) {
      report.addCheckedFile(manifest);
      try (FileInputStream input = new FileInputStream(manifest);
           FileChannel channel = input.getChannel()) {
        CollectingLogMonitor monitor = new CollectingLogMonitor();
        LogReader reader = new LogReader(channel, monitor, true, 0);
        for (Slice record = reader.readRecord(); record != null; record = reader.readRecord()) {
          new VersionEdit(record);
          report.addManifestRecord();
        }
        if (monitor.hasCorruption()) {
          report.addFailure(manifest, monitor.describe());
        }
      } catch (Throwable e) {
        report.addFailure(manifest, rootMessage(e));
      }
    }

    private void checkTable(File tableFile, long fileNumber) {
      report.addCheckedFile(tableFile);
      try (FileInputStream input = new FileInputStream(tableFile);
           FileChannel channel = input.getChannel()) {
        FileChannelTable table = new FileChannelTable(
            tableFile.getName(),
            channel,
            new InternalUserComparator(internalKeyComparator),
            true,
            options,
            new BlockCache(options.blockCacheSize()));
        try {
          InternalTableIterator iterator = new InternalTableIterator(table.iterator());
          iterator.seekToFirst();
          while (iterator.hasNext()) {
            iterator.next();
            report.addSstEntry();
          }
        } finally {
          table.closer().call();
        }
      } catch (Throwable e) {
        report.addFailure(tableFile, "sst " + fileNumber + " failed verification: " + rootMessage(e));
      }
    }

    private void checkWal(File wal) {
      report.addCheckedFile(wal);
      try (FileInputStream input = new FileInputStream(wal);
           FileChannel channel = input.getChannel()) {
        CollectingLogMonitor monitor = new CollectingLogMonitor();
        LogReader reader = new LogReader(channel, monitor, true, 0);
        for (Slice record = reader.readRecord(); record != null; record = reader.readRecord()) {
          SliceInput recordInput = record.input();
          if (recordInput.available() < 12) {
            report.addFailure(wal, "WAL record is too small");
            continue;
          }
          recordInput.readLong();
          int updateSize = recordInput.readInt();
          LdbWriteBatchLog.readWriteBatch(
              recordInput,
              updateSize,
              new LdbWriteBatchLog.ColumnFamilyResolver() {
                @Override
                public LdbColumnFamily getColumnFamily(int cfId) {
                  LdbColumnFamily cf = findColumnFamily(cfId);
                  if (cf == null) {
                    throw new IllegalArgumentException("Unknown column family id in WAL: " + cfId);
                  }
                  return cf;
                }
              });
          report.addWalRecord();
        }
        if (monitor.hasCorruption()) {
          report.addFailure(wal, monitor.describe());
        }
      } catch (Throwable e) {
        report.addFailure(wal, rootMessage(e));
      }
    }

    private LdbColumnFamily findColumnFamily(int cfId) {
      for (LdbColumnFamily cf : options.getColumnFamilies()) {
        if (cf.getId() == cfId) {
          return cf;
        }
      }
      return null;
    }
  }

  private static final class CollectingLogMonitor implements LogMonitor {
    private long corruptionBytes;
    private final List<String> reasons = new ArrayList<>();

    @Override
    public void corruption(long bytes, String reason) {
      corruptionBytes += bytes;
      reasons.add(reason);
    }

    @Override
    public void corruption(long bytes, Throwable reason) {
      corruptionBytes += bytes;
      reasons.add(rootMessage(reason));
    }

    private boolean hasCorruption() {
      return corruptionBytes > 0;
    }

    private String describe() {
      return "corruptionBytes=" + corruptionBytes + ", reasons=" + reasons;
    }
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return current.getClass().getName() + (message == null ? "" : ": " + message);
  }

  /**
   * 备份或恢复报告，记录复制文件、校验结果和失败原因。
   *
   * 报告可直接写入 JSON 文件，便于后续工具或人工审计。
   */
  public static final class BackupReport {
    private String action;
    private File sourceDir;
    private File targetDir;
    private boolean ok = true;
    private final List<String> copiedFiles = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private CheckReport checkReport;

    private void setAction(String action) {
      this.action = action;
    }

    private void setSourceDir(File sourceDir) {
      this.sourceDir = sourceDir;
    }

    private void setTargetDir(File targetDir) {
      this.targetDir = targetDir;
    }

    private void addCopiedFile(String name) {
      copiedFiles.add(name);
    }

    private void addFailure(String failure) {
      ok = false;
      failures.add(failure);
    }

    private void setCheckReport(CheckReport checkReport) {
      this.checkReport = checkReport;
      if (checkReport != null && !checkReport.isOk()) {
        ok = false;
        failures.addAll(checkReport.getFailures());
      }
    }

    /**
     * 返回报告动作类型，当前为 backup 或 restore。
     */
    public String getAction() {
      return action;
    }

    /**
     * 返回备份或恢复的源目录。
     */
    public File getSourceDir() {
      return sourceDir;
    }

    /**
     * 返回备份发布目录或恢复目标目录。
     */
    public File getTargetDir() {
      return targetDir;
    }

    /**
     * 返回备份或恢复流程是否整体成功。
     */
    public boolean isOk() {
      return ok;
    }

    /**
     * 返回已复制的 LDB 文件名列表。
     */
    public List<String> getCopiedFiles() {
      return Collections.unmodifiableList(copiedFiles);
    }

    /**
     * 返回备份或恢复失败原因。
     */
    public List<String> getFailures() {
      return Collections.unmodifiableList(failures);
    }

    /**
     * 返回备份发布目录或恢复目标目录的校验报告。
     */
    public CheckReport getCheckReport() {
      return checkReport;
    }

    /**
     * 将报告序列化为简单 JSON 文本，供备份目录中的报告文件保存。
     */
    public String toJson() {
      StringBuilder builder = new StringBuilder();
      builder.append("{\n");
      appendJsonField(builder, "action", action == null ? "" : action, true);
      appendJsonField(builder, "sourceDir", sourceDir == null ? "" : sourceDir.getAbsolutePath(), true);
      appendJsonField(builder, "targetDir", targetDir == null ? "" : targetDir.getAbsolutePath(), true);
      appendJsonField(builder, "ok", ok, true);
      appendJsonField(builder, "copiedFiles", copiedFiles, true);
      appendJsonField(builder, "failures", failures, true);
      appendJsonField(builder, "checkReport", checkReport == null ? "" : checkReport.toString(), false);
      builder.append("\n}\n");
      return builder.toString();
    }

    private static void appendJsonField(StringBuilder builder, String name, Object value, boolean comma) {
      builder.append("  \"").append(name).append("\": ");
      if (value instanceof String) {
        builder.append('"').append(escape((String) value)).append('"');
      } else if (value instanceof List) {
        appendJsonList(builder, (List<?>) value);
      } else {
        builder.append(value);
      }
      if (comma) {
        builder.append(',');
      }
      builder.append('\n');
    }

    private static void appendJsonList(StringBuilder builder, List<?> values) {
      builder.append('[');
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        builder.append('"').append(escape(String.valueOf(values.get(i)))).append('"');
      }
      builder.append(']');
    }

    private static String escape(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
      return "BackupReport{"
          + "action='" + action + '\''
          + ", sourceDir=" + sourceDir
          + ", targetDir=" + targetDir
          + ", ok=" + ok
          + ", copiedFiles=" + copiedFiles
          + ", failures=" + failures
          + ", checkReport=" + checkReport
          + '}';
    }
  }

  /**
   * 备份版本清理报告，记录保留版本、删除版本和失败原因。
   */
  public static final class BackupCleanupReport {
    private File backupRoot;
    private int keepLast;
    private boolean ok = true;
    private final List<String> retainedBackups = new ArrayList<>();
    private final List<String> deletedBackups = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private void setBackupRoot(File backupRoot) {
      this.backupRoot = backupRoot;
    }

    private void setKeepLast(int keepLast) {
      this.keepLast = keepLast;
    }

    private void addRetainedBackup(String backup) {
      retainedBackups.add(backup);
    }

    private void addDeletedBackup(String backup) {
      deletedBackups.add(backup);
    }

    private void addFailure(String failure) {
      ok = false;
      failures.add(failure);
    }

    /**
     * 返回清理目标备份根目录。
     */
    public File getBackupRoot() {
      return backupRoot;
    }

    /**
     * 返回请求保留的最新备份数量。
     */
    public int getKeepLast() {
      return keepLast;
    }

    /**
     * 返回清理流程是否成功。
     */
    public boolean isOk() {
      return ok;
    }

    /**
     * 返回保留的备份目录名。
     */
    public List<String> getRetainedBackups() {
      return Collections.unmodifiableList(retainedBackups);
    }

    /**
     * 返回已删除的备份目录名。
     */
    public List<String> getDeletedBackups() {
      return Collections.unmodifiableList(deletedBackups);
    }

    /**
     * 返回清理失败原因。
     */
    public List<String> getFailures() {
      return Collections.unmodifiableList(failures);
    }

    @Override
    public String toString() {
      return "BackupCleanupReport{"
          + "backupRoot=" + backupRoot
          + ", keepLast=" + keepLast
          + ", ok=" + ok
          + ", retainedBackups=" + retainedBackups
          + ", deletedBackups=" + deletedBackups
          + ", failures=" + failures
          + '}';
    }
  }

  private static final class BackupEngine {
    private static final String BACKUP_REPORT_FILE = "BACKUP-REPORT.json";
    private static final String RESTORE_REPORT_FILE = "RESTORE-REPORT.json";

    private final File sourceDir;
    private final File targetRoot;
    private final Options options;

    private BackupEngine(File sourceDir, File targetRoot, Options options) {
      this.sourceDir = sourceDir;
      this.targetRoot = targetRoot;
      this.options = options == null ? new Options() : options;
    }

    private static BackupCleanupReport purgeOldBackups(File backupRoot, int keepLast) throws IOException {
      if (keepLast < 0) {
        throw new IllegalArgumentException("keepLast must be >= 0");
      }
      BackupCleanupReport report = new BackupCleanupReport();
      report.setBackupRoot(backupRoot);
      report.setKeepLast(keepLast);
      if (!backupRoot.exists()) {
        return report;
      }
      if (!backupRoot.isDirectory()) {
        throw new IOException("Backup root is not a directory: " + backupRoot);
      }

      List<File> backups = listPublishedBackups(backupRoot);
      Collections.sort(backups, new Comparator<File>() {
        @Override
        public int compare(File left, File right) {
          return left.getName().compareTo(right.getName());
        }
      });

      int firstRetained = Math.max(0, backups.size() - keepLast);
      for (int i = 0; i < backups.size(); i++) {
        File backup = backups.get(i);
        if (i < firstRetained) {
          try {
            deleteRecursively(backup);
            report.addDeletedBackup(backup.getName());
          } catch (IOException e) {
            report.addFailure(backup.getName() + ": " + rootMessage(e));
          }
        } else {
          report.addRetainedBackup(backup.getName());
        }
      }
      return report;
    }

    private BackupReport createBackup() throws IOException {
      BackupReport report = new BackupReport();
      report.setAction("backup");
      report.setSourceDir(sourceDir);

      CheckReport sourceCheck = factory.check(sourceDir, options);
      if (!sourceCheck.isOk()) {
        report.setTargetDir(targetRoot);
        report.setCheckReport(sourceCheck);
        return report;
      }

      if (!targetRoot.exists() && !targetRoot.mkdirs()) {
        throw new IOException("Unable to create backup root: " + targetRoot);
      }
      if (!targetRoot.isDirectory()) {
        throw new IOException("Backup root is not a directory: " + targetRoot);
      }

      File backupDir = nextBackupDir(targetRoot);
      File tempDir = new File(targetRoot, backupDir.getName() + ".tmp");
      if (tempDir.exists()) {
        throw new IOException("Temporary backup directory already exists: " + tempDir);
      }
      report.setTargetDir(backupDir);
      try {
        copyOwnedFiles(sourceDir, tempDir, report);
        writeReport(new File(tempDir, BACKUP_REPORT_FILE), report);
        CheckReport backupCheck = factory.check(tempDir, options);
        report.setCheckReport(backupCheck);
        if (!report.isOk()) {
          writeReport(new File(tempDir, BACKUP_REPORT_FILE), report);
          return report;
        }
        writeReport(new File(tempDir, BACKUP_REPORT_FILE), report);
        publishDirectory(tempDir, backupDir);
        return report;
      } catch (IOException e) {
        report.addFailure(rootMessage(e));
        writeReportIfPossible(new File(tempDir, BACKUP_REPORT_FILE), report);
        return report;
      }
    }

    private BackupReport restoreBackup() throws IOException {
      BackupReport report = new BackupReport();
      report.setAction("restore");
      report.setSourceDir(sourceDir);
      report.setTargetDir(targetRoot);

      CheckReport backupCheck = factory.check(sourceDir, options);
      if (!backupCheck.isOk()) {
        report.setCheckReport(backupCheck);
        return report;
      }
      prepareEmptyDirectory(targetRoot);
      copyOwnedFiles(sourceDir, targetRoot, report);
      CheckReport restoreCheck = factory.check(targetRoot, options);
      report.setCheckReport(restoreCheck);
      writeReport(new File(targetRoot, RESTORE_REPORT_FILE), report);
      return report;
    }

    private File nextBackupDir(File backupRoot) {
      int max = 0;
      File[] files = backupRoot.listFiles();
      if (files != null) {
        for (File file : files) {
          String name = file.getName();
          if (name.startsWith("backup-") && file.isDirectory() && !name.endsWith(".tmp")) {
            try {
              max = Math.max(max, Integer.parseInt(name.substring("backup-".length())));
            } catch (NumberFormatException ignored) {
            }
          }
        }
      }
      return new File(backupRoot, String.format("backup-%06d", max + 1));
    }

    private static List<File> listPublishedBackups(File backupRoot) {
      List<File> backups = new ArrayList<>();
      File[] files = backupRoot.listFiles();
      if (files == null) {
        return backups;
      }
      for (File file : files) {
        if (file.isDirectory() && isPublishedBackupName(file.getName())) {
          backups.add(file);
        }
      }
      return backups;
    }

    private static boolean isPublishedBackupName(String name) {
      if (!name.startsWith("backup-") || name.length() != "backup-000001".length()) {
        return false;
      }
      for (int i = "backup-".length(); i < name.length(); i++) {
        if (!Character.isDigit(name.charAt(i))) {
          return false;
        }
      }
      return true;
    }

    private static void deleteRecursively(File file) throws IOException {
      if (file.isDirectory()) {
        File[] children = file.listFiles();
        if (children != null) {
          for (File child : children) {
            deleteRecursively(child);
          }
        }
      }
      if (!file.delete() && file.exists()) {
        throw new IOException("Unable to delete " + file);
      }
    }

    private void copyOwnedFiles(File source, File target, BackupReport report) throws IOException {
      prepareEmptyDirectory(target);
      for (File file : Filename.listFiles(source)) {
        FileInfo info;
        try {
          info = Filename.parseFileName(file);
        } catch (RuntimeException e) {
          continue;
        }
        if (info == null || !file.isFile()) {
          continue;
        }
        File dst = new File(target, file.getName());
        java.nio.file.Files.copy(
            file.toPath(),
            dst.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        report.addCopiedFile(file.getName());
      }
    }

    private void publishDirectory(File tempDir, File backupDir) throws IOException {
      if (!tempDir.renameTo(backupDir)) {
        throw new IOException("Unable to publish backup " + tempDir + " to " + backupDir);
      }
    }

    private void prepareEmptyDirectory(File dir) throws IOException {
      if (dir.exists()) {
        if (!dir.isDirectory()) {
          throw new IOException("Target exists but is not a directory: " + dir);
        }
        File[] files = dir.listFiles();
        if (files != null && files.length > 0) {
          throw new IOException("Target directory is not empty: " + dir);
        }
      } else if (!dir.mkdirs()) {
        throw new IOException("Unable to create target directory: " + dir);
      }
    }

    private void writeReport(File file, BackupReport report) throws IOException {
      try (FileOutputStream output = new FileOutputStream(file)) {
        output.write(report.toJson().getBytes(UTF_8));
        output.flush();
        output.getFD().sync();
      }
    }

    private void writeReportIfPossible(File file, BackupReport report) {
      try {
        if (file.getParentFile() != null && file.getParentFile().isDirectory()) {
          writeReport(file, report);
        }
      } catch (IOException ignored) {
      }
    }
  }

  /**
   * LDB repair 最小实现：从可读取的 SST/WAL 重建 MANIFEST/CURRENT，并隔离损坏文件。
   *
   * repair 是离线恢复入口，只复用现有磁盘格式；WAL 内容会先重放到内存表，再刷成
   * Level-0 SST，避免修复后的 MANIFEST 继续依赖已失效的旧元数据。
   */
  private static final class Repairer {
    private static final String REPAIR_REPORT_FILE = "REPAIR-REPORT.json";

    private final File databaseDir;
    private final Options options;
    private final InternalKeyComparator internalKeyComparator;
    private final RepairReport report = new RepairReport();

    private Repairer(File databaseDir, Options options) {
      this.databaseDir = databaseDir;
      this.options = options == null ? new Options() : options;
      DBComparator comparator = this.options.comparator();
      UserComparator userComparator = comparator != null
          ? new CustomUserComparator(comparator)
          : new BytewiseComparator();
      this.internalKeyComparator = new InternalKeyComparator(userComparator);
    }

    private void repair() throws IOException {
      if (!databaseDir.exists()) {
        throw new FileNotFoundException("Database directory does not exist: " + databaseDir);
      }
      if (!databaseDir.isDirectory()) {
        throw new IOException("Database path is not a directory: " + databaseDir);
      }
      report.setDatabaseDir(databaseDir);

      List<FileMetaData> liveTables = new ArrayList<>();
      List<File> corruptFiles = new ArrayList<>();
      List<Long> logs = new ArrayList<>();
      long maxFileNumber = 1;
      long maxSequence = 0;

      for (File file : Filename.listFiles(databaseDir)) {
        FileInfo info = Filename.parseFileName(file);
        if (info == null) {
          continue;
        }
        maxFileNumber = Math.max(maxFileNumber, info.getFileNumber());
        if (info.getFileType() == FileType.TABLE) {
          try {
            FileMetaData metaData = readTableMetadata(info.getFileNumber());
            liveTables.add(metaData);
            report.addRecoveredSst(file.getName());
            maxSequence = Math.max(maxSequence, metaData.getLargest().getSequenceNumber());
          } catch (RuntimeException e) {
            corruptFiles.add(file);
          } catch (IOException e) {
            corruptFiles.add(file);
          }
        } else if (info.getFileType() == FileType.LOG) {
          logs.add(info.getFileNumber());
        }
      }

      Collections.sort(logs);
      long nextFileNumber = maxFileNumber + 1;
      WalReplayResult walReplayResult = replayWalLogs(logs, liveTables, nextFileNumber);
      liveTables.addAll(walReplayResult.getTables());
      maxSequence = Math.max(maxSequence, walReplayResult.getMaxSequence());
      nextFileNumber = walReplayResult.getNextFileNumber();
      corruptFiles.addAll(walReplayResult.getCorruptLogs());

      if (liveTables.isEmpty()) {
        throw new IOException("No readable SST or WAL records found for repair: " + databaseDir);
      }

      quarantineFiles(corruptFiles, "corrupt");
      quarantineOldDescriptors();

      long manifestNumber = nextFileNumber++;
      long repairedLogNumber = nextFileNumber++;
      writeManifest(manifestNumber, repairedLogNumber, nextFileNumber, maxSequence, liveTables);
      Filename.setCurrentFile(databaseDir, manifestNumber);
      report.setManifestFileNumber(manifestNumber);
      report.setCurrentFile(Filename.currentFileName());
      report.setLastSequence(maxSequence);
      report.setNextFileNumber(nextFileNumber);
      report.write(databaseDir);
    }

    private FileMetaData readTableMetadata(long fileNumber) throws IOException {
      TableCache tableCache = new TableCache(
          databaseDir,
          Math.max(16, options.maxOpenFiles() - 10),
          new InternalUserComparator(internalKeyComparator),
          options.verifyChecksums(),
          options);
      try {
        InternalTableIterator iterator = tableCache.newIterator(fileNumber);
        iterator.seekToFirst();
        InternalKey smallest = null;
        InternalKey largest = null;
        while (iterator.hasNext()) {
          Entry<InternalKey, ?> entry = iterator.next();
          InternalKey key = entry.getKey();
          if (smallest == null) {
            smallest = key;
          }
          largest = key;
        }
        if (smallest == null) {
          throw new IOException("SST has no entries: " + Filename.tableFileName(fileNumber));
        }
        File tableFile = new File(databaseDir, Filename.tableFileName(fileNumber));
        return new FileMetaData(
            LdbColumnFamily.DEFAULT.getId(),
            fileNumber,
            tableFile.length(),
            smallest,
            largest);
      } finally {
        tableCache.close();
      }
    }

    private void writeManifest(long manifestNumber,
                               long repairedLogNumber,
                               long nextFileNumber,
                               long lastSequence,
                               List<FileMetaData> liveTables) throws IOException {
      VersionEdit edit = new VersionEdit();
      edit.setComparatorName(internalKeyComparator.name());
      edit.setLogNumber(repairedLogNumber);
      edit.setPreviousLogNumber(0);
      edit.setNextFileNumber(nextFileNumber);
      edit.setLastSequenceNumber(lastSequence);
      for (FileMetaData table : liveTables) {
        edit.addFile(table.getCfId(), 0, table);
      }

      File manifest = new File(databaseDir, Filename.descriptorFileName(manifestNumber));
      LogWriter writer = Logs.createLogWriter(manifest, manifestNumber, options);
      try {
        writer.addRecord(edit.encode(), true);
      } finally {
        writer.close();
      }
    }

    private void quarantineOldDescriptors() throws IOException {
      List<File> oldFiles = new ArrayList<>();
      for (File file : Filename.listFiles(databaseDir)) {
        FileInfo info = Filename.parseFileName(file);
        if (info == null) {
          continue;
        }
        if (info.getFileType() == FileType.DESCRIPTOR || info.getFileType() == FileType.CURRENT) {
          oldFiles.add(file);
        }
      }
      quarantineFiles(oldFiles, "old-manifest");
    }

    private void quarantineFiles(List<File> files, String reason) throws IOException {
      if (files.isEmpty()) {
        return;
      }
      File quarantineDir = new File(databaseDir, "corrupt");
      if (!quarantineDir.exists() && !quarantineDir.mkdirs()) {
        throw new IOException("Unable to create repair quarantine directory: " + quarantineDir);
      }
      for (File file : files) {
        if (!file.exists()) {
          continue;
        }
        File target = new File(quarantineDir, reason + "-" + file.getName());
        int attempt = 0;
        while (target.exists()) {
          attempt++;
          target = new File(quarantineDir, reason + "-" + attempt + "-" + file.getName());
        }
        if (!file.renameTo(target)) {
          throw new IOException("Unable to move " + file + " to " + target);
        }
        report.addQuarantinedFile(file.getName(), target.getName(), reason);
      }
    }

    /**
     * 将 repair 过程中发现的 WAL 顺序重放到内存表，并把每个非空列族刷成新的 Level-0 SST。
     */
    private WalReplayResult replayWalLogs(List<Long> logs,
                                          List<FileMetaData> baseTables,
                                          long firstOutputFileNumber) throws IOException {
      if (logs.isEmpty()) {
        return new WalReplayResult(
            Collections.<FileMetaData>emptyList(),
            Collections.<File>emptyList(),
            firstOutputFileNumber,
            0);
      }

      Map<Integer, MemTable> memTables = new HashMap<>();
      for (LdbColumnFamily cf : options.getColumnFamilies()) {
        memTables.put(cf.getId(), new MemTable(internalKeyComparator));
      }

      long maxSequence = 0;
      List<File> corruptLogs = new ArrayList<>();
      for (Long logNumber : logs) {
        try {
          ParsedWalLog parsed = parseWalLog(logNumber);
          applyWalLog(parsed, baseTables, memTables);
          report.addReplayedWal(Filename.logFileName(logNumber));
          report.addDiscardedWalBytes(parsed.getDiscardedBytes());
          maxSequence = Math.max(maxSequence, parsed.getMaxSequence());
        } catch (RuntimeException e) {
          corruptLogs.add(new File(databaseDir, Filename.logFileName(logNumber)));
        } catch (IOException e) {
          corruptLogs.add(new File(databaseDir, Filename.logFileName(logNumber)));
        }
      }

      List<FileMetaData> replayedTables = new ArrayList<>();
      long nextFileNumber = firstOutputFileNumber;
      for (Entry<Integer, MemTable> entry : memTables.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          FileMetaData table = buildTable(entry.getKey(), entry.getValue(), nextFileNumber++);
          if (table != null) {
            replayedTables.add(table);
          }
        }
      }
      return new WalReplayResult(replayedTables, corruptLogs, nextFileNumber, maxSequence);
    }

    /**
     * 预解析单个 WAL 文件；只有整个 WAL 可安全解码后，repair 才会把其中记录应用到输出。
     */
    private ParsedWalLog parseWalLog(long logNumber) throws IOException {
      File logFile = new File(databaseDir, Filename.logFileName(logNumber));
      try (FileInputStream input = new FileInputStream(logFile);
           FileChannel channel = input.getChannel()) {
        CountingLogMonitor monitor = new CountingLogMonitor();
        LogReader reader = new LogReader(channel, monitor, true, 0);
        List<WalBatch> batches = new ArrayList<>();
        long maxSequence = 0;

        for (Slice record = reader.readRecord(); record != null; record = reader.readRecord()) {
          SliceInput recordInput = record.input();
          if (recordInput.available() < 12) {
            continue;
          }

          long sequenceBegin = recordInput.readLong();
          int updateSize = recordInput.readInt();
          LdbWriteBatchImpl batch = LdbWriteBatchLog.readWriteBatch(
              recordInput,
              updateSize,
              new LdbWriteBatchLog.ColumnFamilyResolver() {
                @Override
                public LdbColumnFamily getColumnFamily(int cfId) {
                  LdbColumnFamily cf = findColumnFamily(cfId);
                  if (cf == null) {
                    throw new IllegalArgumentException("Unknown column family id in repair WAL: " + cfId);
                  }
                  return cf;
                }
              });

          batches.add(new WalBatch(sequenceBegin, batch));
          maxSequence = Math.max(maxSequence, sequenceBegin + updateSize - 1);
        }
        return new ParsedWalLog(logNumber, batches, maxSequence, monitor.getCorruptionBytes());
      }
    }

    /**
     * 把已经完整解码的 WAL 批次应用到 repair 内存表，确保坏 WAL 不会留下半应用状态。
     */
    private void applyWalLog(ParsedWalLog parsed,
                             List<FileMetaData> baseTables,
                             Map<Integer, MemTable> memTables) {
      RepairWalHandler handler = new RepairWalHandler(memTables, baseTables);
      for (WalBatch walBatch : parsed.getBatches()) {
        handler.setSequence(walBatch.getSequenceBegin());
        walBatch.getBatch().forEach(handler);
      }
    }

    /**
     * 根据调用方传入的 Options 解析 WAL 中的列族 id，避免 repair 猜测未知列族语义。
     */
    private LdbColumnFamily findColumnFamily(int cfId) {
      for (LdbColumnFamily cf : options.getColumnFamilies()) {
        if (cf.getId() == cfId) {
          return cf;
        }
      }
      return null;
    }

    /**
     * 将 repair WAL replay 产生的内存表写成新的 SST，并返回可写入 MANIFEST 的文件元数据。
     */
    private FileMetaData buildTable(int cfId, MemTable memTable, long fileNumber) throws IOException {
      File file = new File(databaseDir, Filename.tableFileName(fileNumber));
      try {
        InternalKey smallest = null;
        InternalKey largest = null;
        try (FileChannel channel = new FileOutputStream(file).getChannel()) {
          TableBuilder tableBuilder = new TableBuilder(
              options,
              channel,
              new InternalUserComparator(internalKeyComparator));

          for (Entry<InternalKey, Slice> entry : memTable) {
            InternalKey key = entry.getKey();
            if (smallest == null) {
              smallest = key;
            }
            largest = largerMetadataKey(largest, metadataLargestKey(key, entry.getValue()));
            tableBuilder.add(key.encode(), entry.getValue());
          }

          tableBuilder.finish();
          if (options.forceSstOnFlush()) {
            channel.force(true);
          }
        }

        if (smallest == null) {
          return null;
        }
        return new FileMetaData(cfId, fileNumber, file.length(), smallest, largest);
      } catch (IOException e) {
        if (file.exists() && !file.delete()) {
          throw new IOException("Failed to delete incomplete repair SST: " + file, e);
        }
        throw e;
      }
    }

    private InternalKey metadataLargestKey(InternalKey key, Slice value) {
      if (key.getValueType() == ValueType.DELETE_RANGE) {
        return new InternalKey(value, key.getSequenceNumber(), key.getValueType());
      }
      return key;
    }

    private InternalKey largerMetadataKey(InternalKey current, InternalKey candidate) {
      if (current == null || internalKeyComparator.compare(current, candidate) < 0) {
        return candidate;
      }
      return current;
    }

    private static final class WalReplayResult {
      private final List<FileMetaData> tables;
      private final List<File> corruptLogs;
      private final long nextFileNumber;
      private final long maxSequence;

      /**
       * 保存 WAL replay 生成的 SST、下一个可用文件号和重放到的最大 sequence。
       */
      private WalReplayResult(List<FileMetaData> tables,
                              List<File> corruptLogs,
                              long nextFileNumber,
                              long maxSequence) {
        this.tables = tables;
        this.corruptLogs = corruptLogs;
        this.nextFileNumber = nextFileNumber;
        this.maxSequence = maxSequence;
      }

      private List<FileMetaData> getTables() {
        return tables;
      }

      private List<File> getCorruptLogs() {
        return corruptLogs;
      }

      private long getNextFileNumber() {
        return nextFileNumber;
      }

      private long getMaxSequence() {
        return maxSequence;
      }
    }

    private final class RepairWalHandler implements LdbWriteBatchImpl.Handler {
      private final Map<Integer, MemTable> memTables;
      private final List<FileMetaData> baseTables;
      private final Map<BatchKey, Long> localCounters = new HashMap<>();
      private long sequence;

      /**
       * 创建 WAL replay 处理器，所有副作用都写入传入的列族内存表。
       */
      private RepairWalHandler(Map<Integer, MemTable> memTables, List<FileMetaData> baseTables) {
        this.memTables = memTables;
        this.baseTables = baseTables;
      }

      /**
       * 切换当前 WAL record 的起始 sequence，保证 batch 内操作保持原始顺序。
       */
      private void setSequence(long sequence) {
        this.sequence = sequence;
      }

      @Override
      public void put(LdbColumnFamily cf, Slice key, Slice value) {
        getMemTable(cf).add(sequence++, ValueType.VALUE, key, value);
        localCounters.remove(new BatchKey(cf.getId(), key));
      }

      @Override
      public void delete(LdbColumnFamily cf, Slice key) {
        getMemTable(cf).add(sequence++, ValueType.DELETION, key, Slices.EMPTY_SLICE);
        localCounters.remove(new BatchKey(cf.getId(), key));
      }

      @Override
      public void deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
        getMemTable(cf).add(sequence++, ValueType.DELETE_RANGE, beginKey, endKey);
        localCounters.clear();
      }

      @Override
      public void addLong(LdbColumnFamily cf, Slice key, Slice deltaSlice) {
        BatchKey cacheKey = new BatchKey(cf.getId(), key);
        Long current = localCounters.get(cacheKey);
        if (current == null) {
          current = currentCounterValue(cf, key);
        }

        long delta = Slices.decodeLong(deltaSlice)
            .orElseThrow(() -> new IllegalArgumentException("deltaSlice is not a long"));
        long newValue = current + delta;
        localCounters.put(cacheKey, newValue);
        getMemTable(cf).add(sequence++, ValueType.VALUE, key, Slices.encodeLong(newValue));
      }

      /**
       * 获取 repair replay 期间当前可见的计数器值；先看 WAL replay 状态，再回退到已恢复 SST。
       */
      private long currentCounterValue(LdbColumnFamily cf, Slice key) {
        LookupResult result = getMemTable(cf).get(new LookupKey(key, MAX_SEQUENCE_NUMBER));
        if (result == null) {
          result = lookupBaseTables(cf.getId(), key);
        }
        if (result == null || result.isDeleted()) {
          return 0L;
        }
        return Slices.decodeLong(result.getValue()).orElse(0L);
      }

      private LookupResult lookupBaseTables(int cfId, Slice key) {
        InternalKey newest = null;
        Slice newestValue = null;
        boolean deleted = false;
        LookupKey lookupKey = new LookupKey(key, MAX_SEQUENCE_NUMBER);
        TableCache tableCache = new TableCache(
            databaseDir,
            Math.max(16, options.maxOpenFiles() - 10),
            new InternalUserComparator(internalKeyComparator),
            options.verifyChecksums(),
            options);
        try {
          for (FileMetaData table : baseTables) {
            if (table.getCfId() != cfId) {
              continue;
            }
            InternalTableIterator iterator = tableCache.newIterator(table);
            iterator.seek(lookupKey.getInternalKey());
            if (!iterator.hasNext()) {
              continue;
            }
            Entry<InternalKey, Slice> entry = iterator.next();
            InternalKey candidate = entry.getKey();
            if (!candidate.getUserKey().equals(key)) {
              continue;
            }
            if (newest == null || candidate.getSequenceNumber() > newest.getSequenceNumber()) {
              newest = candidate;
              newestValue = entry.getValue();
              deleted = candidate.getValueType() == ValueType.DELETION;
            }
          }
        } finally {
          tableCache.close();
        }
        if (newest == null) {
          return null;
        }
        return deleted
            ? LookupResult.deleted(lookupKey, newest.getSequenceNumber())
            : LookupResult.ok(lookupKey, newestValue, newest.getSequenceNumber());
      }

      /**
       * 获取列族对应的 repair 内存表；未知列族说明调用方 Options 与 WAL 不匹配。
       */
      private MemTable getMemTable(LdbColumnFamily cf) {
        MemTable memTable = memTables.get(cf.getId());
        if (memTable == null) {
          throw new IllegalArgumentException("Unknown column family id in repair WAL: " + cf.getId());
        }
        return memTable;
      }
    }

    private static final class ParsedWalLog {
      private final long fileNumber;
      private final List<WalBatch> batches;
      private final long maxSequence;
      private final long discardedBytes;

      private ParsedWalLog(long fileNumber, List<WalBatch> batches, long maxSequence, long discardedBytes) {
        this.fileNumber = fileNumber;
        this.batches = batches;
        this.maxSequence = maxSequence;
        this.discardedBytes = discardedBytes;
      }

      private List<WalBatch> getBatches() {
        return batches;
      }

      private long getMaxSequence() {
        return maxSequence;
      }

      private long getDiscardedBytes() {
        return discardedBytes;
      }
    }

    private static final class WalBatch {
      private final long sequenceBegin;
      private final LdbWriteBatchImpl batch;

      private WalBatch(long sequenceBegin, LdbWriteBatchImpl batch) {
        this.sequenceBegin = sequenceBegin;
        this.batch = batch;
      }

      private long getSequenceBegin() {
        return sequenceBegin;
      }

      private LdbWriteBatchImpl getBatch() {
        return batch;
      }
    }

    private static final class CountingLogMonitor implements LogMonitor {
      private long corruptionBytes;

      @Override
      public void corruption(long bytes, String reason) {
        corruptionBytes += bytes;
      }

      @Override
      public void corruption(long bytes, Throwable reason) {
        corruptionBytes += bytes;
      }

      private long getCorruptionBytes() {
        return corruptionBytes;
      }
    }

    private static final class RepairReport {
      private File databaseDir;
      private final List<String> recoveredSstFiles = new ArrayList<>();
      private final List<String> replayedWalFiles = new ArrayList<>();
      private final List<String> quarantinedFiles = new ArrayList<>();
      private long discardedWalBytes;
      private long manifestFileNumber;
      private String currentFile;
      private long lastSequence;
      private long nextFileNumber;

      private void setDatabaseDir(File databaseDir) {
        this.databaseDir = databaseDir;
      }

      private void addRecoveredSst(String file) {
        recoveredSstFiles.add(file);
      }

      private void addReplayedWal(String file) {
        replayedWalFiles.add(file);
      }

      private void addQuarantinedFile(String source, String target, String reason) {
        quarantinedFiles.add(source + " -> " + target + " (" + reason + ")");
      }

      private void addDiscardedWalBytes(long bytes) {
        discardedWalBytes += bytes;
      }

      private void setManifestFileNumber(long manifestFileNumber) {
        this.manifestFileNumber = manifestFileNumber;
      }

      private void setCurrentFile(String currentFile) {
        this.currentFile = currentFile;
      }

      private void setLastSequence(long lastSequence) {
        this.lastSequence = lastSequence;
      }

      private void setNextFileNumber(long nextFileNumber) {
        this.nextFileNumber = nextFileNumber;
      }

      private void write(File databaseDir) throws IOException {
        File reportFile = new File(databaseDir, REPAIR_REPORT_FILE);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(reportFile), UTF_8)) {
          writer.write(toJson());
        }
      }

      private String toJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        appendField(builder, "databaseDir", databaseDir == null ? "" : databaseDir.getAbsolutePath(), true);
        appendArray(builder, "recoveredSstFiles", recoveredSstFiles, true);
        appendArray(builder, "replayedWalFiles", replayedWalFiles, true);
        appendArray(builder, "quarantinedFiles", quarantinedFiles, true);
        appendField(builder, "discardedWalBytes", Long.toString(discardedWalBytes), false, true);
        appendField(builder, "manifestFileNumber", Long.toString(manifestFileNumber), false, true);
        appendField(builder, "currentFile", currentFile == null ? "" : currentFile, true);
        appendField(builder, "lastSequence", Long.toString(lastSequence), false, true);
        appendField(builder, "nextFileNumber", Long.toString(nextFileNumber), false, false);
        builder.append("}\n");
        return builder.toString();
      }

      private static void appendField(StringBuilder builder, String name, String value, boolean quote) {
        appendField(builder, name, value, quote, true);
      }

      private static void appendField(StringBuilder builder,
                                      String name,
                                      String value,
                                      boolean quote,
                                      boolean comma) {
        builder.append("  \"").append(name).append("\": ");
        if (quote) {
          builder.append('"').append(escape(value)).append('"');
        } else {
          builder.append(value);
        }
        if (comma) {
          builder.append(',');
        }
        builder.append('\n');
      }

      private static void appendArray(StringBuilder builder, String name, List<String> values, boolean comma) {
        builder.append("  \"").append(name).append("\": [");
        for (int i = 0; i < values.size(); i++) {
          if (i > 0) {
            builder.append(", ");
          }
          builder.append('"').append(escape(values.get(i))).append('"');
        }
        builder.append(']');
        if (comma) {
          builder.append(',');
        }
        builder.append('\n');
      }

      private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
      }
    }

    private static final class BatchKey {
      private final int cfId;
      private final byte[] key;
      private final int hash;

      /**
       * 生成计数器本地缓存键，复制 key 内容以避免 Slice 复用影响哈希一致性。
       */
      private BatchKey(int cfId, Slice key) {
        this.cfId = cfId;
        this.key = key.getBytes();
        this.hash = 31 * cfId + Arrays.hashCode(this.key);
      }

      @Override
      public boolean equals(Object value) {
        if (this == value) {
          return true;
        }
        if (!(value instanceof BatchKey)) {
          return false;
        }
        BatchKey other = (BatchKey) value;
        return cfId == other.cfId && Arrays.equals(key, other.key);
      }

      @Override
      public int hashCode() {
        return hash;
      }
    }
  }
}
