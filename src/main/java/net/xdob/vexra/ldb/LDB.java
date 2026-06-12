package net.xdob.vexra.ldb;

import java.io.Closeable;
import java.util.List;


public interface LDB
    extends  Closeable {
  byte[] get(byte[] key)
      throws DBException;

  byte[] get(byte[] key, ReadOptions options)
      throws DBException;
  byte[] get(LdbColumnFamily cf, byte[] key);
  byte[] get(LdbColumnFamily cf, byte[] key, ReadOptions options);

  SnapshotCursor newSnapshotCursor();

  SnapshotCursor newSnapshotCursor(LdbColumnFamily cf);

  int numberOfFilesInLevel(int level);
  int numberOfFilesInLevel(LdbColumnFamily cf, int level);

  void put(byte[] key, byte[] value)
      throws DBException;

  void delete(byte[] key)
      throws DBException;

  long addLong(byte[] key, long delta)
      throws DBException;

  void put(LdbColumnFamily cf, byte[] key, byte[] value)
      throws DBException;

  void delete(LdbColumnFamily cf, byte[] key)
      throws DBException;

  long addLong(LdbColumnFamily cf, byte[] key, long delta)
      throws DBException;


  void write(LdbWriteBatch updates)
      throws DBException;

  LdbWriteBatch createWriteBatch();

  /**
   * @return null if options.isSnapshot()==false otherwise returns a snapshot
   * of the DB after this operation.
   */
  Snapshot put(byte[] key, byte[] value, WriteOptions options)
      throws DBException;

  /**
   * @return null if options.isSnapshot()==false otherwise returns a snapshot
   * of the DB after this operation.
   */
  Snapshot delete(byte[] key, WriteOptions options)
      throws DBException;

  Snapshot put(LdbColumnFamily cf, byte[] key, byte[] value, WriteOptions options)
      throws DBException;

  Snapshot delete(LdbColumnFamily cf, byte[] key, WriteOptions options)
      throws DBException;

  Snapshot addLong(LdbColumnFamily cf, byte[] key, long delta, WriteOptions options)
      throws DBException;

  /**
   * @return null if options.isSnapshot()==false otherwise returns a snapshot
   * of the DB after this operation.
   */
  Snapshot write(LdbWriteBatch updates, WriteOptions options)
      throws DBException;



  Snapshot getSnapshot();
  Snapshot getSnapshot(LdbColumnFamily cf);

  long[] getApproximateSizes(Range... ranges);

  String getProperty(String name);

  /**
   * Suspends any background compaction threads.  This methods
   * returns once the background compactions are suspended.
   */
  void suspendCompactions()
      throws InterruptedException;

  /**
   * Resumes the background compaction threads.
   */
  void resumeCompactions();

  /**
   * Force a compaction of the specified key range.
   *
   * @param begin if null then compaction start from the first key
   * @param end   if null then compaction ends at the last key
   */
  void compactRange(byte[] begin, byte[] end)
      throws DBException;

  /**
   * 强制压缩指定列族的 key 范围。
   *
   * @param cf 目标列族
   * @param begin 压缩起始 key，不能为 null
   * @param end 压缩结束 key，不能为 null
   * @throws DBException 当列族未知、范围非法、后台压缩失败或数据库只读时抛出
   */
  void compactRange(LdbColumnFamily cf, byte[] begin, byte[] end)
      throws DBException;

  LdbColumnFamily getColumnFamily(int cfId);

  /**
   * 返回当前数据库已注册的列族快照，包含静态 Options 声明和运行时创建的列族。
   *
   * @return 按列族 id 升序排列的不可变快照
   */
  List<LdbColumnFamily> listColumnFamilies();

  /**
   * 运行时创建一个新的列族，并把列族 id/name 持久化到本地注册表。
   *
   * @param cfId 新列族 id，必须大于 0 且不能与已有列族冲突
   * @param name 新列族名称，不能为空
   * @return 已创建的列族定义
   * @throws DBException 数据库只读、列族冲突或注册表落盘失败时抛出
   */
  LdbColumnFamily createColumnFamily(int cfId, String name) throws DBException;

  /**
   * 运行时重命名一个已有列族，列族 id 保持不变。
   *
   * @param cf 目标列族
   * @param newName 新列族名称，不能与其他活动列族冲突
   * @return 重命名后的列族定义
   * @throws DBException 数据库只读、列族未知、名称冲突或注册表落盘失败时抛出
   */
  LdbColumnFamily renameColumnFamily(LdbColumnFamily cf, String newName) throws DBException;

  /**
   * 运行时删除一个空列族。
   *
   * @param cf 目标列族，默认列族和仍包含 MemTable/SST 数据的列族会被拒绝
   * @throws DBException 数据库只读、目标列族非空或注册表落盘失败时抛出
   */
  void dropColumnFamily(LdbColumnFamily cf) throws DBException;

  void checkpoint(String targetDir) throws DBException;

}
