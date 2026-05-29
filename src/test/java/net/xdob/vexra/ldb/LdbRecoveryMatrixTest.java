package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.util.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LDB 恢复矩阵测试。
 *
 * 这些用例覆盖 WAL 重放、flush/SST、manual compaction 和 checkpoint 分叉恢复。
 * 测试只使用本地临时目录，不涉及远程调用、网络、事务或 Raft；目标是约束磁盘格式不变时的
 * 基础恢复语义，为后续故障注入测试提供稳定基线。
 */
class LdbRecoveryMatrixTest {
  private static final LdbColumnFamily META_CF = new TestColumnFamily(17, "recovery-meta");

  @TempDir
  File tempDir;

  /**
   * 验证跨列族 batch 在关闭重启后可完整重放，包含 put、delete 和 addLong。
   */
  @Test
  void shouldRecoverWalBatchWithDeletesCountersAndColumnFamilies() throws Exception {
    File dbDir = new File(tempDir, "wal-batch-recovery");
    Options createOptions = new Options()
        .createIfMissing(true)
        .addColumnFamily(META_CF);

    try (LDB db = LDBFactory.factory.open(dbDir, createOptions)) {
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.put(bytes("user:1"), bytes("default-v1"));
        batch.put(META_CF, bytes("user:1"), bytes("meta-v1"));
        batch.put(bytes("delete-me"), bytes("gone"));
        batch.delete(bytes("delete-me"));
        batch.addLong(META_CF, bytes("counter"), 7L);
        db.write(batch, new WriteOptions().sync(true));
      }
      assertEquals("5", db.getProperty("ldb.lastSequence"));
    }

    try (LDB db = LDBFactory.factory.open(dbDir, reopenOptions())) {
      assertArrayEquals(bytes("default-v1"), db.get(bytes("user:1")));
      assertArrayEquals(bytes("meta-v1"), db.get(META_CF, bytes("user:1")));
      assertNull(db.get(bytes("delete-me")));
      assertEquals(7L, Utils.decodeLong(db.get(META_CF, bytes("counter"))).get().longValue());
      assertEquals("5", db.getProperty("ldb.lastSequence"));
    }
  }

  /**
   * 验证小 writeBuffer 触发 flush/SST 后，重启仍能读取所有已写入数据。
   */
  @Test
  void shouldRecoverAfterFlushToSst() throws Exception {
    File dbDir = new File(tempDir, "flush-recovery");
    Options createOptions = new Options()
        .createIfMissing(true)
        .writeBufferSize(128)
        .forceSstOnFlush(true)
        .addColumnFamily(META_CF);

    try (LDB db = LDBFactory.factory.open(dbDir, createOptions)) {
      for (int i = 0; i < 80; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.put(META_CF, bytes("meta"), bytes("flush-visible"));
      assertTrue(Long.parseLong(db.getProperty("ldb.lastSequence")) >= 81L);
    }

    try (LDB db = LDBFactory.factory.open(dbDir, reopenOptions())) {
      assertArrayEquals(bytes("value-0"), db.get(bytes("k000")));
      assertArrayEquals(bytes("value-79"), db.get(bytes("k079")));
      assertArrayEquals(bytes("flush-visible"), db.get(META_CF, bytes("meta")));
      assertTrue(db.getProperty("ldb.levelFiles").contains("1:0="));
    }
  }

  /**
   * 验证 manual compaction 之后重启，数据仍可通过 SST/manifest 正确恢复。
   */
  @Test
  void shouldRecoverAfterManualCompaction() throws Exception {
    File dbDir = new File(tempDir, "manual-compaction-recovery");
    Options createOptions = new Options()
        .createIfMissing(true)
        .writeBufferSize(128)
        .forceSstOnFlush(true);

    try (LDB db = LDBFactory.factory.open(dbDir, createOptions)) {
      for (int i = 0; i < 60; i++) {
        db.put(bytes(String.format("c%03d", i)), bytes("compact-" + i));
      }
      db.delete(bytes("c010"));
      db.compactRange(bytes("c000"), bytes("c999"));
      assertNull(db.get(bytes("c010")));
      assertArrayEquals(bytes("compact-59"), db.get(bytes("c059")));
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertNull(db.get(bytes("c010")));
      assertArrayEquals(bytes("compact-0"), db.get(bytes("c000")));
      assertArrayEquals(bytes("compact-59"), db.get(bytes("c059")));
    }
  }

  /**
   * 验证 checkpoint 与源库后续写入分叉后，两边都能独立重启并保持各自视图。
   */
  @Test
  void shouldRecoverCheckpointAndSourceAfterDivergence() throws Exception {
    File dbDir = new File(tempDir, "checkpoint-source");
    File checkpointDir = new File(tempDir, "checkpoint-copy");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("before"), bytes("checkpoint"));
      db.checkpoint(checkpointDir.getAbsolutePath());
      db.put(bytes("after"), bytes("source-only"));
      db.compactRange(bytes("a"), bytes("z"));
    }

    try (LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("checkpoint"), checkpoint.get(bytes("before")));
      assertNull(checkpoint.get(bytes("after")));
    }

    try (LDB source = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("checkpoint"), source.get(bytes("before")));
      assertArrayEquals(bytes("source-only"), source.get(bytes("after")));
    }
  }

  private static Options reopenOptions() {
    return new Options()
        .createIfMissing(false)
        .addColumnFamily(META_CF);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  /**
   * 恢复矩阵专用列族，固定 id 用于跨 reopen 验证。
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
}
