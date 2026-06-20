package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.InternalKey;
import net.xdob.vexra.ldb.impl.ValueType;
import net.xdob.vexra.ldb.util.Slices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.8.0 table properties 格式演进测试。
 *
 * 这些用例直接覆盖 SST/table 层：默认 v1 不写 properties，显式 v2 会写入
 * properties block，未知不兼容 feature 必须 fail-fast。测试不依赖完整 DB 打开
 * 流程，便于把文件格式约束固定在最小单元。
 */
class TablePropertiesTest {
  @TempDir
  File tempDir;

  @Test
  void shouldTreatDefaultTableAsLegacyV1() throws Exception {
    File tableFile = new File(tempDir, "legacy.sst");

    writeTable(tableFile, new Options());

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      assertTrue(properties.isLegacy());
      assertEquals(1, properties.getFormatVersion());
      assertTrue(properties.getValues().isEmpty());
      assertTrue(properties.getCompatibleFeatures().isEmpty());
      assertTrue(properties.getIncompatibleFeatures().isEmpty());
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldWriteAndReadV2PropertiesWhenOptedIn() throws Exception {
    File tableFile = new File(tempDir, "v2.sst");

    writeTable(tableFile, new Options().tableFormatVersion(2));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertFalse(properties.isLegacy());
      assertEquals(2, properties.getFormatVersion());
      assertTrue(properties.getCompatibleFeatures().contains("table.properties"));
      assertTrue(properties.getCompatibleFeatures().contains("block.trailer.crc32c"));
      assertTrue(properties.getCompatibleFeatures().contains("index.single-level"));
      assertTrue(properties.getIncompatibleFeatures().isEmpty());
      assertEquals("2", values.get(TableProperties.FORMAT_VERSION_KEY));
      assertEquals("2", values.get("ldb.table.entry_count"));
      assertEquals("1", values.get("ldb.table.data_block_count"));
      assertEquals("single-level", values.get("ldb.table.index_type"));
      assertEquals("crc32c-block-trailer", values.get("ldb.table.checksum"));
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldFailFastForUnknownIncompatibleFeature() {
    BlockBuilder builder = new BlockBuilder(256, 1, new BytewiseComparator());
    builder.add(Slices.utf8Slice(TableProperties.COMPATIBLE_FEATURES_KEY), Slices.utf8Slice("table.properties"));
    builder.add(Slices.utf8Slice(TableProperties.INCOMPATIBLE_FEATURES_KEY), Slices.utf8Slice("future.block.encoding"));
    builder.add(Slices.utf8Slice(TableProperties.FORMAT_VERSION_KEY), Slices.utf8Slice("2"));

    TableProperties properties = TableProperties.read(new Block(builder.finish(), new BytewiseComparator()));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> properties.validateReadable(true, "future.sst"));
    assertTrue(error.getMessage().contains("future.block.encoding"));
    assertDoesNotThrow(() -> properties.validateReadable(false, "future.sst"));
  }

  @Test
  void shouldFailFastForFutureTableFormatVersion() {
    BlockBuilder builder = new BlockBuilder(256, 1, new BytewiseComparator());
    builder.add(Slices.utf8Slice(TableProperties.COMPATIBLE_FEATURES_KEY), Slices.utf8Slice("table.properties"));
    builder.add(Slices.utf8Slice(TableProperties.INCOMPATIBLE_FEATURES_KEY), Slices.utf8Slice(""));
    builder.add(Slices.utf8Slice(TableProperties.FORMAT_VERSION_KEY), Slices.utf8Slice("3"));

    TableProperties properties = TableProperties.read(new Block(builder.finish(), new BytewiseComparator()));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> properties.validateReadable(true, "v3.sst"));
    assertTrue(error.getMessage().contains("unsupported table format version 3"));
    assertDoesNotThrow(() -> properties.validateReadable(false, "v3.sst"));
  }

  @Test
  void shouldRejectMalformedTableFormatVersionProperty() {
    BlockBuilder builder = new BlockBuilder(256, 1, new BytewiseComparator());
    builder.add(Slices.utf8Slice(TableProperties.COMPATIBLE_FEATURES_KEY), Slices.utf8Slice("table.properties"));
    builder.add(Slices.utf8Slice(TableProperties.FORMAT_VERSION_KEY), Slices.utf8Slice("not-a-number"));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> TableProperties.read(new Block(builder.finish(), new BytewiseComparator())));
    assertTrue(error.getMessage().contains("Invalid table format version: not-a-number"));
  }

  @Test
  void shouldRejectNonPositiveTableFormatVersionProperty() {
    assertInvalidTableFormatVersion("0");
    assertInvalidTableFormatVersion("-1");
  }

  private static void assertInvalidTableFormatVersion(String value) {
    BlockBuilder builder = new BlockBuilder(256, 1, new BytewiseComparator());
    builder.add(Slices.utf8Slice(TableProperties.COMPATIBLE_FEATURES_KEY), Slices.utf8Slice("table.properties"));
    builder.add(Slices.utf8Slice(TableProperties.FORMAT_VERSION_KEY), Slices.utf8Slice(value));
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> TableProperties.read(new Block(builder.finish(), new BytewiseComparator())));
    assertTrue(error.getMessage().contains("Invalid table format version: " + value));
  }

  @Test
  void shouldRejectUnsupportedTableFormatVersionOption() {
    assertThrows(IllegalArgumentException.class, () -> new Options().tableFormatVersion(0));
    assertThrows(IllegalArgumentException.class, () -> new Options().tableFormatVersion(3));
    assertEquals(2, new Options().tableFormatVersion(2).tableFormatVersion());
  }

  private static void writeTable(File tableFile, Options options) throws Exception {
    try (RandomAccessFile file = new RandomAccessFile(tableFile, "rw");
         FileChannel channel = file.getChannel()) {
      TableBuilder builder = new TableBuilder(options, channel, new BytewiseComparator());
      builder.add(internalKey("a", 2), Slices.utf8Slice("value-a"));
      builder.add(internalKey("b", 1), Slices.utf8Slice("value-b"));
      builder.finish();
    }
  }

  private static net.xdob.vexra.ldb.util.Slice internalKey(String key, long sequenceNumber) {
    return new InternalKey(Slices.utf8Slice(key), sequenceNumber, ValueType.VALUE).encode();
  }

  private static Table openTable(File tableFile, Options options) throws Exception {
    RandomAccessFile file = new RandomAccessFile(tableFile, "r");
    FileChannel channel = file.getChannel();
    try {
      return new FileChannelTable(
          tableFile.getAbsolutePath(),
          channel,
          new BytewiseComparator(),
          true,
          options,
          null);
    } catch (Throwable t) {
      channel.close();
      file.close();
      throw t;
    }
  }
}
