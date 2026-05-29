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
 * 第十五阶段 Snapshot/Iterator 完整性测试。
 *
 * 这些用例覆盖 snapshot cursor 的资源释放、反向迭代、prefix/range scan 边界，
 * 以及跨 compaction 持有长生命周期 cursor 的旧视图安全性。测试只使用本地临时目录，
 * 不涉及远程调用、网络、事务或 Raft。
 */
class LdbSnapshotIteratorTest {
  @TempDir
  File tempDir;

  @Test
  void shouldTrackSnapshotCursorResourceCounts() throws Exception {
    File dbDir = new File(tempDir, "cursor-resource-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
      assertEquals("0", db.getProperty("ldb.snapshotCursor.activeCount"));

      SnapshotCursor cursor = db.newSnapshotCursor();
      assertEquals("1", db.getProperty("ldb.snapshotCursor.activeCount"));
      assertPropertyContains(db, "ldb.snapshotCursorStats", "openCount=1");

      cursor.close();
      assertEquals("0", db.getProperty("ldb.snapshotCursor.activeCount"));
      assertEquals("1", db.getProperty("ldb.snapshotCursor.closeCount"));
    }
  }

  @Test
  void shouldIterateSnapshotCursorBackward() throws Exception {
    File dbDir = new File(tempDir, "reverse-cursor-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      for (int i = 0; i < 5; i++) {
        db.put(bytes("k" + i), bytes("v" + i));
      }
      db.delete(bytes("k2"));

      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seekToLast();
        assertEquals("k4", string(cursor.key()));
        cursor.prev();
        assertEquals("k3", string(cursor.key()));
        cursor.prev();
        assertEquals("k1", string(cursor.key()));
        cursor.prev();
        assertEquals("k0", string(cursor.key()));
        cursor.prev();
        assertFalse(cursor.isValid());
      }

      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seekForPrev(bytes("k3"));
        assertEquals("k3", string(cursor.key()));
        cursor.next();
        assertEquals("k4", string(cursor.key()));
        cursor.seekForPrev(bytes("k2"));
        assertEquals("k1", string(cursor.key()));
        cursor.seekForPrev(bytes("j"));
        assertFalse(cursor.isValid());
      }
    }
  }

  @Test
  void shouldRespectPrefixAndRangeScanBoundaries() throws Exception {
    File dbDir = new File(tempDir, "prefix-range-cursor-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("a:001"), bytes("a1"));
      db.put(bytes("a:002"), bytes("a2"));
      db.put(bytes("b:001"), bytes("b1"));
      db.put(bytes("b:002"), bytes("b2"));
      db.put(bytes("c:001"), bytes("c1"));

      assertEquals(list("b:001", "b:002"), scanPrefix(db, "b:"));
      assertEquals(list("a:002", "b:001", "b:002"), scanRange(db, "a:002", "c:000"));
    }
  }

  @Test
  void shouldKeepLongLivedSnapshotStableAcrossCompaction() throws Exception {
    File dbDir = new File(tempDir, "long-snapshot-compaction-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256).forceSstOnFlush(true))) {
      for (int i = 0; i < 32; i++) {
        db.put(bytes(String.format("snap:%03d", i)), bytes("old-" + i));
      }

      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        for (int i = 0; i < 32; i++) {
          db.put(bytes(String.format("snap:%03d", i)), bytes("new-" + i));
        }
        db.delete(bytes("snap:010"));
        db.compactRange(bytes("snap:000"), bytes("snap:999"));

        cursor.seek(bytes("snap:000"));
        assertTrue(cursor.isValid());
        assertEquals("snap:000", string(cursor.key()));
        assertEquals("old-0", string(cursor.value()));
        cursor.seek(bytes("snap:010"));
        assertTrue(cursor.isValid());
        assertEquals("snap:010", string(cursor.key()));
        assertEquals("old-10", string(cursor.value()));
        assertEquals("1", db.getProperty("ldb.snapshotCursor.activeCount"));
      }

      assertEquals("0", db.getProperty("ldb.snapshotCursor.activeCount"));
      assertNull(db.get(bytes("snap:010")));
      assertArrayEquals(bytes("new-31"), db.get(bytes("snap:031")));
    }
  }

  private static List<String> scanPrefix(LDB db, String prefix) {
    List<String> keys = new ArrayList<>();
    try (SnapshotCursor cursor = db.newSnapshotCursor()) {
      cursor.seek(bytes(prefix));
      while (cursor.isValid() && string(cursor.key()).startsWith(prefix)) {
        keys.add(string(cursor.key()));
        cursor.next();
      }
    }
    return keys;
  }

  private static List<String> scanRange(LDB db, String begin, String end) {
    List<String> keys = new ArrayList<>();
    try (SnapshotCursor cursor = db.newSnapshotCursor()) {
      cursor.seek(bytes(begin));
      while (cursor.isValid() && string(cursor.key()).compareTo(end) < 0) {
        keys.add(string(cursor.key()));
        cursor.next();
      }
    }
    return keys;
  }

  private static void assertPropertyContains(LDB db, String property, String expected) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    assertTrue(value.contains(expected), property + "=" + value);
  }

  private static List<String> list(String... values) {
    List<String> result = new ArrayList<>();
    for (String value : values) {
      result.add(value);
    }
    return result;
  }

  private static String string(byte[] value) {
    return new String(value, UTF_8);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
