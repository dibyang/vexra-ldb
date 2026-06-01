package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LDB 轻量 benchmark/soak 回归入口。
 *
 * 该测试不设置固定性能阈值，避免 CI 环境波动导致误报；它的职责是稳定跑通典型读写、扫描、
 * checkpoint 和 reopen 工作流，并把核心耗时、容量和 stall 指标约束为可观测、可解析。
 */
class LdbBenchmarkSoakTest {
  @TempDir
  File tempDir;

  @Test
  void shouldExposeStableMicroBenchmarkMetrics() throws Exception {
    File dbDir = new File(tempDir, "micro-benchmark-db");
    File checkpointDir = new File(tempDir, "micro-benchmark-checkpoint");
    BenchmarkReport report;

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(1024)
            .slowOperationThresholdMicros(1)
            .level0CompactionTrigger(4)
            .level0SlowdownWritesTrigger(8)
            .level0StopWritesTrigger(16))) {
      report = runMicroBenchmark(db, checkpointDir);

      assertPositive(report.sequentialWriteNanos);
      assertPositive(report.randomReadNanos);
      assertPositive(report.snapshotScanNanos);
      assertPositive(report.compactNanos);
      assertPositive(report.checkpointNanos);
      assertEquals(128, report.scannedKeys);

      assertLongPropertyAtLeast(db, "ldb.operation.write.count", 128);
      assertLongPropertyAtLeast(db, "ldb.operation.get.count", 128);
      assertLongPropertyAtLeast(db, "ldb.operation.compact.count", 1);
      assertLongPropertyAtLeast(db, "ldb.operation.checkpoint.count", 1);
      assertLongPropertyAtLeast(db, "ldb.totalBytes", 1);
      assertLongPropertyAtLeast(db, "ldb.walBytes", 1);
      assertNotNull(db.getProperty("ldb.sstBytes"));
      assertNotNull(db.getProperty("ldb.compactionBacklog"));
      assertNotNull(db.getProperty("ldb.writeStallStats"));
      assertNotNull(db.getProperty("ldb.compactionStats"));
      assertNotNull(db.getProperty("ldb.operationStats"));
    }

    try (LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(value(0), checkpoint.get(key(0)));
      assertArrayEquals(value(127), checkpoint.get(key(127)));
    }
  }

  @Test
  void shouldSurviveShortReadWriteReopenSoak() throws Exception {
    File dbDir = new File(tempDir, "short-soak-db");

    for (int round = 0; round < 5; round++) {
      try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(round == 0))) {
        for (int i = 0; i < 40; i++) {
          db.put(bytes(String.format("soak:%02d:%03d", round, i)), bytes("value-" + round + "-" + i));
        }
        assertEquals((round + 1) * 40, scanPrefix(db, "soak:"));
        assertNotNull(db.getProperty("ldb.fileCounts"));
        assertNotNull(db.getProperty("ldb.fileBytes"));
      }
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0-0"), db.get(bytes("soak:00:000")));
      assertArrayEquals(bytes("value-4-39"), db.get(bytes("soak:04:039")));
      assertEquals(200, scanPrefix(db, "soak:"));
      assertLongPropertyAtLeast(db, "ldb.totalBytes", 1);
    }
  }

  private static BenchmarkReport runMicroBenchmark(LDB db, File checkpointDir) {
    Random random = new Random(17);
    BenchmarkReport report = new BenchmarkReport();

    long start = System.nanoTime();
    for (int i = 0; i < 128; i++) {
      db.put(key(i), value(i));
    }
    report.sequentialWriteNanos = System.nanoTime() - start;

    start = System.nanoTime();
    for (int i = 0; i < 128; i++) {
      assertNotNull(db.get(key(random.nextInt(128))));
    }
    report.randomReadNanos = System.nanoTime() - start;

    start = System.nanoTime();
    report.scannedKeys = scanPrefix(db, "bench:");
    report.snapshotScanNanos = System.nanoTime() - start;

    start = System.nanoTime();
    db.compactRange(bytes("bench:000"), bytes("bench:999"));
    report.compactNanos = System.nanoTime() - start;

    start = System.nanoTime();
    db.checkpoint(checkpointDir.getAbsolutePath());
    report.checkpointNanos = System.nanoTime() - start;

    return report;
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

  private static void assertPositive(long value) {
    assertTrue(value > 0, "expected positive duration, got " + value);
  }

  private static byte[] key(int index) {
    return bytes(String.format("bench:%03d", index));
  }

  private static byte[] value(int index) {
    return bytes("value-" + index);
  }

  private static String string(byte[] value) {
    return new String(value, UTF_8);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static final class BenchmarkReport {
    private long sequentialWriteNanos;
    private long randomReadNanos;
    private long snapshotScanNanos;
    private long compactNanos;
    private long checkpointNanos;
    private int scannedKeys;
  }
}
