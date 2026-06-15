package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.DbConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Options implements OptionsView {
  private boolean createIfMissing = true;
  private boolean errorIfExists;
  private int writeBufferSize = 64 << 20;
  private boolean forceLogOnClose = false;
  private boolean forceSstOnFlush = false;

  private int maxOpenFiles = 1000;

  private int blockRestartInterval = 16;
  private int blockSize = 4 * 1024;
  private CompressionType compressionType = CompressionType.NONE;
  private boolean verifyChecksums = true;
  private boolean paranoidChecks;
  private DBComparator comparator;
  private long cacheSize;
  private final List<LdbColumnFamily> columnFamilies = new ArrayList<>();
  private FilterPolicy filterPolicy;
  private boolean readOnly;
  private boolean cacheBlocks = true;
  private int blockCacheSize = 4096;
  private final List<LdbPlugin> plugins = new ArrayList<>();
  private long compactionSuspendTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
  private long closeTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
  private long slowOperationThresholdMicros = TimeUnit.SECONDS.toMicros(1);
  private boolean verifyOnOpen;
  private int level0CompactionTrigger = DbConstants.L0_COMPACTION_TRIGGER;
  private int level0SlowdownWritesTrigger = DbConstants.L0_SLOWDOWN_WRITES_TRIGGER;
  private int level0StopWritesTrigger = DbConstants.L0_STOP_WRITES_TRIGGER;
  private long compactionRateLimitBytesPerSecond;
  private long checkpointCopyRateLimitBytesPerSecond;
  private long writeSlowdownDelayNanos = TimeUnit.MILLISECONDS.toNanos(1);
  private boolean groupCommitEnabled;
  private long groupCommitMaxDelayNanos = TimeUnit.MICROSECONDS.toNanos(200);
  private long groupCommitMaxBatchBytes = 1 << 20;
  private boolean pluginCapabilityEnforcement;
  private long pluginCallbackTimeoutMillis;
  private boolean pluginAutoDisableOnTimeout;
  private int pluginAutoDisableFailureThreshold;
  private boolean pluginAsyncEnabled;
  private int pluginAsyncQueueCapacity = 1024;
  private long pluginAsyncCloseTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
  private long pluginMaxTotalCallbackMillis;

  public boolean cacheBlocks() {
    return cacheBlocks;
  }

  public Options cacheBlocks(boolean cacheBlocks) {
    this.cacheBlocks = cacheBlocks;
    return this;
  }

  public int blockCacheSize() {
    return blockCacheSize;
  }

  public Options blockCacheSize(int blockCacheSize) {
    if (blockCacheSize <= 0) {
      throw new IllegalArgumentException("blockCacheSize must be > 0");
    }
    this.blockCacheSize = blockCacheSize;
    return this;
  }


  public boolean readOnly() {
    return readOnly;
  }

  public Options readOnly(boolean readOnly) {
    this.readOnly = readOnly;
    return this;
  }

  public FilterPolicy filterPolicy() {
    return filterPolicy;
  }

  public Options filterPolicy(FilterPolicy filterPolicy) {
    this.filterPolicy = filterPolicy;
    return this;
  }


  public List<LdbColumnFamily> getColumnFamilies() {
    addDefault();
    synchronized (columnFamilies) {
      return Collections.unmodifiableList(new ArrayList<>(columnFamilies));
    }
  }

  public long compactionSuspendTimeoutMillis() {
    return compactionSuspendTimeoutMillis;
  }

  public Options compactionSuspendTimeoutMillis(long compactionSuspendTimeoutMillis) {
    if (compactionSuspendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("compactionSuspendTimeoutMillis must be > 0");
    }
    this.compactionSuspendTimeoutMillis = compactionSuspendTimeoutMillis;
    return this;
  }

  public long closeTimeoutMillis() {
    return closeTimeoutMillis;
  }

  public Options closeTimeoutMillis(long closeTimeoutMillis) {
    if (closeTimeoutMillis <= 0) {
      throw new IllegalArgumentException("closeTimeoutMillis must be > 0");
    }
    this.closeTimeoutMillis = closeTimeoutMillis;
    return this;
  }

  public long slowOperationThresholdMicros() {
    return slowOperationThresholdMicros;
  }

  public Options slowOperationThresholdMicros(long slowOperationThresholdMicros) {
    if (slowOperationThresholdMicros <= 0) {
      throw new IllegalArgumentException("slowOperationThresholdMicros must be > 0");
    }
    this.slowOperationThresholdMicros = slowOperationThresholdMicros;
    return this;
  }

  public boolean verifyOnOpen() {
    return verifyOnOpen;
  }

  public Options verifyOnOpen(boolean verifyOnOpen) {
    this.verifyOnOpen = verifyOnOpen;
    return this;
  }

  public int level0CompactionTrigger() {
    return level0CompactionTrigger;
  }

  public long compactionRateLimitBytesPerSecond() {
    return compactionRateLimitBytesPerSecond;
  }

  public Options compactionRateLimitBytesPerSecond(long compactionRateLimitBytesPerSecond) {
    if (compactionRateLimitBytesPerSecond < 0) {
      throw new IllegalArgumentException("compactionRateLimitBytesPerSecond must be >= 0");
    }
    this.compactionRateLimitBytesPerSecond = compactionRateLimitBytesPerSecond;
    return this;
  }

  public long checkpointCopyRateLimitBytesPerSecond() {
    return checkpointCopyRateLimitBytesPerSecond;
  }

  public Options checkpointCopyRateLimitBytesPerSecond(long checkpointCopyRateLimitBytesPerSecond) {
    if (checkpointCopyRateLimitBytesPerSecond < 0) {
      throw new IllegalArgumentException("checkpointCopyRateLimitBytesPerSecond must be >= 0");
    }
    this.checkpointCopyRateLimitBytesPerSecond = checkpointCopyRateLimitBytesPerSecond;
    return this;
  }

  public Options level0CompactionTrigger(int level0CompactionTrigger) {
    if (level0CompactionTrigger <= 0) {
      throw new IllegalArgumentException("level0CompactionTrigger must be > 0");
    }
    this.level0CompactionTrigger = level0CompactionTrigger;
    validateLevel0Triggers();
    return this;
  }

  public int level0SlowdownWritesTrigger() {
    return level0SlowdownWritesTrigger;
  }

  public Options level0SlowdownWritesTrigger(int level0SlowdownWritesTrigger) {
    if (level0SlowdownWritesTrigger <= 0) {
      throw new IllegalArgumentException("level0SlowdownWritesTrigger must be > 0");
    }
    this.level0SlowdownWritesTrigger = level0SlowdownWritesTrigger;
    validateLevel0Triggers();
    return this;
  }

  public int level0StopWritesTrigger() {
    return level0StopWritesTrigger;
  }

  public long writeSlowdownDelayNanos() {
    return writeSlowdownDelayNanos;
  }

  public Options writeSlowdownDelayNanos(long writeSlowdownDelayNanos) {
    if (writeSlowdownDelayNanos <= 0) {
      throw new IllegalArgumentException("writeSlowdownDelayNanos must be > 0");
    }
    this.writeSlowdownDelayNanos = writeSlowdownDelayNanos;
    return this;
  }

  public boolean groupCommitEnabled() {
    return groupCommitEnabled;
  }

  public Options groupCommitEnabled(boolean groupCommitEnabled) {
    this.groupCommitEnabled = groupCommitEnabled;
    return this;
  }

  public long groupCommitMaxDelayNanos() {
    return groupCommitMaxDelayNanos;
  }

  public Options groupCommitMaxDelayNanos(long groupCommitMaxDelayNanos) {
    if (groupCommitMaxDelayNanos <= 0) {
      throw new IllegalArgumentException("groupCommitMaxDelayNanos must be > 0");
    }
    this.groupCommitMaxDelayNanos = groupCommitMaxDelayNanos;
    return this;
  }

  public long groupCommitMaxBatchBytes() {
    return groupCommitMaxBatchBytes;
  }

  public Options groupCommitMaxBatchBytes(long groupCommitMaxBatchBytes) {
    if (groupCommitMaxBatchBytes <= 0) {
      throw new IllegalArgumentException("groupCommitMaxBatchBytes must be > 0");
    }
    this.groupCommitMaxBatchBytes = groupCommitMaxBatchBytes;
    return this;
  }

  public boolean pluginCapabilityEnforcement() {
    return pluginCapabilityEnforcement;
  }

  public Options pluginCapabilityEnforcement(boolean pluginCapabilityEnforcement) {
    this.pluginCapabilityEnforcement = pluginCapabilityEnforcement;
    return this;
  }

  public long pluginCallbackTimeoutMillis() {
    return pluginCallbackTimeoutMillis;
  }

  public Options pluginCallbackTimeoutMillis(long pluginCallbackTimeoutMillis) {
    if (pluginCallbackTimeoutMillis < 0) {
      throw new IllegalArgumentException("pluginCallbackTimeoutMillis must be >= 0");
    }
    this.pluginCallbackTimeoutMillis = pluginCallbackTimeoutMillis;
    return this;
  }

  public boolean pluginAutoDisableOnTimeout() {
    return pluginAutoDisableOnTimeout;
  }

  public Options pluginAutoDisableOnTimeout(boolean pluginAutoDisableOnTimeout) {
    this.pluginAutoDisableOnTimeout = pluginAutoDisableOnTimeout;
    return this;
  }

  public int pluginAutoDisableFailureThreshold() {
    return pluginAutoDisableFailureThreshold;
  }

  public Options pluginAutoDisableFailureThreshold(int pluginAutoDisableFailureThreshold) {
    if (pluginAutoDisableFailureThreshold < 0) {
      throw new IllegalArgumentException("pluginAutoDisableFailureThreshold must be >= 0");
    }
    this.pluginAutoDisableFailureThreshold = pluginAutoDisableFailureThreshold;
    return this;
  }

  public boolean pluginAsyncEnabled() {
    return pluginAsyncEnabled;
  }

  public Options pluginAsyncEnabled(boolean pluginAsyncEnabled) {
    this.pluginAsyncEnabled = pluginAsyncEnabled;
    return this;
  }

  public int pluginAsyncQueueCapacity() {
    return pluginAsyncQueueCapacity;
  }

  public Options pluginAsyncQueueCapacity(int pluginAsyncQueueCapacity) {
    if (pluginAsyncQueueCapacity <= 0) {
      throw new IllegalArgumentException("pluginAsyncQueueCapacity must be > 0");
    }
    this.pluginAsyncQueueCapacity = pluginAsyncQueueCapacity;
    return this;
  }

  public long pluginAsyncCloseTimeoutMillis() {
    return pluginAsyncCloseTimeoutMillis;
  }

  public Options pluginAsyncCloseTimeoutMillis(long pluginAsyncCloseTimeoutMillis) {
    if (pluginAsyncCloseTimeoutMillis <= 0) {
      throw new IllegalArgumentException("pluginAsyncCloseTimeoutMillis must be > 0");
    }
    this.pluginAsyncCloseTimeoutMillis = pluginAsyncCloseTimeoutMillis;
    return this;
  }

  public long pluginMaxTotalCallbackMillis() {
    return pluginMaxTotalCallbackMillis;
  }

  public Options pluginMaxTotalCallbackMillis(long pluginMaxTotalCallbackMillis) {
    if (pluginMaxTotalCallbackMillis < 0) {
      throw new IllegalArgumentException("pluginMaxTotalCallbackMillis must be >= 0");
    }
    this.pluginMaxTotalCallbackMillis = pluginMaxTotalCallbackMillis;
    return this;
  }

  public Options level0StopWritesTrigger(int level0StopWritesTrigger) {
    if (level0StopWritesTrigger <= 0) {
      throw new IllegalArgumentException("level0StopWritesTrigger must be > 0");
    }
    this.level0StopWritesTrigger = level0StopWritesTrigger;
    validateLevel0Triggers();
    return this;
  }

  private void validateLevel0Triggers() {
    if (level0CompactionTrigger > level0SlowdownWritesTrigger) {
      throw new IllegalArgumentException("level0CompactionTrigger must be <= level0SlowdownWritesTrigger");
    }
    if (level0SlowdownWritesTrigger > level0StopWritesTrigger) {
      throw new IllegalArgumentException("level0SlowdownWritesTrigger must be <= level0StopWritesTrigger");
    }
  }

  /**
   * 返回已注册插件的只读快照。
   */
  public List<LdbPlugin> getPlugins() {
    synchronized (plugins) {
      return Collections.unmodifiableList(new ArrayList<>(plugins));
    }
  }

  /**
   * 注册 LDB 插件。插件会先获得配置机会，用于声明列族等打开前配置。
   */
  public Options addPlugin(LdbPlugin plugin) {
    checkArgNotNull(plugin, "plugin");
    plugin.configure(this);
    synchronized (plugins) {
      plugins.add(plugin);
    }
    return this;
  }

  private void addDefault() {
    synchronized (columnFamilies) {
      if (columnFamilies.stream().noneMatch(cf -> cf.getId() == LdbColumnFamily.DEFAULT.getId())) {
        columnFamilies.add(LdbColumnFamily.DEFAULT);
      }
    }
  }

  public  Options addColumnFamily(LdbColumnFamily columnFamily) {
    checkArgNotNull(columnFamily, "columnFamily");
    synchronized (columnFamilies) {
      if(columnFamilies.stream().anyMatch(cf -> cf.getId() == columnFamily.getId())){
        throw new IllegalArgumentException("Column family with id " + columnFamily.getId() + " already exists");
      }
      columnFamilies.add(columnFamily);
    }
    return this;
  }

  public boolean createIfMissing() {
    return createIfMissing;
  }

  public Options createIfMissing(boolean createIfMissing) {
    this.createIfMissing = createIfMissing;
    return this;
  }

  public boolean errorIfExists() {
    return errorIfExists;
  }

  public Options errorIfExists(boolean errorIfExists) {
    this.errorIfExists = errorIfExists;
    return this;
  }

  public int writeBufferSize() {
    return writeBufferSize;
  }

  public Options writeBufferSize(int writeBufferSize) {
    this.writeBufferSize = writeBufferSize;
    return this;
  }

  public boolean forceLogOnClose() {
    return forceLogOnClose;
  }

  public Options forceLogOnClose(boolean forceLogOnClose) {
    this.forceLogOnClose = forceLogOnClose;
    return this;
  }

  public boolean forceSstOnFlush() {
    return forceSstOnFlush;
  }

  public Options forceSstOnFlush(boolean forceSstOnFlush) {
    this.forceSstOnFlush = forceSstOnFlush;
    return this;
  }

  public int maxOpenFiles() {
    return maxOpenFiles;
  }

  public Options maxOpenFiles(int maxOpenFiles) {
    this.maxOpenFiles = maxOpenFiles;
    return this;
  }

  public int blockRestartInterval() {
    return blockRestartInterval;
  }

  public Options blockRestartInterval(int blockRestartInterval) {
    this.blockRestartInterval = blockRestartInterval;
    return this;
  }

  public int blockSize() {
    return blockSize;
  }

  public Options blockSize(int blockSize) {
    this.blockSize = blockSize;
    return this;
  }

  public CompressionType compressionType() {
    return compressionType;
  }

  public Options compressionType(CompressionType compressionType) {
    checkArgNotNull(compressionType, "compressionType");
    this.compressionType = compressionType;
    return this;
  }

  public boolean verifyChecksums() {
    return verifyChecksums;
  }

  public Options verifyChecksums(boolean verifyChecksums) {
    this.verifyChecksums = verifyChecksums;
    return this;
  }

  public long cacheSize() {
    return cacheSize;
  }

  public Options cacheSize(long cacheSize) {
    this.cacheSize = cacheSize;
    return this;
  }

  public DBComparator comparator() {
    return comparator;
  }

  public Options comparator(DBComparator comparator) {
    this.comparator = comparator;
    return this;
  }


  public boolean paranoidChecks() {
    return paranoidChecks;
  }

  public Options paranoidChecks(boolean paranoidChecks) {
    this.paranoidChecks = paranoidChecks;
    return this;
  }


  static void checkArgNotNull(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException("The " + name + " argument cannot be null");
    }
  }
}
