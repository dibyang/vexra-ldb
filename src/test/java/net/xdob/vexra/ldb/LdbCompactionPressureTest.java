package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compaction 高压可靠性回归。
 *
 * 用小写缓冲制造频繁 flush 和 L0 backlog，在后台/手动 compaction 期间持续执行前台读取和
 * snapshot scan，约束压缩不会破坏已提交数据，也不会让关键观测属性失效。
 */
class LdbCompactionPressureTest {
  @TempDir
  File tempDir;

  @Test
  void shouldKeepCommittedDataReadableDuringManualCompactionPressure() throws Exception {
    File dbDir = new File(tempDir, "manual-compaction-pressure-db");
    byte[] largeValue = repeatedValue('p', 512);

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(512)
            .compactionRateLimitBytesPerSecond(64 * 1024)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(8)
            .level0StopWritesTrigger(20))) {
      for (int i = 0; i < 160; i++) {
        db.put(key(i), largeValue);
      }

      ExecutorService executor = Executors.newSingleThreadExecutor();
      Future<?> compacting = executor.submit(new Runnable() {
        @Override
        public void run() {
          db.compactRange(bytes("pressure:000"), bytes("pressure:999"));
        }
      });

      try {
        int readRounds = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!compacting.isDone() && System.nanoTime() < deadline) {
          assertArrayEquals(largeValue, db.get(key(0)));
          assertArrayEquals(largeValue, db.get(key(159)));
          assertEquals(160, scanPrefix(db, "pressure:"));
          readRounds++;
        }
        compacting.get(15, TimeUnit.SECONDS);
        assertTrue(readRounds > 0, "compaction finished before the read loop observed it");
      } finally {
        executor.shutdownNow();
      }

      assertArrayEquals(largeValue, db.get(key(0)));
      assertArrayEquals(largeValue, db.get(key(159)));
      assertEquals(160, scanPrefix(db, "pressure:"));
      assertLongPropertyAtLeast(db, "ldb.compaction.runCount", 1);
      assertLongPropertyAtLeast(db, "ldb.compaction.successCount", 1);
      assertLongPropertyAtLeast(db, "ldb.compaction.outputBytes", 1);
      assertLongPropertyAtLeast(db, "ldb.compaction.throttleCount", 1);
      assertLongPropertyAtLeast(db, "ldb.totalBytes", 1);
      assertNotNull(db.getProperty("ldb.compactionCandidate"));
      assertNotNull(db.getProperty("ldb.compactionStats"));
      assertNotNull(db.getProperty("ldb.writeStallStats"));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(largeValue, reopened.get(key(0)));
      assertArrayEquals(largeValue, reopened.get(key(159)));
      assertEquals(160, scanPrefix(reopened, "pressure:"));
    }
  }

  @Test
  void shouldRecoverAfterWriteBurstAndRepeatedCompaction() throws Exception {
    File dbDir = new File(tempDir, "repeated-compaction-pressure-db");
    byte[] value = repeatedValue('r', 256);

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(384)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(8)
            .level0StopWritesTrigger(24))) {
      for (int round = 0; round < 4; round++) {
        for (int i = 0; i < 48; i++) {
          db.put(bytes(String.format("round:%02d:%03d", round, i)), value);
        }
        db.compactRange(bytes("round:00:000"), bytes("round:99:999"));
        assertArrayEquals(value, db.get(bytes(String.format("round:%02d:%03d", round, 47))));
        assertLongPropertyAtLeast(db, "ldb.operation.compact.count", round + 1L);
      }

      assertEquals(192, scanPrefix(db, "round:"));
      assertLongPropertyAtLeast(db, "ldb.compaction.successCount", 1);
      assertLongPropertyAtLeast(db, "ldb.sstBytes", 1);
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertEquals(192, scanPrefix(reopened, "round:"));
      assertArrayEquals(value, reopened.get(bytes("round:00:000")));
      assertArrayEquals(value, reopened.get(bytes("round:03:047")));
    }
  }

  private static int scanPrefix(LDB db, String prefix) {
    int scanned = 0;
    try (SnapshotCursor cursor = db.newSnapshotCursor()) {
      cursor.seek(bytes(prefix));
      while (cursor.isValid() && string(cursor.key()).startsWith(prefix)) {
        scanned++;
        cursor.next();
      }
    }
    return scanned;
  }

  private static void assertLongPropertyAtLeast(LDB db, String property, long min) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    assertTrue(Long.parseLong(value) >= min, property + "=" + value);
  }

  private static byte[] key(int index) {
    return bytes(String.format("pressure:%03d", index));
  }

  private static byte[] repeatedValue(char value, int count) {
    byte[] bytes = new byte[count];
    for (int i = 0; i < count; i++) {
      bytes[i] = (byte) value;
    }
    return bytes;
  }

  private static String string(byte[] value) {
    return new String(value, UTF_8);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
