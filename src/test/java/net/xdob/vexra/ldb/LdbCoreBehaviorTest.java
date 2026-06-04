package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.util.Slices;
import net.xdob.vexra.ldb.util.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LDB 基础行为回归测试。
 *
 * 这些用例覆盖核心读写、列族隔离、重启恢复、checkpoint 和插件生命周期，
 * 位置上属于 LDB 对外 API 的轻量级回归网。测试只使用本地临时目录，
 * 不涉及远程调用、事务、网络或 Raft。
 */
class LdbCoreBehaviorTest {
  private static final LdbColumnFamily META_CF = new TestColumnFamily(7, "meta");
  private static final LdbColumnFamily PLUGIN_CF = new TestColumnFamily(8, "plugin");

  @TempDir
  File tempDir;

  /**
   * 验证默认列族和自定义列族的数据互不污染，并且 WAL 重放后仍能读到最新值。
   */
  @Test
  void shouldPersistDefaultAndCustomColumnFamiliesAcrossReopen() throws Exception {
    File dbDir = new File(tempDir, "reopen-db");
    Options createOptions = new Options()
        .createIfMissing(true)
        .addColumnFamily(META_CF);

    try (LDB db = LDBFactory.factory.open(dbDir, createOptions)) {
      db.put(bytes("user:1"), bytes("default-value"));
      db.put(META_CF, bytes("user:1"), bytes("meta-value"));
      assertEquals(3L, db.addLong(META_CF, bytes("counter"), 3L));
      assertEquals(8L, db.addLong(META_CF, bytes("counter"), 5L));
    }

    Options reopenOptions = new Options()
        .createIfMissing(false)
        .addColumnFamily(META_CF);
    try (LDB db = LDBFactory.factory.open(dbDir, reopenOptions)) {
      assertArrayEquals(bytes("default-value"), db.get(bytes("user:1")));
      assertArrayEquals(bytes("meta-value"), db.get(META_CF, bytes("user:1")));
      assertArrayEquals(bytes("default-value"), db.get(LdbColumnFamily.DEFAULT, bytes("user:1")));
      assertEquals(8L, Utils.decodeLong(db.get(META_CF, bytes("counter"))).get().longValue());
      assertNull(db.get(bytes("counter")));
    }
  }

