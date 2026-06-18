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
   * 生成 repair 计划报告但不修改数据库目录。
   *
   * 该入口用于发布前或运维前的 dry-run 诊断：它会读取注册表、SST 和 WAL，
   * 标记可恢复文件与将被隔离的损坏文件，但不会写 MANIFEST、CURRENT、SST 或报告文件。
   */
  public String planRepair(File path, Options options)
      throws IOException {
    return new Repairer(path, options).plan().toJson();
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
    return new BackupEngine(sourceDir, backupRoot, options).createBackup(false);
  }

  /**
   * 创建离线增量备份。
   *
   * 当前增量备份仍发布为可独立恢复的 `backup-000001` 风格目录；当上一备份中存在同名同长度文件时，
   * 优先创建硬链接复用文件，失败时回退为复制，并写入 `BACKUP-MANIFEST.json` 记录复用关系。
   */
  public BackupReport createIncrementalBackup(File sourceDir, File backupRoot, Options options) throws IOException {
    return new BackupEngine(sourceDir, backupRoot, options).createBackup(true);
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
   * 校验备份目录完整性。
   *
   * 该入口复用 LDB 离线 check 逻辑，增量备份通过硬链接或复制发布为完整目录，因此无需 restore 即可校验。
   */
  public CheckReport checkBackup(File backupDir, Options options) {
    CheckReport report = check(backupDir, options);
    BackupEngine.validateBackupMetadata(backupDir, report);
    return report;
  }

  /**
   * 清理旧备份版本，只保留最新的 keepLast 个已发布备份目录。
   *
   * 该方法只识别 `backup-000001` 形式的目录，不删除临时目录或其他文件；keepLast 必须大于等于 0。
   */
  public BackupCleanupReport purgeOldBackups(File backupRoot, int keepLast) throws IOException {
    return BackupEngine.purgeOldBackups(backupRoot, keepLast, false);
  }

  /**
   * 生成旧备份清理计划但不删除任何文件。
   *
   * 该 dry-run 入口会同时计算可删除的备份目录和共享对象仓库中将失去引用的对象，
   * 方便发布或运维脚本在真正清理前审计影响范围。
   */
  public BackupCleanupReport planPurgeBackups(File backupRoot, int keepLast) throws IOException {
    return BackupEngine.purgeOldBackups(backupRoot, keepLast, true);
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
    private List<LdbColumnFamily> columnFamilies;

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

      loadColumnFamilyRegistry();
      checkCurrentFile();
      for (File file : Filename.listFiles(databaseDir)) {
        if (ColumnFamilyRegistry.FILE_NAME.equals(file.getName()) && file.isFile()) {
          report.addCheckedFile(file);
          continue;
        }
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
        String manifestName = currentName.substring(0, currentName.length() - 1);
        try {
          Filename.parseCurrentManifestFileName(manifestName);
        } catch (IllegalArgumentException e) {
          report.addFailure(current, e.getMessage());
          return;
        }
        File manifest = new File(databaseDir, manifestName);
        if (!manifest.isFile()) {
          report.addFailure(current, "CURRENT points to missing manifest: " + manifest.getName());
        }
      } catch (IOException e) {
        report.addFailure(current, rootMessage(e));
      }
    }

    private void loadColumnFamilyRegistry() {
      try {
        columnFamilies = loadColumnFamiliesIncludingDropped(databaseDir, options);
      } catch (Throwable e) {
        report.addFailure(new File(databaseDir, ColumnFamilyRegistry.FILE_NAME), rootMessage(e));
        columnFamilies = options.getColumnFamilies();
      }
    }

    private void checkManifest(File manifest) {
      report.addCheckedFile(manifest);
      try (FileInputStream input = new FileInputStream(manifest);
           FileChannel channel = input.getChannel()) {
        CollectingLogMonitor monitor = new CollectingLogMonitor();
        LogReader reader = new LogReader(channel, monitor, true, 0);
        for (Slice record = reader.readRecord(); record != null; record = reader.readRecord()) {
          VersionEdit edit = new VersionEdit(record);
          validateManifestEdit(manifest, edit);
          report.addManifestRecord();
        }
        if (monitor.hasCorruption()) {
          report.addFailure(manifest, monitor.describe());
        }
      } catch (Throwable e) {
        report.addFailure(manifest, rootMessage(e));
      }
    }

    private void validateManifestEdit(File manifest, VersionEdit edit) {
      for (VersionEdit.CfLevel cfLevel : edit.getNewFiles().keySet()) {
        validateRegisteredManifestCf(manifest, cfLevel.getCfId());
      }
      for (VersionEdit.CfLevel cfLevel : edit.getDeletedFiles().keySet()) {
        validateRegisteredManifestCf(manifest, cfLevel.getCfId());
      }
      for (VersionEdit.CfLevel cfLevel : edit.getCompactPointers().keySet()) {
        validateRegisteredManifestCf(manifest, cfLevel.getCfId());
      }
    }

    private void validateRegisteredManifestCf(File manifest, int cfId) {
      if (findColumnFamily(cfId) == null) {
        report.addFailure(manifest, "Unknown column family id in MANIFEST: " + cfId);
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
            options.cacheBlocks() ? new BlockCache(options.blockCacheSize()) : null);
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
      for (LdbColumnFamily cf : columnFamilies) {
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

  private static List<LdbColumnFamily> loadColumnFamiliesIncludingDropped(File databaseDir, Options options)
      throws IOException {
    List<LdbColumnFamily> result = new ArrayList<>();
    for (ColumnFamilyRegistry.Record record : ColumnFamilyRegistry.loadRecords(databaseDir, options)) {
      result.add(record.getColumnFamily());
    }
    return result;
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
    private final List<String> reusedFiles = new ArrayList<>();
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

    private void addReusedFile(String name) {
      reusedFiles.add(name);
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
     * 返回从上一备份复用的 LDB 文件名列表。
     */
    public List<String> getReusedFiles() {
      return Collections.unmodifiableList(reusedFiles);
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
      appendJsonField(builder, "reusedFiles", reusedFiles, true);
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
    private final List<String> plannedDeletedObjects = new ArrayList<>();
    private final List<String> deletedObjects = new ArrayList<>();
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

    private void addPlannedDeletedObject(String object) {
      plannedDeletedObjects.add(object);
    }

    private void addDeletedObject(String object) {
      deletedObjects.add(object);
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

    public List<String> getPlannedDeletedObjects() {
      return Collections.unmodifiableList(plannedDeletedObjects);
    }

    public List<String> getDeletedObjects() {
      return Collections.unmodifiableList(deletedObjects);
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
          + ", plannedDeletedObjects=" + plannedDeletedObjects
          + ", deletedObjects=" + deletedObjects
          + ", failures=" + failures
          + '}';
    }
  }

  private static final class BackupEngine {
    private static final String BACKUP_REPORT_FILE = "BACKUP-REPORT.json";
    private static final String RESTORE_REPORT_FILE = "RESTORE-REPORT.json";
    private static final String BACKUP_MANIFEST_FILE = "BACKUP-MANIFEST.json";
    private static final String OBJECT_REFS_FILE = "OBJECT-REFS.json";
    private static final String OBJECTS_DIR = "objects";

    private final File sourceDir;
    private final File targetRoot;
    private final Options options;

    private BackupEngine(File sourceDir, File targetRoot, Options options) {
      this.sourceDir = sourceDir;
      this.targetRoot = targetRoot;
      this.options = options == null ? new Options() : options;
    }

    private static BackupCleanupReport purgeOldBackups(File backupRoot, int keepLast, boolean dryRun) throws IOException {
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
      Set<String> retainedObjectIds = new TreeSet<>();
      Set<String> deletedObjectIds = new TreeSet<>();
      for (int i = 0; i < backups.size(); i++) {
        File backup = backups.get(i);
        if (i < firstRetained) {
          deletedObjectIds.addAll(collectObjectIds(backup));
          report.addDeletedBackup(backup.getName());
          if (dryRun) {
            continue;
          }
          try {
            deleteRecursively(backup);
          } catch (IOException e) {
            report.addFailure(backup.getName() + ": " + rootMessage(e));
          }
        } else {
          report.addRetainedBackup(backup.getName());
          retainedObjectIds.addAll(collectObjectIds(backup));
        }
      }
      deletedObjectIds.removeAll(retainedObjectIds);
      for (String objectId : deletedObjectIds) {
        report.addPlannedDeletedObject(objectId);
      }
      if (!dryRun) {
        ObjectStoreRebuild rebuild = rebuildObjectStore(backupRoot, true);
        for (String objectId : rebuild.deletedObjects) {
          report.addDeletedObject(objectId);
        }
      }
      return report;
    }

    private BackupReport createBackup(boolean incremental) throws IOException {
      BackupReport report = new BackupReport();
      report.setAction(incremental ? "incremental-backup" : "backup");
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
      File parentBackup = incremental ? latestPublishedBackup(targetRoot) : null;
      if (tempDir.exists()) {
        throw new IOException("Temporary backup directory already exists: " + tempDir);
      }
      report.setTargetDir(backupDir);
      try {
        copyOwnedFiles(sourceDir, tempDir, parentBackup, report);
        writeBackupManifest(new File(tempDir, BACKUP_MANIFEST_FILE), backupDir, parentBackup, report);
        writeReport(new File(tempDir, BACKUP_REPORT_FILE), report);
        CheckReport backupCheck = factory.check(tempDir, options);
        report.setCheckReport(backupCheck);
        if (!report.isOk()) {
          writeReport(new File(tempDir, BACKUP_REPORT_FILE), report);
          return report;
        }
        writeReport(new File(tempDir, BACKUP_REPORT_FILE), report);
        publishDirectory(tempDir, backupDir);
        rebuildObjectStore(targetRoot, true);
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

      CheckReport backupCheck = factory.checkBackup(sourceDir, options);
      if (!backupCheck.isOk()) {
        report.setCheckReport(backupCheck);
        return report;
      }
      prepareEmptyDirectory(targetRoot);
      copyOwnedFiles(sourceDir, targetRoot, null, report);
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

    private static File latestPublishedBackup(File backupRoot) {
      List<File> backups = listPublishedBackups(backupRoot);
      if (backups.isEmpty()) {
        return null;
      }
      Collections.sort(backups, new Comparator<File>() {
        @Override
        public int compare(File left, File right) {
          return left.getName().compareTo(right.getName());
        }
      });
      return backups.get(backups.size() - 1);
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

    private void copyOwnedFiles(File source, File target, File reuseFrom, BackupReport report) throws IOException {
      prepareEmptyDirectory(target);
      for (File file : Filename.listFiles(source)) {
        if (ColumnFamilyRegistry.FILE_NAME.equals(file.getName()) && file.isFile()) {
          File dst = new File(target, file.getName());
          java.nio.file.Files.copy(
              file.toPath(),
              dst.toPath(),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING,
              java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
          report.addCopiedFile(file.getName());
          continue;
        }
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
        File reusable = reuseFrom == null || info.getFileType() != FileType.TABLE
            ? null
            : new File(reuseFrom, file.getName());
        if (reusable != null && reusable.isFile() && reusable.length() == file.length()) {
          try {
            java.nio.file.Files.createLink(dst.toPath(), reusable.toPath());
            report.addReusedFile(file.getName());
            continue;
          } catch (IOException | UnsupportedOperationException e) {
            // 硬链接只是增量备份的空间优化，失败时回退为复制，保持备份目录可独立恢复。
          }
        }
        java.nio.file.Files.copy(
            file.toPath(),
            dst.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        report.addCopiedFile(file.getName());
      }
    }

    private void writeBackupManifest(File file, File backupDir, File parentBackup, BackupReport report) throws IOException {
      try (FileOutputStream output = new FileOutputStream(file)) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        appendManifestField(builder, "formatVersion", 1, true);
        appendManifestField(builder, "backupId", backupDir.getName(), true);
        appendManifestField(builder, "parentBackupId", parentBackup == null ? "" : parentBackup.getName(), true);
        appendManifestField(builder, "action", report.getAction(), true);
        appendManifestField(builder, "copiedFiles", report.getCopiedFiles(), true);
        appendManifestField(builder, "reusedFiles", report.getReusedFiles(), true);
        appendManifestField(builder, "published", true, false);
        builder.append("\n}\n");
        output.write(builder.toString().getBytes(UTF_8));
        output.flush();
        output.getFD().sync();
      }
    }

    private static Set<String> collectObjectIds(File backupDir) {
      Set<String> result = new TreeSet<>();
      for (File file : Filename.listFiles(backupDir)) {
        if (isBackupMetadata(file) || !file.isFile()) {
          continue;
        }
        FileInfo info = Filename.parseFileName(file);
        if (info == null && !ColumnFamilyRegistry.FILE_NAME.equals(file.getName())) {
          continue;
        }
        try {
          result.add(objectId(file));
        } catch (IOException ignored) {
        }
      }
      return result;
    }

    private static void validateBackupMetadata(File backupDir, CheckReport report) {
      if (!backupDir.isDirectory()) {
        return;
      }
      validateBackupManifest(backupDir, report);
      File backupRoot = backupDir.getParentFile();
      if (backupRoot == null) {
        return;
      }
      File objectsDir = new File(backupRoot, OBJECTS_DIR);
      File refsFile = new File(backupRoot, OBJECT_REFS_FILE);
      if (!objectsDir.exists() && !refsFile.exists()) {
        return;
      }
      if (!objectsDir.isDirectory()) {
        report.addFailure("Backup object store is missing or not a directory: " + objectsDir);
        return;
      }
      if (!refsFile.isFile()) {
        report.addFailure("Backup object refs file is missing: " + refsFile);
        return;
      }
      report.addCheckedFile(refsFile);
      Map<String, Set<String>> expectedRefs;
      try {
        expectedRefs = collectObjectRefs(backupRoot);
      } catch (IOException e) {
        report.addFailure("Unable to rebuild backup object refs: " + rootMessage(e));
        return;
      }
      String refsText;
      try {
        refsText = readText(refsFile);
      } catch (IOException e) {
        report.addFailure(refsFile, rootMessage(e));
        return;
      }
      if (!refsText.contains("\"formatVersion\"") || !refsText.contains("\"objects\"")) {
        report.addFailure(refsFile, "Malformed backup object refs");
      }
      for (Entry<String, Set<String>> entry : expectedRefs.entrySet()) {
        String objectId = entry.getKey();
        File objectFile = new File(objectsDir, objectId);
        if (!objectFile.isFile()) {
          report.addFailure("Missing backup object: " + objectId);
        } else {
          report.addCheckedFile(objectFile);
        }
        String objectEntry = findObjectRefEntry(refsText, objectId);
        if (objectEntry == null) {
          report.addFailure(refsFile, "Missing object ref: " + objectId);
        } else if (!objectEntry.contains("\"refCount\": " + entry.getValue().size())) {
          report.addFailure(refsFile, "Wrong refCount for object: " + objectId);
        }
      }
      File[] objectFiles = objectsDir.listFiles();
      if (objectFiles != null) {
        for (File objectFile : objectFiles) {
          if (objectFile.isFile() && !expectedRefs.containsKey(objectFile.getName())) {
            report.addFailure("Orphan backup object: " + objectFile.getName());
          }
        }
      }
    }

    private static String findObjectRefEntry(String refsText, String objectId) {
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
          "\\{[^}]*\"objectId\"\\s*:\\s*\"" + java.util.regex.Pattern.quote(objectId) + "\"[^}]*\\}");
      java.util.regex.Matcher matcher = pattern.matcher(refsText);
      return matcher.find() ? matcher.group() : null;
    }

    private static void validateBackupManifest(File backupDir, CheckReport report) {
      File manifest = new File(backupDir, BACKUP_MANIFEST_FILE);
      if (!manifest.isFile()) {
        report.addFailure("Missing backup manifest: " + manifest);
        return;
      }
      report.addCheckedFile(manifest);
      try {
        String text = readText(manifest);
        if (!text.contains("\"formatVersion\"")
            || !text.contains("\"backupId\": \"" + CheckReport.escape(backupDir.getName()) + "\"")
            || !text.contains("\"published\": true")) {
          report.addFailure(manifest, "Malformed backup manifest");
        }
      } catch (IOException e) {
        report.addFailure(manifest, rootMessage(e));
      }
    }

    private static Map<String, Set<String>> collectObjectRefs(File backupRoot) throws IOException {
      Map<String, Set<String>> refs = new TreeMap<>();
      for (File backup : listPublishedBackups(backupRoot)) {
        for (File file : Filename.listFiles(backup)) {
          if (isBackupMetadata(file) || !file.isFile()) {
            continue;
          }
          FileInfo info = Filename.parseFileName(file);
          if (info == null && !ColumnFamilyRegistry.FILE_NAME.equals(file.getName())) {
            continue;
          }
          String objectId = objectId(file);
          Set<String> backups = refs.get(objectId);
          if (backups == null) {
            backups = new TreeSet<>();
            refs.put(objectId, backups);
          }
          backups.add(backup.getName());
        }
      }
      return refs;
    }

    private static String readText(File file) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      try (InputStream input = new FileInputStream(file)) {
        int read;
        while ((read = input.read(buffer)) >= 0) {
          output.write(buffer, 0, read);
        }
      }
      return output.toString(UTF_8.name());
    }

    private static ObjectStoreRebuild rebuildObjectStore(File backupRoot, boolean prune) throws IOException {
      ObjectStoreRebuild rebuild = new ObjectStoreRebuild();
      File objectsDir = new File(backupRoot, OBJECTS_DIR);
      if (!objectsDir.exists() && !objectsDir.mkdirs()) {
        throw new IOException("Unable to create backup object store: " + objectsDir);
      }

      Map<String, Set<String>> refs = new TreeMap<>();
      for (File backup : listPublishedBackups(backupRoot)) {
        for (File file : Filename.listFiles(backup)) {
          if (isBackupMetadata(file) || !file.isFile()) {
            continue;
          }
          FileInfo info = Filename.parseFileName(file);
          if (info == null && !ColumnFamilyRegistry.FILE_NAME.equals(file.getName())) {
            continue;
          }
          String objectId = objectId(file);
          Set<String> backups = refs.get(objectId);
          if (backups == null) {
            backups = new TreeSet<>();
            refs.put(objectId, backups);
          }
          backups.add(backup.getName());
          File objectFile = new File(objectsDir, objectId);
          if (!objectFile.isFile()) {
            java.nio.file.Files.copy(
                file.toPath(),
                objectFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
          }
        }
      }

      if (prune) {
        File[] existing = objectsDir.listFiles();
        if (existing != null) {
          for (File file : existing) {
            if (file.isFile() && !refs.containsKey(file.getName())) {
              if (!file.delete() && file.exists()) {
                throw new IOException("Unable to delete unreferenced backup object: " + file);
              }
              rebuild.deletedObjects.add(file.getName());
            }
          }
        }
      }

      writeObjectRefs(new File(backupRoot, OBJECT_REFS_FILE), refs);
      return rebuild;
    }

    private static boolean isBackupMetadata(File file) {
      String name = file.getName();
      return BACKUP_REPORT_FILE.equals(name)
          || RESTORE_REPORT_FILE.equals(name)
          || BACKUP_MANIFEST_FILE.equals(name)
          || OBJECT_REFS_FILE.equals(name);
    }

    private static String objectId(File file) throws IOException {
      java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
      byte[] buffer = new byte[8192];
      try (InputStream input = new FileInputStream(file)) {
        int read;
        while ((read = input.read(buffer)) >= 0) {
          crc32.update(buffer, 0, read);
        }
      }
      return file.getName() + "-" + file.length() + "-" + Long.toHexString(crc32.getValue());
    }

    private static void writeObjectRefs(File file, Map<String, Set<String>> refs) throws IOException {
      try (FileOutputStream output = new FileOutputStream(file)) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"formatVersion\": 1,\n");
        builder.append("  \"objects\": [\n");
        int index = 0;
        for (Entry<String, Set<String>> entry : refs.entrySet()) {
          if (index++ > 0) {
            builder.append(",\n");
          }
          builder.append("    {\"objectId\": \"").append(CheckReport.escape(entry.getKey()))
              .append("\", \"refCount\": ").append(entry.getValue().size())
              .append(", \"backups\": ");
          CheckReport.appendJsonList(builder, new ArrayList<String>(entry.getValue()));
          builder.append('}');
        }
        builder.append("\n  ]\n");
        builder.append("}\n");
        output.write(builder.toString().getBytes(UTF_8));
        output.flush();
        output.getFD().sync();
      }
    }

    private static final class ObjectStoreRebuild {
      private final List<String> deletedObjects = new ArrayList<>();
    }

    private static void appendManifestField(StringBuilder builder, String name, Object value, boolean comma) {
      builder.append("  \"").append(name).append("\": ");
      if (value instanceof String) {
        builder.append('"').append(CheckReport.escape((String) value)).append('"');
      } else if (value instanceof List) {
        CheckReport.appendJsonList(builder, (List<?>) value);
      } else {
        builder.append(value);
      }
      if (comma) {
        builder.append(',');
      }
      builder.append('\n');
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
    private List<LdbColumnFamily> columnFamilies;

    private Repairer(File databaseDir, Options options) {
      this.databaseDir = databaseDir;
      this.options = options == null ? new Options() : options;
      DBComparator comparator = this.options.comparator();
      UserComparator userComparator = comparator != null
          ? new CustomUserComparator(comparator)
          : new BytewiseComparator();
      this.internalKeyComparator = new InternalKeyComparator(userComparator);
      this.columnFamilies = this.options.getColumnFamilies();
    }

    private void repair() throws IOException {
      if (!databaseDir.exists()) {
        throw new FileNotFoundException("Database directory does not exist: " + databaseDir);
      }
      if (!databaseDir.isDirectory()) {
        throw new IOException("Database path is not a directory: " + databaseDir);
      }
      columnFamilies = ColumnFamilyRegistry.load(databaseDir, options);
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

    private RepairReport plan() throws IOException {
      if (!databaseDir.exists()) {
        throw new FileNotFoundException("Database directory does not exist: " + databaseDir);
      }
      if (!databaseDir.isDirectory()) {
        throw new IOException("Database path is not a directory: " + databaseDir);
      }
      columnFamilies = ColumnFamilyRegistry.load(databaseDir, options);
      report.setDryRun(true);
      report.setDatabaseDir(databaseDir);

      List<Long> logs = new ArrayList<>();
      long maxFileNumber = 1;
      long maxSequence = 0;
      boolean hasRecoverableInput = false;

      for (File file : Filename.listFiles(databaseDir)) {
        FileInfo info = Filename.parseFileName(file);
        if (info == null) {
          continue;
        }
        maxFileNumber = Math.max(maxFileNumber, info.getFileNumber());
        if (info.getFileType() == FileType.TABLE) {
          try {
            FileMetaData metaData = readTableMetadata(info.getFileNumber());
            report.addRecoveredSst(file.getName());
            maxSequence = Math.max(maxSequence, metaData.getLargest().getSequenceNumber());
            hasRecoverableInput = true;
          } catch (RuntimeException e) {
            report.addQuarantinedFile(file.getName(), "", "planned-corrupt");
          } catch (IOException e) {
            report.addQuarantinedFile(file.getName(), "", "planned-corrupt");
          }
        } else if (info.getFileType() == FileType.LOG) {
          logs.add(info.getFileNumber());
        }
      }

      Collections.sort(logs);
      for (Long logNumber : logs) {
        try {
          ParsedWalLog parsed = parseWalLog(logNumber);
          report.addReplayedWal(Filename.logFileName(logNumber));
          report.addDiscardedWalBytes(parsed.getDiscardedBytes());
          maxSequence = Math.max(maxSequence, parsed.getMaxSequence());
          if (!parsed.getBatches().isEmpty()) {
            hasRecoverableInput = true;
          }
        } catch (RuntimeException e) {
          report.addQuarantinedFile(Filename.logFileName(logNumber), "", "planned-corrupt");
        } catch (IOException e) {
          report.addQuarantinedFile(Filename.logFileName(logNumber), "", "planned-corrupt");
        }
      }

      if (!hasRecoverableInput) {
        throw new IOException("No readable SST or WAL records found for repair: " + databaseDir);
      }

      long manifestNumber = maxFileNumber + 1;
      long repairedLogNumber = manifestNumber + 1;
      report.setManifestFileNumber(manifestNumber);
      report.setCurrentFile(Filename.currentFileName());
      report.setLastSequence(maxSequence);
      report.setNextFileNumber(repairedLogNumber + 1);
      return report;
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
      boolean hasRangeDeletes = false;
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
      for (LdbColumnFamily cf : columnFamilies) {
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
      for (LdbColumnFamily cf : columnFamilies) {
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
        boolean hasRangeDeletes = false;
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
          if (key.getValueType() == ValueType.DELETE_RANGE) {
            hasRangeDeletes = true;
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
        return new FileMetaData(cfId, fileNumber, file.length(),
            smallest, largest, hasRangeDeletes);
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

    public static final class RepairReport {
      private File databaseDir;
      private final List<String> recoveredSstFiles = new ArrayList<>();
      private final List<String> replayedWalFiles = new ArrayList<>();
      private final List<String> quarantinedFiles = new ArrayList<>();
      private boolean dryRun;
      private long discardedWalBytes;
      private long manifestFileNumber;
      private String currentFile;
      private long lastSequence;
      private long nextFileNumber;

      private void setDatabaseDir(File databaseDir) {
        this.databaseDir = databaseDir;
      }

      private void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
      }

      private void addRecoveredSst(String file) {
        recoveredSstFiles.add(file);
      }

      private void addReplayedWal(String file) {
        replayedWalFiles.add(file);
      }

      private void addQuarantinedFile(String source, String target, String reason) {
        if (target == null || target.isEmpty()) {
          quarantinedFiles.add(source + " (" + reason + ")");
        } else {
          quarantinedFiles.add(source + " -> " + target + " (" + reason + ")");
        }
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

      /**
       * 输出结构化 repair 报告 JSON。
       *
       * dry-run 报告不会对应磁盘上的 `REPAIR-REPORT.json`，调用方可直接读取该字符串。
       */
      public String toJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        appendField(builder, "databaseDir", databaseDir == null ? "" : databaseDir.getAbsolutePath(), true);
        appendField(builder, "dryRun", Boolean.toString(dryRun), false, true);
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
