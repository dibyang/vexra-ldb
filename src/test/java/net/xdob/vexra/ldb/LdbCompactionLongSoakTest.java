package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * compaction 长压测回归入口。
 *
 * 测试通过多轮写入、覆盖、删除、手动压缩和重开验证长链路稳定性；不设置固定耗时阈值。
 */
class LdbCompactionLongSoakTest {
  @TempDir
  File tempDir;

  @Test
  void shouldKeepDataReadableAcrossRepeatedCompactionSoak() throws Exception {
    File dbDir = new File(tempDir, "compaction-long-soak-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .writeBufferSize(256)
        .forceSstOnFlush(true)
        .level0CompactionTrigger(2)
        .level0SlowdownWritesTrigger(6)
        .level0StopWritesTrigger(12)
        .compactionRateLimitBytesPerSecond(128 * 1024))) {
      for (int round = 0; round < 8; round++) {
        for (int i = 0; i < 80; i++) {
          db.put(key(round, i), bytes("value-" + round + "-" + i));
        }
        for (int i = 0; i < 20; i++) {
          db.put(key(round, i), bytes("updated-" + round + "-" + i));
        }
        for (int i = 20; i < 30; i++) {
          db.delete(key(round, i));
        }
        db.compactRange(bytes("soak:"), bytes("soak;"));
        assertArrayEquals(bytes("updated-" + round + "-0"), db.get(key(round, 0)));
        assertNull(db.get(key(round, 20)));
        assertArrayEquals(bytes("value-" + round + "-79"), db.get(key(round, 79)));
      }
      assertTrue(db.getProperty("ldb.compactionStats").contains("runCount="));
      assertTrue(db.getProperty("ldb.compactionStats").contains("outputBytes="));
      assertNotNull(db.getProperty("ldb.compactionPendingBytes"));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("updated-0-0"), reopened.get(key(0, 0)));
      assertNull(reopened.get(key(7, 20)));
      assertArrayEquals(bytes("value-7-79"), reopened.get(key(7, 79)));
    }
  }

  private static byte[] key(int round, int index) {
    return bytes(String.format("soak:%02d:%03d", round, index));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