  /**
   * 验证 snapshot 读隔离：写入后的默认读取能看到新值，snapshot 读取仍保持旧视图。
   */
  @Test
  void shouldReadStableValueFromSnapshotAfterOverwrite() throws Exception {
    File dbDir = new File(tempDir, "snapshot-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v1"));
      Snapshot snapshot = db.getSnapshot();
      try {
        db.put(bytes("k"), bytes("v2"));

        assertArrayEquals(bytes("v2"), db.get(bytes("k")));
        assertArrayEquals(bytes("v1"), db.get(bytes("k"), new ReadOptions().snapshot(snapshot)));
      } finally {
        snapshot.close();
      }
    }
  }

  /**
   * 验证 checkpoint 目录可被重新打开，且包含创建 checkpoint 前已经写入的数据。
   */
  @Test
  void shouldOpenCheckpointWithExistingData() throws Exception {
    File dbDir = new File(tempDir, "source-db");
    File checkpointDir = new File(tempDir, "checkpoint-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("before"), bytes("checkpoint"));
      db.checkpoint(checkpointDir.getAbsolutePath());
      db.put(bytes("after"), bytes("source-only"));
    }

    try (LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("checkpoint"), checkpoint.get(bytes("before")));
      assertNull(checkpoint.get(bytes("after")));
    }
  }

  /**
   * 验证插件能声明列族、观察写入和 checkpoint，并在关闭时释放自身资源。
   */
  @Test
  void shouldInvokePluginLifecycleHooks() throws Exception {
    File dbDir = new File(tempDir, "plugin-db");
    File checkpointDir = new File(tempDir, "plugin-checkpoint");
    RecordingPlugin plugin = new RecordingPlugin(false);
    Options options = new Options()
        .createIfMissing(true)
        .addPlugin(plugin);

    Snapshot writeSnapshot;
    try (LDB db = LDBFactory.factory.open(dbDir, options)) {
      LdbWriteBatch batch = db.createWriteBatch()
          .put(PLUGIN_CF, bytes("plugin-key"), bytes("plugin-value"));
      writeSnapshot = db.write(batch, new WriteOptions().snapshot(true));
      db.checkpoint(checkpointDir.getAbsolutePath());

      assertSame(writeSnapshot, plugin.afterWriteSnapshot);
      assertArrayEquals(bytes("plugin-value"), db.get(PLUGIN_CF, bytes("plugin-key")));
      writeSnapshot.close();
    }

    assertTrue(plugin.events.containsAll(Arrays.asList(
        "configure",
        "open",
        "beforeWrite",
        "afterWrite",
        "beforeCheckpoint",
        "afterCheckpoint",
        "beforeClose",
        "close")));
    assertEquals(dbDir.getCanonicalFile(), plugin.databaseDir.getCanonicalFile());
    assertNotNull(plugin.context.getColumnFamily(PLUGIN_CF.getId()));
    assertTrue(plugin.beforeWriteSawPluginCf);
  }

  /**
   * 验证插件在 beforeWrite 拒绝写入时，LDB 不会落入部分写入状态。
   */
  @Test
  void shouldAbortWriteWhenPluginRejectsBeforeWrite() throws Exception {
    File dbDir = new File(tempDir, "reject-db");
    RecordingPlugin plugin = new RecordingPlugin(true);
    Options options = new Options()
        .createIfMissing(true)
        .addPlugin(plugin);

    try (LDB db = LDBFactory.factory.open(dbDir, options)) {
      DBException error = assertThrows(DBException.class,
          () -> db.put(PLUGIN_CF, bytes("blocked"), bytes("value")));

      assertTrue(error.getMessage().contains("plugin rejected write"));
      assertNull(db.get(PLUGIN_CF, bytes("blocked")));
      assertFalse(plugin.events.contains("afterWrite"));
    }
  }

  /**
   * 验证同一个 write batch 横跨多个列族时，重启后仍完整保留所有变更。
   */
  @Test
  void shouldReplayMultiColumnFamilyBatchAfterReopen() throws Exception {
    File dbDir = new File(tempDir, "batch-replay-db");
    Options createOptions = new Options()
        .createIfMissing(true)
        .addColumnFamily(META_CF);

    try (LDB db = LDBFactory.factory.open(dbDir, createOptions)) {
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.put(bytes("keep"), bytes("default-value"));
        batch.put(META_CF, bytes("meta"), bytes("meta-value"));
        batch.delete(bytes("deleted"));
        batch.addLong(META_CF, bytes("counter"), 10L);
        db.write(batch, new WriteOptions().sync(true));
      }
    }

    Options reopenOptions = new Options()
        .createIfMissing(false)
        .addColumnFamily(META_CF);
    try (LDB db = LDBFactory.factory.open(dbDir, reopenOptions)) {
      assertArrayEquals(bytes("default-value"), db.get(bytes("keep")));
      assertArrayEquals(bytes("meta-value"), db.get(META_CF, bytes("meta")));
      assertNull(db.get(bytes("deleted")));
      assertEquals(10L, Utils.decodeLong(db.get(META_CF, bytes("counter"))).get().longValue());
    }
  }

  /**
   * 验证 checkpoint 目标目录非空时会失败，并且源库仍可继续写入和读取。
   */
  @Test
  void shouldKeepSourceUsableWhenCheckpointTargetIsNotEmpty() throws Exception {
    File dbDir = new File(tempDir, "checkpoint-source-db");
    File checkpointDir = new File(tempDir, "not-empty-checkpoint");
    assertTrue(checkpointDir.mkdirs());
    assertTrue(new File(checkpointDir, "marker").createNewFile());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("before"), bytes("value"));

      DBException error = assertThrows(DBException.class,
          () -> db.checkpoint(checkpointDir.getAbsolutePath()));

      assertTrue(error.getMessage().contains("Failed to create checkpoint"));
      db.put(bytes("after"), bytes("still-writable"));
      assertArrayEquals(bytes("value"), db.get(bytes("before")));
      assertArrayEquals(bytes("still-writable"), db.get(bytes("after")));
    }
  }

  @Test
  void shouldApplyDeleteRangeInWriteBatch() throws Exception {
    File dbDir = new File(tempDir, "delete-range-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.put(bytes("keep"), bytes("value"));
        batch.deleteRange(bytes("a"), bytes("z"));
        db.write(batch);
        assertNull(db.get(bytes("keep")));
      }
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertNull(db.get(bytes("keep")));
    }
  }

  /**
   * 验证 readOnly 配置在真实只读打开实现前会显式失败，避免调用方误以为已经只读保护。
   */
  /**
   * 验证非法 addLong delta 会在写 WAL 前失败，避免 batch 内其它写入被部分持久化。
   */
  @Test
  void shouldRejectInvalidAddLongDeltaBeforePersistingBatch() throws Exception {
    File dbDir = new File(tempDir, "invalid-add-long-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true).addColumnFamily(META_CF))) {
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.put(bytes("keep"), bytes("value"));
        batch.addLong(META_CF, Slices.wrappedBuffer(bytes("counter")), Slices.wrappedBuffer(bytes("bad")));

        DBException error = assertThrows(DBException.class, () -> db.write(batch));

        assertTrue(error.getMessage().contains("addLong delta must be an 8-byte long"));
        assertNull(db.get(bytes("keep")));
        assertEquals("0", db.getProperty("ldb.lastSequence"));
      }
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false).addColumnFamily(META_CF))) {
      assertNull(db.get(bytes("keep")));
      assertNull(db.get(META_CF, bytes("counter")));
    }
  }

  @Test
  void shouldApplyPluginMutatedDeleteRangeBatch() throws Exception {
    File dbDir = new File(tempDir, "plugin-mutated-batch-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).addPlugin(new MutatingBeforeWritePlugin()))) {
      db.put(bytes("keep"), bytes("value"));
      assertNull(db.get(bytes("keep")));
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertNull(db.get(bytes("keep")));
    }
  }

  /**
   * 验证 write API 对未知 batch 实现和空 options 给出明确错误，而不是暴露隐式类型转换失败。
   */
  @Test
  void shouldRejectUnsupportedWriteBatchAndNullOptions() throws Exception {
    File dbDir = new File(tempDir, "write-api-boundary-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      DBException error = assertThrows(DBException.class, () -> db.write(new UnsupportedWriteBatch()));

      assertTrue(error.getMessage().contains("Unsupported LdbWriteBatch implementation"));
      assertThrows(NullPointerException.class, () -> db.write(db.createWriteBatch(), null));
    }
  }

  @Test
  void shouldOpenReadOnlyWithoutCreatingFilesAndRejectWrites() throws Exception {
    File dbDir = new File(tempDir, "readonly-db");
    String[] beforeFiles;

    try (LDB writer = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      writer.put(bytes("k"), bytes("v1"), new WriteOptions().sync(true));
      beforeFiles = sortedFileNames(dbDir);

      try (LDB readOnly = LDBFactory.factory.open(dbDir,
          new Options().createIfMissing(false).readOnly(true))) {
        assertArrayEquals(bytes("v1"), readOnly.get(bytes("k")));
        assertEquals("true", readOnly.getProperty("ldb.readOnly"));
        assertEquals("true", readOnly.getProperty("ldb.readOnlyCompactionSkipped"));
        assertFalse(readOnly.getProperty("ldb.referencedLogNumbers").isEmpty());

        DBException writeError = assertThrows(DBException.class,
            () -> readOnly.put(bytes("blocked"), bytes("value")));
        assertTrue(writeError.getMessage().contains("read-only"));
        assertThrows(DBException.class, () -> readOnly.compactRange(bytes("a"), bytes("z")));
        assertThrows(DBException.class,
            () -> readOnly.checkpoint(new File(tempDir, "readonly-checkpoint").getAbsolutePath()));
      }
    }

    assertArrayEquals(beforeFiles, sortedFileNames(dbDir));
  }

  @Test
  void shouldFailReadOnlyOpenWhenManifestIsMissing() throws Exception {
    File dbDir = new File(tempDir, "readonly-missing-current");
    assertThrows(Exception.class,
        () -> LDBFactory.factory.open(dbDir, new Options().createIfMissing(false).readOnly(true)));
  }

  /**
   * 验证 Options 返回列族只读快照，调用方不能绕过 addColumnFamily 修改内部状态。
   */
  @Test
  void shouldExposeImmutableColumnFamilySnapshot() {
    Options options = new Options().addColumnFamily(META_CF);
    List<LdbColumnFamily> columnFamilies = options.getColumnFamilies();

    assertTrue(columnFamilies.contains(META_CF));
    assertThrows(UnsupportedOperationException.class, () -> columnFamilies.add(PLUGIN_CF));
    assertFalse(options.getColumnFamilies().contains(PLUGIN_CF));
  }

  /**
   * 验证 write batch 暴露的列族集合是只读快照，插件或调用方不能篡改内部 touched 集合。
   */
  @Test
  void shouldExposeImmutableWriteBatchColumnFamilySnapshot() {
    LdbWriteBatch batch = new net.xdob.vexra.ldb.impl.LdbWriteBatchImpl()
        .put(META_CF, bytes("k"), bytes("v"));
    Set<LdbColumnFamily> columnFamilies = batch.getColumnFamilies();

    assertTrue(columnFamilies.contains(META_CF));
    assertThrows(UnsupportedOperationException.class, columnFamilies::clear);
    assertTrue(batch.getColumnFamilies().contains(META_CF));
  }

  /**
   * 验证可靠性相关超时配置会拒绝非法值，避免调用方误配置成无限等待或立即超时。
   */
  @Test
  void shouldRejectInvalidReliabilityTimeouts() {
    Options options = new Options();

    assertThrows(IllegalArgumentException.class, () -> options.closeTimeoutMillis(0));
    assertThrows(IllegalArgumentException.class, () -> options.compactionSuspendTimeoutMillis(0));
    assertThrows(IllegalArgumentException.class, () -> options.slowOperationThresholdMicros(0));
    assertThrows(IllegalArgumentException.class, () -> options.compactionRateLimitBytesPerSecond(-1));
    assertThrows(IllegalArgumentException.class, () -> options.level0CompactionTrigger(0));
    assertThrows(IllegalArgumentException.class, () -> options.level0SlowdownWritesTrigger(0));
    assertThrows(IllegalArgumentException.class, () -> options.level0StopWritesTrigger(0));
    assertThrows(IllegalArgumentException.class, () -> options.writeSlowdownDelayNanos(0));
    assertThrows(IllegalArgumentException.class, () -> options.groupCommitMaxDelayNanos(0));
    assertThrows(IllegalArgumentException.class, () -> options.groupCommitMaxBatchBytes(0));
    assertThrows(IllegalArgumentException.class, () -> new Options()
        .level0SlowdownWritesTrigger(3)
        .level0CompactionTrigger(4));
    assertThrows(IllegalArgumentException.class, () -> new Options()
        .level0StopWritesTrigger(7)
        .level0SlowdownWritesTrigger(8));
    assertEquals(100L, options.closeTimeoutMillis(100).closeTimeoutMillis());
    assertEquals(200L, options.compactionSuspendTimeoutMillis(200).compactionSuspendTimeoutMillis());
    assertEquals(300L, options.slowOperationThresholdMicros(300).slowOperationThresholdMicros());
    assertEquals(400L, options.compactionRateLimitBytesPerSecond(400).compactionRateLimitBytesPerSecond());
    assertEquals(3, options.level0CompactionTrigger(3).level0CompactionTrigger());
    assertEquals(6, options.level0SlowdownWritesTrigger(6).level0SlowdownWritesTrigger());
    assertEquals(9, options.level0StopWritesTrigger(9).level0StopWritesTrigger());
    assertEquals(500_000L, options.writeSlowdownDelayNanos(500_000L).writeSlowdownDelayNanos());
    assertTrue(options.groupCommitEnabled(true).groupCommitEnabled());
    assertEquals(700_000L, options.groupCommitMaxDelayNanos(700_000L).groupCommitMaxDelayNanos());
    assertEquals(8192L, options.groupCommitMaxBatchBytes(8192L).groupCommitMaxBatchBytes());
  }

  /**
   * 验证重复 resume 不会让暂停计数变负，后续 suspend/resume 仍能正常完成。
   */
  @Test
  void shouldKeepCompactionSuspensionCounterNonNegative() throws Exception {
    File dbDir = new File(tempDir, "compaction-suspend-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).compactionSuspendTimeoutMillis(1_000))) {
      db.resumeCompactions();
      db.suspendCompactions();
      db.resumeCompactions();
    }
  }

  /**
   * 验证 getProperty 提供基础诊断信息，便于排查 sequence、列族和后台异常状态。
   */
  @Test
  void shouldExposeBasicDiagnosticProperties() throws Exception {
    File dbDir = new File(tempDir, "property-db");
    Options options = new Options()
        .createIfMissing(true)
        .addColumnFamily(META_CF);

    try (LDB db = LDBFactory.factory.open(dbDir, options)) {
      db.put(bytes("k"), bytes("v"));

      assertEquals(dbDir.getAbsolutePath(), db.getProperty("ldb.databaseDir"));
      assertEquals("1", db.getProperty("ldb.lastSequence"));
      assertEquals("", db.getProperty("ldb.backgroundException"));
      assertFalse(db.getProperty("ldb.currentLogNumber").isEmpty());
      assertFalse(db.getProperty("ldb.manifestFileNumber").isEmpty());
      assertTrue(Long.parseLong(db.getProperty("ldb.memTableBytes")) > 0);
      assertTrue(db.getProperty("ldb.columnFamilyMemTableBytes").contains("1:default="));
      assertTrue(db.getProperty("ldb.columnFamilyMemTableBytes").contains("7:meta="));
      assertTrue(db.getProperty("ldb.levelFiles").contains("1:0="));
      assertTrue(db.getProperty("ldb.levelFiles").contains("7:0="));
      assertTrue(db.getProperty("ldb.fileCounts").contains("log="));
      assertTrue(db.getProperty("ldb.fileBytes").contains("log="));
      assertTrue(Long.parseLong(db.getProperty("ldb.totalBytes")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.walBytes")) > 0);
      assertNotNull(db.getProperty("ldb.sstBytes"));
      assertNotNull(db.getProperty("ldb.compactionBacklog"));
      assertNotNull(db.getProperty("ldb.compactionScore"));
      assertNotNull(db.getProperty("ldb.compactionLevel"));
      assertTrue(Long.parseLong(db.getProperty("ldb.columnFamily.1.memTableBytes")) > 0);
      assertNotNull(db.getProperty("ldb.columnFamily.7.memTableBytes"));
      assertTrue(db.getProperty("ldb.columnFamily.1.levelFiles").contains("0="));
      assertTrue(db.getProperty("ldb.columnFamily.7.levelFiles").contains("0="));
      assertNull(db.getProperty("ldb.columnFamily.999.levelFiles"));
      assertTrue(db.getProperty("ldb.columnFamilies").contains("1:default"));
      assertTrue(db.getProperty("ldb.columnFamilies").contains("7:meta"));
      assertNull(db.getProperty("unknown"));
    }
  }

  /**
   * 将字符串转成 UTF-8 字节，避免测试里重复编码细节。
   */
  /**
   * 验证 public compactRange 复用已有手工压缩流程，压缩后数据仍可读取，诊断属性仍可观测 level 文件。
   */
  @Test
  void shouldCompactDefaultColumnFamilyRangeWithoutLosingData() throws Exception {
    File dbDir = new File(tempDir, "manual-compact-db");
    Options options = new Options()
        .createIfMissing(true)
        .writeBufferSize(128);

    try (LDB db = LDBFactory.factory.open(dbDir, options)) {
      for (int i = 0; i < 30; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }

      db.compactRange(bytes("k000"), bytes("k999"));

      assertArrayEquals(bytes("value-0"), db.get(bytes("k000")));
      assertArrayEquals(bytes("value-29"), db.get(bytes("k029")));
      assertTrue(db.getProperty("ldb.levelFiles").contains("1:0="));
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), db.get(bytes("k000")));
      assertArrayEquals(bytes("value-29"), db.get(bytes("k029")));
    }
  }

  /**
   * 验证列族级 compactRange 只压缩目标列族，不会要求其它列族一起 flush/compact。
   */
  @Test
  void shouldCompactSpecificColumnFamilyRangeWithoutLosingData() throws Exception {
    File dbDir = new File(tempDir, "manual-compact-cf-db");
    Options options = new Options()
        .createIfMissing(true)
        .writeBufferSize(128)
        .addColumnFamily(META_CF);

    try (LDB db = LDBFactory.factory.open(dbDir, options)) {
      for (int i = 0; i < 35; i++) {
        db.put(META_CF, bytes(String.format("m%03d", i)), bytes("meta-" + i));
      }
      db.put(bytes("default-live"), bytes("still-in-default"));

      db.compactRange(META_CF, bytes("m000"), bytes("m999"));

      assertArrayEquals(bytes("meta-0"), db.get(META_CF, bytes("m000")));
      assertArrayEquals(bytes("meta-34"), db.get(META_CF, bytes("m034")));
      assertArrayEquals(bytes("still-in-default"), db.get(bytes("default-live")));
      assertTrue(db.getProperty("ldb.columnFamily.7.levelFiles").contains("0="));
      assertNotNull(db.getProperty("ldb.columnFamily.1.memTableBytes"));
    }

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).addColumnFamily(META_CF))) {
      assertArrayEquals(bytes("meta-0"), db.get(META_CF, bytes("m000")));
      assertArrayEquals(bytes("still-in-default"), db.get(bytes("default-live")));
    }
  }

  /**
   * 验证列族级 compactRange 对未知列族和非法边界给出明确错误。
   */
  @Test
  void shouldRejectInvalidColumnFamilyCompactRange() throws Exception {
    File dbDir = new File(tempDir, "manual-compact-cf-invalid-db");
    LdbColumnFamily missing = new TestColumnFamily(77, "missing");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      assertThrows(IllegalArgumentException.class,
          () -> db.compactRange(missing, bytes("a"), bytes("z")));
      assertThrows(NullPointerException.class,
          () -> db.compactRange(null, bytes("a"), bytes("z")));
      DBException error = assertThrows(DBException.class,
          () -> db.compactRange(LdbColumnFamily.DEFAULT, bytes("z"), bytes("a")));
      assertTrue(error.getMessage().contains("begin must be smaller than end"));
    }
  }

  /**
   * 验证 compactRange 对非法边界显式失败，避免调用方误以为空区间已经执行压缩。
   */
  @Test
  void shouldRejectInvalidCompactRangeBounds() throws Exception {
    File dbDir = new File(tempDir, "manual-compact-invalid-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      DBException error = assertThrows(DBException.class,
          () -> db.compactRange(bytes("z"), bytes("a")));

      assertTrue(error.getMessage().contains("begin must be smaller than end"));
      assertThrows(NullPointerException.class, () -> db.compactRange(null, bytes("z")));
      assertThrows(NullPointerException.class, () -> db.compactRange(bytes("a"), null));
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static String[] sortedFileNames(File dir) {
    String[] names = dir.list();
    assertNotNull(names);
    Arrays.sort(names);
    return names;
  }

  /**
   * 测试专用列族定义，固定 id 便于验证 reopen 和插件配置。
   */
  private static class TestColumnFamily implements LdbColumnFamily {
    private final int id;
    private final String name;

    private TestColumnFamily(int id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public String getName() {
      return name;
    }
  }

  /**
   * 记录插件生命周期的测试插件。
   *
   * 插件不启动线程、不持有外部资源，只记录 LDB 回调顺序和关键上下文，
   * 用于约束插件化扩展不会绕过列族注册和写入失败语义。
   */
  /**
   * 测试专用的外部 batch 实现，用于验证 LDB 会拒绝无法安全编码 WAL 的未知实现。
   */
  private static class UnsupportedWriteBatch implements LdbWriteBatch {
    @Override
    public LdbWriteBatch touch(LdbColumnFamily cf) {
      return this;
    }

    @Override
    public LdbWriteBatch put(LdbColumnFamily cf, byte[] key, byte[] value) {
      return this;
    }

    @Override
    public LdbWriteBatch delete(LdbColumnFamily cf, byte[] key) {
      return this;
    }

    @Override
    public LdbWriteBatch deleteRange(LdbColumnFamily cf, byte[] beginKey, byte[] endKey) {
      return this;
    }

    @Override
    public LdbWriteBatch put(byte[] key, byte[] value) {
      return this;
    }

    @Override
    public LdbWriteBatch delete(byte[] key) {
      return this;
    }

    @Override
    public LdbWriteBatch deleteRange(byte[] beginKey, byte[] endKey) {
      return this;
    }

    @Override
    public LdbWriteBatch put(LdbColumnFamily cf, net.xdob.vexra.ldb.util.Slice key, net.xdob.vexra.ldb.util.Slice value) {
      return this;
    }

    @Override
    public LdbWriteBatch delete(LdbColumnFamily cf, net.xdob.vexra.ldb.util.Slice key) {
      return this;
    }

    @Override
    public LdbWriteBatch deleteRange(LdbColumnFamily cf, net.xdob.vexra.ldb.util.Slice beginKey, net.xdob.vexra.ldb.util.Slice endKey) {
      return this;
    }

    @Override
    public LdbWriteBatch put(net.xdob.vexra.ldb.util.Slice key, net.xdob.vexra.ldb.util.Slice value) {
      return this;
    }

    @Override
    public LdbWriteBatch delete(net.xdob.vexra.ldb.util.Slice key) {
      return this;
    }

    @Override
    public LdbWriteBatch deleteRange(net.xdob.vexra.ldb.util.Slice beginKey, net.xdob.vexra.ldb.util.Slice endKey) {
      return this;
    }

    @Override
    public LdbWriteBatch addLong(LdbColumnFamily cf, byte[] key, long delta) {
      return this;
    }

    @Override
    public LdbWriteBatch addLong(byte[] key, long delta) {
      return this;
    }

    @Override
    public LdbWriteBatch addLong(LdbColumnFamily cf, net.xdob.vexra.ldb.util.Slice key, net.xdob.vexra.ldb.util.Slice delta) {
      return this;
    }

    @Override
    public Set<LdbColumnFamily> getColumnFamilies() {
      return Collections.emptySet();
    }

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public void close() {
    }
  }

  /**
   * 测试专用插件：模拟 beforeWrite 回调误改 batch，验证二次写前校验仍能兜住。
   */
  private static class MutatingBeforeWritePlugin implements LdbPlugin {
    @Override
    public void beforeWrite(LdbWriteBatch updates, WriteOptions options) {
      updates.deleteRange(bytes("a"), bytes("z"));
    }
  }

  private static class RecordingPlugin implements LdbPlugin {
    private final boolean rejectBeforeWrite;
    private final List<String> events = new ArrayList<>();
    private LdbPluginContext context;
    private File databaseDir;
    private boolean beforeWriteSawPluginCf;
    private Snapshot afterWriteSnapshot;

    private RecordingPlugin(boolean rejectBeforeWrite) {
      this.rejectBeforeWrite = rejectBeforeWrite;
    }

    /**
     * 插件配置阶段声明列族，确保 LDB 打开前能创建对应 ColumnFamilyState。
     */
    @Override
    public void configure(Options options) {
      events.add("configure");
      options.addColumnFamily(PLUGIN_CF);
    }

    /**
     * 打开完成后保存受控上下文，后续测试用它验证插件可见的数据库信息。
     */
    @Override
    public void onOpen(LdbPluginContext context) {
      events.add("open");
      this.context = context;
      this.databaseDir = context.getDatabaseDir();
    }

    /**
     * 写入前检查本批次是否触达插件列族；需要拒绝时直接中断写入。
     */
    @Override
    public void beforeWrite(LdbWriteBatch updates, WriteOptions options) {
      events.add("beforeWrite");
      beforeWriteSawPluginCf = updates.getColumnFamilies().contains(PLUGIN_CF);
      if (rejectBeforeWrite) {
        throw new DBException("plugin rejected write");
      }
    }

    /**
     * 写入后记录 snapshot，用于验证回调拿到的是本次写入返回给调用方的对象。
     */
    @Override
    public void afterWrite(LdbWriteBatch updates, WriteOptions options, Snapshot snapshot) {
      events.add("afterWrite");
      afterWriteSnapshot = snapshot;
    }

    /**
     * checkpoint 前记录事件，约束插件能参与备份前置校验。
     */
    @Override
    public void beforeCheckpoint(File targetDir) {
      events.add("beforeCheckpoint");
    }

    /**
     * checkpoint 后记录事件，约束插件能感知备份完成。
     */
    @Override
    public void afterCheckpoint(File targetDir) {
      events.add("afterCheckpoint");
    }

    /**
     * LDB 关闭前记录事件，插件可在真实实现里停止提交新工作。
     */
    @Override
    public void beforeClose() {
      events.add("beforeClose");
    }

    /**
     * LDB 关闭后释放插件资源，测试里只记录事件。
     */
    @Override
    public void close() {
      events.add("close");
    }
  }
}
