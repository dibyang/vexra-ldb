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

  default boolean blockCacheWarmOnOpen() {
    return false;
  }

  /**
   * 返回新写 SST/table 文件的格式版本。
   *
   * <p>默认方法用于保持第三方 OptionsView 实现的二进制/源码兼容性。默认值为
   * 1，表示未显式 opt-in 时仍按 legacy v1 table 格式写入。
   */
  default int tableFormatVersion() {
    return 1;
  }

  /**
   * 返回 v2 table 是否写入 properties block。
   *
   * <p>该值只有在 tableFormatVersion 为 2 时才影响落盘；默认 true 表示显式启用
   * v2 时会写入自描述 properties。
   */
  default boolean writeTableProperties() {
    return true;
  }

  /**
   * 返回是否允许读取缺少 properties block 的 legacy v1 SST。
   *
   * <p>默认 true，保障新版本插件观察到的策略与旧库兼容边界一致。
   */
  default boolean allowLegacyTableFormat() {
    return true;
  }

  /**
   * 返回遇到未知 table feature 或未来 table format version 时是否 fail-fast。
   *
   * <p>默认 true，避免插件误以为未知格式会被生产读路径静默接受。关闭该策略只应
   * 用于诊断性读取（diagnostic-only），不应作为生产回滚方案。
   */
  default boolean failOnUnknownTableFeature() {
    return true;
  }

  /**
   * 返回新写 v3 SST 时是否启用 block-local index。
   */
  default boolean writeBlockLocalIndex() {
    return false;
  }

  /**
   * 返回 block-local index 锚点间隔。
   */
  default int blockLocalIndexInterval() {
    return 4;
  }

  /**
   * 返回新写 v4 SST 时是否启用 sparse entry-anchor index。
   */
  default boolean writeEntryAnchorIndex() {
    return false;
  }

  /**
   * 返回 entry-anchor index 的 entry 间隔。
   */
  default int entryAnchorIndexInterval() {
    return 4;
  }

  /**
   * 返回单个 data block 写入 entry-anchor index 的最少 anchor 数。
   */
  default int entryAnchorIndexAdmissionMinAnchors() {
    return 2;
  }

  /**
   * 返回新写 v4 SST 时是否启用 data block inline seek index。
   */
  default boolean writeInlineBlockSeekIndex() {
    return false;
  }

  /**
   * 返回 inline seek anchor 的 entry 间隔。
   */
  default int inlineBlockSeekIndexInterval() {
    return 4;
  }

  /**
   * 返回单个 data block 写入 inline seek index 的最少 anchor 数。
   */
  default int inlineBlockSeekIndexAdmissionMinAnchors() {
    return 2;
  }

  int blockCacheSize();

  default int blockCacheAdmissionMinReads() {
    return 1;
  }

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
