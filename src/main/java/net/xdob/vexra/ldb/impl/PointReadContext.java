package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.table.UserComparator;

/**
 * 单次 public point-get 请求内的轻量读上下文。
 *
 * <p>该对象只在 `Version.get` 到 `Level/Level0` 的一次调用链中传递，不保存到
 * `LDbImpl` 或 public API 对象上，因此不会把 table/block 生命周期暴露给调用方，也不会引入跨线程共享语义。
 * 当前上下文只缓存最近一次命中的候选 SST 文件，用于连续邻近 key 在同一 level/file 内读取时跳过重复文件定位；
 * 真正的 block 复用仍由 table 层最近 block/index cache 完成。</p>
 */
final class PointReadContext {
  private static final int MAX_REUSE_CALLS = 4096;

  private final UserComparator userComparator;
  private Version version;
  private int cfId = -1;
  private FileMetaData lastFile;
  private int lastLevel = -1;
  private int remainingReuseCalls;

  PointReadContext(UserComparator userComparator) {
    this.userComparator = userComparator;
  }

  /**
   * 进入一次 public get 调用前刷新上下文边界。
   *
   * <p>同一线程内只有 current Version、列族和短时间窗口都一致时才保留最近文件；
   * 任一条件变化都清空缓存，避免跨版本 compaction、跨列族或长时间闲置后的误复用。</p>
   */
  void prepare(Version currentVersion, int currentCfId) {
    if (version != currentVersion
        || cfId != currentCfId
        || remainingReuseCalls <= 0) {
      reset(currentVersion, currentCfId);
    }
    remainingReuseCalls--;
  }

  /**
   * 如果最近一次命中的 SST 仍覆盖当前 user key，则直接返回该文件。
   */
  FileMetaData findCoveredFile(int level, LookupKey key) {
    if (lastFile == null || lastLevel != level) {
      return null;
    }
    if (covers(lastFile, key)) {
      return lastFile;
    }
    return null;
  }

  /**
   * 记录当前点查实际命中的候选 SST，用于同一请求后续 level 内复用。
   */
  void remember(int level, FileMetaData fileMetaData) {
    lastLevel = level;
    lastFile = fileMetaData;
  }

  boolean covers(FileMetaData fileMetaData, LookupKey key) {
    return userComparator.compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) >= 0
        && userComparator.compare(key.getUserKey(), fileMetaData.getLargest().getUserKey()) <= 0;
  }

  private void reset(Version currentVersion, int currentCfId) {
    version = currentVersion;
    cfId = currentCfId;
    lastFile = null;
    lastLevel = -1;
    remainingReuseCalls = MAX_REUSE_CALLS;
  }
}
