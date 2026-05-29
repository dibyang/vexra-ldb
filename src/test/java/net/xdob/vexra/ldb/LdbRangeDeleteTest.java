package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Range Delete 第九阶段的入口测试。
 *
 * 9.2 覆盖 WAL、MemTable 和 point get 的最小闭环；iterator/compaction 的完整语义
 * 由后续 9.4/9.5 的矩阵继续补齐。
 */
class LdbRangeDeleteTest {
  @TempDir
  File tempDir;

  /**
   * 验证空区间不会进入 batch，调用方可以直接看到 deleteRange 的边界错误。
   */
  @Test
  void shouldRejectEmptyDeleteRangeBeforeWrite() throws Exception {
    try (LdbWriteBatch batch = new net.xdob.vexra.ldb.impl.LdbWriteBatchImpl()) {
      DBException error = assertThrows(DBException.class,
          () -> batch.deleteRange(bytes("same"), bytes("same")));

      assertTrue(error.getMessage().contains("deleteRange beginKey must be smaller than endKey"));
      assertTrue(batch.isEmpty());
    }
  }

  /**
   * 验证反向区间不会进入 batch，避免后续 WAL 编码阶段接收到语义不明确的 tombstone。
   */
  @Test
  void shouldRejectReversedDeleteRangeBeforeWrite() throws Exception {
    try (LdbWriteBatch batch = new net.xdob.vexra.ldb.impl.LdbWriteBatchImpl()) {
      DBException error = assertThrows(DBException.class,
          () -> batch.deleteRange(bytes("z"), bytes("a")));

      assertTrue(error.getMessage().contains("deleteRange beginKey must be smaller than endKey"));
      assertTrue(batch.isEmpty());
    }
  }

  /**
   * 验证合法区间会遮蔽区间内旧值，同时不影响半开区间外的 key。
   */
  @Test
  void shouldMaskPointReadsInsideRange() throws Exception {
    File dbDir = new File(tempDir, "range-delete-point-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("a"), bytes("left"));
      db.put(bytes("b"), bytes("inside"));
      db.put(bytes("z"), bytes("right"));

      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("b"), bytes("z"));
        db.write(batch);
      }

