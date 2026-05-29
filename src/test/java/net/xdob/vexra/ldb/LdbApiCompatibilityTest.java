package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十六阶段 API 兼容与生态测试。
 *
 * 这些用例不引入新的 RocksDB 扩展语义，只验证 LDB 能通过稳定 property
 * 向调用方说明当前 Options 映射、已支持能力、显式不支持能力和统计入口。
 */
class LdbApiCompatibilityTest {
  private static final LdbColumnFamily META_CF = new TestColumnFamily(16, "api-meta");

  @TempDir
  File tempDir;

  @Test
  void shouldExposeRocksDbStyleOptionsMappingAndEffectiveValues() throws Exception {
    File dbDir = new File(tempDir, "api-options-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .addColumnFamily(META_CF)
            .writeBufferSize(1024)
            .maxOpenFiles(64)
            .blockRestartInterval(8)
            .blockSize(2048)
            .compressionType(CompressionType.LZ4)
            .verifyChecksums(false)
            .cacheSize(4096)
            .cacheBlocks(false)
            .blockCacheSize(128)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(6)
            .level0StopWritesTrigger(9)
            .compactionRateLimitBytesPerSecond(12345)
            .compactionSuspendTimeoutMillis(1500)
            .closeTimeoutMillis(2000)
            .slowOperationThresholdMicros(7)
            .forceLogOnClose(true)
            .forceSstOnFlush(true))) {
      assertPropertyContains(db, "ldb.api.compatibility", "rocksdbOptions=partial");
      assertPropertyContains(db, "ldb.api.compatibility", "unsupportedConfig=rejected");
      assertPropertyContains(db, "ldb.api.compatibility", "statistics=properties");
      assertPropertyContains(db, "ldb.api.compatibility", "ldbToolCommands=partial");
      assertPropertyContains(db, "ldb.api.optionsMapping", "createIfMissing=supported");
      assertPropertyContains(db, "ldb.api.optionsMapping", "columnFamilies=supported");
      assertPropertyContains(db, "ldb.api.optionsMapping", "mergeOperator=unsupported");
      assertPropertyContains(db, "ldb.api.optionsMapping", "prefixExtractor=unsupported");
      assertPropertyContains(db, "ldb.api.optionsMapping", "rocksdbToolCommands=unsupported");

      assertPropertyContains(db, "ldb.api.optionValues", "writeBufferSize=1024");
      assertPropertyContains(db, "ldb.api.optionValues", "maxOpenFiles=64");
      assertPropertyContains(db, "ldb.api.optionValues", "compressionType=LZ4");
      assertPropertyContains(db, "ldb.api.optionValues", "verifyChecksums=false");
      assertPropertyContains(db, "ldb.api.optionValues", "cacheBlocks=false");
      assertPropertyContains(db, "ldb.api.optionValues", "columnFamilyCount=2");
      assertPropertyContains(db, "ldb.api.optionValues", "level0CompactionTrigger=3");
      assertPropertyContains(db, "ldb.api.optionValues", "compactionRateLimitBytesPerSecond=12345");
      assertPropertyContains(db, "ldb.api.optionValues", "forceLogOnClose=true");
      assertPropertyContains(db, "ldb.api.optionValues", "forceSstOnFlush=true");
    }
  }

  @Test
  void shouldExposeSupportedUnsupportedFeaturesAndStableStatisticsProperties() throws Exception {
    File dbDir = new File(tempDir, "api-features-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
      assertArrayEquals(bytes("v"), db.get(bytes("k")));

      assertPropertyContains(db, "ldb.api.supportedFeatures", "rangeDelete");
      assertPropertyContains(db, "ldb.api.supportedFeatures", "readOnly");
      assertPropertyContains(db, "ldb.api.supportedFeatures", "snapshotCursor");
      assertPropertyContains(db, "ldb.api.supportedFeatures", "ldbToolCheck");
      assertPropertyContains(db, "ldb.api.supportedFeatures", "ldbToolRepair");
      assertPropertyContains(db, "ldb.api.supportedFeatures", "ldbToolBackup");
      assertPropertyContains(db, "ldb.api.supportedFeatures", "ldbToolRestore");
      assertPropertyContains(db, "ldb.api.supportedFeatures", "ldbToolCheckpoint");
      assertPropertyContains(db, "ldb.api.unsupportedFeatures", "mergeOperator");
      assertPropertyContains(db, "ldb.api.unsupportedFeatures", "prefixExtractor");
      assertPropertyContains(db, "ldb.api.unsupportedFeatures", "rocksdbToolCommands");

      assertPropertyContains(db, "ldb.operationStats", "get.count=");
      assertPropertyContains(db, "ldb.compactionStats", "runCount=");
      assertPropertyContains(db, "ldb.walPolicy", "scheme=global");
      assertPropertyContains(db, "ldb.snapshotCursorStats", "openCount=");
      assertNull(db.getProperty("ldb.api.unknown"));
    }
  }

  @Test
  void shouldRejectInvalidCompatibilityOptionsWithCallerVisibleErrors() {
    assertThrows(IllegalArgumentException.class, () -> new Options().blockCacheSize(0));
    assertThrows(IllegalArgumentException.class, () -> new Options().compressionType(null));
    assertThrows(IllegalArgumentException.class, () -> new Options().level0CompactionTrigger(0));
    assertThrows(IllegalArgumentException.class, () -> new Options().level0SlowdownWritesTrigger(0));
    assertThrows(IllegalArgumentException.class, () -> new Options().level0StopWritesTrigger(0));
    assertThrows(IllegalArgumentException.class, () -> new Options().compactionRateLimitBytesPerSecond(-1));
  }

  private static void assertPropertyContains(LDB db, String property, String expected) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    assertTrue(value.contains(expected), property + "=" + value);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
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
