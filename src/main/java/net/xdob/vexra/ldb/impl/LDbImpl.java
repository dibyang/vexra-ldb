package net.xdob.vexra.ldb.impl;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.xdob.vexra.ldb.*;
import net.xdob.vexra.ldb.impl.Filename.FileInfo;
import net.xdob.vexra.ldb.impl.Filename.FileType;
import net.xdob.vexra.ldb.impl.LdbWriteBatchImpl.Handler;
import net.xdob.vexra.ldb.impl.MemTable.MemTableIterator;
import net.xdob.vexra.ldb.table.BytewiseComparator;
import net.xdob.vexra.ldb.table.CustomUserComparator;
import net.xdob.vexra.ldb.table.TableBuilder;
import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.impl.DbConstants.*;
import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static net.xdob.vexra.ldb.impl.ValueType.*;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_INT;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_LONG;
import static net.xdob.vexra.ldb.util.Slices.writeLengthPrefixedBytes;

@SuppressWarnings("AccessingNonPublicFieldOfAnotherObject")
public class LDbImpl implements LDB {
  static Logger LOG = LoggerFactory.getLogger(LDbImpl.class);
  private static final String CHECKPOINT_REPORT_FILE = "CHECKPOINT-REPORT.json";
  private static final String REPAIR_REPORT_FILE = "REPAIR-REPORT.json";
  private static final String BACKUP_REPORT_FILE = "BACKUP-REPORT.json";
  private static final String RESTORE_REPORT_FILE = "RESTORE-REPORT.json";
  private static final String BACKUP_MANIFEST_FILE = "BACKUP-MANIFEST.json";
  private static final String OBJECT_REFS_FILE = "OBJECT-REFS.json";
  private static final String OBJECTS_DIR = "objects";
  private final Options options;
  private final File databaseDir;
  private final List<LdbPlugin> plugins;
  private final List<PluginStats> pluginStats;
  private DbLock dbLock;

  private final AtomicBoolean shuttingDown = new AtomicBoolean();
  private final ReentrantLock mutex = new ReentrantLock();
  private final Condition backgroundCondition = mutex.newCondition();

  private final Set<Long> pendingOutputs = new HashSet<>();
  private final ConcurrentHashMap<Integer, ColumnFamilyState> cfs = new ConcurrentHashMap<>();
  private final LinkedHashMap<Integer, ColumnFamilyRegistry.Record> columnFamilyRecords = new LinkedHashMap<>();

  private final InternalKeyComparator internalKeyComparator;

  private volatile Throwable backgroundException;
  private final ExecutorService compactionExecutor;
  private final ThreadPoolExecutor pluginNotificationExecutor;
  private final AtomicLong pluginAsyncSubmitted = new AtomicLong();
  private final AtomicLong pluginAsyncCompleted = new AtomicLong();
  private final AtomicLong pluginAsyncRejected = new AtomicLong();
  private Future<?> backgroundCompaction;

  private TableCache tableCache;
  private VersionSet versions;
  private LogWriter log;

  private ManualCompaction manualCompaction;
  private long maxRecoveredSequence = 0;
  private long lastSequence;
  private final OperationStats getStats = new OperationStats("get");
  private final OperationStats writeStats = new OperationStats("write");
  private final OperationStats compactStats = new OperationStats("compact");
  private final OperationStats checkpointStats = new OperationStats("checkpoint");
  private final AtomicLong writeSlowdownDelayCount = new AtomicLong();
  private final AtomicLong writeSlowdownDelayNanos = new AtomicLong();
  private final AtomicLong writeImmutableWaitCount = new AtomicLong();
  private final AtomicLong writeImmutableWaitNanos = new AtomicLong();
  private final AtomicLong writeLevel0StopWaitCount = new AtomicLong();
  private final AtomicLong writeLevel0StopWaitNanos = new AtomicLong();
  private final Object groupCommitMutex = new Object();
  private final LinkedList<GroupCommitRequest> groupCommitQueue = new LinkedList<>();
  private final AtomicLong groupCommitGroupCount = new AtomicLong();
  private final AtomicLong groupCommitRequestCount = new AtomicLong();
  private final AtomicLong groupCommitSyncCount = new AtomicLong();
  private final AtomicLong groupCommitWaitNanos = new AtomicLong();
  private boolean groupCommitLeaderActive;
  private final AtomicBoolean compactionRunning = new AtomicBoolean();
  private final AtomicLong compactionRunCount = new AtomicLong();
  private final AtomicLong compactionSuccessCount = new AtomicLong();
  private final AtomicLong compactionFailureCount = new AtomicLong();
  private final AtomicLong compactionOutputBytes = new AtomicLong();
  private final AtomicLong compactionThrottleDelayCount = new AtomicLong();
  private final AtomicLong compactionThrottleDelayNanos = new AtomicLong();
  private final AtomicLong compactionCancelCount = new AtomicLong();
  private final AtomicLong compactionCleanupFileCount = new AtomicLong();
  private volatile String lastCheckpointSummary = "none";
  private final AtomicLong snapshotCursorOpenCount = new AtomicLong();
  private final AtomicLong snapshotCursorCloseCount = new AtomicLong();
  private volatile String lastCompactionFailure = "";

