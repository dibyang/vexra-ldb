package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.impl.BloomFilterPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十阶段的轻量压力与观测入口测试。
 *
 * 这些用例不设置固定性能阈值，只验证典型读写/扫描/压缩/checkpoint 工作流能跑通，
 * 并且关键容量与 backlog 属性可以被稳定读取，方便后续 benchmark/soak 扩展。
 */
class LdbObservabilityTest {
  private static final LdbColumnFamily METRICS_CF = new TestColumnFamily(11, "metrics");

  @TempDir
  File tempDir;

  @Test
  void shouldExposeFileCapacityAndCompactionProperties() throws Exception {
    File dbDir = new File(tempDir, "observability-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(256)
            .writeSlowdownDelayNanos(250_000L)
            .compactionRateLimitBytesPerSecond(1_000_000_000L)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(6)
            .level0StopWritesTrigger(9))) {
      for (int i = 0; i < 40; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }

      assertPropertyContains(db, "ldb.fileCounts", "log=");
      assertPropertyContains(db, "ldb.fileBytes", "log=");
      assertTrue(Long.parseLong(db.getProperty("ldb.totalBytes")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.liveDataBytes")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.walBytes")) > 0);
      assertNotNull(db.getProperty("ldb.sstBytes"));
      assertNotNull(db.getProperty("ldb.compactionBacklog"));
      assertNotNull(db.getProperty("ldb.compactionScore"));
      assertNotNull(db.getProperty("ldb.compactionLevel"));
      assertNotNull(db.getProperty("ldb.compactionCfId"));
      assertNonNegativeProperty(db, "ldb.compactionPendingBytes");
      assertPropertyContains(db, "ldb.compactionScores", "default=");
      assertPropertyContains(db, "ldb.compactionCandidate", "cf=");
      assertEquals("3", db.getProperty("ldb.compaction.level0CompactionTrigger"));
      assertNotNull(db.getProperty("ldb.columnFamily.1.compactionScore"));
      assertNotNull(db.getProperty("ldb.columnFamily.1.compactionLevel"));
      assertNonNegativeProperty(db, "ldb.columnFamily.1.compactionPendingBytes");
      assertEquals("6", db.getProperty("ldb.writeStall.level0SlowdownTrigger"));
      assertEquals("9", db.getProperty("ldb.writeStall.level0StopTrigger"));
      assertEquals("250000", db.getProperty("ldb.writeStall.slowdownDelayNanos"));
      assertNonNegativeProperty(db, "ldb.writeStall.slowdownCount");
      assertNonNegativeProperty(db, "ldb.writeStall.immutableWaitCount");
      assertNonNegativeProperty(db, "ldb.writeStall.level0StopWaitCount");
      assertPropertyContains(db, "ldb.writeStallStats", "slowdown.count=");
      assertPropertyContains(db, "ldb.writeStallStats", "slowdown.delayNanos=250000");
      assertNotNull(db.getProperty("ldb.compaction.running"));
      assertNonNegativeProperty(db, "ldb.compaction.runCount");
      assertNonNegativeProperty(db, "ldb.compaction.successCount");
      assertNonNegativeProperty(db, "ldb.compaction.failureCount");
      assertNonNegativeProperty(db, "ldb.compaction.outputBytes");
      assertEquals("1000000000", db.getProperty("ldb.compaction.rateLimitBytesPerSecond"));
      assertNonNegativeProperty(db, "ldb.compaction.throttleCount");
      assertNonNegativeProperty(db, "ldb.compaction.throttleMicros");
      assertNonNegativeProperty(db, "ldb.compaction.cancelCount");
      assertNonNegativeProperty(db, "ldb.compaction.cleanupFileCount");
      assertEquals("", db.getProperty("ldb.compaction.lastFailure"));
      assertPropertyContains(db, "ldb.compactionStats", "runCount=");
      assertPropertyContains(db, "ldb.compactionStats", "cleanupFileCount=");

      db.compactRange(bytes("k000"), bytes("k999"));

