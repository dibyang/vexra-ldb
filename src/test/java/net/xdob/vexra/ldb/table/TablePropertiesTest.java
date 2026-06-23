package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.BloomFilterPolicy;
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
  void shouldWriteAndReadV3PropertiesSkeletonWhenOptedIn() throws Exception {
    File tableFile = new File(tempDir, "v3-disabled.sst");

    writeTable(tableFile, new Options().tableFormatVersion(3).blockLocalIndexInterval(4));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertFalse(properties.isLegacy());
      assertEquals(3, properties.getFormatVersion());
      assertTrue(properties.getCompatibleFeatures().contains("table.properties"));
      assertTrue(properties.getIncompatibleFeatures().isEmpty());
      assertEquals("3", values.get(TableProperties.FORMAT_VERSION_KEY));
      assertEquals("false", values.get(TableProperties.BLOCK_LOCAL_INDEX_KEY));
      assertEquals("", values.get(TableProperties.BLOCK_LOCAL_INDEX_VERSION_KEY));
      assertEquals("disabled", values.get(TableProperties.BLOCK_LOCAL_INDEX_POLICY_KEY));
      assertEquals("4", values.get(TableProperties.BLOCK_LOCAL_INDEX_INTERVAL_KEY));
      assertEquals("0", values.get(TableProperties.BLOCK_LOCAL_INDEX_BYTES_KEY));
      assertEquals("0", values.get(TableProperties.BLOCK_LOCAL_INDEX_COVERED_BLOCKS_KEY));
      assertDoesNotThrow(() -> properties.validateReadable(true, "v3-disabled.sst"));
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldWriteFilterSelfDescriptionWhenBloomFilterIsEnabled() throws Exception {
    File tableFile = new File(tempDir, "v3-filter.sst");

    writeTable(tableFile, new Options()
        .tableFormatVersion(3)
        .filterPolicy(new BloomFilterPolicy(10)));

    Table table = openTable(tableFile, new Options().filterPolicy(new BloomFilterPolicy(10)));
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertEquals("vexra.BuiltinBloomFilter2", values.get(TableProperties.FILTER_POLICY_KEY));
      assertEquals("full-key", values.get(TableProperties.FILTER_SCOPE_KEY));
      assertEquals("2", values.get(TableProperties.FILTER_KEY_COUNT_KEY));
      assertEquals("10", values.get(TableProperties.FILTER_BITS_PER_KEY_KEY));
      assertTrue(Long.parseLong(values.get(TableProperties.FILTER_BLOCK_BYTES_KEY)) > 0);
      assertFalse(table.mayContain(Slices.utf8Slice("a-miss")));
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldWriteAndReadV3BlockLocalIndexDirectoryWhenOptedIn() throws Exception {
    File tableFile = new File(tempDir, "v3-enabled.sst");

    writeDenseLargeValueTable(tableFile, new Options()
        .blockRestartInterval(1)
        .blockSize(32 * 1024)
        .tableFormatVersion(3)
        .writeBlockLocalIndex(true)
        .blockLocalIndexInterval(1));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertEquals(3, properties.getFormatVersion());
      assertTrue(properties.getIncompatibleFeatures().contains(TableProperties.BLOCK_LOCAL_INDEX_FEATURE));
      assertEquals("true", values.get(TableProperties.BLOCK_LOCAL_INDEX_KEY));
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_VERSION_KEY));
      assertEquals("restart-anchor", values.get(TableProperties.BLOCK_LOCAL_INDEX_POLICY_KEY));
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_INTERVAL_KEY));
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_COVERED_BLOCKS_KEY));
      assertTrue(Long.parseLong(values.get(TableProperties.BLOCK_LOCAL_INDEX_DATA_BLOCK_BYTES_KEY)) > 0);
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_CANDIDATE_BLOCKS_KEY));
      assertEquals("0", values.get(TableProperties.BLOCK_LOCAL_INDEX_SKIPPED_BLOCKS_KEY));
      assertTrue(Long.parseLong(values.get(TableProperties.BLOCK_LOCAL_INDEX_SPACE_AMPLIFICATION_PPM_KEY)) > 0);
      assertTrue(values.get(TableProperties.BLOCK_LOCAL_INDEX_ADMISSION_POLICY_KEY).contains("max-space-ppm=250000"));
      assertFalse(table.getBlockLocalIndexDirectory().isEmpty());

      BlockHandle localIndexHandle = table.getBlockLocalIndexDirectory().values().iterator().next();
      Block localIndexBlock = table.openBlock(localIndexHandle);
      BlockIterator iterator = localIndexBlock.iterator();
      assertTrue(iterator.hasNext());
      BlockEntry firstAnchor = iterator.next();
      assertTrue(new String(firstAnchor.getValue().getBytes(), java.nio.charset.StandardCharsets.UTF_8).contains(":"));
      assertEquals(internalKey("k000", 1), table.get(internalKey("k000", 1)).getKey());
      assertEquals(internalKey("k001", 1), table.get(internalKey("k001", 1)).getKey());
      assertEquals(2, table.get(java.util.Arrays.asList(internalKey("k000", 1), internalKey("k001", 1))).size());
      assertEquals(internalKey("k000", 1),
          table.get(java.util.Arrays.asList(internalKey("k000", 1), internalKey("k001", 1))).get(0).getKey());
      assertEquals(internalKey("k001", 1),
          table.get(java.util.Arrays.asList(internalKey("k000", 1), internalKey("k001", 1))).get(1).getKey());
      String stats = table.getBlockLocalIndexStats();
      assertTrue(stats.contains("directoryEntries=1"));
      assertTrue(stats.contains("seekCount="));
      assertTrue(stats.contains("hitCount="));
      assertTrue(stats.contains("seekCount=2"));
      assertTrue(stats.contains("hitCount=2"));

      java.util.List<net.xdob.vexra.ldb.util.Slice> denseLookup = new java.util.ArrayList<>();
      for (int i = 0; i < 8; i++) {
        denseLookup.add(internalKey(String.format(java.util.Locale.ROOT, "k%03d", i), 1));
      }
      assertEquals(8, table.get(denseLookup).size());
      String denseStats = table.getBlockLocalIndexStats();
      assertTrue(denseStats.contains("seekCount=10"));
      assertTrue(denseStats.contains("hitCount=10"));
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldFallbackToDataBlockSeekWhenBlockLocalIndexBlockIsCorrupt() throws Exception {
    File tableFile = new File(tempDir, "v3-corrupt-local-index.sst");

    writeDenseLargeValueTable(tableFile, new Options()
        .blockRestartInterval(1)
        .blockSize(32 * 1024)
        .tableFormatVersion(3)
        .writeBlockLocalIndex(true)
        .blockLocalIndexInterval(1));

    BlockHandle localIndexHandle;
    Table table = openTable(tableFile, new Options());
    try {
      localIndexHandle = table.getBlockLocalIndexDirectory().values().iterator().next();
    } finally {
      table.closer().call();
    }

    corruptByte(tableFile, localIndexHandle.getOffset() + Math.max(0, localIndexHandle.getDataSize() / 2));

    Table fallbackTable = openTable(tableFile, new Options());
    try {
      assertEquals(internalKey("k001", 1), fallbackTable.get(internalKey("k001", 1)).getKey());
      String stats = fallbackTable.getBlockLocalIndexStats();
      assertTrue(stats.contains("seekCount=1"), stats);
      assertTrue(stats.contains("hitCount=0"), stats);
      assertTrue(stats.contains("fallbackCount=1"), stats);
    } finally {
      fallbackTable.closer().call();
    }
  }

  @Test
  void shouldFailFastWhenBlockLocalIndexMetaindexEntryIsMissing() throws Exception {
    File tableFile = new File(tempDir, "v3-missing-local-index-directory.sst");

    writeDenseLargeValueTable(tableFile, new Options()
        .blockRestartInterval(1)
        .blockSize(32 * 1024)
        .tableFormatVersion(3)
        .writeBlockLocalIndex(true)
        .blockLocalIndexInterval(1));

    replaceAsciiOccurrenceInMetaindexBlock(tableFile, "block_local_index", "xlock_local_index");

    Exception error = assertThrows(Exception.class, () -> openTable(tableFile, new Options()));
    assertTrue(error.getMessage().contains("BLOCK_LOCAL_INDEX_DIRECTORY_MISSING"), error.getMessage());
  }

  @Test
  void shouldWriteAndReadV4EntryAnchorIndexDirectoryWhenOptedIn() throws Exception {
    File tableFile = new File(tempDir, "v4-entry-anchor.sst");

    writeDenseTable(tableFile, new Options()
        .blockRestartInterval(4)
        .tableFormatVersion(4)
        .writeEntryAnchorIndex(true)
        .entryAnchorIndexInterval(1)
        .entryAnchorIndexAdmissionMinAnchors(1));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertEquals(4, properties.getFormatVersion());
      assertTrue(properties.getIncompatibleFeatures().contains(TableProperties.ENTRY_ANCHOR_INDEX_FEATURE));
      assertEquals("true", values.get(TableProperties.ENTRY_ANCHOR_INDEX_KEY));
      assertEquals("1", values.get(TableProperties.ENTRY_ANCHOR_INDEX_VERSION_KEY));
      assertEquals("sparse-entry-anchor", values.get(TableProperties.ENTRY_ANCHOR_INDEX_POLICY_KEY));
      assertEquals("1", values.get(TableProperties.ENTRY_ANCHOR_INDEX_INTERVAL_KEY));
      assertEquals("1", values.get(TableProperties.ENTRY_ANCHOR_INDEX_COVERED_BLOCKS_KEY));
      assertTrue(Long.parseLong(values.get(TableProperties.ENTRY_ANCHOR_INDEX_BYTES_KEY)) > 0);
      assertTrue(Long.parseLong(values.get(TableProperties.ENTRY_ANCHOR_INDEX_ANCHOR_COUNT_KEY)) > 0);

      assertEquals(internalKey("k001", 1), table.get(internalKey("k001", 1)).getKey());
      String stats = table.getEntryAnchorIndexStats();
      assertTrue(stats.contains("declared=true"));
      assertTrue(stats.contains("directoryHandlePresent=true"));
      assertTrue(stats.contains("directoryLoaded=false"));
      assertTrue(stats.contains("seekCount=0"));
      assertTrue(stats.contains("hitCount=0"));
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
    builder.add(Slices.utf8Slice(TableProperties.FORMAT_VERSION_KEY), Slices.utf8Slice("5"));

    TableProperties properties = TableProperties.read(new Block(builder.finish(), new BytewiseComparator()));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> properties.validateReadable(true, "v5.sst"));
    assertTrue(error.getMessage().contains("unsupported table format version 5"));
    assertDoesNotThrow(() -> properties.validateReadable(false, "v5.sst"));
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
    assertEquals(2, new Options().tableFormatVersion(2).tableFormatVersion());
    assertEquals(3, new Options().tableFormatVersion(3).tableFormatVersion());
    assertEquals(4, new Options().tableFormatVersion(4).tableFormatVersion());
    assertThrows(IllegalArgumentException.class, () -> new Options().tableFormatVersion(5));
      assertThrows(IllegalArgumentException.class, () -> new Options().blockLocalIndexInterval(0));
      assertThrows(IllegalArgumentException.class, () -> new Options().entryAnchorIndexInterval(0));
      assertThrows(IllegalArgumentException.class, () -> new Options().entryAnchorIndexAdmissionMinAnchors(0));
      assertThrows(IllegalArgumentException.class, () -> new Options().inlineBlockSeekIndexInterval(0));
      assertThrows(IllegalArgumentException.class, () -> new Options().inlineBlockSeekIndexAdmissionMinAnchors(0));
  }

  @Test
  void shouldRejectIncompatibleFeaturesWithoutPropertiesGuard() {
    assertThrows(IllegalArgumentException.class, () -> writeTable(
        new File(tempDir, "v3-local-index-no-properties.sst"),
        new Options()
            .tableFormatVersion(3)
            .writeTableProperties(false)
            .writeBlockLocalIndex(true)));

    assertThrows(IllegalArgumentException.class, () -> writeTable(
        new File(tempDir, "v4-entry-anchor-no-properties.sst"),
        new Options()
            .tableFormatVersion(4)
            .writeTableProperties(false)
            .writeEntryAnchorIndex(true)));

    assertThrows(IllegalArgumentException.class, () -> writeTable(
        new File(tempDir, "v4-inline-seek-no-properties.sst"),
        new Options()
            .tableFormatVersion(4)
            .writeTableProperties(false)
            .writeInlineBlockSeekIndex(true)));
  }

  @Test
  void shouldSkipBlockLocalIndexWhenSpaceAmplificationWouldBeTooHigh() throws Exception {
    File tableFile = new File(tempDir, "v3-space-guard-skip.sst");

    writeDenseTable(tableFile, new Options()
        .blockRestartInterval(1)
        .tableFormatVersion(3)
        .writeBlockLocalIndex(true)
        .blockLocalIndexInterval(1));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertEquals(3, properties.getFormatVersion());
      assertFalse(properties.getIncompatibleFeatures().contains(TableProperties.BLOCK_LOCAL_INDEX_FEATURE));
      assertEquals("false", values.get(TableProperties.BLOCK_LOCAL_INDEX_KEY));
      assertEquals("0", values.get(TableProperties.BLOCK_LOCAL_INDEX_COVERED_BLOCKS_KEY));
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_CANDIDATE_BLOCKS_KEY));
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_SKIPPED_BLOCKS_KEY));
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_SKIPPED_SPACE_BLOCKS_KEY));
      assertEquals("0", values.get(TableProperties.BLOCK_LOCAL_INDEX_SPACE_AMPLIFICATION_PPM_KEY));
      assertTrue(table.getBlockLocalIndexDirectory().isEmpty());
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldKeepLegacyFormatWhenAutoBlockLocalIndexWritesNoIndex() throws Exception {
    File tableFile = new File(tempDir, "auto-local-index-skipped-legacy.sst");

    assertTrue(new Options().writeBlockLocalIndex());

    writeDenseTable(tableFile, new Options()
        .blockRestartInterval(1)
        .blockLocalIndexInterval(1));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();

      assertTrue(properties.isLegacy());
      assertEquals(1, properties.getFormatVersion());
      assertTrue(properties.getIncompatibleFeatures().isEmpty());
      assertTrue(table.getBlockLocalIndexDirectory().isEmpty());
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldPromoteAutoBlockLocalIndexToV3WhenIndexIsWritten() throws Exception {
    File tableFile = new File(tempDir, "auto-local-index-promoted-v3.sst");

    writeDenseLargeValueTable(tableFile, new Options()
        .blockRestartInterval(1)
        .blockSize(32 * 1024)
        .writeBlockLocalIndex(true)
        .blockLocalIndexInterval(1));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertFalse(properties.isLegacy());
      assertEquals(3, properties.getFormatVersion());
      assertTrue(properties.getIncompatibleFeatures().contains(TableProperties.BLOCK_LOCAL_INDEX_FEATURE));
      assertEquals("true", values.get(TableProperties.BLOCK_LOCAL_INDEX_KEY));
      assertEquals("1", values.get(TableProperties.BLOCK_LOCAL_INDEX_VERSION_KEY));
      assertFalse(table.getBlockLocalIndexDirectory().isEmpty());
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldWriteAndReadV4InlineBlockSeekIndexWhenOptedIn() throws Exception {
    File tableFile = new File(tempDir, "v4-inline-seek.sst");

    writeDenseTable(tableFile, new Options()
        .blockRestartInterval(4)
        .tableFormatVersion(4)
        .writeInlineBlockSeekIndex(true)
        .inlineBlockSeekIndexInterval(2)
        .inlineBlockSeekIndexAdmissionMinAnchors(1));

    Table table = openTable(tableFile, new Options());
    try {
      TableProperties properties = table.getProperties();
      Map<String, String> values = properties.getValues();

      assertEquals(4, properties.getFormatVersion());
      assertTrue(properties.getIncompatibleFeatures().contains(TableProperties.INLINE_BLOCK_SEEK_INDEX_FEATURE));
      assertEquals("true", values.get(TableProperties.INLINE_BLOCK_SEEK_INDEX_KEY));
      assertEquals("1", values.get(TableProperties.INLINE_BLOCK_SEEK_INDEX_VERSION_KEY));
      assertEquals("inline-sparse-anchor", values.get(TableProperties.INLINE_BLOCK_SEEK_INDEX_POLICY_KEY));
      assertEquals("2", values.get(TableProperties.INLINE_BLOCK_SEEK_INDEX_INTERVAL_KEY));
      assertEquals("1", values.get(TableProperties.INLINE_BLOCK_SEEK_INDEX_COVERED_BLOCKS_KEY));
      assertTrue(Long.parseLong(values.get(TableProperties.INLINE_BLOCK_SEEK_INDEX_BYTES_KEY)) > 0);
      assertTrue(Long.parseLong(values.get(TableProperties.INLINE_BLOCK_SEEK_INDEX_ANCHOR_COUNT_KEY)) > 0);
      assertEquals(internalKey("k000", 1), table.get(internalKey("k000", 1)).getKey());
      assertEquals(internalKey("k017", 1), table.get(internalKey("k017", 1)).getKey());
    } finally {
      table.closer().call();
    }
  }

  @Test
  void shouldIncludeInlineSeekIndexInBlockSizeEstimate() {
    BlockBuilder builder = new BlockBuilder(
        256,
        4,
        new BytewiseComparator(),
        true,
        1,
        1);
    builder.add(Slices.utf8Slice("k000"), Slices.utf8Slice("value-0"));
    builder.add(Slices.utf8Slice("k001"), Slices.utf8Slice("value-1"));

    int estimatedBeforeFinish = builder.currentSizeEstimate();
    int actualAfterFinish = builder.finish().length();

    assertTrue(estimatedBeforeFinish >= actualAfterFinish);
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

  private static void writeDenseTable(File tableFile, Options options) throws Exception {
    try (RandomAccessFile file = new RandomAccessFile(tableFile, "rw");
         FileChannel channel = file.getChannel()) {
      TableBuilder builder = new TableBuilder(options, channel, new BytewiseComparator());
      for (int i = 0; i < 32; i++) {
        String key = String.format(java.util.Locale.ROOT, "k%03d", i);
        builder.add(internalKey(key, 1), Slices.utf8Slice("value-" + i));
      }
      builder.finish();
    }
  }

  private static void writeDenseLargeValueTable(File tableFile, Options options) throws Exception {
    try (RandomAccessFile file = new RandomAccessFile(tableFile, "rw");
         FileChannel channel = file.getChannel()) {
      TableBuilder builder = new TableBuilder(options, channel, new BytewiseComparator());
      StringBuilder value = new StringBuilder();
      for (int i = 0; i < 512; i++) {
        value.append('v');
      }
      net.xdob.vexra.ldb.util.Slice largeValue = Slices.utf8Slice(value.toString());
      for (int i = 0; i < 32; i++) {
        String key = String.format(java.util.Locale.ROOT, "k%03d", i);
        builder.add(internalKey(key, 1), largeValue);
      }
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

  private static void corruptByte(File file, long position) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.seek(position);
      int value = raf.read();
      raf.seek(position);
      raf.write(value ^ 0x7f);
    }
  }

  private static void replaceAsciiOccurrenceInMetaindexBlock(File file, String expected, String replacement)
      throws Exception {
    assertEquals(expected.length(), replacement.length());
    byte[] expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] replacementBytes = replacement.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      byte[] footerBytes = new byte[Footer.ENCODED_LENGTH];
      raf.seek(raf.length() - Footer.ENCODED_LENGTH);
      raf.readFully(footerBytes);
      BlockHandle metaindexHandle = Footer.readFooter(Slices.wrappedBuffer(footerBytes)).getMetaindexBlockHandle();

      byte[] data = new byte[metaindexHandle.getDataSize()];
      raf.seek(metaindexHandle.getOffset());
      raf.readFully(data);
      byte[] trailerBytes = new byte[BlockTrailer.ENCODED_LENGTH];
      raf.readFully(trailerBytes);

      int match = -1;
      for (int i = 0; i <= data.length - expectedBytes.length; i++) {
        boolean same = true;
        for (int j = 0; j < expectedBytes.length; j++) {
          if (data[i + j] != expectedBytes[j]) {
            same = false;
            break;
          }
        }
        if (same) {
          match = i;
        }
      }
      assertTrue(match >= 0, "Missing token " + expected);
      System.arraycopy(replacementBytes, 0, data, match, replacementBytes.length);

      BlockTrailer oldTrailer = BlockTrailer.readBlockTrailer(Slices.wrappedBuffer(trailerBytes));
      BlockTrailer newTrailer = new BlockTrailer(
          oldTrailer.getCompressionType(),
          TableBuilder.crc32c(Slices.wrappedBuffer(data), oldTrailer.getCompressionType()));
      raf.seek(metaindexHandle.getOffset());
      raf.write(data);
      raf.write(BlockTrailer.writeBlockTrailer(newTrailer).getBytes());
    }
  }
}