  public LDbImpl(Options options, File databaseDir) throws IOException {
    requireNonNull(options, "options is null");
    requireNonNull(databaseDir, "databaseDir is null");
    this.options = options;
    this.databaseDir = databaseDir;
    this.plugins = orderedPlugins(options.getPlugins());
    this.pluginStats = createPluginStats(this.plugins);

    DBComparator comparator = options.comparator();
    UserComparator userComparator = comparator != null
        ? new CustomUserComparator(comparator)
        : new BytewiseComparator();
    internalKeyComparator = new InternalKeyComparator(userComparator);

    ThreadFactory compactionThreadFactory = new ThreadFactoryBuilder()
        .setNameFormat("leveldb-compaction-%s")
        .setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            System.out.printf("%s%n", t);
            e.printStackTrace();
          }
        })
        .build();
    compactionExecutor = Executors.newSingleThreadExecutor(compactionThreadFactory);
    pluginNotificationExecutor = options.pluginAsyncEnabled()
        ? new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(options.pluginAsyncQueueCapacity()),
            new ThreadFactoryBuilder()
                .setNameFormat("ldb-plugin-notification-%s")
                .build(),
            new ThreadPoolExecutor.AbortPolicy())
        : null;

    checkArgument(options.getColumnFamilies() != null && !options.getColumnFamilies().isEmpty(),
        "No column families configured");

    if (options.readOnly()) {
      if (!databaseDir.exists()) {
        throw new FileNotFoundException("Read-only LDB directory does not exist: " + databaseDir);
      }
    } else {
      databaseDir.mkdirs();
    }
    checkArgument(databaseDir.exists(),
        "Database directory '%s' does not exist and could not be created", databaseDir);
    checkArgument(databaseDir.isDirectory(),
        "Database directory '%s' is not a directory", databaseDir);
    verifyExistingDatabaseOnOpen();

    boolean opened = false;
    try {
      mutex.lock();
      try {
      if (!options.readOnly()) {
        dbLock = new DbLock(new File(databaseDir, Filename.lockFileName()));
      }

      List<ColumnFamilyRegistry.Record> openedRecords = ColumnFamilyRegistry.loadRecords(databaseDir, options);
      columnFamilyRecords.clear();
      for (ColumnFamilyRegistry.Record record : openedRecords) {
        columnFamilyRecords.put(record.getColumnFamily().getId(), record);
      }
      List<LdbColumnFamily> openedColumnFamilies = listColumnFamiliesLocked();
      checkArgument(!openedColumnFamilies.isEmpty(), "No column families configured");

      for (LdbColumnFamily cf : openedColumnFamilies) {
        ColumnFamilyState cfState = new ColumnFamilyState(cf, databaseDir, options, internalKeyComparator);
        cfs.put(cf.getId(), cfState);
      }

      int tableCacheSize = options.maxOpenFiles() - 10;
      this.tableCache = new TableCache(
          databaseDir,
          tableCacheSize,
          new InternalUserComparator(internalKeyComparator),
          options.verifyChecksums(),
          options);

      this.versions = new VersionSet(databaseDir, tableCache, internalKeyComparator, options);
      this.versions.recover();

      long manifestRecoveredSequence = versions.getLastSequence();

      VersionEdit edit = new VersionEdit();
      long walRecoveredSequence = options.readOnly()
          ? recoverLogsIntoMemTables()
          : recoverLogs(edit);
      maxRecoveredSequence = Math.max(maxRecoveredSequence, walRecoveredSequence);

      lastSequence = Math.max(manifestRecoveredSequence, maxRecoveredSequence);
      versions.setLastSequence(lastSequence);

      if (!options.readOnly()) {
        ColumnFamilyRegistry.storeRecords(databaseDir, columnFamilyRecords.values());
        forceDirectory(databaseDir);
        // 再创建新 WAL，并把 logNumber + lastSequence 一起写进 MANIFEST
        long logFileNumber = versions.getNextFileNumber();
        this.log = Logs.createLogWriter(
            new File(databaseDir, Filename.logFileName(logFileNumber)),
            logFileNumber,  options);

        edit.setLogNumber(log.getFileNumber());
        versions.logAndApply(edit);

        deleteObsoleteFiles();
        maybeScheduleCompaction();
      }
      } finally {
        mutex.unlock();
      }
      notifyOpen();
      opened = true;
    } finally {
      if (!opened) {
        closeAfterOpenFailure();
      }
    }
  }

  private void closeAfterOpenFailure() {
    shuttingDown.set(true);
    compactionExecutor.shutdownNow();
    if (pluginNotificationExecutor != null) {
      pluginNotificationExecutor.shutdownNow();
    }

    if (log != null) {
      try {
        log.close();
      } catch (Exception e) {
        LOG.warn("LDB log close failed after open failure: {}", databaseDir, e);
      }
    }
    if (versions != null) {
      try {
        versions.destroy();
      } catch (Exception e) {
        LOG.warn("LDB versions close failed after open failure: {}", databaseDir, e);
      }
    }
    if (tableCache != null) {
      try {
        tableCache.close();
      } catch (Exception e) {
        LOG.warn("LDB table cache close failed after open failure: {}", databaseDir, e);
      }
    }
    for (ColumnFamilyState cfState : cfs.values()) {
      try {
        cfState.close();
      } catch (Exception e) {
        LOG.warn("LDB column family close failed after open failure: {} {}",
            databaseDir, cfState.getColumnFamily().getName(), e);
      }
    }
    if (dbLock != null) {
      try {
        dbLock.release();
      } catch (Exception e) {
        LOG.warn("LDB lock release failed after open failure: {}", databaseDir, e);
      }
    }
    try {
      closePlugins();
    } catch (RuntimeException e) {
      LOG.warn("LDB plugin close failed after open failure: {}", databaseDir, e);
    }
  }

  private void verifyExistingDatabaseOnOpen() {
    if (!options.verifyOnOpen()) {
      return;
    }
    File current = new File(databaseDir, Filename.currentFileName());
    if (!current.exists() && options.createIfMissing()) {
      return;
    }
    LDBFactory.CheckReport report = LDBFactory.factory.check(databaseDir, options);
    if (!report.isOk()) {
      throw new DBException("LDB startup verification failed: " + report);
    }
  }

  private class PluginContext implements LdbPluginContext {
    private final OptionsView optionsView = new OptionsSnapshot(options);
    private final LdbPlugin plugin;

    private PluginContext(LdbPlugin plugin) {
      this.plugin = plugin;
    }

    @Override
    public File getDatabaseDir() {
      requireCapability(plugin, LdbPluginCapability.METADATA_READ, "getDatabaseDir");
      return databaseDir;
    }

    @Override
    public Options getOptions() {
      requireCapability(plugin, LdbPluginCapability.METADATA_READ, "getOptions");
      return options;
    }

    @Override
    public OptionsView getOptionsView() {
      requireCapability(plugin, LdbPluginCapability.METADATA_READ, "getOptionsView");
      return optionsView;
    }

    @Override
    public List<LdbColumnFamily> getColumnFamilies() {
      requireCapability(plugin, LdbPluginCapability.METADATA_READ, "getColumnFamilies");
      return LDbImpl.this.listColumnFamilies();
    }

    @Override
    public LdbColumnFamily getColumnFamily(int cfId) {
      requireCapability(plugin, LdbPluginCapability.METADATA_READ, "getColumnFamily");
      return LDbImpl.this.getColumnFamily(cfId);
    }

    @Override
    public LdbWriteBatch createWriteBatch() {
      requireCapability(plugin, LdbPluginCapability.MUTATE_WRITE_BATCH, "createWriteBatch");
      return LDbImpl.this.createWriteBatch();
    }

    @Override
    public SnapshotCursor newSnapshotCursor(LdbColumnFamily cf) {
      requireCapability(plugin, LdbPluginCapability.METADATA_READ, "newSnapshotCursor");
      return LDbImpl.this.newSnapshotCursor(cf);
    }

    @Override
    public String getProperty(String name) {
      requireCapability(plugin, LdbPluginCapability.METADATA_READ, "getProperty");
      return LDbImpl.this.getProperty(name);
    }
  }

  private static List<PluginStats> createPluginStats(List<LdbPlugin> plugins) {
    List<PluginStats> stats = new ArrayList<>();
    for (int i = 0; i < plugins.size(); i++) {
      stats.add(new PluginStats(i, plugins.get(i)));
    }
    return Collections.unmodifiableList(stats);
  }

  private static List<LdbPlugin> orderedPlugins(List<LdbPlugin> source) {
    List<PluginRegistration> registrations = new ArrayList<>();
    for (int i = 0; i < source.size(); i++) {
      registrations.add(new PluginRegistration(i, source.get(i)));
    }
    Collections.sort(registrations, new Comparator<PluginRegistration>() {
      @Override
      public int compare(PluginRegistration left, PluginRegistration right) {
        int order = Integer.compare(left.plugin.descriptor().order(), right.plugin.descriptor().order());
        if (order != 0) {
          return order;
        }
        return Integer.compare(left.registrationIndex, right.registrationIndex);
      }
    });
    List<LdbPlugin> ordered = new ArrayList<>();
    for (PluginRegistration registration : registrations) {
      ordered.add(registration.plugin);
    }
    return Collections.unmodifiableList(ordered);
  }

  private void notifyOpen() {
    for (int i = 0; i < plugins.size(); i++) {
      LdbPlugin plugin = plugins.get(i);
      PluginStats stats = pluginStats.get(i);
      if (stats.disabled()) {
        continue;
      }
      long start = System.nanoTime();
      try {
        plugin.onOpen(new PluginContext(plugin));
        long elapsed = System.nanoTime() - start;
        stats.recordSuccess("onOpen", elapsed);
        recordTimeoutIfNeeded(stats, "onOpen", elapsed);
        disableAfterTotalBudget(stats, "onOpen");
      } catch (DBException e) {
        stats.recordFailure("onOpen", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "onOpen");
        disableAfterTotalBudget(stats, "onOpen");
        throw e;
      } catch (RuntimeException e) {
        stats.recordFailure("onOpen", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "onOpen");
        disableAfterTotalBudget(stats, "onOpen");
        throw new DBException("LDB plugin onOpen failed: " + plugin.getClass().getName(), e);
      }
    }
  }

  private void notifyBeforeWrite(LdbWriteBatch updates, WriteOptions writeOptions) {
    for (int i = 0; i < plugins.size(); i++) {
      LdbPlugin plugin = plugins.get(i);
      PluginStats stats = pluginStats.get(i);
      if (stats.disabled()) {
        continue;
      }
      WriteEvent event = new PluginWriteEvent(updates, writeOptions, null, false);
      Long fingerprint = beforeWriteFingerprint(plugin, updates);
      long start = System.nanoTime();
      try {
        plugin.beforeWrite(event);
        verifyBeforeWriteCapability(plugin, updates, fingerprint);
        long elapsed = System.nanoTime() - start;
        stats.recordSuccess("beforeWrite", elapsed);
        recordTimeoutIfNeeded(stats, "beforeWrite", elapsed);
        disableAfterTotalBudget(stats, "beforeWrite");
      } catch (DBException e) {
        stats.recordFailure("beforeWrite", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "beforeWrite");
        disableAfterTotalBudget(stats, "beforeWrite");
        throw e;
      } catch (RuntimeException e) {
        stats.recordFailure("beforeWrite", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "beforeWrite");
        disableAfterTotalBudget(stats, "beforeWrite");
        throw new DBException("LDB plugin beforeWrite failed: " + plugin.getClass().getName(), e);
      }
    }
  }

  private Long beforeWriteFingerprint(LdbPlugin plugin, LdbWriteBatch updates) {
    if (!options.pluginCapabilityEnforcement()
        || plugin.descriptor().capabilities().contains(LdbPluginCapability.MUTATE_WRITE_BATCH)) {
      return null;
    }
    return fingerprint(updates);
  }

  private void requireCapability(LdbPlugin plugin, LdbPluginCapability capability, String operation) {
    if (!options.pluginCapabilityEnforcement()
        || plugin.descriptor().capabilities().contains(capability)) {
      return;
    }
    throw new DBException("LDB plugin operation requires " + capability.name()
        + " capability: plugin=" + plugin.descriptor().name() + ",operation=" + operation);
  }

  private void requireCheckpointCapabilityIfOverridden(LdbPlugin plugin, String operation) {
    if (!overridesPluginMethod(plugin, operation, File.class)) {
      return;
    }
    requireCapability(plugin, LdbPluginCapability.CHECKPOINT_HOOK, operation);
  }

  private static boolean overridesPluginMethod(LdbPlugin plugin, String methodName, Class<?>... parameterTypes) {
    LdbPlugin actual = unwrapPlugin(plugin);
    try {
      return actual.getClass().getMethod(methodName, parameterTypes).getDeclaringClass() != LdbPlugin.class;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static LdbPlugin unwrapPlugin(LdbPlugin plugin) {
    LdbPlugin current = plugin;
    for (int i = 0; i < 8; i++) {
      LdbPlugin next = current.unwrap();
      if (next == null || next == current) {
        return current;
      }
      current = next;
    }
    return current;
  }

  private void verifyBeforeWriteCapability(LdbPlugin plugin, LdbWriteBatch updates, Long fingerprint) {
    if (fingerprint == null) {
      return;
    }
    long after = fingerprint(updates);
    if (after != fingerprint.longValue()) {
      throw new DBException("LDB plugin mutated write batch without MUTATE_WRITE_BATCH capability: "
          + plugin.descriptor().name());
    }
  }

  private static long fingerprint(LdbWriteBatch updates) {
    if (!(updates instanceof LdbWriteBatchImpl)) {
      return 31L * updates.size() + updates.getColumnFamilies().hashCode();
    }
    final long[] hash = {1469598103934665603L};
    LdbWriteBatchImpl internal = (LdbWriteBatchImpl) updates;
    hash[0] = fingerprintPart(hash[0], internal.size());
    hash[0] = fingerprintPart(hash[0], internal.getApproximateSize());
    internal.forEach(new Handler() {
      @Override
      public void put(LdbColumnFamily cf, Slice key, Slice value) {
        hash[0] = fingerprintPart(hash[0], 1);
        hash[0] = fingerprintPart(hash[0], cf.getId());
        hash[0] = fingerprintPart(hash[0], Arrays.hashCode(key.getBytes()));
        hash[0] = fingerprintPart(hash[0], Arrays.hashCode(value.getBytes()));
      }

      @Override
      public void delete(LdbColumnFamily cf, Slice key) {
        hash[0] = fingerprintPart(hash[0], 2);
        hash[0] = fingerprintPart(hash[0], cf.getId());
        hash[0] = fingerprintPart(hash[0], Arrays.hashCode(key.getBytes()));
      }

      @Override
      public void deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
        hash[0] = fingerprintPart(hash[0], 3);
        hash[0] = fingerprintPart(hash[0], cf.getId());
        hash[0] = fingerprintPart(hash[0], Arrays.hashCode(beginKey.getBytes()));
        hash[0] = fingerprintPart(hash[0], Arrays.hashCode(endKey.getBytes()));
      }

      @Override
      public void addLong(LdbColumnFamily cf, Slice key, Slice value) {
        hash[0] = fingerprintPart(hash[0], 4);
        hash[0] = fingerprintPart(hash[0], cf.getId());
        hash[0] = fingerprintPart(hash[0], Arrays.hashCode(key.getBytes()));
        hash[0] = fingerprintPart(hash[0], Arrays.hashCode(value.getBytes()));
      }
    });
    return hash[0];
  }

  private static long fingerprintPart(long current, long value) {
    long next = current ^ value;
    return next * 1099511628211L;
  }

  private void notifyAfterWrite(LdbWriteBatch updates, WriteOptions writeOptions, Snapshot snapshot) {
    for (int i = 0; i < plugins.size(); i++) {
      final LdbPlugin plugin = plugins.get(i);
      final PluginStats stats = pluginStats.get(i);
      if (stats.disabled()) {
        continue;
      }
      final WriteEvent event = new PluginWriteEvent(updates, writeOptions, snapshot, true);
      if (options.pluginAsyncEnabled()) {
        submitPostCommitPluginCallback(plugin, stats, "afterWrite", new Runnable() {
          @Override
          public void run() {
            plugin.afterWrite(event);
          }
        });
        continue;
      }
      long start = System.nanoTime();
      try {
        plugin.afterWrite(event);
        long elapsed = System.nanoTime() - start;
        stats.recordSuccess("afterWrite", elapsed);
        recordTimeoutIfNeeded(stats, "afterWrite", elapsed);
        disableAfterTotalBudget(stats, "afterWrite");
      } catch (DBException e) {
        stats.recordFailure("afterWrite", System.nanoTime() - start, e, true);
        disableAfterFailureThreshold(stats, "afterWrite");
        disableAfterTotalBudget(stats, "afterWrite");
        handlePostCommitPluginFailure(plugin, "afterWrite", e);
      } catch (RuntimeException e) {
        stats.recordFailure("afterWrite", System.nanoTime() - start, e, true);
        disableAfterFailureThreshold(stats, "afterWrite");
        disableAfterTotalBudget(stats, "afterWrite");
        handlePostCommitPluginFailure(plugin, "afterWrite", e);
      }
    }
  }

  private void notifyBeforeCheckpoint(File targetDir) {
    for (int i = 0; i < plugins.size(); i++) {
      LdbPlugin plugin = plugins.get(i);
      PluginStats stats = pluginStats.get(i);
      if (stats.disabled()) {
        continue;
      }
      requireCheckpointCapabilityIfOverridden(plugin, "beforeCheckpoint");
      long start = System.nanoTime();
      try {
        plugin.beforeCheckpoint(targetDir);
        long elapsed = System.nanoTime() - start;
        stats.recordSuccess("beforeCheckpoint", elapsed);
        recordTimeoutIfNeeded(stats, "beforeCheckpoint", elapsed);
        disableAfterTotalBudget(stats, "beforeCheckpoint");
      } catch (DBException e) {
        stats.recordFailure("beforeCheckpoint", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "beforeCheckpoint");
        disableAfterTotalBudget(stats, "beforeCheckpoint");
        throw e;
      } catch (RuntimeException e) {
        stats.recordFailure("beforeCheckpoint", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "beforeCheckpoint");
        disableAfterTotalBudget(stats, "beforeCheckpoint");
        throw new DBException("LDB plugin beforeCheckpoint failed: " + plugin.getClass().getName(), e);
      }
    }
  }

  private void notifyAfterCheckpoint(File targetDir) {
    for (int i = 0; i < plugins.size(); i++) {
      final LdbPlugin plugin = plugins.get(i);
      final PluginStats stats = pluginStats.get(i);
      if (stats.disabled()) {
        continue;
      }
      requireCheckpointCapabilityIfOverridden(plugin, "afterCheckpoint");
      if (options.pluginAsyncEnabled()) {
        submitPostCommitPluginCallback(plugin, stats, "afterCheckpoint", new Runnable() {
          @Override
          public void run() {
            plugin.afterCheckpoint(targetDir);
          }
        });
        continue;
      }
      long start = System.nanoTime();
      try {
        plugin.afterCheckpoint(targetDir);
        long elapsed = System.nanoTime() - start;
        stats.recordSuccess("afterCheckpoint", elapsed);
        recordTimeoutIfNeeded(stats, "afterCheckpoint", elapsed);
        disableAfterTotalBudget(stats, "afterCheckpoint");
      } catch (DBException e) {
        stats.recordFailure("afterCheckpoint", System.nanoTime() - start, e, true);
        disableAfterFailureThreshold(stats, "afterCheckpoint");
        disableAfterTotalBudget(stats, "afterCheckpoint");
        handlePostCommitPluginFailure(plugin, "afterCheckpoint", e);
      } catch (RuntimeException e) {
        stats.recordFailure("afterCheckpoint", System.nanoTime() - start, e, true);
        disableAfterFailureThreshold(stats, "afterCheckpoint");
        disableAfterTotalBudget(stats, "afterCheckpoint");
        handlePostCommitPluginFailure(plugin, "afterCheckpoint", e);
      }
    }
  }

  private void notifyBeforeClose() {
    for (int i = 0; i < plugins.size(); i++) {
      LdbPlugin plugin = plugins.get(i);
      PluginStats stats = pluginStats.get(i);
      if (stats.disabled()) {
        continue;
      }
      long start = System.nanoTime();
      try {
        plugin.beforeClose();
        long elapsed = System.nanoTime() - start;
        stats.recordSuccess("beforeClose", elapsed);
        recordTimeoutIfNeeded(stats, "beforeClose", elapsed);
        disableAfterTotalBudget(stats, "beforeClose");
      } catch (Exception e) {
        stats.recordFailure("beforeClose", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "beforeClose");
        disableAfterTotalBudget(stats, "beforeClose");
        LOG.warn("LDB plugin beforeClose failed: {}", plugin.getClass().getName(), e);
      }
    }
  }

  private void closePlugins() {
    for (int i = 0; i < plugins.size(); i++) {
      LdbPlugin plugin = plugins.get(i);
      PluginStats stats = pluginStats.get(i);
      long start = System.nanoTime();
      try {
        plugin.close();
        long elapsed = System.nanoTime() - start;
        stats.recordSuccess("close", elapsed);
        recordTimeoutIfNeeded(stats, "close", elapsed);
        disableAfterTotalBudget(stats, "close");
      } catch (Exception e) {
        stats.recordFailure("close", System.nanoTime() - start, e, false);
        disableAfterFailureThreshold(stats, "close");
        disableAfterTotalBudget(stats, "close");
        LOG.warn("LDB plugin close failed: {}", plugin.getClass().getName(), e);
      }
    }
  }

  private void recordTimeoutIfNeeded(PluginStats stats, String phase, long elapsedNanos) {
    long timeoutMillis = options.pluginCallbackTimeoutMillis();
    if (timeoutMillis <= 0 || TimeUnit.NANOSECONDS.toMillis(elapsedNanos) < timeoutMillis) {
      return;
    }
    stats.recordTimeout(phase, elapsedNanos);
    if (options.pluginAutoDisableOnTimeout()) {
      stats.disable("timeout:" + phase);
    }
  }

  private void disableAfterFailureThreshold(PluginStats stats, String phase) {
    int threshold = options.pluginAutoDisableFailureThreshold();
    if (threshold > 0 && stats.consecutiveFailures() >= threshold) {
      stats.disable("failure-threshold:" + phase);
    }
  }

  private void disableAfterTotalBudget(PluginStats stats, String phase) {
    long budgetMillis = options.pluginMaxTotalCallbackMillis();
    if (!stats.disabled()
        && budgetMillis > 0
        && TimeUnit.NANOSECONDS.toMillis(stats.totalNanos()) >= budgetMillis) {
      stats.disable("total-callback-budget:" + phase);
    }
  }

  private void submitPostCommitPluginCallback(final LdbPlugin plugin, final PluginStats stats,
                                              final String phase, final Runnable callback) {
    if (pluginNotificationExecutor == null) {
      return;
    }
    try {
      pluginNotificationExecutor.execute(new Runnable() {
        @Override
        public void run() {
          long start = System.nanoTime();
          try {
            callback.run();
            long elapsed = System.nanoTime() - start;
            stats.recordSuccess(phase, elapsed);
            recordTimeoutIfNeeded(stats, phase, elapsed);
            disableAfterTotalBudget(stats, phase);
          } catch (DBException e) {
            stats.recordFailure(phase, System.nanoTime() - start, e, true);
            disableAfterFailureThreshold(stats, phase);
            disableAfterTotalBudget(stats, phase);
            LOG.warn("LDB plugin {} async post-commit notification failed: {}",
                phase, plugin.getClass().getName(), e);
          } catch (RuntimeException e) {
            stats.recordFailure(phase, System.nanoTime() - start, e, true);
            disableAfterFailureThreshold(stats, phase);
            disableAfterTotalBudget(stats, phase);
            LOG.warn("LDB plugin {} async post-commit notification failed: {}",
                phase, plugin.getClass().getName(), e);
          } finally {
            pluginAsyncCompleted.incrementAndGet();
          }
        }
      });
      pluginAsyncSubmitted.incrementAndGet();
    } catch (RejectedExecutionException e) {
      pluginAsyncRejected.incrementAndGet();
      stats.recordFailure(phase, 0, e, true);
      stats.disable("async-queue-rejected:" + phase);
      disableAfterTotalBudget(stats, phase);
    }
  }

  private void handlePostCommitPluginFailure(LdbPlugin plugin, String phase, RuntimeException failure) {
    String message = "LDB plugin " + phase + " post-commit notification failed after data was committed: "
        + plugin.getClass().getName();
    if (plugin.descriptor().failurePolicy() == LdbPluginFailurePolicy.RECORD_AND_CONTINUE) {
      LOG.warn("{}: {}", message, failure.getMessage(), failure);
      return;
    }
    if (failure instanceof DBException && failure.getMessage() != null
        && failure.getMessage().contains("post-commit notification failed")) {
      throw failure;
    }
    throw new DBException(message, failure);
  }

  private static final class PluginWriteEvent implements WriteEvent {
    private final LdbWriteBatch batch;
    private final WriteOptions writeOptions;
    private final Snapshot snapshot;
    private final boolean committed;

    private PluginWriteEvent(LdbWriteBatch batch, WriteOptions writeOptions, Snapshot snapshot, boolean committed) {
      this.batch = batch;
      this.writeOptions = writeOptions;
      this.snapshot = snapshot;
      this.committed = committed;
    }

    @Override
    public WriteBatchView getBatch() {
      return batch;
    }

    @Override
    public WriteOptions getWriteOptions() {
      return writeOptions;
    }

    @Override
    public Snapshot getSnapshot() {
      return snapshot;
    }

    @Override
    public boolean isCommitted() {
      return committed;
    }
  }

  private static final class OptionsSnapshot implements OptionsView {
    private final boolean createIfMissing;
    private final boolean errorIfExists;
    private final int writeBufferSize;
    private final boolean forceLogOnClose;
    private final boolean forceSstOnFlush;
    private final int maxOpenFiles;
    private final int blockRestartInterval;
    private final int blockSize;
    private final CompressionType compressionType;
    private final boolean verifyChecksums;
    private final boolean paranoidChecks;
    private final DBComparator comparator;
    private final long cacheSize;
    private final List<LdbColumnFamily> columnFamilies;
    private final FilterPolicy filterPolicy;
    private final boolean readOnly;
    private final boolean cacheBlocks;
    private final boolean blockCacheWarmOnOpen;
    private final int tableFormatVersion;
    private final boolean writeTableProperties;
    private final boolean allowLegacyTableFormat;
    private final boolean failOnUnknownTableFeature;
    private final boolean writeBlockLocalIndex;
    private final int blockLocalIndexInterval;
    private final int blockCacheSize;
    private final long compactionSuspendTimeoutMillis;
    private final long closeTimeoutMillis;
    private final long slowOperationThresholdMicros;
    private final boolean verifyOnOpen;
    private final int level0CompactionTrigger;
    private final int level0SlowdownWritesTrigger;
    private final int level0StopWritesTrigger;
    private final long compactionRateLimitBytesPerSecond;
    private final long checkpointCopyRateLimitBytesPerSecond;
    private final long writeSlowdownDelayNanos;
    private final boolean groupCommitEnabled;
    private final long groupCommitMaxDelayNanos;
    private final long groupCommitMaxBatchBytes;

    private OptionsSnapshot(Options options) {
      this.createIfMissing = options.createIfMissing();
      this.errorIfExists = options.errorIfExists();
      this.writeBufferSize = options.writeBufferSize();
      this.forceLogOnClose = options.forceLogOnClose();
      this.forceSstOnFlush = options.forceSstOnFlush();
      this.maxOpenFiles = options.maxOpenFiles();
      this.blockRestartInterval = options.blockRestartInterval();
      this.blockSize = options.blockSize();
      this.compressionType = options.compressionType();
      this.verifyChecksums = options.verifyChecksums();
      this.paranoidChecks = options.paranoidChecks();
      this.comparator = options.comparator();
      this.cacheSize = options.cacheSize();
      this.columnFamilies = options.getColumnFamilies();
      this.filterPolicy = options.filterPolicy();
      this.readOnly = options.readOnly();
      this.cacheBlocks = options.cacheBlocks();
      this.blockCacheWarmOnOpen = options.blockCacheWarmOnOpen();
      this.tableFormatVersion = options.tableFormatVersion();
      this.writeTableProperties = options.writeTableProperties();
      this.allowLegacyTableFormat = options.allowLegacyTableFormat();
      this.failOnUnknownTableFeature = options.failOnUnknownTableFeature();
      this.writeBlockLocalIndex = options.writeBlockLocalIndex();
      this.blockLocalIndexInterval = options.blockLocalIndexInterval();
      this.blockCacheSize = options.blockCacheSize();
      this.compactionSuspendTimeoutMillis = options.compactionSuspendTimeoutMillis();
      this.closeTimeoutMillis = options.closeTimeoutMillis();
      this.slowOperationThresholdMicros = options.slowOperationThresholdMicros();
      this.verifyOnOpen = options.verifyOnOpen();
      this.level0CompactionTrigger = options.level0CompactionTrigger();
      this.level0SlowdownWritesTrigger = options.level0SlowdownWritesTrigger();
      this.level0StopWritesTrigger = options.level0StopWritesTrigger();
      this.compactionRateLimitBytesPerSecond = options.compactionRateLimitBytesPerSecond();
      this.checkpointCopyRateLimitBytesPerSecond = options.checkpointCopyRateLimitBytesPerSecond();
      this.writeSlowdownDelayNanos = options.writeSlowdownDelayNanos();
      this.groupCommitEnabled = options.groupCommitEnabled();
      this.groupCommitMaxDelayNanos = options.groupCommitMaxDelayNanos();
      this.groupCommitMaxBatchBytes = options.groupCommitMaxBatchBytes();
    }

    @Override
    public boolean createIfMissing() {
      return createIfMissing;
    }

    @Override
    public boolean errorIfExists() {
      return errorIfExists;
    }

    @Override
    public int writeBufferSize() {
      return writeBufferSize;
    }

    @Override
    public boolean forceLogOnClose() {
      return forceLogOnClose;
    }

    @Override
    public boolean forceSstOnFlush() {
      return forceSstOnFlush;
    }

    @Override
    public int maxOpenFiles() {
      return maxOpenFiles;
    }

    @Override
    public int blockRestartInterval() {
      return blockRestartInterval;
    }

    @Override
    public int blockSize() {
      return blockSize;
    }

    @Override
    public CompressionType compressionType() {
      return compressionType;
    }

    @Override
    public boolean verifyChecksums() {
      return verifyChecksums;
    }

    @Override
    public boolean paranoidChecks() {
      return paranoidChecks;
    }

    @Override
    public DBComparator comparator() {
      return comparator;
    }

    @Override
    public long cacheSize() {
      return cacheSize;
    }

    @Override
    public List<LdbColumnFamily> getColumnFamilies() {
      return columnFamilies;
    }

    @Override
    public FilterPolicy filterPolicy() {
      return filterPolicy;
    }

    @Override
    public boolean readOnly() {
      return readOnly;
    }

    @Override
    public boolean cacheBlocks() {
      return cacheBlocks;
    }

    @Override
    public boolean blockCacheWarmOnOpen() {
      return blockCacheWarmOnOpen;
    }

    @Override
    public int tableFormatVersion() {
      return tableFormatVersion;
    }

    @Override
    public boolean writeTableProperties() {
      return writeTableProperties;
    }

    @Override
    public boolean allowLegacyTableFormat() {
      return allowLegacyTableFormat;
    }

    @Override
    public boolean failOnUnknownTableFeature() {
      return failOnUnknownTableFeature;
    }

    @Override
    public boolean writeBlockLocalIndex() {
      return writeBlockLocalIndex;
    }

    @Override
    public int blockLocalIndexInterval() {
      return blockLocalIndexInterval;
    }

    @Override
    public int blockCacheSize() {
      return blockCacheSize;
    }

    @Override
    public long compactionSuspendTimeoutMillis() {
      return compactionSuspendTimeoutMillis;
    }

    @Override
    public long closeTimeoutMillis() {
      return closeTimeoutMillis;
    }

    @Override
    public long slowOperationThresholdMicros() {
      return slowOperationThresholdMicros;
    }

    @Override
    public boolean verifyOnOpen() {
      return verifyOnOpen;
    }

    @Override
    public int level0CompactionTrigger() {
      return level0CompactionTrigger;
    }

    @Override
    public int level0SlowdownWritesTrigger() {
      return level0SlowdownWritesTrigger;
    }

    @Override
    public int level0StopWritesTrigger() {
      return level0StopWritesTrigger;
    }

    @Override
    public long compactionRateLimitBytesPerSecond() {
      return compactionRateLimitBytesPerSecond;
    }

    @Override
    public long checkpointCopyRateLimitBytesPerSecond() {
      return checkpointCopyRateLimitBytesPerSecond;
    }

    @Override
    public long writeSlowdownDelayNanos() {
      return writeSlowdownDelayNanos;
    }

    @Override
    public boolean groupCommitEnabled() {
      return groupCommitEnabled;
    }

    @Override
    public long groupCommitMaxDelayNanos() {
      return groupCommitMaxDelayNanos;
    }

    @Override
    public long groupCommitMaxBatchBytes() {
      return groupCommitMaxBatchBytes;
    }
  }

  private static final class PluginStats {
    private static final AtomicLong FAILURE_SEQUENCE = new AtomicLong();
    private final int index;
    private final LdbPluginDescriptor descriptor;
    private final ConcurrentHashMap<String, PhaseStats> phases = new ConcurrentHashMap<>();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();
    private final AtomicLong timeoutCount = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private volatile PluginFailure lastFailure;
    private volatile boolean disabled;
    private volatile String degradationReason = "";

    private PluginStats(int index, LdbPlugin plugin) {
      this.index = index;
      this.descriptor = plugin.descriptor();
    }

    private void recordSuccess(String phase, long nanos) {
      phase(phase).record(nanos, false);
      totalNanos.addAndGet(nanos);
      updateMax(maxNanos, nanos);
      consecutiveFailures.set(0);
    }

    private void recordFailure(String phase, long nanos, Throwable failure, boolean committed) {
      phase(phase).record(nanos, true);
      totalNanos.addAndGet(nanos);
      updateMax(maxNanos, nanos);
      consecutiveFailures.incrementAndGet();
      lastFailure = new PluginFailure(
          FAILURE_SEQUENCE.incrementAndGet(),
          phase,
          descriptor.name(),
          failure.getMessage(),
          committed);
    }

    private void recordTimeout(String phase, long nanos) {
      timeoutCount.incrementAndGet();
      phase(phase).recordTimeout(nanos);
      degradationReason = "timeout:" + phase;
    }

    private void disable(String reason) {
      disabled = true;
      degradationReason = reason;
    }

    private PhaseStats phase(String phase) {
      PhaseStats stats = phases.get(phase);
      if (stats != null) {
        return stats;
      }
      PhaseStats created = new PhaseStats();
      PhaseStats existing = phases.putIfAbsent(phase, created);
      return existing == null ? created : existing;
    }

    private long callbackCount() {
      long count = 0;
      for (PhaseStats stats : phases.values()) {
        count += stats.count.get();
      }
      return count;
    }

    private long failureCount() {
      long count = 0;
      for (PhaseStats stats : phases.values()) {
        count += stats.failures.get();
      }
      return count;
    }

    private long maxNanos() {
      return maxNanos.get();
    }

    private long totalNanos() {
      return totalNanos.get();
    }

    private PluginFailure lastFailure() {
      return lastFailure;
    }

    private long timeoutCount() {
      return timeoutCount.get();
    }

    private long consecutiveFailures() {
      return consecutiveFailures.get();
    }

    private boolean disabled() {
      return disabled;
    }

    private boolean degraded() {
      return disabled || timeoutCount.get() > 0 || !degradationReason.isEmpty();
    }

    private String summary() {
      StringBuilder builder = new StringBuilder();
      builder.append("name=").append(descriptor.name())
          .append(",version=").append(descriptor.version())
          .append(",order=").append(descriptor.order())
          .append(",capabilities=").append(capabilitiesSummary(descriptor))
          .append(",failurePolicy=").append(descriptor.failurePolicy())
          .append(",callbacks=").append(callbackCount())
          .append(",failures=").append(failureCount())
          .append(",timeouts=").append(timeoutCount())
          .append(",disabled=").append(disabled)
          .append(",degradationReason=").append(degradationReason)
          .append(",totalMicros=").append(TimeUnit.NANOSECONDS.toMicros(totalNanos.get()))
          .append(",maxMicros=").append(TimeUnit.NANOSECONDS.toMicros(maxNanos.get()));
      List<String> phaseNames = new ArrayList<>(phases.keySet());
      Collections.sort(phaseNames);
      for (String phase : phaseNames) {
        PhaseStats stats = phases.get(phase);
        builder.append(',').append(phase).append(".count=").append(stats.count.get())
            .append(',').append(phase).append(".failures=").append(stats.failures.get())
            .append(',').append(phase).append(".timeouts=").append(stats.timeouts.get());
      }
      PluginFailure failure = lastFailure;
      if (failure != null) {
        builder.append(",lastFailure=").append(failure.summary());
      }
      return builder.toString();
    }

    private static String capabilitiesSummary(LdbPluginDescriptor descriptor) {
      if (descriptor.capabilities().isEmpty()) {
        return "compatibility";
      }
      List<String> names = new ArrayList<>();
      for (LdbPluginCapability capability : descriptor.capabilities()) {
        names.add(capability.name());
      }
      Collections.sort(names);
      return String.join("|", names);
    }

    private static void updateMax(AtomicLong target, long value) {
      long current;
      do {
        current = target.get();
        if (value <= current) {
          return;
        }
      } while (!target.compareAndSet(current, value));
    }
  }

  private static final class PluginRegistration {
    private final int registrationIndex;
    private final LdbPlugin plugin;

    private PluginRegistration(int registrationIndex, LdbPlugin plugin) {
      this.registrationIndex = registrationIndex;
      this.plugin = plugin;
    }
  }

  private static final class PhaseStats {
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();

    private void record(long nanos, boolean failed) {
      count.incrementAndGet();
      if (failed) {
        failures.incrementAndGet();
      }
      PluginStats.updateMax(maxNanos, nanos);
    }

    private void recordTimeout(long nanos) {
      timeouts.incrementAndGet();
      PluginStats.updateMax(maxNanos, nanos);
    }
  }

  private static final class PluginFailure {
    private final long sequence;
    private final String phase;
    private final String pluginName;
    private final String message;
    private final boolean committed;

    private PluginFailure(long sequence, String phase, String pluginName, String message, boolean committed) {
      this.sequence = sequence;
      this.phase = phase;
      this.pluginName = pluginName;
      this.message = message == null ? "" : message.replace(',', ';');
      this.committed = committed;
    }

    private String summary() {
      return phase + ':' + pluginName + ":committed=" + committed;
    }

    private String detail() {
      return "phase=" + phase
          + ",plugin=" + pluginName
          + ",committed=" + committed
          + ",message=" + message;
    }
  }

  private long recoverLogs(VersionEdit edit) throws IOException {
    long minLogNumber = versions.getLogNumber();
    long previousLogNumber = versions.getPrevLogNumber();
    List<File> filenames = Filename.listFiles(databaseDir);

    List<Long> logs = new ArrayList<>();
    for (File filename : filenames) {
      FileInfo fileInfo = Filename.parseFileName(filename);
      if (fileInfo != null
          && fileInfo.getFileType() == FileType.LOG
          && ((fileInfo.getFileNumber() >= minLogNumber)
          || (fileInfo.getFileNumber() == previousLogNumber))) {
        logs.add(fileInfo.getFileNumber());
      }
    }

    Collections.sort(logs);

    Map<Integer, MemTable> recoveringMemTables = new HashMap<>();
    for (ColumnFamilyState state : cfs.values()) {
      recoveringMemTables.put(state.getColumnFamily().getId(), new MemTable(internalKeyComparator));
    }
    long maxSequence = 0;
    for (Long fileNumber : logs) {
      long seq = recoverLogFile(fileNumber, edit, recoveringMemTables);
      if (seq > maxSequence) {
        maxSequence = seq;
      }
    }
    return maxSequence;
  }

  /**
   * 只读打开时重放现有 WAL 到当前实例的 MemTable 视图，不创建 SST、不写 MANIFEST。
   */
  private long recoverLogsIntoMemTables() throws IOException {
    long minLogNumber = versions.getLogNumber();
    long previousLogNumber = versions.getPrevLogNumber();
    List<File> filenames = Filename.listFiles(databaseDir);

    List<Long> logs = new ArrayList<>();
    for (File filename : filenames) {
      FileInfo fileInfo = Filename.parseFileName(filename);
      if (fileInfo != null
          && fileInfo.getFileType() == FileType.LOG
          && ((fileInfo.getFileNumber() >= minLogNumber)
          || (fileInfo.getFileNumber() == previousLogNumber))) {
        logs.add(fileInfo.getFileNumber());
      }
    }

    Collections.sort(logs);
    long maxSequence = 0;
    for (Long fileNumber : logs) {
      maxSequence = Math.max(maxSequence, recoverLogFileIntoMemTables(fileNumber));
    }
    return maxSequence;
  }

  /**
   * 只读恢复单个 WAL 文件；副作用仅限当前实例内存，不改变磁盘文件集合。
   */
  private long recoverLogFileIntoMemTables(long fileNumber) throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    File file = new File(databaseDir, Filename.logFileName(fileNumber));
    try (FileInputStream fis = new FileInputStream(file);
         FileChannel channel = fis.getChannel()) {

      LogMonitor logMonitor = LogMonitors.logMonitor();
      LogReader logReader = new LogReader(channel, logMonitor, true, 0);
      long maxSequence = 0;

      for (Slice record = logReader.readRecord(); record != null; record = logReader.readRecord()) {
        SliceInput sliceInput = record.input();

        if (sliceInput.available() < 12) {
          logMonitor.corruption(sliceInput.available(), "log record too small");
          continue;
        }

        long sequenceBegin = sliceInput.readLong();
        int updateSize = sliceInput.readInt();

        LdbWriteBatchImpl writeBatch = LdbWriteBatchLog.readWriteBatch(
            sliceInput,
            updateSize,
            new LdbWriteBatchLog.ColumnFamilyResolver() {
              @Override
              public LdbColumnFamily getColumnFamily(int cfId) {
                return LDbImpl.this.getColumnFamily(cfId);
              }
            });
        writeBatch.forEach(new InsertIntoHandler(getColumnFamilyStateMap(), sequenceBegin, versions));

        long recoveredLastSequence = sequenceBegin + updateSize - 1;
        if (recoveredLastSequence > maxSequence) {
          maxSequence = recoveredLastSequence;
        }
      }
      return maxSequence;
    }
  }

  private long recoverLogFile(long fileNumber, VersionEdit edit, Map<Integer, MemTable> recoveringMemTables) throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    File file = new File(databaseDir, Filename.logFileName(fileNumber));
    try (FileInputStream fis = new FileInputStream(file);
         FileChannel channel = fis.getChannel()) {

      LogMonitor logMonitor = LogMonitors.logMonitor();
      LogReader logReader = new LogReader(channel, logMonitor, true, 0);

      long maxSequence = 0;

      for (Slice record = logReader.readRecord(); record != null; record = logReader.readRecord()) {
        SliceInput sliceInput = record.input();

        if (sliceInput.available() < 12) {
          logMonitor.corruption(sliceInput.available(), "log record too small");
          continue;
        }

        long sequenceBegin = sliceInput.readLong();
        int updateSize = sliceInput.readInt();

        LdbWriteBatchImpl writeBatch = LdbWriteBatchLog.readWriteBatch(
            sliceInput,
            updateSize,
            new LdbWriteBatchLog.ColumnFamilyResolver() {
              @Override
              public LdbColumnFamily getColumnFamily(int cfId) {
                return LDbImpl.this.getColumnFamily(cfId);
              }
            });
        writeBatch.forEach(new RecoverIntoHandler(recoveringMemTables, versions , sequenceBegin));

        long recoveredLastSequence = sequenceBegin + updateSize - 1;
        if (recoveredLastSequence > maxSequence) {
          maxSequence = recoveredLastSequence;
        }

        for (Entry<Integer, MemTable> e : recoveringMemTables.entrySet()) {
          if (e.getValue().approximateMemoryUsage() > options.writeBufferSize()) {
            writeLevel0Table(e.getKey(), e.getValue(), edit, null);
            recoveringMemTables.put(e.getKey(), new MemTable(internalKeyComparator));
          }
        }
      }

      for (Entry<Integer, MemTable> e : recoveringMemTables.entrySet()) {
        if (!e.getValue().isEmpty()) {
          writeLevel0Table(e.getKey(), e.getValue(), edit, null);
        }
      }

      return maxSequence;
    }
  }

  @Override
  public void close() {
    if (shuttingDown.getAndSet(true)) {
      return;
    }

    List<Throwable> closeFailures = new ArrayList<>();
    try {
      notifyBeforeClose();
    } catch (RuntimeException e) {
      recordCloseFailure(closeFailures, "plugins.beforeClose", e);
    }
    compactionExecutor.shutdown();
    try {
      long closeDeadlineMillis = System.currentTimeMillis() + options.closeTimeoutMillis();
      waitForBackgroundCompactionOnClose(closeFailures, closeDeadlineMillis);
      long awaitMillis = closeDeadlineMillis - System.currentTimeMillis();
      if (awaitMillis <= 0 || !compactionExecutor.awaitTermination(awaitMillis, TimeUnit.MILLISECONDS)) {
        compactionExecutor.shutdownNow();
        recordCloseFailure(closeFailures, "compactionExecutor.awaitTermination",
            new TimeoutException("Timed out closing compaction executor after "
                + options.closeTimeoutMillis() + " ms"));
      }
      waitForPluginNotificationsOnClose(closeFailures);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      compactionExecutor.shutdownNow();
      if (pluginNotificationExecutor != null) {
        pluginNotificationExecutor.shutdownNow();
      }
      recordCloseFailure(closeFailures, "compactionExecutor.awaitTermination", e);
    } finally {
      try {
        if (log != null) {
          log.close();
        }
      } catch (Exception e) {
        recordCloseFailure(closeFailures, "log", e);
      }

      try {
        versions.destroy();
      } catch (Exception e) {
        recordCloseFailure(closeFailures, "versions", e);
      }

      try {
        tableCache.close();
      } catch (Exception e) {
        recordCloseFailure(closeFailures, "tableCache", e);
      }

      for (ColumnFamilyState cfState : cfs.values()) {
        try {
          cfState.close();
        } catch (Exception e) {
          recordCloseFailure(closeFailures, "columnFamily:" + cfState.getColumnFamily().getName(), e);
        }
      }

      try {
        if (dbLock != null) {
          dbLock.release();
        }
      } catch (Exception e) {
        recordCloseFailure(closeFailures, "dbLock", e);
      }
      try {
        closePlugins();
      } catch (RuntimeException e) {
        recordCloseFailure(closeFailures, "plugins.close", e);
      }
      if (!closeFailures.isEmpty()) {
        throw new DBException("Failed to close LDB cleanly: " + databaseDir, closeFailures.get(0));
      }
    }
  }

  private void waitForBackgroundCompactionOnClose(List<Throwable> closeFailures, long closeDeadlineMillis)
      throws InterruptedException {
    mutex.lock();
    try {
      while (backgroundCompaction != null) {
        long waitMillis = closeDeadlineMillis - System.currentTimeMillis();
        if (waitMillis <= 0) {
          backgroundCompaction.cancel(true);
          compactionCancelCount.incrementAndGet();
          recordCloseFailure(closeFailures, "backgroundCompaction",
              new TimeoutException("Timed out waiting for background compaction after "
                  + options.closeTimeoutMillis() + " ms"));
          return;
        }
        backgroundCondition.await(waitMillis, TimeUnit.MILLISECONDS);
      }
    } finally {
      mutex.unlock();
    }
  }

  private void waitForPluginNotificationsOnClose(List<Throwable> closeFailures) throws InterruptedException {
    if (pluginNotificationExecutor == null) {
      return;
    }
    pluginNotificationExecutor.shutdown();
    if (!pluginNotificationExecutor.awaitTermination(
        options.pluginAsyncCloseTimeoutMillis(), TimeUnit.MILLISECONDS)) {
      pluginNotificationExecutor.shutdownNow();
      recordCloseFailure(closeFailures, "pluginNotificationExecutor.awaitTermination",
          new TimeoutException("Timed out closing plugin notification executor after "
              + options.pluginAsyncCloseTimeoutMillis() + " ms"));
    }
  }

  private void recordCloseFailure(List<Throwable> failures, String resource, Throwable failure) {
    failures.add(failure);
    LOG.warn("Failed to close LDB resource {} for {}", resource, databaseDir, failure);
  }

  @Override
  public String getProperty(String name) {
    checkBackgroundException();
    requireNonNull(name, "name is null");
    if ("ldb.databaseDir".equals(name)) {
      return databaseDir.getAbsolutePath();
    }
      if ("ldb.backgroundException".equals(name)) {
        Throwable failure = backgroundException;
        return failure == null ? "" : failure.getClass().getName() + ": " + failure.getMessage();
      }
      if ("ldb.readOnly".equals(name)) {
        return Boolean.toString(options.readOnly());
      }
      if ("ldb.readOnlyCompactionSkipped".equals(name)) {
        return Boolean.toString(options.readOnly());
      }
      if ("ldb.slowOperationThresholdMicros".equals(name)) {
        return Long.toString(options.slowOperationThresholdMicros());
      }
      String pluginProperty = pluginProperty(name);
      if (pluginProperty != null) {
        return pluginProperty;
      }
      String apiCompatibilityProperty = apiCompatibilityProperty(name);
      if (apiCompatibilityProperty != null) {
        return apiCompatibilityProperty;
      }
      String snapshotCursorProperty = snapshotCursorProperty(name);
      if (snapshotCursorProperty != null) {
        return snapshotCursorProperty;
      }
      String operationProperty = operationProperty(name);
      if (operationProperty != null) {
        return operationProperty;
      }
      String checkpointProperty = checkpointProperty(name);
      if (checkpointProperty != null) {
        return checkpointProperty;
      }
      String writeStallProperty = writeStallProperty(name);
      if (writeStallProperty != null) {
        return writeStallProperty;
      }
      String compactionProperty = compactionProperty(name);
      if (compactionProperty != null) {
        return compactionProperty;
      }
      if ("ldb.blockCacheStats".equals(name)) {
        return tableCache.blockCacheStats();
      }
      if ("ldb.sstReadStats".equals(name)) {
        return versions.sstReadStats();
      }
      if ("ldb.tableFormat".equals(name)) {
        return versions.tableFormatStats();
      }
      if ("ldb.storageFormat".equals(name)) {
        return versions.storageFormatStats();
      }
      if ("ldb.tableFormatPolicy".equals(name)) {
        return tableFormatPolicy();
      }
      if ("ldb.prefixReadiness".equals(name)) {
        return prefixReadiness();
      }

      mutex.lock();
      try {
      if ("ldb.lastSequence".equals(name)) {
        return Long.toString(lastSequence);
      }
      if ("ldb.currentLogNumber".equals(name)) {
        return log == null ? "" : Long.toString(log.getFileNumber());
      }
      if ("ldb.referencedLogNumbers".equals(name)) {
        return referencedLogNumbers();
      }
      if ("ldb.walFiles".equals(name)) {
        return walFiles();
      }
      if ("ldb.walFileCount".equals(name)) {
        return Integer.toString(logFileNumbers().size());
      }
      if ("ldb.recyclableLogNumbers".equals(name)) {
        return recyclableLogNumbers();
      }
      if ("ldb.walLifecycle".equals(name)) {
        return walLifecycle();
      }
      if ("ldb.walPolicy".equals(name)) {
        return walPolicy();
      }
      if ("ldb.recoveryEvidence".equals(name)) {
        return recoveryEvidence();
      }
      if ("ldb.backupEvidence".equals(name)) {
        return backupEvidence();
      }
      if ("ldb.walArchiveEnabled".equals(name)) {
        return "false";
      }
      if ("ldb.walGroupCommitEnabled".equals(name)) {
        return Boolean.toString(options.groupCommitEnabled());
      }
      if ("ldb.groupCommitStats".equals(name)) {
        return groupCommitStats();
      }
      if ("ldb.walWriteThrottlePolicy".equals(name)) {
        return "write-stall";
      }
      if ("ldb.manifestFileNumber".equals(name)) {
        return Long.toString(versions.getManifestFileNumber());
      }
      if ("ldb.pendingOutputs".equals(name)) {
        return Integer.toString(pendingOutputs.size());
      }
      if ("ldb.memTableBytes".equals(name)) {
        return Long.toString(totalMemTableBytes());
      }
      if ("ldb.columnFamilyMemTableBytes".equals(name)) {
        return columnFamilyMemTableBytes();
      }
      if ("ldb.columnFamilyEvidence".equals(name)) {
        return columnFamilyEvidence();
      }
      if ("ldb.levelFiles".equals(name)) {
        return levelFiles();
      }
      if ("ldb.fileCounts".equals(name)) {
        return fileStats().countsString();
      }
      if ("ldb.fileBytes".equals(name)) {
        return fileStats().bytesString();
      }
      if ("ldb.totalBytes".equals(name)) {
        return Long.toString(fileStats().totalBytes());
      }
      if ("ldb.liveDataBytes".equals(name)) {
        return Long.toString(liveDataBytes());
      }
      if ("ldb.walBytes".equals(name)) {
        return Long.toString(fileStats().bytes(FileType.LOG));
      }
      if ("ldb.sstBytes".equals(name)) {
        return Long.toString(fileStats().bytes(FileType.TABLE));
      }
      if ("ldb.compactionBacklog".equals(name)) {
        return Boolean.toString(versions.needsCompaction());
      }
      if ("ldb.compactionScore".equals(name)) {
        return Double.toString(versions.getCurrent().getCompactionScore());
      }
      if ("ldb.compactionLevel".equals(name)) {
        return Integer.toString(versions.getCurrent().getCompactionLevel());
      }
      if ("ldb.compactionCfId".equals(name)) {
        return Integer.toString(versions.getCurrent().getCompactionCfId());
      }
      if ("ldb.compactionPendingBytes".equals(name)) {
        return Long.toString(compactionPendingBytes());
      }
      if ("ldb.compactionCandidate".equals(name)) {
        return compactionCandidate();
      }
      if ("ldb.compactionScores".equals(name)) {
        return compactionScores();
      }
      String columnFamilyProperty = columnFamilyProperty(name);
      if (columnFamilyProperty != null) {
        return columnFamilyProperty;
      }
      if ("ldb.columnFamilies".equals(name)) {
        return columnFamiliesProperty();
      }
      return null;
    } finally {
      mutex.unlock();
    }
  }

  private String pluginProperty(String name) {
    if ("ldb.plugins".equals(name)) {
      StringBuilder builder = new StringBuilder();
      for (PluginStats stats : pluginStats) {
        if (builder.length() > 0) {
          builder.append(',');
        }
        builder.append(stats.index)
            .append(':').append(stats.descriptor.name())
            .append(":order=").append(stats.descriptor.order())
            .append(":capabilities=").append(PluginStats.capabilitiesSummary(stats.descriptor));
      }
      return builder.toString();
    }
    if ("ldb.pluginStats".equals(name)) {
      long callbacks = 0;
      long failures = 0;
      long maxNanos = 0;
      PluginFailure latest = null;
      for (PluginStats stats : pluginStats) {
        callbacks += stats.callbackCount();
        failures += stats.failureCount();
        maxNanos = Math.max(maxNanos, stats.maxNanos());
        PluginFailure failure = stats.lastFailure();
        if (failure != null && (latest == null || failure.sequence > latest.sequence)) {
          latest = failure;
        }
      }
      return "count=" + pluginStats.size()
          + ",callbacks=" + callbacks
          + ",failures=" + failures
          + ",maxMicros=" + TimeUnit.NANOSECONDS.toMicros(maxNanos)
          + ",lastFailure=" + (latest == null ? "" : latest.summary());
    }
    if ("ldb.plugin.executionPolicy".equals(name)) {
      return "asyncEnabled=" + options.pluginAsyncEnabled()
          + ",sandbox=false"
          + ",capabilityEnforcement=" + options.pluginCapabilityEnforcement()
          + ",callbackTimeoutMillis=" + options.pluginCallbackTimeoutMillis()
          + ",maxTotalCallbackMillis=" + options.pluginMaxTotalCallbackMillis()
          + ",autoDisableOnTimeout=" + options.pluginAutoDisableOnTimeout()
          + ",autoDisableFailureThreshold=" + options.pluginAutoDisableFailureThreshold()
          + ",configure=syncFailFast"
          + ",onOpen=syncFailFast"
          + ",beforeWrite=syncFailFast"
          + ",afterWrite=syncPostCommitCandidate"
          + ",beforeCheckpoint=syncFailFast"
          + ",afterCheckpoint=syncPostCommitCandidate"
          + ",beforeClose=syncRecordAndClose"
          + ",close=syncRecordAndClose";
    }
    if ("ldb.plugin.asyncStats".equals(name)) {
      return "enabled=" + options.pluginAsyncEnabled()
          + ",queueCapacity=" + options.pluginAsyncQueueCapacity()
          + ",submitted=" + pluginAsyncSubmitted.get()
          + ",completed=" + pluginAsyncCompleted.get()
          + ",rejected=" + pluginAsyncRejected.get()
          + ",pending=" + Math.max(0L, pluginAsyncSubmitted.get() - pluginAsyncCompleted.get());
    }
    if ("ldb.plugin.sandbox".equals(name)) {
      return "false";
    }
    if ("ldb.plugin.degraded".equals(name)) {
      for (PluginStats stats : pluginStats) {
        if (stats.degraded()) {
          return "true";
        }
      }
      return "false";
    }
    if ("ldb.plugin.disabled".equals(name)) {
      StringBuilder builder = new StringBuilder();
      for (PluginStats stats : pluginStats) {
        if (stats.disabled()) {
          if (builder.length() > 0) {
            builder.append(',');
          }
          builder.append(stats.index).append(':').append(stats.descriptor.name());
        }
      }
      return builder.toString();
    }
    if ("ldb.plugin.lastFailure".equals(name)) {
      PluginFailure latest = null;
      for (PluginStats stats : pluginStats) {
        PluginFailure failure = stats.lastFailure();
        if (failure != null && (latest == null || failure.sequence > latest.sequence)) {
          latest = failure;
        }
      }
      return latest == null ? "" : latest.detail();
    }
    String prefix = "ldb.plugin.";
    String suffix = ".stats";
    if (name.startsWith(prefix) && name.endsWith(suffix)) {
      String indexText = name.substring(prefix.length(), name.length() - suffix.length());
      try {
        int index = Integer.parseInt(indexText);
        if (index >= 0 && index < pluginStats.size()) {
          return pluginStats.get(index).summary();
        }
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private String apiCompatibilityProperty(String name) {
    if ("ldb.api.compatibility".equals(name)) {
      return "rocksdbOptions=partial"
          + ",unsupportedConfig=rejected"
          + ",ignoredConfig=none"
          + ",statistics=properties"
          + ",mergeOperator=unsupported"
          + ",prefixExtractor=unsupported"
          + ",rocksdbToolCommands=unsupported"
          + ",ldbToolCommands=partial"
          + ",runtimeColumnFamilyLifecycle=minimal";
    }
    if ("ldb.api.supportedFeatures".equals(name)) {
      return "basicReadWrite,multiGet,columnFamilies,rangeDelete,readOnly,checkpoint,backup,verifyCheck,repair,"
          + "incrementalBackup,groupCommit,operationHistograms,blockCacheStats,"
          + "snapshotCursor,reverseSnapshotCursor,properties,plugins,ldbToolCheck,ldbToolProperties,"
          + "ldbToolScan,"
          + "ldbToolIncrementalBackup,ldbToolCheckBackup,"
          + "ldbToolRepair,ldbToolBackup,ldbToolRestore,ldbToolCheckpoint,"
          + "runtimeColumnFamilyList,runtimeColumnFamilyCreate,runtimeColumnFamilyDropEmpty,"
          + "runtimeColumnFamilyDropNonEmpty,runtimeColumnFamilyRename";
    }
    if ("ldb.api.unsupportedFeatures".equals(name)) {
      return "mergeOperator,prefixExtractor,rocksdbToolCommands,transactions,customEnv,ttl,secondaryIndex";
    }
    if ("ldb.api.ecosystemGaps".equals(name)) {
      return "mergeOperator=requiresDeterministicOperatorAndDiskMetadata"
          + ",prefixExtractor=requiresComparatorFilterAndSnapshotSemantics"
          + ",transactions=requiresIsolationAndCommitProtocol"
          + ",ttl=requiresExpirationMetadataAndCompactionPolicy"
          + ",customEnv=requiresFilesystemAbstraction"
          + ",runtimeColumnFamilyDropNonEmpty=implementedWithRegistryTombstoneAndBestEffortSstGc"
          + ",runtimeColumnFamilyRename=implementedWithStableCfIdRegistryUpdate"
          + ",rocksdbToolCommands=requiresCommandCompatibilityLayer"
          + ",secondaryIndex=requiresIndexFormatAndConsistencyModel";
    }
    if ("ldb.api.rocksdbGapPlan".equals(name)) {
      return "planVersion=rocksdb-gap-next-1"
          + ",nextVersion=0.6.0"
          + ",rocksdbBaseline=11.1.1"
          + ",baselinePolicy=fixedDocumentationAndDynamicReleaseGateRecord"
          + ",lowRiskImplementation=multiGet"
          + ",advancedApiPolicy=mergeOperator|prefixExtractor|transactions|ttl|customEnv:unsupported";
    }
    if ("ldb.api.optionsMapping".equals(name)) {
      return "createIfMissing=supported"
          + ",errorIfExists=supported"
          + ",writeBufferSize=supported"
          + ",maxOpenFiles=supported"
          + ",blockRestartInterval=supported"
          + ",blockSize=supported"
          + ",compressionType=supported"
          + ",verifyChecksums=supported"
          + ",paranoidChecks=supported"
          + ",comparator=supported"
          + ",filterPolicy=supported"
          + ",cacheSize=supported"
          + ",cacheBlocks=supported"
          + ",blockCacheWarmOnOpen=supported"
          + ",tableFormatVersion=supported"
          + ",writeTableProperties=supported"
          + ",allowLegacyTableFormat=supported"
          + ",failOnUnknownTableFeature=supported"
          + ",writeBlockLocalIndex=supported"
          + ",blockLocalIndexInterval=supported"
          + ",blockCacheSize=supported"
          + ",readOnly=supported"
          + ",columnFamilies=supported"
          + ",level0CompactionTrigger=supported"
          + ",level0SlowdownWritesTrigger=supported"
          + ",level0StopWritesTrigger=supported"
          + ",writeSlowdownDelayNanos=supported"
          + ",groupCommitEnabled=supported"
          + ",groupCommitMaxDelayNanos=supported"
          + ",groupCommitMaxBatchBytes=supported"
          + ",compactionRateLimitBytesPerSecond=supported"
          + ",checkpointCopyRateLimitBytesPerSecond=supported"
          + ",compactionSuspendTimeoutMillis=supported"
          + ",closeTimeoutMillis=supported"
          + ",slowOperationThresholdMicros=supported"
          + ",verifyOnOpen=supported"
          + ",forceLogOnClose=supported"
          + ",forceSstOnFlush=supported"
          + ",multiGet=supported"
          + ",mergeOperator=unsupported"
          + ",prefixExtractor=unsupported"
          + ",ldbToolCommands=partial"
          + ",statistics=properties"
          + ",rocksdbToolCommands=unsupported";
    }
    if ("ldb.api.optionValues".equals(name)) {
      return optionValues();
    }
    return null;
  }

  private String tableFormatPolicy() {
    int tableFormatVersion = options.tableFormatVersion();
    boolean selfDescribingWrites = tableFormatVersion >= 2 && options.writeTableProperties();
    String newWrites;
    if (tableFormatVersion == 1) {
      newWrites = "v1";
    }
    else if (!options.writeTableProperties()) {
      newWrites = "v" + tableFormatVersion + "-without-properties-diagnostic";
    }
    else if (tableFormatVersion == 2) {
      newWrites = "v2-properties";
    }
    else if (options.writeBlockLocalIndex()) {
      newWrites = "v3-block-local-index";
    }
    else {
      newWrites = "v3-properties-block-local-index-disabled";
    }
    String productionState = selfDescribingWrites ? "explicit-v" + tableFormatVersion : "default-legacy";
    String unknownFeaturePolicy = options.failOnUnknownTableFeature() ? "fail-fast" : "diagnostic-only";
    return "newWrites=" + newWrites
        + ",configuredTableFormatVersion=" + tableFormatVersion
        + ",writeTableProperties=" + options.writeTableProperties()
        + ",writeBlockLocalIndex=" + options.writeBlockLocalIndex()
        + ",blockLocalIndexInterval=" + options.blockLocalIndexInterval()
        + ",blockLocalIndexState=" + (options.writeBlockLocalIndex() ? "writer-opt-in" : "disabled")
        + ",legacyReads=" + (options.allowLegacyTableFormat() ? "allowed" : "rejected")
        + ",unknownFeaturePolicy=" + unknownFeaturePolicy
        + ",futureVersionPolicy=" + unknownFeaturePolicy
        + ",rollback=new-writes-tableFormatVersion-1"
        + ",existingV2=readable-by-current-version"
        + ",productionState=" + productionState;
  }

  private String optionValues() {
    return "createIfMissing=" + options.createIfMissing()
        + ",errorIfExists=" + options.errorIfExists()
        + ",writeBufferSize=" + options.writeBufferSize()
        + ",maxOpenFiles=" + options.maxOpenFiles()
        + ",blockRestartInterval=" + options.blockRestartInterval()
        + ",blockSize=" + options.blockSize()
        + ",compressionType=" + options.compressionType()
        + ",verifyChecksums=" + options.verifyChecksums()
        + ",paranoidChecks=" + options.paranoidChecks()
        + ",comparator=" + (options.comparator() == null ? "bytewise" : "custom")
        + ",filterPolicy=" + (options.filterPolicy() == null ? "none" : options.filterPolicy().getClass().getName())
        + ",cacheSize=" + options.cacheSize()
        + ",cacheBlocks=" + options.cacheBlocks()
        + ",blockCacheWarmOnOpen=" + options.blockCacheWarmOnOpen()
        + ",tableFormatVersion=" + options.tableFormatVersion()
        + ",writeTableProperties=" + options.writeTableProperties()
        + ",allowLegacyTableFormat=" + options.allowLegacyTableFormat()
        + ",failOnUnknownTableFeature=" + options.failOnUnknownTableFeature()
        + ",writeBlockLocalIndex=" + options.writeBlockLocalIndex()
        + ",blockLocalIndexInterval=" + options.blockLocalIndexInterval()
        + ",blockCacheSize=" + options.blockCacheSize()
        + ",readOnly=" + options.readOnly()
        + ",columnFamilyCount=" + cfs.size()
        + ",level0CompactionTrigger=" + options.level0CompactionTrigger()
        + ",level0SlowdownWritesTrigger=" + options.level0SlowdownWritesTrigger()
        + ",level0StopWritesTrigger=" + options.level0StopWritesTrigger()
        + ",writeSlowdownDelayNanos=" + options.writeSlowdownDelayNanos()
        + ",groupCommitEnabled=" + options.groupCommitEnabled()
        + ",groupCommitMaxDelayNanos=" + options.groupCommitMaxDelayNanos()
        + ",groupCommitMaxBatchBytes=" + options.groupCommitMaxBatchBytes()
        + ",compactionRateLimitBytesPerSecond=" + options.compactionRateLimitBytesPerSecond()
        + ",checkpointCopyRateLimitBytesPerSecond=" + options.checkpointCopyRateLimitBytesPerSecond()
        + ",compactionSuspendTimeoutMillis=" + options.compactionSuspendTimeoutMillis()
        + ",closeTimeoutMillis=" + options.closeTimeoutMillis()
        + ",slowOperationThresholdMicros=" + options.slowOperationThresholdMicros()
        + ",verifyOnOpen=" + options.verifyOnOpen()
        + ",forceLogOnClose=" + options.forceLogOnClose()
        + ",forceSstOnFlush=" + options.forceSstOnFlush();
  }

  private String prefixReadiness() {
    return "defaultPolicy=disabled"
        + ",prefixExtractor=unsupported"
        + ",prefixBloom=unsupported"
        + ",cacheWarmup=notImplemented"
        + ",readPath=fullKey"
        + ",rangeScanStop=callerControlled"
        + ",comparator=" + (options.comparator() == null ? "bytewise" : "custom")
        + ",filterPolicy=" + (options.filterPolicy() == null ? "none" : options.filterPolicy().getClass().getName())
        + ",cacheBlocks=" + options.cacheBlocks()
        + ",blockCacheWarmOnOpen=" + options.blockCacheWarmOnOpen()
        + ",blockCacheSize=" + options.blockCacheSize()
        + ",requiredBeforeEnable=keyEncodingContract|comparatorPrefixOrder|rangeDeleteSemantics|snapshotVisibility|misconfigurationFailFast"
        + ",risk=missedReadsIfMisconfigured";
  }

  private long totalMemTableBytes() {
    long total = 0;
    for (ColumnFamilyState state : cfs.values()) {
      total += state.getMemTable().approximateMemoryUsage();
      if (state.getImmutableMemTable() != null) {
        total += state.getImmutableMemTable().approximateMemoryUsage();
      }
    }
    return total;
  }

  private String operationProperty(String name) {
    if ("ldb.operationStats".equals(name)) {
      return getStats.summary() + "," + writeStats.summary() + ","
          + compactStats.summary() + "," + checkpointStats.summary();
    }
    String prefix = "ldb.operation.";
    if (!name.startsWith(prefix)) {
      return null;
    }
    String remainder = name.substring(prefix.length());
    int separator = remainder.indexOf('.');
    if (separator <= 0 || separator + 1 >= remainder.length()) {
      return null;
    }
    OperationStats stats = operationStats(remainder.substring(0, separator));
    if (stats == null) {
      return null;
    }
    return stats.property(remainder.substring(separator + 1));
  }

  private String checkpointProperty(String name) {
    if ("ldb.checkpointStats".equals(name)) {
      return "copyRateLimitBytesPerSecond=" + options.checkpointCopyRateLimitBytesPerSecond()
          + ",last=" + lastCheckpointSummary;
    }
    if ("ldb.checkpoint.copyRateLimitBytesPerSecond".equals(name)) {
      return Long.toString(options.checkpointCopyRateLimitBytesPerSecond());
    }
    if ("ldb.checkpoint.last".equals(name)) {
      return lastCheckpointSummary;
    }
    return null;
  }

  private String snapshotCursorProperty(String name) {
    if ("ldb.snapshotCursorStats".equals(name)) {
      return "openCount=" + snapshotCursorOpenCount.get()
          + ",closeCount=" + snapshotCursorCloseCount.get()
          + ",activeCount=" + activeSnapshotCursorCount();
    }
    if ("ldb.snapshotCursor.openCount".equals(name)) {
      return Long.toString(snapshotCursorOpenCount.get());
    }
    if ("ldb.snapshotCursor.closeCount".equals(name)) {
      return Long.toString(snapshotCursorCloseCount.get());
    }
    if ("ldb.snapshotCursor.activeCount".equals(name)) {
      return Long.toString(activeSnapshotCursorCount());
    }
    return null;
  }

  private long activeSnapshotCursorCount() {
    return snapshotCursorOpenCount.get() - snapshotCursorCloseCount.get();
  }

  private String groupCommitStats() {
    return "enabled=" + options.groupCommitEnabled()
        + ",maxDelayNanos=" + options.groupCommitMaxDelayNanos()
        + ",maxBatchBytes=" + options.groupCommitMaxBatchBytes()
        + ",groups=" + groupCommitGroupCount.get()
        + ",requests=" + groupCommitRequestCount.get()
        + ",syncGroups=" + groupCommitSyncCount.get()
        + ",waitNanos=" + groupCommitWaitNanos.get();
  }

  private OperationStats operationStats(String operation) {
    if ("get".equals(operation)) {
      return getStats;
    }
    if ("write".equals(operation)) {
      return writeStats;
    }
    if ("compact".equals(operation)) {
      return compactStats;
    }
    if ("checkpoint".equals(operation)) {
      return checkpointStats;
    }
    return null;
  }

  private String writeStallProperty(String name) {
    if ("ldb.writeStallStats".equals(name)) {
      return "slowdown.count=" + writeSlowdownDelayCount.get()
          + ",slowdown.totalMicros=" + nanosToMicros(writeSlowdownDelayNanos)
          + ",slowdown.delayNanos=" + options.writeSlowdownDelayNanos()
          + ",immutableWait.count=" + writeImmutableWaitCount.get()
          + ",immutableWait.totalMicros=" + nanosToMicros(writeImmutableWaitNanos)
          + ",level0StopWait.count=" + writeLevel0StopWaitCount.get()
          + ",level0StopWait.totalMicros=" + nanosToMicros(writeLevel0StopWaitNanos);
    }
    if ("ldb.writeStall.slowdownCount".equals(name)) {
      return Long.toString(writeSlowdownDelayCount.get());
    }
    if ("ldb.writeStall.slowdownMicros".equals(name)) {
      return Long.toString(nanosToMicros(writeSlowdownDelayNanos));
    }
    if ("ldb.writeStall.immutableWaitCount".equals(name)) {
      return Long.toString(writeImmutableWaitCount.get());
    }
    if ("ldb.writeStall.immutableWaitMicros".equals(name)) {
      return Long.toString(nanosToMicros(writeImmutableWaitNanos));
    }
    if ("ldb.writeStall.level0StopWaitCount".equals(name)) {
      return Long.toString(writeLevel0StopWaitCount.get());
    }
    if ("ldb.writeStall.level0StopWaitMicros".equals(name)) {
      return Long.toString(nanosToMicros(writeLevel0StopWaitNanos));
    }
    if ("ldb.writeStall.level0SlowdownTrigger".equals(name)) {
      return Integer.toString(options.level0SlowdownWritesTrigger());
    }
    if ("ldb.writeStall.level0StopTrigger".equals(name)) {
      return Integer.toString(options.level0StopWritesTrigger());
    }
    if ("ldb.writeStall.slowdownDelayNanos".equals(name)) {
      return Long.toString(options.writeSlowdownDelayNanos());
    }
    if ("ldb.compaction.level0CompactionTrigger".equals(name)) {
      return Integer.toString(options.level0CompactionTrigger());
    }
    return null;
  }

  private long nanosToMicros(AtomicLong nanos) {
    return TimeUnit.NANOSECONDS.toMicros(nanos.get());
  }

  private String compactionProperty(String name) {
    if ("ldb.compactionStats".equals(name)) {
      return "running=" + compactionRunning.get()
          + ",runCount=" + compactionRunCount.get()
          + ",successCount=" + compactionSuccessCount.get()
          + ",failureCount=" + compactionFailureCount.get()
          + ",outputBytes=" + compactionOutputBytes.get()
          + ",throttle.count=" + compactionThrottleDelayCount.get()
          + ",throttle.totalMicros=" + nanosToMicros(compactionThrottleDelayNanos)
          + ",cancelCount=" + compactionCancelCount.get()
          + ",cleanupFileCount=" + compactionCleanupFileCount.get()
          + ",lastFailure=" + lastCompactionFailure;
    }
    if ("ldb.compaction.rateLimitBytesPerSecond".equals(name)) {
      return Long.toString(options.compactionRateLimitBytesPerSecond());
    }
    if ("ldb.compaction.running".equals(name)) {
      return Boolean.toString(compactionRunning.get());
    }
    if ("ldb.compaction.runCount".equals(name)) {
      return Long.toString(compactionRunCount.get());
    }
    if ("ldb.compaction.successCount".equals(name)) {
      return Long.toString(compactionSuccessCount.get());
    }
    if ("ldb.compaction.failureCount".equals(name)) {
      return Long.toString(compactionFailureCount.get());
    }
    if ("ldb.compaction.outputBytes".equals(name)) {
      return Long.toString(compactionOutputBytes.get());
    }
    if ("ldb.compaction.throttleCount".equals(name)) {
      return Long.toString(compactionThrottleDelayCount.get());
    }
    if ("ldb.compaction.throttleMicros".equals(name)) {
      return Long.toString(nanosToMicros(compactionThrottleDelayNanos));
    }
    if ("ldb.compaction.cancelCount".equals(name)) {
      return Long.toString(compactionCancelCount.get());
    }
    if ("ldb.compaction.cleanupFileCount".equals(name)) {
      return Long.toString(compactionCleanupFileCount.get());
    }
    if ("ldb.compaction.lastFailure".equals(name)) {
      return lastCompactionFailure;
    }
    return null;
  }

  private String columnFamilyProperty(String name) {
    String prefix = "ldb.columnFamily.";
    if (!name.startsWith(prefix)) {
      return null;
    }
    String remainder = name.substring(prefix.length());
    int separator = remainder.indexOf('.');
    if (separator <= 0 || separator + 1 >= remainder.length()) {
      return null;
    }

    int cfId;
    try {
      cfId = Integer.parseInt(remainder.substring(0, separator));
    } catch (NumberFormatException e) {
      return null;
    }
    ColumnFamilyState state = cfs.get(cfId);
    if (state == null) {
      return null;
    }

    String property = remainder.substring(separator + 1);
    if ("memTableBytes".equals(property)) {
      return Long.toString(memTableBytes(state));
    }
    if ("levelFiles".equals(property)) {
      return levelFiles(state);
    }
    if ("compactionScore".equals(property)) {
      return Double.toString(columnFamilyCompactionCandidate(cfId).score);
    }
    if ("compactionLevel".equals(property)) {
      return Integer.toString(columnFamilyCompactionCandidate(cfId).level);
    }
    if ("compactionPendingBytes".equals(property)) {
      return Long.toString(columnFamilyCompactionPendingBytes(cfId));
    }
    return null;
  }

  /**
   * 返回当前版本按 score 选出的全局 compaction 候选，供运维判断 backlog 指向哪个列族。
   */
  private String compactionCandidate() {
    Version version = versions.getCurrent();
    int cfId = version.getCompactionCfId();
    if (cfId < 0) {
      return "";
    }
    LdbColumnFamily cf = columnFamilyById(cfId);
    CompactionCandidate candidate = columnFamilyCompactionCandidate(cfId);
    return "cf=" + cfId
        + ",name=" + (cf == null ? "" : cf.getName())
        + ",level=" + candidate.level
        + ",score=" + candidate.score
        + ",bytes=" + candidate.bytes;
  }

  /**
   * 汇总每个列族当前最需要 compaction 的层级、评分和该层文件字节数。
   */
  private String compactionScores() {
    List<ColumnFamilyState> states = sortedColumnFamilyStates();
    StringBuilder builder = new StringBuilder();
    for (ColumnFamilyState state : states) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      int cfId = state.getColumnFamily().getId();
      CompactionCandidate candidate = columnFamilyCompactionCandidate(cfId);
      builder.append(cfId).append(':').append(state.getColumnFamily().getName())
          .append("=level").append(candidate.level)
          .append('/').append(candidate.score)
          .append('/').append(candidate.bytes);
    }
    return builder.toString();
  }

  /**
   * 估算所有列族中已经达到 compaction 触发阈值的文件字节数。
   */
  private long compactionPendingBytes() {
    long total = 0;
    for (ColumnFamilyState state : cfs.values()) {
      total += columnFamilyCompactionPendingBytes(state.getColumnFamily().getId());
    }
    return total;
  }

  /**
   * 估算单个列族中达到 compaction 阈值的文件字节数，并把 seek compaction 文件计入 backlog。
   */
  private long columnFamilyCompactionPendingBytes(int cfId) {
    Version version = versions.getCurrent();
    long total = 0;
    for (int level = 0; level + 1 < NUM_LEVELS; level++) {
      long bytes = levelBytes(version, cfId, level);
      double score = compactionScore(version, cfId, level, bytes);
      if (score >= 1.0) {
        total += bytes;
      }
    }
    FileMetaData seekFile = version.getFileToCompact();
    if (seekFile != null && version.getFileToCompactCfId() == cfId) {
      total += seekFile.getFileSize();
    }
    return total;
  }

  /**
   * 计算单个列族的最佳 compaction 候选层级；该值用于属性展示，不直接修改调度状态。
   */
  private CompactionCandidate columnFamilyCompactionCandidate(int cfId) {
    Version version = versions.getCurrent();
    int bestLevel = -1;
    long bestBytes = 0;
    double bestScore = -1;
    for (int level = 0; level + 1 < NUM_LEVELS; level++) {
      long bytes = levelBytes(version, cfId, level);
      double score = compactionScore(version, cfId, level, bytes);
      if (score > bestScore) {
        bestScore = score;
        bestLevel = level;
        bestBytes = bytes;
      }
    }
    return new CompactionCandidate(bestLevel, bestScore, bestBytes);
  }

  /**
   * 按 LevelDB 分层规则计算指定列族和层级的 compaction score。
   */
  private double compactionScore(Version version, int cfId, int level, long bytes) {
    if (level == 0) {
      return 1.0 * version.numberOfFilesInLevel(cfId, level) / options.level0CompactionTrigger();
    }
    return 1.0 * bytes / maxBytesForCompactionLevel(level);
  }

  /**
   * 统计指定列族和层级已经落盘的 SST 字节数。
   */
  private long levelBytes(Version version, int cfId, int level) {
    long bytes = 0;
    for (FileMetaData file : version.getFiles(cfId, level)) {
      bytes += file.getFileSize();
    }
    return bytes;
  }

  /**
   * 根据列族 id 查找配置对象；属性输出使用它补充可读名称。
   */
  private LdbColumnFamily columnFamilyById(int cfId) {
    for (LdbColumnFamily cf : options.getColumnFamilies()) {
      if (cf.getId() == cfId) {
        return cf;
      }
    }
    return null;
  }

  /**
   * 返回指定层级触发 compaction 的目标字节数，保持与 VersionSet 的评分规则一致。
   */
  private static double maxBytesForCompactionLevel(int level) {
    double result = 10 * 1048576.0;
    while (level > 1) {
      result *= 10;
      level--;
    }
    return result;
  }

  private long memTableBytes(ColumnFamilyState state) {
    long bytes = state.getMemTable().approximateMemoryUsage();
    if (state.getImmutableMemTable() != null) {
      bytes += state.getImmutableMemTable().approximateMemoryUsage();
    }
    return bytes;
  }

  private long liveDataBytes() {
    long bytes = totalMemTableBytes();
    for (FileMetaData fileMetaData : versions.getLiveFiles()) {
      bytes += fileMetaData.getFileSize();
    }
    return Math.max(bytes, fileStats().totalBytes());
  }

  private String columnFamilyMemTableBytes() {
    List<ColumnFamilyState> states = sortedColumnFamilyStates();
    StringBuilder builder = new StringBuilder();
    for (ColumnFamilyState state : states) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(state.getColumnFamily().getId()).append(':')
          .append(state.getColumnFamily().getName()).append('=').append(memTableBytes(state));
    }
    return builder.toString();
  }

  private String columnFamiliesProperty() {
    List<LdbColumnFamily> columnFamilies = listColumnFamiliesLocked();
    StringBuilder builder = new StringBuilder();
    for (LdbColumnFamily cf : columnFamilies) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(cf.getId()).append(':').append(cf.getName());
    }
    return builder.toString();
  }

  private String columnFamilyEvidence() {
    int active = 0;
    int dropped = 0;
    StringBuilder registry = new StringBuilder();
    for (ColumnFamilyRegistry.Record record : columnFamilyRecords.values()) {
      if (record.isActive()) {
        active++;
      } else {
        dropped++;
      }
      if (registry.length() > 0) {
        registry.append('|');
      }
      registry.append(record.isActive() ? 'A' : 'D')
          .append(':')
          .append(record.getColumnFamily().getId())
          .append(':')
          .append(record.getColumnFamily().getName());
    }
    if (columnFamilyRecords.isEmpty()) {
      active = cfs.size();
      for (ColumnFamilyState state : sortedColumnFamilyStates()) {
        if (registry.length() > 0) {
          registry.append('|');
        }
        registry.append('A')
            .append(':')
            .append(state.getColumnFamily().getId())
            .append(':')
            .append(state.getColumnFamily().getName());
      }
    }
    return "registryFile=" + fileState(ColumnFamilyRegistry.FILE_NAME)
        + ",activeCount=" + active
        + ",droppedCount=" + dropped
        + ",activeFamilies=" + columnFamiliesProperty()
        + ",registryRecords=" + registry
        + ",memTableBytes=" + columnFamilyMemTableBytes()
        + ",levelFiles=" + levelFiles()
        + ",dropPolicy=tombstoneNoCfIdReuse"
        + ",renamePolicy=stableCfId"
        + ",perCfOptions=unsupported";
  }

  private String referencedLogNumbers() {
    List<Long> logs = new ArrayList<>(getReferencedLogNumbers());
    Collections.sort(logs);
    return joinLongs(logs);
  }

  private String walFiles() {
    return joinLongs(logFileNumbers());
  }

  private String recyclableLogNumbers() {
    Set<Long> referenced = getReferencedLogNumbers();
    List<Long> recyclable = new ArrayList<>();
    for (Long logNumber : logFileNumbers()) {
      if (!referenced.contains(logNumber)) {
        recyclable.add(logNumber);
      }
    }
    return joinLongs(recyclable);
  }

  private String walLifecycle() {
    return "current=" + (log == null ? "" : log.getFileNumber())
        + ",referenced=" + referencedLogNumbers()
        + ",files=" + walFiles()
        + ",recyclable=" + recyclableLogNumbers();
  }

  private String walPolicy() {
    return "scheme=global"
        + ",archive=disabled"
        + ",recycle=delete-obsolete"
        + ",groupCommit=" + (options.groupCommitEnabled() ? "enabled" : "disabled")
        + ",writeThrottle=write-stall";
  }

  private String recoveryEvidence() {
    return "databaseDir=" + databaseDir.getAbsolutePath()
        + ",currentLogNumber=" + (log == null ? "" : log.getFileNumber())
        + ",referencedLogNumbers=" + referencedLogNumbers()
        + ",manifestFileNumber=" + versions.getManifestFileNumber()
        + ",walPolicy=global"
        + ",currentFile=" + fileState("CURRENT")
        + ",manifestFile=" + fileState(Filename.descriptorFileName(versions.getManifestFileNumber()))
        + ",repairReport=" + REPAIR_REPORT_FILE
        + ",repairReportState=" + fileState(REPAIR_REPORT_FILE)
        + ",checkEntry=LDBFactory.check"
        + ",repairPlanEntry=LDBFactory.planRepair"
        + ",repairEntry=LDBFactory.repair"
        + ",formatChanges=none";
  }

  private String backupEvidence() {
    return "backupRoot=callerProvided"
        + ",checkpointReport=" + CHECKPOINT_REPORT_FILE
        + ",checkpointLast=" + lastCheckpointSummary
        + ",backupReport=" + BACKUP_REPORT_FILE
        + ",restoreReport=" + RESTORE_REPORT_FILE
        + ",backupManifest=" + BACKUP_MANIFEST_FILE
        + ",objectRefs=" + OBJECT_REFS_FILE
        + ",objectsDir=" + OBJECTS_DIR
        + ",backupChain=fullOrIncrementalDirectories"
        + ",checkBackupEntry=LDBFactory.checkBackup"
        + ",purgeDryRunEntry=LDBFactory.planPurgeBackups"
        + ",objectStoreMaintenance=rebuildAndPruneOnBackup";
  }

  private String fileState(String fileName) {
    File file = new File(databaseDir, fileName);
    if (file.isFile()) {
      return "present:" + fileName;
    }
    if (file.exists()) {
      return "nonFile:" + fileName;
    }
    return "missing:" + fileName;
  }

  private List<Long> logFileNumbers() {
    List<Long> logs = new ArrayList<>();
    for (File file : Filename.listFiles(databaseDir)) {
      FileInfo info = Filename.parseFileName(file);
      if (info != null && info.getFileType() == FileType.LOG && file.isFile()) {
        logs.add(info.getFileNumber());
      }
    }
    Collections.sort(logs);
    return logs;
  }

  private String joinLongs(List<Long> values) {
    StringBuilder builder = new StringBuilder();
    for (Long value : values) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(value);
    }
    return builder.toString();
  }

  private FileStats fileStats() {
    FileStats stats = new FileStats();
    for (File file : Filename.listFiles(databaseDir)) {
      FileInfo info = Filename.parseFileName(file);
      if (info != null && file.isFile()) {
        stats.add(info.getFileType(), file.length());
      }
    }
    return stats;
  }

  private static final class FileStats {
    private final EnumMap<FileType, Integer> counts =
        new EnumMap<>(FileType.class);
    private final EnumMap<FileType, Long> bytes =
        new EnumMap<>(FileType.class);

    private void add(FileType type, long length) {
      Integer count = counts.get(type);
      counts.put(type, count == null ? 1 : count + 1);
      Long currentBytes = bytes.get(type);
      bytes.put(type, currentBytes == null ? length : currentBytes + length);
    }

    private long bytes(FileType type) {
      Long value = bytes.get(type);
      return value == null ? 0L : value;
    }

    private long totalBytes() {
      long total = 0;
      for (Long value : bytes.values()) {
        total += value;
      }
      return total;
    }

    private String countsString() {
      StringBuilder builder = new StringBuilder();
      for (FileType type : FileType.values()) {
        append(builder, type.name().toLowerCase(Locale.ROOT), count(type));
      }
      return builder.toString();
    }

    private String bytesString() {
      StringBuilder builder = new StringBuilder();
      for (FileType type : FileType.values()) {
        append(builder, type.name().toLowerCase(Locale.ROOT), bytes(type));
      }
      return builder.toString();
    }

    private int count(FileType type) {
      Integer value = counts.get(type);
      return value == null ? 0 : value;
    }

    private void append(StringBuilder builder, String key, long value) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(key).append('=').append(value);
    }
  }

  /**
   * compaction 候选的轻量快照，仅用于属性计算期间传递展示字段。
   */
  private static final class CompactionCandidate {
    private final int level;
    private final double score;
    private final long bytes;

    private CompactionCandidate(int level, double score, long bytes) {
      this.level = level;
      this.score = score;
      this.bytes = bytes;
    }
  }

  private static final class OperationStats {
    private static final long[] HISTOGRAM_BUCKET_MICROS = {10, 100, 1_000, 10_000};

    private final String name;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();
    private final AtomicLong slowCount = new AtomicLong();
    private final AtomicLong[] histogramBuckets = new AtomicLong[HISTOGRAM_BUCKET_MICROS.length + 1];

    private OperationStats(String name) {
      this.name = name;
      for (int i = 0; i < histogramBuckets.length; i++) {
        histogramBuckets[i] = new AtomicLong();
      }
    }

    private void record(long elapsedNanos, long thresholdMicros, File databaseDir) {
      count.incrementAndGet();
      totalNanos.addAndGet(elapsedNanos);
      updateMax(elapsedNanos);
      long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(elapsedNanos);
      histogramBuckets[bucketIndex(elapsedMicros)].incrementAndGet();
      if (elapsedMicros >= thresholdMicros) {
        slowCount.incrementAndGet();
        LOG.warn("Slow LDB operation {} took {} us for {}", name, elapsedMicros, databaseDir);
      }
    }

    private void updateMax(long elapsedNanos) {
      long current = maxNanos.get();
      while (elapsedNanos > current && !maxNanos.compareAndSet(current, elapsedNanos)) {
        current = maxNanos.get();
      }
    }

    private String property(String property) {
      if ("count".equals(property)) {
        return Long.toString(count.get());
      }
      if ("totalMicros".equals(property)) {
        return Long.toString(TimeUnit.NANOSECONDS.toMicros(totalNanos.get()));
      }
      if ("avgMicros".equals(property)) {
        long currentCount = count.get();
        if (currentCount == 0) {
          return "0";
        }
        return Long.toString(TimeUnit.NANOSECONDS.toMicros(totalNanos.get() / currentCount));
      }
      if ("maxMicros".equals(property)) {
        return Long.toString(TimeUnit.NANOSECONDS.toMicros(maxNanos.get()));
      }
      if ("slowCount".equals(property)) {
        return Long.toString(slowCount.get());
      }
      if ("histogramMicros".equals(property)) {
        return histogramMicros();
      }
      return null;
    }

    private String summary() {
      return name + ".count=" + property("count")
          + "," + name + ".avgMicros=" + property("avgMicros")
          + "," + name + ".maxMicros=" + property("maxMicros")
          + "," + name + ".slowCount=" + property("slowCount")
          + "," + name + ".histogramMicros=" + property("histogramMicros");
    }

    private int bucketIndex(long elapsedMicros) {
      for (int i = 0; i < HISTOGRAM_BUCKET_MICROS.length; i++) {
        if (elapsedMicros <= HISTOGRAM_BUCKET_MICROS[i]) {
          return i;
        }
      }
      return HISTOGRAM_BUCKET_MICROS.length;
    }

    private String histogramMicros() {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < HISTOGRAM_BUCKET_MICROS.length; i++) {
        if (builder.length() > 0) {
          builder.append(';');
        }
        builder.append("le").append(HISTOGRAM_BUCKET_MICROS[i]).append('=')
            .append(histogramBuckets[i].get());
      }
      if (builder.length() > 0) {
        builder.append(';');
      }
      builder.append("gt").append(HISTOGRAM_BUCKET_MICROS[HISTOGRAM_BUCKET_MICROS.length - 1])
          .append('=').append(histogramBuckets[HISTOGRAM_BUCKET_MICROS.length].get());
      return builder.toString();
    }
  }

  private String levelFiles() {
    List<ColumnFamilyState> states = sortedColumnFamilyStates();
    StringBuilder builder = new StringBuilder();
    for (ColumnFamilyState state : states) {
      int cfId = state.getColumnFamily().getId();
      for (int level = 0; level < NUM_LEVELS; level++) {
        if (builder.length() > 0) {
          builder.append(',');
        }
        builder.append(cfId).append(':').append(level).append('=')
            .append(versions.numberOfFilesInLevel(cfId, level));
      }
    }
    return builder.toString();
  }

  private String levelFiles(ColumnFamilyState state) {
    StringBuilder builder = new StringBuilder();
    int cfId = state.getColumnFamily().getId();
    for (int level = 0; level < NUM_LEVELS; level++) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(level).append('=').append(versions.numberOfFilesInLevel(cfId, level));
    }
    return builder.toString();
  }

  private List<ColumnFamilyState> sortedColumnFamilyStates() {
    List<ColumnFamilyState> states = new ArrayList<>(cfs.values());
    Collections.sort(states, new Comparator<ColumnFamilyState>() {
      @Override
      public int compare(ColumnFamilyState left, ColumnFamilyState right) {
        return Integer.compare(left.getColumnFamily().getId(), right.getColumnFamily().getId());
      }
    });
    return states;
  }

  private void deleteObsoleteFiles() {
    checkState(mutex.isHeldByCurrentThread());

    Set<Long> live = new HashSet<>(pendingOutputs);
    for (FileMetaData fileMetaData : versions.getLiveFiles()) {
      live.add(fileMetaData.getNumber());
    }

    Set<Long> referencedLogs = getReferencedLogNumbers();

    for (File file : Filename.listFiles(databaseDir)) {
      FileInfo fileInfo = Filename.parseFileName(file);
      if (fileInfo == null) {
        continue;
      }

      long number = fileInfo.getFileNumber();
      boolean keep = true;
      switch (fileInfo.getFileType()) {
        case LOG:
          keep = referencedLogs.contains(number);
          break;
        case DESCRIPTOR:
          keep = (number >= versions.getManifestFileNumber());
          break;
        case TABLE:
          keep = live.contains(number);
          break;
        case TEMP:
          keep = live.contains(number);
          break;
        case CURRENT:
        case DB_LOCK:
        case INFO_LOG:
          keep = true;
          break;
      }

      if (!keep) {
        if (fileInfo.getFileType() == FileType.TABLE) {
          tableCache.evict(number);
        }
        deleteFileOrWarn(file, "obsolete " + fileInfo.getFileType());
      }
    }
  }

  private void deleteFileOrWarn(File file, String reason) {
    if (file.exists() && !file.delete()) {
      LOG.warn("Failed to delete LDB file {} during {}", file, reason);
    }
  }

  /**
   * 刷 MemTable
   */
  public void flushMemTable() {
    checkWritable("flushMemTable");
    mutex.lock();
    try {
      LdbWriteBatchImpl empty = new LdbWriteBatchImpl();
      for (ColumnFamilyState state : cfs.values()) {
        empty.touch(state.getColumnFamily());
      }
      makeRoomForWrite(true, empty);
      for (ColumnFamilyState state : cfs.values()) {
        while (state.getImmutableMemTable() != null) {
          backgroundCondition.awaitUninterruptibly();
        }
      }
    } finally {
      mutex.unlock();
    }
  }

  public void flushMemTable(LdbColumnFamily cf) {
    checkWritable("flushMemTable");
    mutex.lock();
    try {
      LdbWriteBatchImpl empty = new LdbWriteBatchImpl();
      empty.touch(cf);
      makeRoomForWrite(true, empty);

      ColumnFamilyState state = getColumnFamilyState(cf);
      while (state.getImmutableMemTable() != null) {
        backgroundCondition.awaitUninterruptibly();
      }
    } finally {
      mutex.unlock();
    }
  }

  /**
   * 手工 compact 先按 default CF 处理；你后面可以再扩一个带 cfId 的 API。
   */
  public void compactRange(int level, Slice start, Slice end) {
    compactRange(LdbColumnFamily.DEFAULT, level, start, end);
  }

  /**
   * 按列族触发单层手工 compaction，复用后台 compaction 串行调度。
   */
  public void compactRange(LdbColumnFamily cf, int level, Slice start, Slice end) {
    requireNonNull(cf, "cf is null");
    checkWritable("compactRange");
    checkArgument(level >= 0, "level is negative");
    checkArgument(level + 1 < NUM_LEVELS, "level is greater than or equal to %s", NUM_LEVELS);
    requireNonNull(start, "start is null");
    requireNonNull(end, "end is null");
    getColumnFamilyState(cf);

    mutex.lock();
    try {
      while (manualCompaction != null) {
        backgroundCondition.awaitUninterruptibly();
      }
      manualCompaction = new ManualCompaction(cf.getId(), level, start, end);
      maybeScheduleCompaction();

      while (this.manualCompaction != null) {
        backgroundCondition.awaitUninterruptibly();
      }
    } finally {
      mutex.unlock();
    }
  }

  private void maybeScheduleCompaction() {
    checkState(mutex.isHeldByCurrentThread());

    if (options.readOnly()) {
      return;
    }
    if (backgroundCompaction != null) {
      return;
    }
    if (shuttingDown.get()) {
      return;
    }
    if (!hasCompactionWork()) {
      return;
    }

    backgroundCompaction = compactionExecutor.submit(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        try {
          backgroundCall();
        } catch (DatabaseShutdownException ignored) {
          compactionCancelCount.incrementAndGet();
        } catch (Throwable e) {
          backgroundException = e;
          compactionFailureCount.incrementAndGet();
          lastCompactionFailure = e.getClass().getName() + ": " + e.getMessage();
        }
        return null;
      }
    });
  }

  private boolean hasCompactionWork() {
    if (manualCompaction != null) {
      return true;
    }
    for (ColumnFamilyState cfState : cfs.values()) {
      if (cfState.getImmutableMemTable() != null) {
        return true;
      }
    }
    return versions.needsCompaction();
  }

  public void checkBackgroundException() {
    Throwable e = backgroundException;
    if (e != null) {
      throw new BackgroundProcessingException(e);
    }
  }

  private void backgroundCall() throws IOException {
    mutex.lock();
    try {
      if (backgroundCompaction == null) {
        return;
      }

      try {
        if (!shuttingDown.get()) {
          compactionRunning.set(true);
          compactionRunCount.incrementAndGet();
          backgroundCompaction();
          compactionSuccessCount.incrementAndGet();
        }
      } finally {
        compactionRunning.set(false);
        backgroundCompaction = null;
      }
    } finally {
      try {
        maybeScheduleCompaction();
      } finally {
        try {
          backgroundCondition.signalAll();
        } finally {
          mutex.unlock();
        }
      }
    }
  }

  private void backgroundCompaction() throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    for (ColumnFamilyState cfState : cfs.values()) {
      compactMemTableInternal(cfState);
    }

    Compaction compaction = null;
    if (manualCompaction != null) {
      compaction = versions.compactRange(
          manualCompaction.cfId,
          manualCompaction.level,
          new InternalKey(manualCompaction.begin, MAX_SEQUENCE_NUMBER, VALUE),
          new InternalKey(manualCompaction.end, 0, DELETION));
    } else {
      for (ColumnFamilyState cfState : cfs.values()) {
        compaction = versions.pickCompaction(cfState.getColumnFamily().getId());
        if (compaction != null) {
          break;
        }
      }
    }

    if (compaction == null) {
      if (manualCompaction != null) {
        manualCompaction = null;
      }
      return;
    }

    if (manualCompaction == null && compaction.isTrivialMove()) {
      checkState(compaction.getLevelInputs().size() == 1);
      int cfId = compaction.getCfId();
      FileMetaData fileMetaData = compaction.getLevelInputs().get(0);
      compaction.getEdit().deleteFile(cfId, compaction.getLevel(), fileMetaData.getNumber());
      compaction.getEdit().addFile(cfId, compaction.getLevel() + 1, fileMetaData);
      versions.logAndApply(compaction.getEdit());
    } else {
      CompactionState compactionState = new CompactionState(compaction);
      boolean completed = false;
      try {
        doCompactionWork(compactionState);
        completed = true;
      } finally {
        cleanupCompaction(compactionState, !completed);
      }
    }

    if (manualCompaction != null) {
      manualCompaction = null;
    }
  }

  private void cleanupCompaction(CompactionState compactionState, boolean deleteOutputs) {
    checkState(mutex.isHeldByCurrentThread());

    if (compactionState.builder != null) {
      compactionState.builder.abandon();
      compactionState.builder = null;
    }
    if (compactionState.outfile != null) {
      try {
        compactionState.outfile.close();
      } catch (IOException e) {
        LOG.warn("Failed to close unfinished compaction output {} for {}",
            compactionState.currentFileNumber, databaseDir, e);
      }
      compactionState.outfile = null;
    }
    if (compactionState.currentFileNumber != 0) {
      pendingOutputs.remove(compactionState.currentFileNumber);
      if (deleteOutputs) {
        deleteCompactionOutput(compactionState.currentFileNumber, "unfinished compaction output");
      }
    }

    for (FileMetaData output : compactionState.outputs) {
      pendingOutputs.remove(output.getNumber());
      if (deleteOutputs) {
        deleteCompactionOutput(output.getNumber(), "uninstalled compaction output");
      }
    }
  }

  private void deleteCompactionOutput(long fileNumber, String reason) {
    File file = new File(databaseDir, Filename.tableFileName(fileNumber));
    if (file.exists()) {
      tableCache.evict(fileNumber);
      deleteFileOrWarn(file, reason);
      compactionCleanupFileCount.incrementAndGet();
    }
  }

  private Map<Integer, ColumnFamilyState> getColumnFamilyStateMap() {
    return Collections.unmodifiableMap(cfs);
  }

  @Override
  public byte[] get(byte[] key) throws DBException {
    return get(LdbColumnFamily.DEFAULT, key, null);
  }

  @Override
  public byte[] get(LdbColumnFamily cf, byte[] key) {
    return get(cf, key, null);
  }

  public byte[] get(LdbColumnFamily cf, byte[] key, ReadOptions options) throws DBException {
    long start = System.nanoTime();
    try {
      checkBackgroundException();
      ColumnFamilyState state = getColumnFamilyState(cf);
      LookupKey lookupKey;

      mutex.lock();
      try {
        lookupKey = new LookupKey(Slices.wrappedBuffer(key), readSequence(cf, options));

        LookupResult lookupResult = state.getMemTable().get(lookupKey);
        if (lookupResult != null) {
          Slice value = lookupResult.getValue();
          return value == null ? null : value.getBytes();
        }

        if (state.getImmutableMemTable() != null) {
          lookupResult = state.getImmutableMemTable().get(lookupKey);
          if (lookupResult != null) {
            Slice value = lookupResult.getValue();
            return value == null ? null : value.getBytes();
          }
        }
      } finally {
        mutex.unlock();
      }

      LookupResult lookupResult = versions.get(cf.getId(), lookupKey);

      mutex.lock();
      try {
        if (versions.needsCompaction()) {
          maybeScheduleCompaction();
        }
      } finally {
        mutex.unlock();
      }

      if (lookupResult != null) {
        Slice value = lookupResult.getValue();
        if (value != null) {
          return value.getBytes();
        }
      }
      return null;
    } finally {
      getStats.record(System.nanoTime() - start, this.options.slowOperationThresholdMicros(), databaseDir);
    }
  }

  @Override
  public byte[] get(byte[] key, ReadOptions options) throws DBException {
    return get(LdbColumnFamily.DEFAULT, key, options);
  }

  private long readSequence(LdbColumnFamily cf, ReadOptions options) {
    if (options != null && options.snapshot() != null) {
      return getSnapshot(cf, options).getLastSequence();
    }
    return lastSequence;
  }

  private long readSequence(LdbColumnFamily cf) {
    getColumnFamilyState(cf);
    return lastSequence;
  }

  @Override
  public List<byte[]> get(List<byte[]> keys) throws DBException {
    return get(LdbColumnFamily.DEFAULT, keys);
  }

  @Override
  public List<byte[]> get(List<byte[]> keys, ReadOptions options) throws DBException {
    return get(LdbColumnFamily.DEFAULT, keys, options);
  }

  @Override
  public List<byte[]> get(LdbColumnFamily cf, List<byte[]> keys) throws DBException {
    requireNonNull(cf, "cf is null");
    requireNonNull(keys, "keys is null");
    for (byte[] key : keys) {
      requireNonNull(key, "key is null");
    }
    return get(cf, keys, readSequence(cf));
  }

  private List<byte[]> get(LdbColumnFamily cf, List<byte[]> keys, long snapshotSequence) {
    long start = System.nanoTime();
    try {
      checkBackgroundException();
      ColumnFamilyState state = getColumnFamilyState(cf);
      List<byte[]> values = new ArrayList<byte[]>(Collections.nCopies(keys.size(), (byte[]) null));
      List<LookupKey> missedLookupKeys = new ArrayList<LookupKey>();
      List<Integer> missedIndexes = new ArrayList<Integer>();

      mutex.lock();
      try {
        for (int i = 0; i < keys.size(); i++) {
          LookupKey lookupKey = new LookupKey(Slices.wrappedBuffer(keys.get(i)), snapshotSequence);
          LookupResult lookupResult = state.getMemTable().get(lookupKey);
          if (lookupResult != null) {
            values.set(i, valueBytes(lookupResult));
            continue;
          }

          if (state.getImmutableMemTable() != null) {
            lookupResult = state.getImmutableMemTable().get(lookupKey);
            if (lookupResult != null) {
              values.set(i, valueBytes(lookupResult));
              continue;
            }
          }
          missedLookupKeys.add(lookupKey);
          missedIndexes.add(i);
        }
      } finally {
        mutex.unlock();
      }

      List<LookupResult> lookupResults = versions.get(cf.getId(), missedLookupKeys);
      for (int i = 0; i < lookupResults.size(); i++) {
        LookupResult lookupResult = lookupResults.get(i);
        if (lookupResult != null) {
          values.set(missedIndexes.get(i), valueBytes(lookupResult));
        }
      }

      mutex.lock();
      try {
        if (versions.needsCompaction()) {
          maybeScheduleCompaction();
        }
      } finally {
        mutex.unlock();
      }
      return values;
    } finally {
      getStats.record(System.nanoTime() - start, this.options.slowOperationThresholdMicros(), databaseDir);
    }
  }

  @Override
  public List<byte[]> get(LdbColumnFamily cf, List<byte[]> keys, ReadOptions options) throws DBException {
    requireNonNull(cf, "cf is null");
    requireNonNull(keys, "keys is null");
    requireNonNull(options, "options is null");
    for (byte[] key : keys) {
      requireNonNull(key, "key is null");
    }

    long start = System.nanoTime();
    try {
      checkBackgroundException();
      ColumnFamilyState state = getColumnFamilyState(cf);
      List<byte[]> values = new ArrayList<byte[]>(Collections.nCopies(keys.size(), (byte[]) null));
      List<LookupKey> missedLookupKeys = new ArrayList<LookupKey>();
      List<Integer> missedIndexes = new ArrayList<Integer>();

      mutex.lock();
      try {
        SnapshotImpl snapshot = getSnapshot(cf, options);
        for (int i = 0; i < keys.size(); i++) {
          LookupKey lookupKey = new LookupKey(Slices.wrappedBuffer(keys.get(i)), snapshot.getLastSequence());
          LookupResult lookupResult = state.getMemTable().get(lookupKey);
          if (lookupResult != null) {
            values.set(i, valueBytes(lookupResult));
            continue;
          }

          if (state.getImmutableMemTable() != null) {
            lookupResult = state.getImmutableMemTable().get(lookupKey);
            if (lookupResult != null) {
              values.set(i, valueBytes(lookupResult));
              continue;
            }
          }
          missedLookupKeys.add(lookupKey);
          missedIndexes.add(i);
        }
      } finally {
        mutex.unlock();
      }

      List<LookupResult> lookupResults = versions.get(cf.getId(), missedLookupKeys);
      for (int i = 0; i < lookupResults.size(); i++) {
        LookupResult lookupResult = lookupResults.get(i);
        if (lookupResult != null) {
          values.set(missedIndexes.get(i), valueBytes(lookupResult));
        }
      }

      mutex.lock();
      try {
        if (versions.needsCompaction()) {
          maybeScheduleCompaction();
        }
      } finally {
        mutex.unlock();
      }
      return values;
    } finally {
      getStats.record(System.nanoTime() - start, this.options.slowOperationThresholdMicros(), databaseDir);
    }
  }

  private static byte[] valueBytes(LookupResult lookupResult) {
    Slice value = lookupResult.getValue();
    return value == null ? null : value.getBytes();
  }

  @Override
  public void put(byte[] key, byte[] value) throws DBException {
    put(key, value, new WriteOptions());
  }

  @Override
  public Snapshot put(byte[] key, byte[] value, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().put(key, value), options);
  }

  @Override
  public void delete(byte[] key) throws DBException {
    writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().delete(key), new WriteOptions());
  }

  @Override
  public long addLong(byte[] key, long delta) throws DBException {
    return addLong(LdbColumnFamily.DEFAULT, key, delta);
  }

  @Override
  public void put(LdbColumnFamily cf, byte[] key, byte[] value) throws DBException {
    put(cf, key, value, new WriteOptions());
  }

  @Override
  public void delete(LdbColumnFamily cf, byte[] key) throws DBException {
    delete(cf, key, new WriteOptions());
  }

  @Override
  public long addLong(LdbColumnFamily cf, byte[] key, long delta) throws DBException {
    addLong(cf, key, delta, new WriteOptions());
    byte[] bytes = get(cf, key);
    if (bytes == null) {
      throw new IllegalArgumentException("key not found");
    }
    return Slices.decodeLong(bytes).orElseThrow(() -> new IllegalArgumentException("key not found"));
  }

  @Override
  public Snapshot delete(byte[] key, WriteOptions options) throws DBException {
    return delete(LdbColumnFamily.DEFAULT, key, options);
  }

  @Override
  public Snapshot put(LdbColumnFamily cf, byte[] key, byte[] value, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().put(cf, key, value), options);
  }

  @Override
  public Snapshot delete(LdbColumnFamily cf, byte[] key, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().delete(cf, key), options);
  }

  @Override
  public Snapshot addLong(LdbColumnFamily cf, byte[] key, long delta, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().addLong(cf, key, delta), options);
  }

  @Override
  public void write(LdbWriteBatch updates) throws DBException {
    writeInternal(requireInternalWriteBatch(updates), new WriteOptions());
  }

  @Override
  public Snapshot write(LdbWriteBatch updates, WriteOptions options) throws DBException {
    return writeInternal(requireInternalWriteBatch(updates), options);
  }

  public Snapshot writeInternal(LdbWriteBatchImpl updates, WriteOptions options) throws DBException {
    long start = System.nanoTime();
    try {
      requireNonNull(updates, "updates is null");
      requireNonNull(options, "options is null");
      checkWritable("write");
      checkBackgroundException();
      validateWriteBatch(updates);
      notifyBeforeWrite(updates, options);
      validateWriteBatch(updates);
      Snapshot snapshot = this.options.groupCommitEnabled() && !updates.isEmpty()
          ? writeWithGroupCommit(updates, options)
          : writeAlone(updates, options);
      notifyAfterWrite(updates, options, snapshot);
      return snapshot;
    } finally {
      writeStats.record(System.nanoTime() - start, this.options.slowOperationThresholdMicros(), databaseDir);
    }
  }

  private Snapshot writeAlone(LdbWriteBatchImpl updates, WriteOptions options) {
    mutex.lock();
    try {
      long sequenceEnd = applyWriteLocked(updates, options, options.sync());
      return options.snapshot() ? new SnapshotImpl(versions.getCurrent(), sequenceEnd) : null;
    } finally {
      mutex.unlock();
    }
  }

  private Snapshot writeWithGroupCommit(LdbWriteBatchImpl updates, WriteOptions options) {
    GroupCommitRequest request = new GroupCommitRequest(updates, options);
    boolean leader = false;
    synchronized (groupCommitMutex) {
      groupCommitQueue.add(request);
      groupCommitMutex.notifyAll();
      while (!request.done) {
        if (!groupCommitLeaderActive && groupCommitQueue.peek() == request) {
          groupCommitLeaderActive = true;
          leader = true;
          break;
        }
        waitForGroupCommit(request);
      }
    }
    if (leader) {
      runGroupCommitLeader(request);
    } else if (request.failure != null) {
      throw request.failure;
    }
    return request.snapshot;
  }

  private void waitForGroupCommit(GroupCommitRequest request) {
    try {
      groupCommitMutex.wait();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (!request.done) {
        request.done = true;
        request.failure = new DBException("Interrupted while waiting for group commit", e);
        groupCommitQueue.remove(request);
      }
    }
  }

  private void runGroupCommitLeader(GroupCommitRequest leaderRequest) {
    while (!leaderRequest.done) {
      List<GroupCommitRequest> group = collectGroupCommitRequests();
      RuntimeException failure = null;
      try {
        applyGroupCommit(group);
      } catch (RuntimeException e) {
        failure = e;
      }
      synchronized (groupCommitMutex) {
        for (GroupCommitRequest request : group) {
          request.failure = failure;
          request.done = true;
        }
        groupCommitLeaderActive = false;
        groupCommitMutex.notifyAll();
      }
      if (failure != null && leaderRequest.failure != null) {
        throw leaderRequest.failure;
      }
      synchronized (groupCommitMutex) {
        if (!leaderRequest.done && !groupCommitLeaderActive && groupCommitQueue.peek() == leaderRequest) {
          groupCommitLeaderActive = true;
          continue;
        }
      }
    }
    if (leaderRequest.failure != null) {
      throw leaderRequest.failure;
    }
  }

  private List<GroupCommitRequest> collectGroupCommitRequests() {
    long start = System.nanoTime();
    long deadline = start + options.groupCommitMaxDelayNanos();
    synchronized (groupCommitMutex) {
      while (System.nanoTime() < deadline && estimatedGroupCommitBytes() < options.groupCommitMaxBatchBytes()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          break;
        }
        try {
          long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
          int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
          groupCommitMutex.wait(millis, nanos);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      List<GroupCommitRequest> group = new ArrayList<>();
      long bytes = 0;
      while (!groupCommitQueue.isEmpty()) {
        GroupCommitRequest next = groupCommitQueue.peek();
        long nextBytes = Math.max(1, next.updates.getApproximateSize());
        if (!group.isEmpty() && bytes + nextBytes > options.groupCommitMaxBatchBytes()) {
          break;
        }
        group.add(groupCommitQueue.removeFirst());
        bytes += nextBytes;
      }
      groupCommitWaitNanos.addAndGet(System.nanoTime() - start);
      return group;
    }
  }

  private long estimatedGroupCommitBytes() {
    long bytes = 0;
    for (GroupCommitRequest request : groupCommitQueue) {
      bytes += Math.max(1, request.updates.getApproximateSize());
    }
    return bytes;
  }

  private void applyGroupCommit(List<GroupCommitRequest> group) {
    if (group.isEmpty()) {
      return;
    }
    boolean sync = false;
    for (GroupCommitRequest request : group) {
      sync |= request.options.sync();
    }
    mutex.lock();
    try {
      List<GroupCommitRequest> prepared = new ArrayList<>(group.size());
      for (GroupCommitRequest request : group) {
        makeRoomForWrite(false, request.updates);
      }
      for (int i = 0; i < group.size(); i++) {
        GroupCommitRequest request = group.get(i);
        long sequenceBegin = lastSequence + 1;
        long sequenceEnd = sequenceBegin + request.updates.size() - 1;
        lastSequence = sequenceEnd;
        versions.setLastSequence(sequenceEnd);

        Slice record = writeWriteBatch(request.updates, sequenceBegin);
        appendToLog(record, sync && i == group.size() - 1);
        request.sequenceEnd = sequenceEnd;
        prepared.add(request);
      }
      // WAL 全部写入成功后再统一应用 MemTable，避免组内后续 WAL 失败时出现
      // “调用方收到失败但前序请求已经可见”的部分提交状态。
      for (GroupCommitRequest request : prepared) {
        long sequenceBegin = request.sequenceEnd - request.updates.size() + 1;
        request.updates.forEach(new InsertIntoHandler(getColumnFamilyStateMap(), sequenceBegin, versions));
        request.snapshot = request.options.snapshot() ? new SnapshotImpl(versions.getCurrent(), request.sequenceEnd) : null;
      }
    } finally {
      mutex.unlock();
    }
    groupCommitGroupCount.incrementAndGet();
    groupCommitRequestCount.addAndGet(group.size());
    if (sync) {
      groupCommitSyncCount.incrementAndGet();
    }
  }

  private long applyWriteLocked(LdbWriteBatchImpl updates, WriteOptions options, boolean sync) {
    long sequenceEnd;

    if (!updates.isEmpty()) {
      makeRoomForWrite(false, updates);

      long sequenceBegin = lastSequence + 1;
      sequenceEnd = sequenceBegin + updates.size() - 1;
      lastSequence = sequenceEnd;
      versions.setLastSequence(sequenceEnd);

      Slice record = writeWriteBatch(updates, sequenceBegin);
      appendToLog(record, sync);

      updates.forEach(new InsertIntoHandler(getColumnFamilyStateMap(), sequenceBegin, versions ));
    } else {
      sequenceEnd = lastSequence;
    }
    return sequenceEnd;
  }

  private static final class GroupCommitRequest {
    private final LdbWriteBatchImpl updates;
    private final WriteOptions options;
    private long sequenceEnd;
    private Snapshot snapshot;
    private RuntimeException failure;
    private boolean done;

    private GroupCommitRequest(LdbWriteBatchImpl updates, WriteOptions options) {
      this.updates = updates;
      this.options = options;
    }
  }

  private LdbWriteBatchImpl requireInternalWriteBatch(LdbWriteBatch updates) {
    requireNonNull(updates, "updates is null");
    if (!(updates instanceof LdbWriteBatchImpl)) {
      throw new DBException("Unsupported LdbWriteBatch implementation: " + updates.getClass().getName());
    }
    return (LdbWriteBatchImpl) updates;
  }

  private void validateWriteBatch(LdbWriteBatchImpl updates) {
    updates.validateForWrite();
  }

  private void appendToLog(Slice record, boolean sync) {
    try {
      log.addRecord(record, sync);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public LdbWriteBatch createWriteBatch() {
    checkBackgroundException();
    return new LdbWriteBatchImpl();
  }

  private void checkWritable(String operation) {
    if (this.options.readOnly()) {
      throw new DBException("LDB is opened read-only; " + operation + " is not allowed: " + databaseDir);
    }
  }


  public RawCursor newRawCursor() {
    return newRawCursor(LdbColumnFamily.DEFAULT);
  }

  RawCursor newRawCursor(LdbColumnFamily cf) {
    return new DbRawCursor(internalIterator(cf));
  }

  @Override
  public SnapshotCursor newSnapshotCursor() {
    return newSnapshotCursor(LdbColumnFamily.DEFAULT);
  }

  @Override
  public SnapshotCursor newSnapshotCursor(LdbColumnFamily cf) {
    checkBackgroundException();
    requireNonNull(cf, "cf is null");

    SnapshotImpl snapshot = getSnapshot(cf, new ReadOptions());
    snapshotCursorOpenCount.incrementAndGet();
    return new DbSnapshotCursor(
        newRawCursor(cf),
        snapshot,
        internalKeyComparator,
        new Runnable() {
          @Override
          public void run() {
            snapshotCursorCloseCount.incrementAndGet();
          }
        }
    );
  }

  DbIterator internalIterator(LdbColumnFamily cf) {
    checkBackgroundException();
    mutex.lock();
    try {
      ColumnFamilyState state = getColumnFamilyState(cf);

      MemTableIterator immutableIterator = null;
      if (state.getImmutableMemTable() != null) {
        immutableIterator = state.getImmutableMemTable().iterator();
      }

      Version current = versions.getCurrent();
      return new DbIterator(
          state.getMemTable().iterator(),
          immutableIterator,
          current,
          current.getLevel0Files(cf.getId()),
          current.getLevelIterators(cf.getId()),
          internalKeyComparator);
    } finally {
      mutex.unlock();
    }
  }

  @Override
  public Snapshot getSnapshot() {
    checkBackgroundException();
    return getSnapshot(LdbColumnFamily.DEFAULT, new ReadOptions());
  }

  @Override
  public Snapshot getSnapshot(LdbColumnFamily cf) {
    return getSnapshot(cf, new ReadOptions());
  }

  private SnapshotImpl getSnapshot(ReadOptions options) {
    return getSnapshot(LdbColumnFamily.DEFAULT, options);
  }

  private SnapshotImpl getSnapshot(LdbColumnFamily cf, ReadOptions options) {
    SnapshotImpl snapshot;
    if (options.snapshot() != null) {
      snapshot = (SnapshotImpl) options.snapshot();
    } else {
      snapshot = new SnapshotImpl(versions.getCurrent(), lastSequence);
      //snapshot.close();
    }
    return snapshot;
  }

  public int numberOfFilesInLevel(int level) {
    return numberOfFilesInLevel(LdbColumnFamily.DEFAULT, level);
  }

  @Override
  public int numberOfFilesInLevel(LdbColumnFamily cf, int level) {
    return versions.getCurrent().getFiles(cf.getId(), level).size();
  }

  private void makeRoomForWrite(boolean force, LdbWriteBatchImpl updates) {
    checkState(mutex.isHeldByCurrentThread());

    boolean allowDelay = !force;
    List<ColumnFamilyState> touched = getTouchedColumnFamilies(updates);

    while (true) {
      if (allowDelay && anyLevel0Slowdown(touched)) {
        long delayStart = System.nanoTime();
        try {
          mutex.unlock();
          LockSupport.parkNanos(options.writeSlowdownDelayNanos());
        } finally {
          mutex.lock();
          writeSlowdownDelayCount.incrementAndGet();
          writeSlowdownDelayNanos.addAndGet(System.nanoTime() - delayStart);
        }
        allowDelay = false;
        continue;
      }

      boolean hasRoom = true;
      boolean shouldWait = false;
      boolean waitForImmutable = false;
      boolean waitForLevel0Stop = false;
      List<ColumnFamilyState> needRotate = new ArrayList<>();

      for (ColumnFamilyState cfState : touched) {
        int cfId = cfState.getColumnFamily().getId();

        if (!force && cfState.getMemTable().approximateMemoryUsage() <= options.writeBufferSize()) {
          continue;
        }

        hasRoom = false;

        if (cfState.getImmutableMemTable() != null) {
          shouldWait = true;
          waitForImmutable = true;
          break;
        }

        if (versions.numberOfFilesInLevel(cfId, 0) >= options.level0StopWritesTrigger()) {
          shouldWait = true;
          waitForLevel0Stop = true;
          break;
        }

        needRotate.add(cfState);
      }

      if (hasRoom) {
        break;
      }

      if (shouldWait) {
        long waitStart = System.nanoTime();
        backgroundCondition.awaitUninterruptibly();
        long waited = System.nanoTime() - waitStart;
        if (waitForImmutable) {
          writeImmutableWaitCount.incrementAndGet();
          writeImmutableWaitNanos.addAndGet(waited);
        }
        if (waitForLevel0Stop) {
          writeLevel0StopWaitCount.incrementAndGet();
          writeLevel0StopWaitNanos.addAndGet(waited);
        }
        continue;
      }

      if (!needRotate.isEmpty()) {
        rotateMemTables(needRotate);
      }

      force = false;
      maybeScheduleCompaction();
    }
  }


  private void rotateMemTables(List<ColumnFamilyState> states) {
    checkState(mutex.isHeldByCurrentThread());
    checkArgument(states != null && !states.isEmpty(), "states is empty");

    long oldLogNumber = log.getFileNumber();

    // 1. 先关闭旧 WAL
    try {
      log.close();
    } catch (IOException e) {
      throw new RuntimeException("Unable to close log file " + databaseDir, e);
    }

    // 2. 一次性创建新 WAL
    long newLogNumber = versions.getNextFileNumber();
    try {
      log = Logs.createLogWriter(
          new File(databaseDir, Filename.logFileName(newLogNumber)),
          newLogNumber, options);
    } catch (IOException e) {
      throw new RuntimeException("Unable to open new log file in " + databaseDir, e);
    }

    // 3. 把新 logNumber 持久化到 MANIFEST
    VersionEdit edit = new VersionEdit();
    edit.setLogNumber(newLogNumber);
    edit.setPreviousLogNumber(oldLogNumber);
    try {
      versions.logAndApply(edit);
    } catch (IOException e) {
      throw new RuntimeException("Unable to persist new log number", e);
    }

    // 4. 所有需要 rotate 的 CF 共享这一个 oldLogNumber
    for (ColumnFamilyState cfState : states) {
      cfState.setImmutableLogNumber(oldLogNumber);
      cfState.setImmutableMemTable(cfState.getMemTable());
      cfState.setMemTable(new MemTable(internalKeyComparator));
    }
  }

  private List<ColumnFamilyState> getTouchedColumnFamilies(LdbWriteBatchImpl updates) {
    List<ColumnFamilyState> result = new ArrayList<>();
    for (LdbColumnFamily cf : updates.getColumnFamilies()) {
      result.add(getColumnFamilyState(cf));
    }
    return result;
  }

  private ColumnFamilyState getColumnFamilyState(LdbColumnFamily cf) {
    ColumnFamilyState familyState = cfs.get(cf.getId());
    if (familyState == null) {
      throw new IllegalArgumentException("Column family " + cf.getName() + " does not exist");
    }
    return familyState;
  }

  private boolean anyLevel0Slowdown(List<ColumnFamilyState> states) {
    for (ColumnFamilyState state : states) {
      if (versions.numberOfFilesInLevel(state.getColumnFamily().getId(), 0) > options.level0SlowdownWritesTrigger()) {
        return true;
      }
    }
    return false;
  }


  private void compactMemTableInternal(ColumnFamilyState cfState) throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    if (cfState.getImmutableMemTable() == null) {
      return;
    }

    try {
      VersionEdit edit = new VersionEdit();
      int cfId = cfState.getColumnFamily().getId();
      Version base = versions.getCurrent();

      writeLevel0Table(cfId, cfState.getImmutableMemTable(), edit, base);

      if (shuttingDown.get()) {
        throw new DatabaseShutdownException("Database shutdown during memtable compaction");
      }

      edit.setPreviousLogNumber(0);
      edit.setLogNumber(log.getFileNumber());
      versions.logAndApply(edit);

      cfState.setImmutableMemTable(null);
      cfState.setImmutableLogNumber(0);

      deleteObsoleteFiles();
    } finally {
      backgroundCondition.signalAll();
    }
  }

  private Set<Long> getReferencedLogNumbers() {
    Set<Long> referenced = new HashSet<>();
    referenced.add(versions.getLogNumber()); // 当前 log 一定保留

    for (ColumnFamilyState cfState : cfs.values()) {
      long immutableLogNumber = cfState.getImmutableLogNumber();
      if (immutableLogNumber > 0) {
        referenced.add(immutableLogNumber);
      }
    }
    return referenced;
  }

  private void writeLevel0Table(int cfId, MemTable mem, VersionEdit edit, Version base)
      throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    if (mem.isEmpty()) {
      return;
    }

    long fileNumber = versions.getNextFileNumber();
    pendingOutputs.add(fileNumber);
    mutex.unlock();

    FileMetaData meta;
    try {
      meta = buildTable(cfId, mem, fileNumber);
    } finally {
      mutex.lock();
    }

    pendingOutputs.remove(fileNumber);

    int level = 0;
    if (meta != null && meta.getFileSize() > 0) {
      Slice minUserKey = meta.getSmallest().getUserKey();
      Slice maxUserKey = meta.getLargest().getUserKey();
      if (base != null) {
        level = base.pickLevelForMemTableOutput(cfId, minUserKey, maxUserKey);
      }
      edit.addFile(cfId, level, meta);
      compactionOutputBytes.addAndGet(meta.getFileSize());
    }

  }

  private FileMetaData buildTable(int cfId,
                                  SeekingIterable<InternalKey, Slice> data,
                                  long fileNumber) throws IOException {
    File file = new File(databaseDir, Filename.tableFileName(fileNumber));
    try {
      InternalKey smallest = null;
      InternalKey largest = null;
      boolean hasRangeDeletes = false;

      FileChannel channel = new FileOutputStream(file).getChannel();
      try {
        TableBuilder tableBuilder = new TableBuilder(
            options,
            channel,
            new InternalUserComparator(internalKeyComparator));

        for (Entry<InternalKey, Slice> entry : data) {
          InternalKey key = entry.getKey();
          if (smallest == null) {
            smallest = key;
          }
          if (key.getValueType() == DELETE_RANGE) {
            hasRangeDeletes = true;
          }
          largest = largerMetadataKey(largest, metadataLargestKey(key, entry.getValue()));
          Slice encodedKey = key.encode();
          Slice value = entry.getValue();
          tableBuilder.add(encodedKey, value);
          throttleCompactionOutput(encodedKey.length() + value.length());
        }

        tableBuilder.finish();
      } finally {
        try {
          if (options.forceSstOnFlush()) {
            channel.force(true);
          }
        } finally {
          channel.close();
        }
      }

      if (smallest == null) {
        return null;
      }

      FileMetaData fileMetaData = new FileMetaData(cfId, fileNumber, file.length(),
          smallest, largest, hasRangeDeletes);
      tableCache.newIterator(fileMetaData);
      return fileMetaData;
    } catch (IOException e) {
      deleteFileOrWarn(file, "failed level0 table build");
      throw e;
    }
  }

  private void doCompactionWork(CompactionState compactionState) throws IOException {
    checkState(mutex.isHeldByCurrentThread());
    checkArgument(
        versions.numberOfBytesInLevel(compactionState.getCompaction().getCfId(),
            compactionState.getCompaction().getLevel()) > 0);
    checkArgument(compactionState.builder == null);
    checkArgument(compactionState.outfile == null);

    compactionState.smallestSnapshot = lastSequence;

    mutex.unlock();
    try {
      MergingIterator iterator = versions.makeInputIterator(compactionState.compaction);

      Slice currentUserKey = null;
      boolean hasCurrentUserKey = false;
      long lastSequenceForKey = MAX_SEQUENCE_NUMBER;

      while (iterator.hasNext() && !shuttingDown.get()) {
        InternalKey key = iterator.peek().getKey();
        if (compactionState.compaction.shouldStopBefore(key) && compactionState.builder != null) {
          finishCompactionOutputFile(compactionState);
        }

        boolean drop = false;
        if (!hasCurrentUserKey ||
            internalKeyComparator.getUserComparator().compare(key.getUserKey(), currentUserKey) != 0) {
          currentUserKey = key.getUserKey();
          hasCurrentUserKey = true;
          lastSequenceForKey = MAX_SEQUENCE_NUMBER;
        }

        if (key.getValueType() == DELETE_RANGE) {
          drop = false;
        } else if (lastSequenceForKey <= compactionState.smallestSnapshot) {
          drop = true;
        } else if (key.getValueType() == DELETION
            && key.getSequenceNumber() <= compactionState.smallestSnapshot
            && compactionState.compaction.isBaseLevelForKey(key.getUserKey())) {
          drop = true;
        }

        lastSequenceForKey = key.getSequenceNumber();

        if (!drop) {
          if (compactionState.builder == null) {
            openCompactionOutputFile(compactionState);
          }
          if (compactionState.builder.getEntryCount() == 0) {
            compactionState.currentSmallest = key;
          }
          if (key.getValueType() == DELETE_RANGE) {
            compactionState.currentHasRangeDeletes = true;
          }
          compactionState.currentLargest =
              largerMetadataKey(compactionState.currentLargest, metadataLargestKey(key, iterator.peek().getValue()));
          Slice encodedKey = key.encode();
          Slice value = iterator.peek().getValue();
          compactionState.builder.add(encodedKey, value);
          throttleCompactionOutput(encodedKey.length() + value.length());

          if (compactionState.builder.getFileSize()
              >= compactionState.compaction.getMaxOutputFileSize()) {
            finishCompactionOutputFile(compactionState);
          }
        }
        iterator.next();
      }

      if (shuttingDown.get()) {
        throw new DatabaseShutdownException("DB shutdown during compaction");
      }
      if (compactionState.builder != null) {
        finishCompactionOutputFile(compactionState);
      }
    } finally {
      mutex.lock();
    }

    installCompactionResults(compactionState);
  }

  private void openCompactionOutputFile(CompactionState compactionState) throws FileNotFoundException {
    requireNonNull(compactionState, "compactionState is null");
    checkArgument(compactionState.builder == null, "compactionState builder is not null");

    mutex.lock();
    try {
      long fileNumber = versions.getNextFileNumber();
      pendingOutputs.add(fileNumber);
      compactionState.currentFileNumber = fileNumber;
      compactionState.currentFileSize = 0;
      compactionState.currentSmallest = null;
      compactionState.currentLargest = null;
      compactionState.currentHasRangeDeletes = false;

      File file = new File(databaseDir, Filename.tableFileName(fileNumber));
      compactionState.outfile = new FileOutputStream(file).getChannel();
      compactionState.builder = new TableBuilder(
          options,
          compactionState.outfile,
          new InternalUserComparator(internalKeyComparator));
    } finally {
      mutex.unlock();
    }
  }

  private void finishCompactionOutputFile(CompactionState compactionState) throws IOException {
    requireNonNull(compactionState, "compactionState is null");
    checkArgument(compactionState.outfile != null);
    checkArgument(compactionState.builder != null);

    long outputNumber = compactionState.currentFileNumber;
    checkArgument(outputNumber != 0);

    long currentEntries = compactionState.builder.getEntryCount();
    compactionState.builder.finish();

    long currentBytes = compactionState.builder.getFileSize();
    compactionState.currentFileSize = currentBytes;
    compactionState.totalBytes += currentBytes;

    FileMetaData currentFileMetaData = new FileMetaData(
        compactionState.compaction.getCfId(),
        compactionState.currentFileNumber,
        compactionState.currentFileSize,
        compactionState.currentSmallest,
        compactionState.currentLargest,
        compactionState.currentHasRangeDeletes);
    compactionState.outputs.add(currentFileMetaData);

    compactionState.builder = null;

    if(options.forceSstOnFlush()){
      compactionState.outfile.force(true);
    }
    compactionState.outfile.close();
    compactionState.outfile = null;

    if (currentEntries > 0) {
      tableCache.newIterator(outputNumber);
    }
  }

  private void installCompactionResults(CompactionState compact) throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    compact.compaction.addInputDeletions(compact.compaction.getEdit());
    int level = compact.compaction.getLevel();
    int cfId = compact.compaction.getCfId();

    for (FileMetaData output : compact.outputs) {
      compact.compaction.getEdit().addFile(cfId, level + 1, output);
      pendingOutputs.remove(output.getNumber());
      compactionOutputBytes.addAndGet(output.getFileSize());
    }

    try {
      versions.logAndApply(compact.compaction.getEdit());
      deleteObsoleteFiles();
    } catch (IOException e) {
      for (FileMetaData output : compact.outputs) {
        File file = new File(databaseDir, Filename.tableFileName(output.getNumber()));
        deleteFileOrWarn(file, "failed compaction install");
      }
      compact.outputs.clear();
    }
  }

  /**
   * 对 compaction 输出做可选限速。默认配置为 0 时完全跳过；开启后只在后台线程 sleep，
   * 不持有 DB mutex，避免限速期间阻塞前台读写路径。
   */
  private void throttleCompactionOutput(long outputBytes) {
    long rate = options.compactionRateLimitBytesPerSecond();
    if (rate <= 0 || outputBytes <= 0) {
      return;
    }

    long sleepNanos = (long) Math.ceil((double) outputBytes * 1_000_000_000D / (double) rate);
    if (sleepNanos <= 0) {
      return;
    }
    compactionThrottleDelayCount.incrementAndGet();
    long start = System.nanoTime();
    try {
      TimeUnit.NANOSECONDS.sleep(sleepNanos);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DatabaseShutdownException("Interrupted while throttling compaction output");
    } finally {
      compactionThrottleDelayNanos.addAndGet(System.nanoTime() - start);
    }
  }

  @Override
  public long[] getApproximateSizes(Range... ranges) {
    requireNonNull(ranges, "ranges is null");
    long[] sizes = new long[ranges.length];
    for (int i = 0; i < ranges.length; i++) {
      sizes[i] = getApproximateSizes(ranges[i]);
    }
    return sizes;
  }

  public long getApproximateSizes(Range range) {
    long total = 0;
    Version v = versions.getCurrent();
    for (LdbColumnFamily cf : options.getColumnFamilies()) {
      InternalKey startKey = new InternalKey(Slices.wrappedBuffer(range.start()), MAX_SEQUENCE_NUMBER, VALUE);
      InternalKey limitKey = new InternalKey(Slices.wrappedBuffer(range.limit()), MAX_SEQUENCE_NUMBER, VALUE);
      long startOffset = v.getApproximateOffsetOf(cf.getId(), startKey);
      long limitOffset = v.getApproximateOffsetOf(cf.getId(), limitKey);
      total += (limitOffset >= startOffset ? limitOffset - startOffset : 0);
    }
    return total;
  }

  private static class CompactionState {
    private final Compaction compaction;
    private final List<FileMetaData> outputs = new ArrayList<>();

    private long smallestSnapshot;

    private FileChannel outfile;
    private TableBuilder builder;

    private long currentFileNumber;
    private long currentFileSize;
    private InternalKey currentSmallest;
    private InternalKey currentLargest;
    private boolean currentHasRangeDeletes;

    private long totalBytes;

    private CompactionState(Compaction compaction) {
      this.compaction = compaction;
    }

    public Compaction getCompaction() {
      return compaction;
    }
  }

  private static class ManualCompaction {
    private final int cfId;
    private final int level;
    private final Slice begin;
    private final Slice end;

    private ManualCompaction(int cfId, int level, Slice begin, Slice end) {
      this.cfId = cfId;
      this.level = level;
      this.begin = begin;
      this.end = end;
    }
  }

  public LdbColumnFamily getColumnFamily(int cfId) {
    ColumnFamilyState familyState = cfs.get(cfId);
    if (familyState == null) {
      throw new IllegalArgumentException("Unknown column family id in log: " + cfId);
    }
    return familyState.getColumnFamily();
  }

  @Override
  public List<LdbColumnFamily> listColumnFamilies() {
    mutex.lock();
    try {
      return Collections.unmodifiableList(listColumnFamiliesLocked());
    } finally {
      mutex.unlock();
    }
  }

  private List<LdbColumnFamily> listColumnFamiliesLocked() {
    List<LdbColumnFamily> result = new ArrayList<>();
    if (!columnFamilyRecords.isEmpty()) {
      for (ColumnFamilyRegistry.Record record : columnFamilyRecords.values()) {
        if (record.isActive()) {
          result.add(record.getColumnFamily());
        }
      }
    } else {
      for (ColumnFamilyState state : cfs.values()) {
        result.add(state.getColumnFamily());
      }
    }
    Collections.sort(result, new Comparator<LdbColumnFamily>() {
      @Override
      public int compare(LdbColumnFamily left, LdbColumnFamily right) {
        return Integer.compare(left.getId(), right.getId());
      }
    });
    return result;
  }

  @Override
  public LdbColumnFamily createColumnFamily(int cfId, String name) throws DBException {
    checkWritable("createColumnFamily");
    checkBackgroundException();
    LdbColumnFamily cf = new PersistentColumnFamily(cfId, name);

    mutex.lock();
    try {
      if (cfs.containsKey(cfId)) {
        throw new DBException("Column family id already exists: " + cfId);
      }
      if (columnFamilyRecords.containsKey(cfId)) {
        throw new DBException("Column family id was used by a lifecycle record and cannot be reused: " + cfId);
      }
      for (ColumnFamilyState state : cfs.values()) {
        if (state.getColumnFamily().getName().equals(name)) {
          throw new DBException("Column family name already exists: " + name);
        }
      }

      ColumnFamilyState state = new ColumnFamilyState(cf, databaseDir, options, internalKeyComparator);
      cfs.put(cfId, state);
      columnFamilyRecords.put(cfId, new ColumnFamilyRegistry.Record(cf, true));
      try {
        ColumnFamilyRegistry.storeRecords(databaseDir, columnFamilyRecords.values());
        forceDirectory(databaseDir);
      } catch (IOException e) {
        cfs.remove(cfId);
        columnFamilyRecords.remove(cfId);
        state.close();
        throw new DBException("Failed to persist column family registry: " + name, e);
      }
      return cf;
    } catch (IOException e) {
      throw new DBException("Failed to create column family: " + name, e);
    } finally {
      mutex.unlock();
    }
  }

  @Override
  public LdbColumnFamily renameColumnFamily(LdbColumnFamily cf, String newName) throws DBException {
    requireNonNull(cf, "cf is null");
    requireNonNull(newName, "newName is null");
    checkWritable("renameColumnFamily");
    checkBackgroundException();
    String trimmed = newName.trim();
    if (trimmed.isEmpty()) {
      throw new DBException("Column family name is empty");
    }
    flushMemTable();

    mutex.lock();
    try {
      ColumnFamilyState oldState = getColumnFamilyState(cf);
      for (ColumnFamilyState state : cfs.values()) {
        if (state.getColumnFamily().getId() != cf.getId() && state.getColumnFamily().getName().equals(trimmed)) {
          throw new DBException("Column family name already exists: " + trimmed);
        }
      }
      LdbColumnFamily renamed = new PersistentColumnFamily(cf.getId(), trimmed);
      ColumnFamilyState newState = new ColumnFamilyState(renamed, databaseDir, options, internalKeyComparator);
      cfs.put(cf.getId(), newState);
      columnFamilyRecords.put(cf.getId(), new ColumnFamilyRegistry.Record(renamed, true));
      try {
        ColumnFamilyRegistry.storeRecords(databaseDir, columnFamilyRecords.values());
        forceDirectory(databaseDir);
      } catch (IOException e) {
        cfs.put(cf.getId(), oldState);
        columnFamilyRecords.put(cf.getId(), new ColumnFamilyRegistry.Record(oldState.getColumnFamily(), true));
        newState.close();
        throw new DBException("Failed to persist column family registry after rename: " + cf.getName(), e);
      }
      oldState.close();
      return renamed;
    } catch (IOException e) {
      throw new DBException("Failed to rename column family: " + cf.getName(), e);
    } finally {
      mutex.unlock();
    }
  }

  @Override
  public void dropColumnFamily(LdbColumnFamily cf) throws DBException {
    requireNonNull(cf, "cf is null");
    checkWritable("dropColumnFamily");
    checkBackgroundException();
    if (cf.getId() == LdbColumnFamily.DEFAULT.getId()) {
      throw new DBException("Default column family cannot be dropped");
    }
    flushMemTable();

    mutex.lock();
    try {
      ColumnFamilyState state = getColumnFamilyState(cf);
      ColumnFamilyRegistry.Record previousRecord = columnFamilyRecords.get(cf.getId());
      if (previousRecord == null) {
        previousRecord = new ColumnFamilyRegistry.Record(state.getColumnFamily(), true);
      }
      columnFamilyRecords.put(cf.getId(), previousRecord.dropped());
      try {
        ColumnFamilyRegistry.storeRecords(databaseDir, columnFamilyRecords.values());
        forceDirectory(databaseDir);
      } catch (IOException e) {
        throw new DBException("Failed to persist column family registry after drop: " + cf.getName(), e);
      }

      VersionEdit edit = new VersionEdit();
      for (int level = 0; level < NUM_LEVELS; level++) {
        for (FileMetaData file : versions.getCurrent().getFiles(cf.getId(), level)) {
          edit.deleteFile(cf.getId(), level, file.getNumber());
        }
      }
      if (!edit.getDeletedFiles().isEmpty()) {
        try {
          versions.logAndApply(edit);
        } catch (IOException e) {
          LOG.warn("Column family {} was tombstoned but SST GC edit failed", cf.getName(), e);
        }
      }

      cfs.remove(cf.getId());
      state.close();
      deleteObsoleteFiles();
    } finally {
      mutex.unlock();
    }
  }

  private boolean isColumnFamilyEmpty(ColumnFamilyState state) {
    if (!state.getMemTable().isEmpty() || state.getImmutableMemTable() != null) {
      return false;
    }
    for (int level = 0; level < NUM_LEVELS; level++) {
      if (versions.numberOfFilesInLevel(state.getColumnFamily().getId(), level) > 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void checkpoint(String targetDir) throws DBException {
    long start = System.nanoTime();
    requireNonNull(targetDir, "targetDir is null");
    checkWritable("checkpoint");

    File target = new File(targetDir).getAbsoluteFile();
    File temp = checkpointTempDir(target);
    boolean suspended = false;
    boolean published = false;
    CheckpointCopyStats copyStats = null;
    notifyBeforeCheckpoint(target);

    try {
      // 1. 先把所有 memtable 刷下去；这里不能先 suspendCompactions，
      //    否则 flushMemTable() 可能永远等不到 immutable memtable 被 compact 完。
      flushMemTable();

      // 2. 再暂停 compaction，冻结文件集合
      suspendCompactions();
      suspended = true;

      // 3. 在锁内收集需要纳入 checkpoint 的文件集合
      final List<File> filesToCopy;
      mutex.lock();
      try {
        checkBackgroundException();

        // checkpoint 目录必须不存在，或是空目录，避免混入旧文件
        prepareCheckpointTarget(target, temp);

        filesToCopy = collectCheckpointFilesLocked();
      } finally {
        mutex.unlock();
      }

      // 4. 实际复制文件
      copyStats = new CheckpointCopyStats(target, temp,
          filesToCopy.size(), options.checkpointCopyRateLimitBytesPerSecond());
      copyStats.status = "copying";
      lastCheckpointSummary = copyStats.summary();
      for (File src : filesToCopy) {
        File dst = new File(temp, src.getName());
        copyForCheckpoint(src, dst, copyStats);
        lastCheckpointSummary = copyStats.summary();
      }

      // 5. 最后再 fsync 一下目录，降低宕机时目录项丢失风险
      forceDirectory(temp);
      copyStats.status = "checking";
      lastCheckpointSummary = copyStats.summary();
      writeCheckpointReport(temp, copyStats);
      forceDirectory(temp);
      long publishStart = System.nanoTime();
      copyStats.status = "publishing";
      lastCheckpointSummary = copyStats.summary();
      publishCheckpoint(target, temp);
      copyStats.publishNanos = System.nanoTime() - publishStart;
      copyStats.status = "published";
      published = true;
      lastCheckpointSummary = copyStats.summary();
      notifyAfterCheckpoint(target);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DBException("Interrupted while suspending compactions for checkpoint", e);
    } catch (IOException e) {
      throw new DBException("Failed to create checkpoint at " + targetDir, e);
    } finally {
      if (!published && temp.exists() && !FileUtils.deleteRecursively(temp)) {
        LOG.warn("Failed to clean incomplete checkpoint temp directory {}", temp);
      }
      if (!published && copyStats != null) {
        copyStats.status = "failed";
        lastCheckpointSummary = copyStats.summary();
      }
      if (suspended) {
        resumeCompactions();
      }
      checkpointStats.record(System.nanoTime() - start, this.options.slowOperationThresholdMicros(), databaseDir);
    }
  }

  private void writeCheckpointReport(File target, CheckpointCopyStats copyStats) throws IOException {
    long checkStart = System.nanoTime();
    LDBFactory.CheckReport report = LDBFactory.factory.check(target, options);
    copyStats.checkNanos = System.nanoTime() - checkStart;
    File reportFile = new File(target, CHECKPOINT_REPORT_FILE);
    try (FileOutputStream output = new FileOutputStream(reportFile)) {
      output.write(appendCheckpointReportFields(report.toJson(), copyStats)
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.flush();
      output.getFD().sync();
    }
    if (!report.isOk()) {
      throw new IOException("Checkpoint verification failed: " + report);
    }
  }

  private List<File> collectCheckpointFilesLocked() throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    List<File> result = new ArrayList<>();

    // 1. CURRENT
    File current = new File(databaseDir, Filename.currentFileName());
    if (current.exists()) {
      result.add(current);
    }

    // 2. 当前 MANIFEST
    File manifest = new File(databaseDir,
        Filename.descriptorFileName(versions.getManifestFileNumber()));
    if (manifest.exists()) {
      result.add(manifest);
    }

    // 3. 所有 live SST/TABLE 文件
    File columnFamilyRegistry = new File(databaseDir, ColumnFamilyRegistry.FILE_NAME);
    if (columnFamilyRegistry.exists()) {
      result.add(columnFamilyRegistry);
    }

    Set<Long> live = new HashSet<>();
    for (FileMetaData meta : versions.getLiveFiles()) {
      live.add(meta.getNumber());
    }

    for (Long fileNumber : live) {
      File table = new File(databaseDir, Filename.tableFileName(fileNumber));
      if (table.exists()) {
        result.add(table);
      }
    }

    // 4. 保守起见，把当前仍被引用的 WAL 也带上
    //    虽然 flushMemTable() 之后通常不再需要旧 WAL，但带上更稳妥。
    for (Long logNumber : getReferencedLogNumbers()) {
      File logFile = new File(databaseDir, Filename.logFileName(logNumber));
      if (logFile.exists()) {
        result.add(logFile);
      }
    }

    // 5. 可选：INFO_LOG 一并带过去，便于排查问题
    for (File f : Filename.listFiles(databaseDir)) {
      FileInfo info = Filename.parseFileName(f);
      if (info != null && info.getFileType() == FileType.INFO_LOG) {
        result.add(f);
      }
    }

    return result;
  }

  private File checkpointTempDir(File target) {
    File parent = target.getParentFile();
    if (parent == null) {
      parent = new File(".").getAbsoluteFile();
    }
    return new File(parent, target.getName() + ".tmp-"
        + System.currentTimeMillis() + "-" + Thread.currentThread().getId());
  }

  private void prepareCheckpointTarget(File target, File temp) throws IOException {
    File parent = target.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Unable to create checkpoint parent directory: " + parent);
    }
    assertMissingOrEmptyDirectory(target);
    if (temp.exists()) {
      throw new IOException("Checkpoint temp directory already exists: " + temp);
    }
    if (!temp.mkdirs()) {
      throw new IOException("Unable to create checkpoint temp directory: " + temp);
    }
  }

  private void assertMissingOrEmptyDirectory(File dir) throws IOException {
    if (dir.exists()) {
      if (!dir.isDirectory()) {
        throw new IOException("Checkpoint target exists but is not a directory: " + dir);
      }
      File[] children = dir.listFiles();
      if (children != null && children.length > 0) {
        throw new IOException("Checkpoint target directory is not empty: " + dir);
      }
    }
  }

  private void publishCheckpoint(File target, File temp) throws IOException {
    if (target.exists() && !target.delete()) {
      throw new IOException("Unable to remove empty checkpoint target before publish: " + target);
    }
    try {
      java.nio.file.Files.move(temp.toPath(), target.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      java.nio.file.Files.move(temp.toPath(), target.toPath());
    }
    File parent = target.getParentFile();
    if (parent != null) {
      forceDirectory(parent);
    }
  }

  private void copyForCheckpoint(File src, File dst, CheckpointCopyStats stats)
      throws IOException, InterruptedException {
    stats.totalBytes += Math.max(0L, src.length());
    // SST/TABLE 尽量硬链接，速度快且接近 RocksDB checkpoint 的效果
    String name = src.getName().toLowerCase(Locale.ROOT);
    boolean maybeTableFile =
        name.endsWith(".sst") || name.endsWith(".ldb") || name.endsWith(".table");

    if (maybeTableFile) {
      try {
        java.nio.file.Files.createLink(dst.toPath(), src.toPath());
        stats.hardLinkedFiles++;
        stats.hardLinkedBytes += Math.max(0L, src.length());
        return;
      } catch (UnsupportedOperationException
               | IOException
               | SecurityException e) {
        // 硬链接失败就回退到普通复制
      }
    }
    if (maybeTableFile) {
      LOG.info("Unable to hard link {} to {}, falling back to copy", src, dst);
    }
    long copyStart = System.nanoTime();
    copyFileForCheckpoint(src, dst, stats.copyRateLimitBytesPerSecond);
    stats.copyNanos += System.nanoTime() - copyStart;
    stats.copiedFiles++;
    stats.copiedBytes += Math.max(0L, src.length());
  }

  private void copyFileForCheckpoint(File src, File dst, long rateLimitBytesPerSecond)
      throws IOException, InterruptedException {
    if (rateLimitBytesPerSecond <= 0) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Interrupted before copying checkpoint file " + src);
      }
      java.nio.file.Files.copy(
          src.toPath(),
          dst.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Interrupted after copying checkpoint file " + src);
      }
      return;
    }
    byte[] buffer = new byte[64 * 1024];
    long copied = 0;
    long start = System.nanoTime();
    try (InputStream input = new BufferedInputStream(new FileInputStream(src));
         OutputStream output = new BufferedOutputStream(new FileOutputStream(dst))) {
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("Interrupted while copying checkpoint file " + src);
        }
        output.write(buffer, 0, read);
        copied += read;
        throttleCheckpointCopy(copied, start, rateLimitBytesPerSecond);
      }
      output.flush();
    }
    dst.setLastModified(src.lastModified());
  }

  private void throttleCheckpointCopy(long copiedBytes, long startNanos, long rateLimitBytesPerSecond)
      throws InterruptedException {
    double expectedNanos = copiedBytes * 1_000_000_000.0D / rateLimitBytesPerSecond;
    long delayNanos = (long) expectedNanos - (System.nanoTime() - startNanos);
    if (delayNanos <= 0) {
      return;
    }
    long millis = TimeUnit.NANOSECONDS.toMillis(delayNanos);
    int nanos = (int) (delayNanos - TimeUnit.MILLISECONDS.toNanos(millis));
    Thread.sleep(millis, nanos);
  }

  private String appendCheckpointReportFields(String reportJson, CheckpointCopyStats stats) {
    String trimmed = reportJson.trim();
    if (!trimmed.endsWith("}")) {
      return reportJson;
    }
    String prefix = trimmed.substring(0, trimmed.length() - 1);
    String separator = prefix.trim().endsWith("{") ? "" : ",";
    return prefix + separator
        + "\n  \"checkpointTargetDir\": \"" + jsonEscape(stats.target.getAbsolutePath()) + "\","
        + "\n  \"checkpointTempDir\": \"" + jsonEscape(stats.temp.getAbsolutePath()) + "\","
        + "\n  \"checkpointTotalFiles\": " + stats.totalFiles + ","
        + "\n  \"checkpointHardLinkedFiles\": " + stats.hardLinkedFiles + ","
        + "\n  \"checkpointCopiedFiles\": " + stats.copiedFiles + ","
        + "\n  \"checkpointTotalBytes\": " + stats.totalBytes + ","
        + "\n  \"checkpointHardLinkedBytes\": " + stats.hardLinkedBytes + ","
        + "\n  \"checkpointCopiedBytes\": " + stats.copiedBytes + ","
        + "\n  \"checkpointCopyMicros\": " + TimeUnit.NANOSECONDS.toMicros(stats.copyNanos) + ","
        + "\n  \"checkpointCheckMicros\": " + TimeUnit.NANOSECONDS.toMicros(stats.checkNanos) + ","
        + "\n  \"checkpointCopyRateLimitBytesPerSecond\": " + stats.copyRateLimitBytesPerSecond
        + "\n}";
  }

  private static String jsonEscape(String value) {
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

  private static final class CheckpointCopyStats {
    private final File target;
    private final File temp;
    private final int totalFiles;
    private final long copyRateLimitBytesPerSecond;
    private int hardLinkedFiles;
    private int copiedFiles;
    private long totalBytes;
    private long hardLinkedBytes;
    private long copiedBytes;
    private long copyNanos;
    private long checkNanos;
    private long publishNanos;
    private String status = "pending";

    private CheckpointCopyStats(File target, File temp, int totalFiles,
                                long copyRateLimitBytesPerSecond) {
      this.target = target;
      this.temp = temp;
      this.totalFiles = totalFiles;
      this.copyRateLimitBytesPerSecond = copyRateLimitBytesPerSecond;
    }

    private String summary() {
      return "status=" + status
          + ",target=" + target.getAbsolutePath()
          + ",files=" + totalFiles
          + ",hardLinkedFiles=" + hardLinkedFiles
          + ",copiedFiles=" + copiedFiles
          + ",totalBytes=" + totalBytes
          + ",hardLinkedBytes=" + hardLinkedBytes
          + ",copiedBytes=" + copiedBytes
          + ",copyMicros=" + TimeUnit.NANOSECONDS.toMicros(copyNanos)
          + ",checkMicros=" + TimeUnit.NANOSECONDS.toMicros(checkNanos)
          + ",publishMicros=" + TimeUnit.NANOSECONDS.toMicros(publishNanos)
          + ",copyRateLimitBytesPerSecond=" + copyRateLimitBytesPerSecond;
    }
  }

  private void forceDirectory(File dir) {
    // 有些平台/文件系统不支持对目录 force，失败就忽略
    try (FileChannel ch = FileChannel.open(
        dir.toPath(),
        java.nio.file.StandardOpenOption.READ)) {
      ch.force(true);
    } catch (Exception e) {
      LOG.warn("Failed to force LDB directory {}", dir, e);
    }
  }

  private InternalKey metadataLargestKey(InternalKey key, Slice value) {
    if (key.getValueType() == DELETE_RANGE) {
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

  private Slice writeWriteBatch(LdbWriteBatchImpl updates, long sequenceBegin) {
    Slice record = Slices.allocate(SIZE_OF_LONG + SIZE_OF_INT + updates.getApproximateSize());
    final SliceOutput sliceOutput = record.output();
    sliceOutput.writeLong(sequenceBegin);
    sliceOutput.writeInt(updates.size());
    updates.forEach(new Handler() {
      @Override
      public void put(LdbColumnFamily cf, Slice key, Slice value) {
        sliceOutput.writeByte(VALUE.getPersistentId());
        sliceOutput.writeInt(cf.getId());
        writeLengthPrefixedBytes(sliceOutput, key);
        writeLengthPrefixedBytes(sliceOutput, value);
      }

      @Override
      public void delete(LdbColumnFamily cf, Slice key) {
        sliceOutput.writeByte(DELETION.getPersistentId());
        sliceOutput.writeInt(cf.getId());
        writeLengthPrefixedBytes(sliceOutput, key);
      }

      @Override
      public void deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
        sliceOutput.writeByte(DELETE_RANGE.getPersistentId());
        sliceOutput.writeInt(cf.getId());
        writeLengthPrefixedBytes(sliceOutput, beginKey);
        writeLengthPrefixedBytes(sliceOutput, endKey);
      }

      @Override
      public void addLong(LdbColumnFamily cf, Slice key, Slice deltaSlice) {
        sliceOutput.writeByte(ADD_LONG.getPersistentId());
        sliceOutput.writeInt(cf.getId());
        writeLengthPrefixedBytes(sliceOutput, key);
        writeLengthPrefixedBytes(sliceOutput, deltaSlice);
      }
    });
    return record.slice(0, sliceOutput.size());
  }

  private static class RecoverIntoHandler implements Handler {
    private final Map<Integer, MemTable> recoveringTables;
    private final Map<BatchKey, Long> localCache = new HashMap<>();
    private final VersionSet versions;
    private long sequence;

    private RecoverIntoHandler(Map<Integer, MemTable> recoveringTables, VersionSet versions, long sequenceBegin) {
      this.recoveringTables = recoveringTables;
      this.versions = versions;
      this.sequence = sequenceBegin;
    }

    @Override
    public void put(LdbColumnFamily cf, Slice key, Slice value) {
      recoveringTables.get(cf.getId()).add(sequence++, ValueType.VALUE, key, value);
    }

    @Override
    public void delete(LdbColumnFamily cf, Slice key) {
      recoveringTables.get(cf.getId()).add(sequence++, ValueType.DELETION, key, Slices.EMPTY_SLICE);
      localCache.remove(new BatchKey(cf.getId(), key));
    }

    @Override
    public void deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
      recoveringTables.get(cf.getId()).add(sequence++, ValueType.DELETE_RANGE, beginKey, endKey);
      localCache.clear();
    }

    @Override
    public void addLong(LdbColumnFamily cf, Slice key, Slice deltaSlice) {
      BatchKey cacheKey = new BatchKey(cf.getId(), key);

      Long current = localCache.get(cacheKey);
      if (current == null) {
        current = getCurrentLongValue(cf, key);
      }

      long delta = Slices.decodeLong(deltaSlice)
          .orElseThrow(() -> new IllegalArgumentException("deltaSlice is not a long"));

      long newValue = current + delta;

      localCache.put(cacheKey, newValue);

      recoveringTables.get(cf.getId()).add(sequence++, VALUE, key, Slices.encodeLong(newValue));
    }

    private long getCurrentLongValue(LdbColumnFamily cf, Slice key) {
      LookupKey lookupKey = new LookupKey(key, MAX_SEQUENCE_NUMBER);

      // 1. recovering memtable / current memtable
      LookupResult lr = recoveringTables.get(cf.getId()).get(lookupKey);
      if (lr != null) {
        return lr.isDeleted() ? 0L : Slices.decodeLong(lr.getValue()).orElse(0L);
      }

      // 2. SST / VersionSet
      LookupResult fromVersion = versions.get(cf.getId(), lookupKey);
      if (fromVersion != null) {
        return fromVersion.isDeleted() ? 0L : Slices.decodeLong(fromVersion.getValue()).orElse(0L);
      }

      return 0L;
    }

  }

  private static class InsertIntoHandler implements Handler {
    private long sequence;
    private final Map<Integer, ColumnFamilyState> memTables;
    private final VersionSet versions;
    private final Map<BatchKey, Long> localCache = new HashMap<>();
    public InsertIntoHandler(Map<Integer, ColumnFamilyState> memTables, long sequenceBegin, VersionSet versions) {
      this.memTables = memTables;
      this.sequence = sequenceBegin;
      this.versions = versions;
    }

    @Override
    public void put(LdbColumnFamily cf, Slice key, Slice value) {
      getCFState(cf).getMemTable().add(sequence++, VALUE, key, value);
    }

    @Override
    public void delete(LdbColumnFamily cf, Slice key) {
      getCFState(cf).getMemTable().add(sequence++, DELETION, key, Slices.EMPTY_SLICE);
      localCache.remove(new BatchKey(cf.getId(), key));
    }

    @Override
    public void deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
      getCFState(cf).getMemTable().add(sequence++, DELETE_RANGE, beginKey, endKey);
      localCache.clear();
    }

    @Override
    public void addLong(LdbColumnFamily cf, Slice key, Slice deltaSlice) {
      BatchKey cacheKey = new BatchKey(cf.getId(), key);

      Long current = localCache.get(cacheKey);
      if (current == null) {
        current = getCurrentLongValue(cf, key);
      }

      long delta = Slices.decodeLong(deltaSlice)
          .orElseThrow(() -> new IllegalArgumentException("deltaSlice is not a long"));

      long newValue = current + delta;

      localCache.put(cacheKey, newValue);

      getCFState(cf).getMemTable().add(sequence++, VALUE, key, Slices.encodeLong(newValue));
    }

    private long getCurrentLongValue(LdbColumnFamily cf, Slice key) {
      ColumnFamilyState cfState = getCFState(cf);
      LookupKey lookupKey = new LookupKey(key, MAX_SEQUENCE_NUMBER);

      LookupResult lr = cfState.getMemTable().get(lookupKey);
      if (lr != null) {
        return lr.isDeleted() ? 0L : Slices.decodeLong(lr.getValue()).orElse(0L);
      }

      MemTable imm = cfState.getImmutableMemTable();
      if (imm != null) {
        lr = imm.get(lookupKey);
        if (lr != null) {
          return lr.isDeleted() ? 0L : Slices.decodeLong(lr.getValue()).orElse(0L);
        }
      }

      LookupResult fromVersion = versions.get(cf.getId(), lookupKey);
      if (fromVersion != null) {
        return fromVersion.isDeleted() ? 0L : Slices.decodeLong(fromVersion.getValue()).orElse(0L);
      }

      return 0L;
    }

    private ColumnFamilyState getCFState(LdbColumnFamily cf) {
      ColumnFamilyState familyState = memTables.get(cf.getId());
      if (familyState == null) {
        throw new IllegalArgumentException("Unknown column family id: " + cf.getId());
      }
      return familyState;
    }
  }

  private static final class BatchKey {
    private final int cfId;
    private final byte[] key;
    private final int hash;

    BatchKey(int cfId, Slice key) {
      this.cfId = cfId;
      this.key = key.getBytes();
      this.hash = 31 * cfId + Arrays.hashCode(this.key);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof BatchKey)) return false;
      BatchKey other = (BatchKey) o;
      return cfId == other.cfId &&
          Arrays.equals(key, other.key);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  public static class DatabaseShutdownException extends DBException {
    public DatabaseShutdownException() {
    }

    public DatabaseShutdownException(String message) {
      super(message);
    }
  }

  public static class BackgroundProcessingException extends DBException {
    public BackgroundProcessingException(Throwable cause) {
      super(cause);
    }
  }

  private final Object suspensionMutex = new Object();
  private int suspensionCounter;

  @Override
  public void suspendCompactions() throws InterruptedException {
    final Future<?> suspensionTask;
    try {
      suspensionTask = compactionExecutor.submit(new Runnable() {
        @Override
        public void run() {
          try {
            synchronized (suspensionMutex) {
              suspensionCounter++;
              suspensionMutex.notifyAll();
              while (suspensionCounter > 0 && !compactionExecutor.isShutdown()) {
                suspensionMutex.wait(500);
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      });
    } catch (RejectedExecutionException e) {
      throw new DBException("Failed to suspend compactions; executor is closed", e);
    }
    long deadline = System.currentTimeMillis() + options.compactionSuspendTimeoutMillis();
    synchronized (suspensionMutex) {
      while (suspensionCounter < 1) {
        long waitMillis = deadline - System.currentTimeMillis();
        if (waitMillis <= 0) {
          suspensionTask.cancel(true);
          throw new DBException("Timed out suspending compactions after "
              + options.compactionSuspendTimeoutMillis() + " ms");
        }
        suspensionMutex.wait(waitMillis);
      }
    }
  }

  @Override
  public void resumeCompactions() {
    synchronized (suspensionMutex) {
      if (suspensionCounter == 0) {
        LOG.warn("resumeCompactions called without a matching suspend for {}", databaseDir);
        return;
      }
      suspensionCounter--;
      suspensionMutex.notifyAll();
    }
  }

  @Override
  public void compactRange(byte[] begin, byte[] end) throws DBException {
    compactRange(LdbColumnFamily.DEFAULT, begin, end);
  }

  @Override
  public void compactRange(LdbColumnFamily cf, byte[] begin, byte[] end) throws DBException {
    long started = System.nanoTime();
    try {
      requireNonNull(cf, "cf is null");
      requireNonNull(begin, "begin is null");
      requireNonNull(end, "end is null");
      checkWritable("compactRange");
      checkBackgroundException();
      getColumnFamilyState(cf);

      Slice start = Slices.wrappedBuffer(begin);
      Slice limit = Slices.wrappedBuffer(end);
      if (internalKeyComparator.getUserComparator().compare(start, limit) >= 0) {
        throw new DBException("compactRange begin must be smaller than end");
      }

      flushMemTable();
      for (int level = 0; level + 1 < NUM_LEVELS; level++) {
        compactRange(cf, level, start, limit);
        checkBackgroundException();
      }
    } finally {
      compactStats.record(System.nanoTime() - started, this.options.slowOperationThresholdMicros(), databaseDir);
    }
  }
}
