package net.xdob.vexra.ldb;

import java.util.List;

/**
 * LDB 配置只读视图，供插件在打开后读取有效配置。
 *
 * 该视图不提供 setter，避免插件通过上下文误改运行时配置；返回的集合也必须是只读快照。
 */
public interface OptionsView {
  boolean createIfMissing();

  boolean errorIfExists();

  int writeBufferSize();

  boolean forceLogOnClose();

  boolean forceSstOnFlush();

  int maxOpenFiles();

  int blockRestartInterval();

  int blockSize();

  CompressionType compressionType();

  boolean verifyChecksums();

  boolean paranoidChecks();

  DBComparator comparator();

  long cacheSize();

  List<LdbColumnFamily> getColumnFamilies();

  FilterPolicy filterPolicy();

  boolean readOnly();

  boolean cacheBlocks();

  int blockCacheSize();

  long compactionSuspendTimeoutMillis();

  long closeTimeoutMillis();

  long slowOperationThresholdMicros();

  boolean verifyOnOpen();

  int level0CompactionTrigger();

  int level0SlowdownWritesTrigger();

  int level0StopWritesTrigger();

  long compactionRateLimitBytesPerSecond();

  long checkpointCopyRateLimitBytesPerSecond();

  long writeSlowdownDelayNanos();

  boolean groupCommitEnabled();

  long groupCommitMaxDelayNanos();

  long groupCommitMaxBatchBytes();
}