      assertArrayEquals(bytes("left"), db.get(bytes("a")));
      assertNull(db.get(bytes("b")));
      assertArrayEquals(bytes("right"), db.get(bytes("z")));
    }
  }

  /**
   * 验证 snapshot 仍能看到 range tombstone 之前的旧值，range delete 之后重新 put 的值可见。
   */
  @Test
  void shouldRespectSnapshotAndNewerPutAfterRangeDelete() throws Exception {
    File dbDir = new File(tempDir, "range-delete-snapshot-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v1"));
      Snapshot snapshot = db.getSnapshot();
      try {
        try (LdbWriteBatch batch = db.createWriteBatch()) {
          batch.deleteRange(bytes("a"), bytes("z"));
          db.write(batch);
        }

        assertNull(db.get(bytes("k")));
        assertArrayEquals(bytes("v1"), db.get(bytes("k"), new ReadOptions().snapshot(snapshot)));

        db.put(bytes("k"), bytes("v2"));
        assertArrayEquals(bytes("v2"), db.get(bytes("k")));
      } finally {
        snapshot.close();
      }
    }
  }

  /**
   * 验证包含 DELETE_RANGE 的 WAL 在重启后可以恢复，并继续遮蔽区间内旧值。
   */
  @Test
  void shouldRecoverRangeDeleteFromWalAfterReopen() throws Exception {
    File dbDir = new File(tempDir, "range-delete-recovery-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("b"), bytes("inside"), new WriteOptions().sync(true));
      db.put(bytes("z"), bytes("outside"), new WriteOptions().sync(true));
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("a"), bytes("z"));
        db.write(batch, new WriteOptions().sync(true));
      }
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertNull(db.get(bytes("b")));
      assertArrayEquals(bytes("outside"), db.get(bytes("z")));
    }
  }

  /**
   * 验证只有 range tombstone 的恢复输出也会扩大 SST 元数据覆盖范围，避免旧 SST 中的 key 重新可见。
   */
  @Test
  void shouldRecoverRangeDeleteOnlyWalOverExistingSst() throws Exception {
    File dbDir = new File(tempDir, "range-delete-only-tombstone-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128))) {
      db.put(bytes("b"), bytes("old"), new WriteOptions().sync(true));
      db.compactRange(bytes("a"), bytes("z"));
    }

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).writeBufferSize(128))) {
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("a"), bytes("z"));
        db.write(batch, new WriteOptions().sync(true));
      }
    }

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).writeBufferSize(128))) {
      assertNull(db.get(bytes("b")));
    }
  }

  /**
   * 验证 snapshot cursor 顺序扫描会跳过被 range tombstone 覆盖的 key。
   */
  @Test
  void shouldMaskSnapshotCursorScanInsideRange() throws Exception {
    File dbDir = new File(tempDir, "range-delete-cursor-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("a"), bytes("left"));
      db.put(bytes("b"), bytes("inside-b"));
      db.put(bytes("c"), bytes("inside-c"));
      db.put(bytes("z"), bytes("right"));
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("b"), bytes("z"));
        db.write(batch);
      }

      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seekToFirst();
        assertEquals("a,z", collectKeys(cursor));
      }

      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seek(bytes("c"));
        assertTrue(cursor.isValid());
        assertArrayEquals(bytes("z"), cursor.key());
      }
    }
  }

  /**
   * 验证 range delete 之前创建的 cursor 仍保留旧视图，不会被后续 tombstone 遮蔽。
   */
  @Test
  void shouldKeepSnapshotCursorOldViewBeforeRangeDelete() throws Exception {
    File dbDir = new File(tempDir, "range-delete-cursor-snapshot-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("a"), bytes("left"));
      db.put(bytes("b"), bytes("old"));
      db.put(bytes("z"), bytes("right"));

      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        try (LdbWriteBatch batch = db.createWriteBatch()) {
          batch.deleteRange(bytes("b"), bytes("z"));
          db.write(batch);
        }

        cursor.seekToFirst();
        assertEquals("a,b,z", collectKeys(cursor));
      }
    }
  }

  /**
   * 验证 range 起点被后续 put 覆盖时，cursor 仍会保留同 key 上较旧 tombstone 对后续 key 的遮蔽。
   */
  @Test
  void shouldKeepRangeTombstoneActiveAfterNewerPutAtBeginKeyDuringScan() throws Exception {
    File dbDir = new File(tempDir, "range-delete-cursor-begin-put-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("b"), bytes("old-b"));
      db.put(bytes("c"), bytes("old-c"));
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("b"), bytes("z"));
        db.write(batch);
      }
      db.put(bytes("b"), bytes("new-b"));
      db.put(bytes("z"), bytes("right"));

      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seekToFirst();
        assertEquals("b,z", collectKeys(cursor));
      }
    }
  }

  /**
   * 验证 WAL 恢复成 SST 后，snapshot cursor 仍能按 range tombstone 遮蔽扫描结果。
   */
  @Test
  void shouldMaskSnapshotCursorScanAfterReopen() throws Exception {
    File dbDir = new File(tempDir, "range-delete-cursor-reopen-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("a"), bytes("left"), new WriteOptions().sync(true));
      db.put(bytes("b"), bytes("inside"), new WriteOptions().sync(true));
      db.put(bytes("z"), bytes("right"), new WriteOptions().sync(true));
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("b"), bytes("z"));
        db.write(batch, new WriteOptions().sync(true));
      }
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seekToFirst();
        assertEquals("a,z", collectKeys(cursor));
      }
    }
  }

  /**
   * 验证 compaction 不会因为 beginKey 上有更新的 put 就丢弃 range tombstone。
   */
  @Test
  void shouldRetainRangeTombstoneThroughCompactionWhenBeginKeyHasNewerPut() throws Exception {
    File dbDir = new File(tempDir, "range-delete-compaction-begin-put-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128))) {
      db.put(bytes("b"), bytes("old-b"), new WriteOptions().sync(true));
      db.put(bytes("c"), bytes("old-c"), new WriteOptions().sync(true));
      db.put(bytes("z"), bytes("right"), new WriteOptions().sync(true));
      db.compactRange(bytes("a"), bytes("z"));

      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("b"), bytes("z"));
        db.write(batch, new WriteOptions().sync(true));
      }
      db.put(bytes("b"), bytes("new-b"), new WriteOptions().sync(true));

      db.compactRange(bytes("a"), bytes("z"));

      assertArrayEquals(bytes("new-b"), db.get(bytes("b")));
      assertNull(db.get(bytes("c")));
      assertArrayEquals(bytes("right"), db.get(bytes("z")));
    }

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).writeBufferSize(128))) {
      assertArrayEquals(bytes("new-b"), db.get(bytes("b")));
      assertNull(db.get(bytes("c")));
      assertArrayEquals(bytes("right"), db.get(bytes("z")));
      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seekToFirst();
        assertEquals("b,z", collectKeys(cursor));
      }
    }
  }

  /**
   * 验证 deleteRange 的 null 边界仍按现有 API 约束抛出 NullPointerException。
   */
  @Test
  void shouldRejectNullDeleteRangeBounds() throws Exception {
    try (LdbWriteBatch batch = new net.xdob.vexra.ldb.impl.LdbWriteBatchImpl()) {
      assertThrows(NullPointerException.class, () -> batch.deleteRange((byte[]) null, bytes("z")));
      assertThrows(NullPointerException.class, () -> batch.deleteRange(bytes("a"), (byte[]) null));
      assertThrows(NullPointerException.class,
          () -> batch.deleteRange((LdbColumnFamily) null, bytes("a"), bytes("z")));
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static String collectKeys(SnapshotCursor cursor) {
    List<String> keys = new ArrayList<>();
    while (cursor.isValid()) {
      keys.add(new String(cursor.key(), UTF_8));
      cursor.next();
    }
    return String.join(",", keys);
  }
}