      assertTrue(Long.parseLong(db.getProperty("ldb.sstBytes")) > 0);
      assertPropertyContains(db, "ldb.fileCounts", "table=");
      assertTrue(Long.parseLong(db.getProperty("ldb.compaction.runCount")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.compaction.successCount")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.compaction.outputBytes")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.compaction.throttleCount")) > 0);
    }
  }

  @Test
  void shouldRunLightweightBenchmarkWorkflow() throws Exception {
    File dbDir = new File(tempDir, "benchmark-workflow-db");
    File checkpointDir = new File(tempDir, "benchmark-checkpoint");
    Random random = new Random(7);

    long writeNanos;
    long readNanos;
    long scanNanos;
    long compactNanos;
    long checkpointNanos;

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(512)
            .checkpointCopyRateLimitBytesPerSecond(1024 * 1024)
            .slowOperationThresholdMicros(1))) {
      long start = System.nanoTime();
      for (int i = 0; i < 64; i++) {
        db.put(bytes(String.format("bench:%03d", i)), bytes("value-" + i));
      }
      writeNanos = System.nanoTime() - start;

      start = System.nanoTime();
      for (int i = 0; i < 64; i++) {
        int key = random.nextInt(64);
        assertNotNull(db.get(bytes(String.format("bench:%03d", key))));
      }
      readNanos = System.nanoTime() - start;

      start = System.nanoTime();
      int scanned = 0;
      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seek(bytes("bench:000"));
        while (cursor.isValid() && new String(cursor.key(), UTF_8).startsWith("bench:")) {
          scanned++;
          cursor.next();
        }
      }
      scanNanos = System.nanoTime() - start;
      assertEquals(64, scanned);

      start = System.nanoTime();
      db.compactRange(bytes("bench:000"), bytes("bench:999"));
      compactNanos = System.nanoTime() - start;

      start = System.nanoTime();
      db.checkpoint(checkpointDir.getAbsolutePath());
      checkpointNanos = System.nanoTime() - start;

      assertTrue(writeNanos > 0);
      assertTrue(readNanos > 0);
      assertTrue(scanNanos > 0);
      assertTrue(compactNanos > 0);
      assertTrue(checkpointNanos > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.totalBytes")) > 0);
      assertEquals("1", db.getProperty("ldb.slowOperationThresholdMicros"));
      assertOperationCount(db, "get", 64);
      assertOperationCount(db, "write", 64);
      assertOperationCount(db, "compact", 1);
      assertOperationCount(db, "checkpoint", 1);
      assertEquals("1048576", db.getProperty("ldb.checkpoint.copyRateLimitBytesPerSecond"));
      assertPropertyContains(db, "ldb.checkpointStats", "last=status=published");
      assertPropertyContains(db, "ldb.checkpoint.last", "copiedFiles=");
      assertTrue(Long.parseLong(db.getProperty("ldb.operation.get.maxMicros")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.operation.write.maxMicros")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.operation.compact.maxMicros")) > 0);
      assertTrue(Long.parseLong(db.getProperty("ldb.operation.checkpoint.maxMicros")) > 0);
      assertPropertyContains(db, "ldb.operation.write.histogramMicros", "le10=");
      assertPropertyContains(db, "ldb.operation.write.histogramMicros", "gt10000=");
      assertTrue(Long.parseLong(db.getProperty("ldb.operation.write.slowCount")) > 0);
      assertPropertyContains(db, "ldb.operationStats", "get.count=");
      assertPropertyContains(db, "ldb.operationStats", "write.histogramMicros=");
      assertPropertyContains(db, "ldb.operationStats", "checkpoint.slowCount=");
    }

    try (LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), checkpoint.get(bytes("bench:000")));
      assertArrayEquals(bytes("value-63"), checkpoint.get(bytes("bench:063")));
    }
  }

  @Test
  void shouldExposeBlockCacheStatsAndHonorCacheSwitch() throws Exception {
    File cachedDir = new File(tempDir, "block-cache-enabled-db");

    try (LDB db = LDBFactory.factory.open(cachedDir,
        new Options().createIfMissing(true).writeBufferSize(256).blockCacheSize(32))) {
      for (int i = 0; i < 32; i++) {
        db.put(bytes(String.format("cache:%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("cache:000"), bytes("cache:999"));
    }

    try (LDB db = LDBFactory.factory.open(cachedDir,
        new Options().createIfMissing(false).blockCacheSize(32))) {
      for (int round = 0; round < 2; round++) {
        for (int i = 0; i < 32; i++) {
          assertNotNull(db.get(bytes(String.format("cache:%03d", i))));
        }
      }
      assertPropertyContains(db, "ldb.blockCacheStats", "enabled=true");
      assertPropertyContains(db, "ldb.blockCacheStats", "hits=");
      assertPropertyContains(db, "ldb.blockCacheStats", "misses=");
    }

    File disabledDir = new File(tempDir, "block-cache-disabled-db");
    try (LDB db = LDBFactory.factory.open(disabledDir,
        new Options().createIfMissing(true).cacheBlocks(false))) {
      db.put(bytes("k"), bytes("v"));
      assertEquals("enabled=false", db.getProperty("ldb.blockCacheStats"));
    }
  }

  @Test
  void shouldExposeSstReadStatsForPointLookupProfiling() throws Exception {
    File dbDir = new File(tempDir, "sst-read-stats-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(128)
            .blockCacheSize(32)
            .filterPolicy(new BloomFilterPolicy(16)))) {
      for (int i = 0; i < 40; i++) {
        db.put(bytes(String.format("sst:%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("sst:000"), bytes("sst:999"));

      assertArrayEquals(bytes("value-0"), db.get(bytes("sst:000")));
      assertArrayEquals(bytes("value-39"), db.get(bytes("sst:039")));
      assertNull(db.get(bytes("sst:020!")));
      assertEquals(3, db.get(Arrays.asList(bytes("sst:001"), bytes("sst:020!"), bytes("sst:039"))).size());

      assertPropertyContains(db, "ldb.sstReadStats", "pointGets=");
      assertPropertyContains(db, "ldb.sstReadStats", "candidateFiles=");
      assertPropertyContains(db, "ldb.sstReadStats", "filterSkips=");
      assertPropertyContains(db, "ldb.sstReadStats", "tableReads=");
      assertPropertyContains(db, "ldb.sstReadStats", "tableRequests=");
      assertPropertyContains(db, "ldb.sstReadStats", "iteratorRequests=");
      assertPropertyContains(db, "ldb.sstReadStats", "directGetRequests=");
      assertPropertyContains(db, "ldb.sstReadStats", "directGetHits=");
      assertPropertyContains(db, "ldb.sstReadStats", "directGetMisses=");
      assertPropertyContains(db, "ldb.sstReadStats", "directGetBatchRequests=");
      assertPropertyContains(db, "ldb.sstReadStats", "directGetBatchKeys=");
      assertPropertyContains(db, "ldb.sstReadStats", "mayContainRequests=");
      String stats = db.getProperty("ldb.sstReadStats");
      assertStatAtLeast(stats, "directGetRequests", 1);
      assertStatAtLeast(stats, "directGetHits", 1);
      assertStatAtLeast(stats, "directGetBatchRequests", 1);
      assertStatAtLeast(stats, "directGetBatchKeys", 1);
      assertStatAtLeast(stats, "filterSkips", 1);
      assertStatAtLeast(stats, "mayContainRequests", 1);
      assertStatAtLeast(stats, "mayContainFalse", 1);
    }
  }

  @Test
  void shouldExposeStorageFormatProperties() throws Exception {
    File legacyDir = new File(tempDir, "storage-format-legacy-db");

    try (LDB db = LDBFactory.factory.open(legacyDir,
        new Options().createIfMissing(true).writeBufferSize(128))) {
      db.put(bytes("legacy"), bytes("v1"));
      db.compactRange(bytes("a"), bytes("z"));

      assertPropertyContains(db, "ldb.tableFormat", "tables=");
      assertPropertyContains(db, "ldb.tableFormat", "legacy=");
      assertPropertyContains(db, "ldb.storageFormat", "table={");
      assertPropertyContains(db, "ldb.storageFormat", "manifest=version-edit-log-v1");
      assertPropertyContains(db, "ldb.storageFormat", "backupMetadata=json-v1");
      assertPropertyContains(db, "ldb.tableFormatPolicy", "newWrites=v1");
      assertPropertyContains(db, "ldb.tableFormatPolicy", "productionState=default-legacy");
      assertPropertyContains(db, "ldb.tableFormatPolicy", "rollback=new-writes-tableFormatVersion-1");
    }

    File v2Dir = new File(tempDir, "storage-format-v2-db");
    try (LDB db = LDBFactory.factory.open(v2Dir,
        new Options().createIfMissing(true).writeBufferSize(128).tableFormatVersion(2))) {
      db.put(bytes("v2"), bytes("value"));
      db.compactRange(bytes("a"), bytes("z"));

      assertPropertyContains(db, "ldb.tableFormat", "v2=");
      assertPropertyContains(db, "ldb.tableFormat", "formatVersion=2");
      assertPropertyContains(db, "ldb.tableFormat", "table.properties");
      assertPropertyContains(db, "ldb.storageFormat", "wal=log-v1");
      assertPropertyContains(db, "ldb.tableFormatPolicy", "newWrites=v2-properties");
      assertPropertyContains(db, "ldb.tableFormatPolicy", "productionState=explicit-v2");
      assertPropertyContains(db, "ldb.tableFormatPolicy", "unknownFeaturePolicy=fail-fast");
    }
  }

  @Test
  void shouldSurviveRepeatedReopenScanSoak() throws Exception {
    File dbDir = new File(tempDir, "reopen-soak-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      for (int i = 0; i < 48; i++) {
        db.put(bytes(String.format("soak:%03d", i)), bytes("value-" + i));
      }
    }

    for (int round = 0; round < 3; round++) {
      try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
        assertArrayEquals(bytes("value-0"), db.get(bytes("soak:000")));
        assertArrayEquals(bytes("value-47"), db.get(bytes("soak:047")));

        int scanned = 0;
        try (SnapshotCursor cursor = db.newSnapshotCursor()) {
          cursor.seek(bytes("soak:000"));
          while (cursor.isValid() && new String(cursor.key(), UTF_8).startsWith("soak:")) {
            scanned++;
            cursor.next();
          }
        }
        assertEquals(48, scanned);
        assertNotNull(db.getProperty("ldb.writeStallStats"));
      }
    }
  }

  @Test
  void shouldKeepMultiColumnFamilyReadsConsistentAcrossCompactionSoak() throws Exception {
    File dbDir = new File(tempDir, "multi-cf-compaction-soak-db");
    Options options = new Options()
        .createIfMissing(true)
        .addColumnFamily(METRICS_CF)
        .writeBufferSize(512)
        .level0CompactionTrigger(3)
        .level0SlowdownWritesTrigger(6)
        .level0StopWritesTrigger(9);

    try (LDB db = LDBFactory.factory.open(dbDir, options)) {
      for (int i = 0; i < 96; i++) {
        db.put(bytes(String.format("user:%03d", i)), bytes("default-" + i));
        db.put(METRICS_CF, bytes(String.format("metric:%03d", i)), bytes("metric-" + i));
        if (i % 8 == 0) {
          db.put(bytes("shared"), bytes("default-" + i));
          db.put(METRICS_CF, bytes("shared"), bytes("metric-" + i));
        }
      }

      db.compactRange(bytes("user:000"), bytes("user:999"));
      db.compactRange(METRICS_CF, bytes("metric:000"), bytes("metric:999"));

      assertArrayEquals(bytes("default-95"), db.get(bytes("user:095")));
      assertArrayEquals(bytes("metric-95"), db.get(METRICS_CF, bytes("metric:095")));
      assertArrayEquals(bytes("default-88"), db.get(bytes("shared")));
      assertArrayEquals(bytes("metric-88"), db.get(METRICS_CF, bytes("shared")));
      assertPropertyContains(db, "ldb.compactionScores", "11:metrics=");
      assertNotNull(db.getProperty("ldb.columnFamily.11.compactionScore"));
      assertNonNegativeProperty(db, "ldb.columnFamily.11.compactionPendingBytes");
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).addColumnFamily(METRICS_CF))) {
      assertArrayEquals(bytes("default-0"), reopened.get(bytes("user:000")));
      assertArrayEquals(bytes("default-95"), reopened.get(bytes("user:095")));
      assertArrayEquals(bytes("metric-0"), reopened.get(METRICS_CF, bytes("metric:000")));
      assertArrayEquals(bytes("metric-95"), reopened.get(METRICS_CF, bytes("metric:095")));
      assertArrayEquals(bytes("default-88"), reopened.get(bytes("shared")));
      assertArrayEquals(bytes("metric-88"), reopened.get(METRICS_CF, bytes("shared")));
    }
  }

  @Test
  void shouldKeepReadsConsistentDuringRateLimitedCompaction() throws Exception {
    File dbDir = new File(tempDir, "rate-limited-compaction-read-soak-db");
    byte[] largeValue = repeatedValue('x', 512);

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(512)
            .compactionRateLimitBytesPerSecond(64 * 1024)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(8)
            .level0StopWritesTrigger(12))) {
      for (int i = 0; i < 96; i++) {
        db.put(bytes(String.format("live:%03d", i)), largeValue);
      }

      ExecutorService executor = Executors.newSingleThreadExecutor();
      Future<?> compacting = executor.submit(new Runnable() {
        @Override
        public void run() {
          db.compactRange(bytes("live:000"), bytes("live:999"));
        }
      });

      try {
        int observedReads = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!compacting.isDone() && System.nanoTime() < deadline) {
          assertArrayEquals(largeValue, db.get(bytes("live:000")));
          assertArrayEquals(largeValue, db.get(bytes("live:095")));
          assertEquals(96, scanPrefix(db, "live:"));
          observedReads++;
        }
        compacting.get(10, TimeUnit.SECONDS);
        assertTrue(observedReads > 0);
      } finally {
        executor.shutdownNow();
      }

      assertArrayEquals(largeValue, db.get(bytes("live:000")));
      assertArrayEquals(largeValue, db.get(bytes("live:095")));
      assertEquals(96, scanPrefix(db, "live:"));
      assertTrue(Long.parseLong(db.getProperty("ldb.compaction.throttleCount")) > 0);
    }
  }

  @Test
  void shouldExposeStableSignalsAfterWriteBurstSoak() throws Exception {
    File dbDir = new File(tempDir, "write-burst-observability-soak-db");
    byte[] value = repeatedValue('b', 256);

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(512)
            .compactionRateLimitBytesPerSecond(256 * 1024)
            .slowOperationThresholdMicros(1)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(6)
            .level0StopWritesTrigger(12))) {
      for (int round = 0; round < 4; round++) {
        for (int i = 0; i < 40; i++) {
          db.put(bytes(String.format("burst:%02d:%03d", round, i)), value);
        }
        assertNotNull(db.getProperty("ldb.compactionBacklog"));
        assertNotNull(db.getProperty("ldb.compactionCandidate"));
        assertNonNegativeProperty(db, "ldb.compactionPendingBytes");
        assertNonNegativeProperty(db, "ldb.writeStall.slowdownCount");
        assertNonNegativeProperty(db, "ldb.writeStall.level0StopWaitCount");
        assertPropertyContains(db, "ldb.writeStallStats", "level0StopWait.count=");
      }

      assertArrayEquals(value, db.get(bytes("burst:00:000")));
      assertArrayEquals(value, db.get(bytes("burst:03:039")));
      assertEquals("160", db.getProperty("ldb.operation.write.count"));
      assertTrue(Long.parseLong(db.getProperty("ldb.operation.write.slowCount")) > 0);
      assertPropertyContains(db, "ldb.operationStats", "write.count=");
      assertPropertyContains(db, "ldb.compactionStats", "outputBytes=");
      assertNonNegativeProperty(db, "ldb.compaction.throttleCount");
      assertNonNegativeProperty(db, "ldb.compaction.cleanupFileCount");
    }
  }

  @Test
  void shouldResumeWritesAfterCompactionSuspensionUnderPressure() throws Exception {
    File dbDir = new File(tempDir, "suspend-resume-write-pressure-db");
    byte[] value = repeatedValue('s', 512);

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(256)
            .compactionSuspendTimeoutMillis(1_000)
            .level0StopWritesTrigger(30)
            .level0SlowdownWritesTrigger(20)
            .level0CompactionTrigger(10))) {
      db.suspendCompactions();
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Future<?> writer = executor.submit(new Runnable() {
        @Override
        public void run() {
          for (int i = 0; i < 20; i++) {
            db.put(bytes(String.format("suspend:%03d", i)), value);
          }
        }
      });

      try {
        TimeUnit.MILLISECONDS.sleep(100);
        assertFalse(writer.isDone(), "writer should wait while compactions are suspended");
      } finally {
        db.resumeCompactions();
      }

      try {
        writer.get(10, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }

      assertArrayEquals(value, db.get(bytes("suspend:000")));
      assertArrayEquals(value, db.get(bytes("suspend:019")));
      assertTrue(Long.parseLong(db.getProperty("ldb.writeStall.immutableWaitCount")) > 0);
      assertPropertyContains(db, "ldb.writeStallStats", "immutableWait.count=");
    }
  }

  @Test
  void shouldExposeFileCapacityWatermarksAcrossCompactionAndReopen() throws Exception {
    File dbDir = new File(tempDir, "capacity-watermark-soak-db");
    byte[] value = repeatedValue('w', 384);

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(512)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(6)
            .level0StopWritesTrigger(12))) {
      for (int i = 0; i < 80; i++) {
        db.put(bytes(String.format("watermark:%03d", i)), value);
      }

      assertTrue(longProperty(db, "ldb.totalBytes") > 0);
      assertTrue(longProperty(db, "ldb.walBytes") > 0);
      assertPropertyContains(db, "ldb.fileBytes", "log=");
      assertNonNegativeProperty(db, "ldb.compactionPendingBytes");

      db.compactRange(bytes("watermark:000"), bytes("watermark:999"));

      assertTrue(longProperty(db, "ldb.sstBytes") > 0);
      assertTrue(longProperty(db, "ldb.totalBytes") >= longProperty(db, "ldb.sstBytes"));
      assertTrue(longProperty(db, "ldb.compaction.outputBytes") > 0);
      assertPropertyContains(db, "ldb.fileBytes", "table=");
      assertPropertyContains(db, "ldb.compactionStats", "outputBytes=");
      assertArrayEquals(value, db.get(bytes("watermark:079")));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(value, reopened.get(bytes("watermark:000")));
      assertArrayEquals(value, reopened.get(bytes("watermark:079")));
      assertTrue(longProperty(reopened, "ldb.totalBytes") > 0);
      assertTrue(longProperty(reopened, "ldb.sstBytes") > 0);
      assertNonNegativeProperty(reopened, "ldb.compactionPendingBytes");
      assertPropertyContains(reopened, "ldb.fileBytes", "table=");
    }
  }

  @Test
  void shouldReleaseLockAfterCloseTimeoutDuringCompactionSuspension() throws Exception {
    File dbDir = new File(tempDir, "close-timeout-suspension-db");
    LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .closeTimeoutMillis(50)
            .compactionSuspendTimeoutMillis(1_000));
    db.put(bytes("stable"), bytes("value"));
    db.suspendCompactions();

    DBException error = assertThrows(DBException.class, db::close);
    assertTrue(error.getMessage().contains("Failed to close LDB cleanly"), error.getMessage());

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value"), reopened.get(bytes("stable")));
      assertNotNull(reopened.getProperty("ldb.databaseDir"));
    }
  }

  private static void assertPropertyContains(LDB db, String property, String expected) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    assertTrue(value.contains(expected), property + "=" + value);
  }

  private static void assertNonNegativeProperty(LDB db, String property) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    assertTrue(Long.parseLong(value) >= 0, property + "=" + value);
  }

  private static void assertOperationCount(LDB db, String operation, long expected) {
    assertEquals(Long.toString(expected), db.getProperty("ldb.operation." + operation + ".count"));
    assertNotNull(db.getProperty("ldb.operation." + operation + ".avgMicros"));
    assertNotNull(db.getProperty("ldb.operation." + operation + ".slowCount"));
  }

  private static long longProperty(LDB db, String property) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    return Long.parseLong(value);
  }

  private static void assertStatAtLeast(String stats, String key, long minimum) {
    assertNotNull(stats, key);
    String prefix = key + "=";
    for (String part : stats.split(",")) {
      if (part.startsWith(prefix)) {
        assertTrue(Long.parseLong(part.substring(prefix.length())) >= minimum, stats);
        return;
      }
    }
    fail("Missing stat " + key + " in " + stats);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static byte[] repeatedValue(char value, int count) {
    byte[] bytes = new byte[count];
    for (int i = 0; i < count; i++) {
      bytes[i] = (byte) value;
    }
    return bytes;
  }

  private static int scanPrefix(LDB db, String prefix) {
    int scanned = 0;
    try (SnapshotCursor cursor = db.newSnapshotCursor()) {
      cursor.seek(bytes(prefix));
      while (cursor.isValid() && new String(cursor.key(), UTF_8).startsWith(prefix)) {
        scanned++;
        cursor.next();
      }
    }
    return scanned;
  }

  private static final class TestColumnFamily implements LdbColumnFamily {
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
