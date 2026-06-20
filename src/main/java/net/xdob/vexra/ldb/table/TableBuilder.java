package net.xdob.vexra.ldb.table;

import com.google.common.base.Throwables;
import net.xdob.vexra.ldb.CompressionType;
import net.xdob.vexra.ldb.FilterPolicy;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.InternalKey;
import net.xdob.vexra.ldb.util.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.impl.VersionSet.TARGET_FILE_SIZE;

public class TableBuilder {
  /**
   * TABLE_MAGIC_NUMBER was picked by running
   * echo http://code.google.com/p/leveldb/ | sha1sum
   * and taking the leading 64 bits.
   */
  public static final long TABLE_MAGIC_NUMBER = 0xdb4775248b80fb57L;

  private final int blockRestartInterval;
  private final int blockSize;
  private final CompressionType compressionType;
  private final Options options;

  private final FileChannel fileChannel;
  private final BlockBuilder dataBlockBuilder;
  private final BlockBuilder indexBlockBuilder;
  private final FilterPolicy filterPolicy;
  private Slice lastKey;
  private Slice firstKey;
  private final UserComparator userComparator;

  private long entryCount;
  private long dataBlockCount;

  // Either Finish() or Abandon() has been called.
  private boolean closed;

  // We do not emit the index entry for a block until we have seen the
  // first key for the next data block.  This allows us to use shorter
  // keys in the index block.  For example, consider a block boundary
  // between the keys "the quick brown fox" and "the who".  We can use
  // "the r" as the key for the index block entry since it is >= all
  // entries in the first block and < all entries in subsequent
  // blocks.
  private boolean pendingIndexEntry;
  private BlockHandle pendingHandle;  // Handle to add to index block

  private Slice compressedOutput;
  private CompressionType lastWrittenCompressionType = CompressionType.NONE;

  private long position;

  private final Set<Slice> filterKeySet = new HashSet<>();
  private final List<Slice> filterKeys = new ArrayList<>();
  private final Set<CompressionType> dataCompressionTypes = new LinkedHashSet<>();

  public TableBuilder(Options options, FileChannel fileChannel, UserComparator userComparator) {
    requireNonNull(options, "options is null");
    requireNonNull(fileChannel, "fileChannel is null");
    try {
      checkState(position == fileChannel.position(), "Expected position %s to equal fileChannel.position %s", position, fileChannel.position());
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }

    this.fileChannel = fileChannel;
    this.userComparator = userComparator;
    this.options = options;

    blockRestartInterval = options.blockRestartInterval();
    blockSize = options.blockSize();
    compressionType = options.compressionType();
    filterPolicy = options.filterPolicy();
    dataBlockBuilder = new BlockBuilder((int) Math.min(blockSize * 1.1, TARGET_FILE_SIZE), blockRestartInterval, userComparator);

    // with expected 50% compression
    int expectedNumberOfBlocks = 1024;
    indexBlockBuilder = new BlockBuilder(BlockHandle.MAX_ENCODED_LENGTH * expectedNumberOfBlocks, 1, userComparator);

    lastKey = Slices.EMPTY_SLICE;
  }

  public long getEntryCount() {
    return entryCount;
  }

  public long getFileSize()
      throws IOException {
    return position + dataBlockBuilder.currentSizeEstimate();
  }

  public void add(BlockEntry blockEntry)
      throws IOException {
    requireNonNull(blockEntry, "blockEntry is null");
    add(blockEntry.getKey(), blockEntry.getValue());
  }

  public void add(Slice key, Slice value)
      throws IOException {
    requireNonNull(key, "key is null");
    requireNonNull(value, "value is null");

    checkState(!closed, "table is finished");
    InternalKey ik = new InternalKey(key);
    Slice userKey = ik.getUserKey();
    if (filterKeySet.add(userKey)) {
      filterKeys.add(userKey);
    }

    if (entryCount > 0) {
      assert (userComparator.compare(key, lastKey) > 0) : "key must be greater than last key";
    }

    // If we just wrote a block, we can now add the handle to index block
    if (pendingIndexEntry) {
      checkState(dataBlockBuilder.isEmpty(), "Internal error: Table has a pending index entry but data block builder is empty");

      Slice shortestSeparator = userComparator.findShortestSeparator(lastKey, key);

      Slice handleEncoding = BlockHandle.writeBlockHandle(pendingHandle);
      indexBlockBuilder.add(shortestSeparator, handleEncoding);
      pendingIndexEntry = false;
    }

    if (entryCount == 0) {
      firstKey = key;
    }
    lastKey = key;
    entryCount++;
    dataBlockBuilder.add(key, value);

    int estimatedBlockSize = dataBlockBuilder.currentSizeEstimate();
    if (estimatedBlockSize >= blockSize) {
      flush();
    }
  }

  private void flush()
      throws IOException {
    checkState(!closed, "table is finished");
    if (dataBlockBuilder.isEmpty()) {
      return;
    }

    checkState(!pendingIndexEntry, "Internal error: Table already has a pending index entry to flush");

    pendingHandle = writeBlock(dataBlockBuilder);
    dataBlockCount++;
    dataCompressionTypes.add(lastWrittenCompressionType);
    pendingIndexEntry = true;
  }

  private BlockHandle writeBlock(BlockBuilder blockBuilder)
      throws IOException {
    // close the block
    Slice raw = blockBuilder.finish();

    // attempt to compress the block
    Slice blockContents = raw;
    CompressionType blockCompressionType = CompressionType.NONE;
    if (compressionType == CompressionType.LZ4) {
      ensureCompressedOutputCapacity(maxCompressedLength(raw.length()));
      SliceOutput out = compressedOutput.output();

      // 先写原始长度
      VariableLengthQuantity.writeVariableLengthInt(raw.length(), out);
      int headerSize = out.size();
      // 再把压缩数据写到 header 后面
      int compressedSize = Lz4Codec.compress(
          raw.getRawArray(),
          raw.getRawOffset(),
          raw.length(),
          compressedOutput.getRawArray(),
          headerSize
      );

      int totalSize = headerSize + compressedSize;

      // 压缩后总大小仍需比原始小 enough
      if (totalSize < raw.length() - (raw.length() / 8)) {
        blockContents = compressedOutput.slice(0, totalSize);
        blockCompressionType = CompressionType.LZ4;
      }
    }

    // create block trailer
    lastWrittenCompressionType = blockCompressionType;
    BlockTrailer blockTrailer = new BlockTrailer(blockCompressionType, crc32c(blockContents, blockCompressionType));
    Slice trailer = BlockTrailer.writeBlockTrailer(blockTrailer);

    // create a handle to this block
    BlockHandle blockHandle = new BlockHandle(position, blockContents.length());

    // write data and trailer
    position += fileChannel.write(new ByteBuffer[]{blockContents.toByteBuffer(), trailer.toByteBuffer()});

    // clean up state
    blockBuilder.reset();

    return blockHandle;
  }

  private static int maxCompressedLength(int length) {
    // Compressed data can be defined as:
    //    compressed := item* literal*
    //    item       := literal* copy
    //
    // The trailing literal sequence has a space blowup of at most 62/60
    // since a literal of length 60 needs one tag byte + one extra byte
    // for length information.
    //
    // Item blowup is trickier to measure.  Suppose the "copy" op copies
    // 4 bytes of data.  Because of a special check in the encoding code,
    // we produce a 4-byte copy only if the offset is < 65536.  Therefore
    // the copy op takes 3 bytes to encode, and this type of item leads
    // to at most the 62/60 blowup for representing literals.
    //
    // Suppose the "copy" op copies 5 bytes of data.  If the offset is big
    // enough, it will take 5 bytes to encode the copy op.  Therefore the
    // worst case here is a one-byte literal followed by a five-byte copy.
    // I.e., 6 bytes of input turn into 7 bytes of "compressed" data.
    //
    // This last factor dominates the blowup, so the final estimate is:
    return 32 + length + (length / 6);
  }


  public void finish() throws IOException {
    checkState(!closed, "table is finished");

    // flush current data block
    flush();

    // mark table as closed
    closed = true;

    // 先写 filter block（如果启用）
    BlockHandle filterBlockHandle = writeFilterBlockIfNeeded();

    // 写 properties block（如果显式启用 table format v2）
    BlockHandle propertiesBlockHandle = writePropertiesBlockIfNeeded(filterBlockHandle);

    // 写 meta index block
    BlockBuilder metaIndexBlockBuilder =
        new BlockBuilder(256, blockRestartInterval, new BytewiseComparator());

    if (filterBlockHandle != null) {
      String key = "filter." + filterPolicy.name();
      Slice handleEncoding = BlockHandle.writeBlockHandle(filterBlockHandle);
      metaIndexBlockBuilder.add(Slices.utf8Slice(key), handleEncoding);
    }
    if (propertiesBlockHandle != null) {
      Slice handleEncoding = BlockHandle.writeBlockHandle(propertiesBlockHandle);
      metaIndexBlockBuilder.add(Slices.utf8Slice(TableProperties.META_INDEX_KEY), handleEncoding);
    }

    BlockHandle metaindexBlockHandle = writeBlock(metaIndexBlockBuilder);

    // add last handle to index block
    if (pendingIndexEntry) {
      Slice shortSuccessor = userComparator.findShortSuccessor(lastKey);
      Slice handleEncoding = BlockHandle.writeBlockHandle(pendingHandle);
      indexBlockBuilder.add(shortSuccessor, handleEncoding);
      pendingIndexEntry = false;
    }

    // write index block
    BlockHandle indexBlockHandle = writeBlock(indexBlockBuilder);

    // write footer
    Footer footer = new Footer(metaindexBlockHandle, indexBlockHandle);
    Slice footerEncoding = Footer.writeFooter(footer);
    position += fileChannel.write(footerEncoding.toByteBuffer());
  }

  private BlockHandle writeFilterBlockIfNeeded() throws IOException {
    if (filterPolicy == null || filterKeys.isEmpty()) {
      return null;
    }

    byte[] filterBytes = filterPolicy.createFilter(filterKeys);
    if (filterBytes == null || filterBytes.length == 0) {
      return null;
    }

    return writeRawBlock(Slices.wrappedBuffer(filterBytes));
  }

  /**
   * 在 table format v2 opt-in 时写入 properties block。
   *
   * 当前默认仍写 v1；只有调用方显式设置 `Options.tableFormatVersion(2)` 并保持
   * `writeTableProperties=true` 时才会落盘。properties block 使用普通 block 编码，
   * 便于旧 reader 忽略未知 metaindex entry，新 reader 解析格式版本和 feature set。
   */
  private BlockHandle writePropertiesBlockIfNeeded(BlockHandle filterBlockHandle) throws IOException {
    if (options.tableFormatVersion() < 2 || !options.writeTableProperties()) {
      return null;
    }

    BlockBuilder propertiesBlockBuilder =
        new BlockBuilder(512, 1, new BytewiseComparator());
    addProperty(propertiesBlockBuilder, TableProperties.COMPATIBLE_FEATURES_KEY, compatibleFeatures(filterBlockHandle));
    addProperty(propertiesBlockBuilder, "ldb.format.created_by", "vexra-ldb/0.8.0-SNAPSHOT");
    addProperty(propertiesBlockBuilder, TableProperties.INCOMPATIBLE_FEATURES_KEY, "");
    addProperty(propertiesBlockBuilder, TableProperties.FORMAT_VERSION_KEY, "2");
    addProperty(propertiesBlockBuilder, "ldb.table.checksum", "crc32c-block-trailer");
    addProperty(propertiesBlockBuilder, "ldb.table.compression", compressionTypes());
    addProperty(propertiesBlockBuilder, "ldb.table.data_block_count", Long.toString(dataBlockCount));
    addProperty(propertiesBlockBuilder, "ldb.table.entry_count", Long.toString(entryCount));
    addProperty(propertiesBlockBuilder, "ldb.table.filter_policy", filterPolicy == null ? "" : filterPolicy.name());
    addProperty(propertiesBlockBuilder, "ldb.table.filter_scope", filterBlockHandle == null ? "" : "full-key");
    addProperty(propertiesBlockBuilder, "ldb.table.index_type", "single-level");
    addProperty(propertiesBlockBuilder, "ldb.table.largest_key", lastKey == null ? "" : java.util.Base64.getEncoder().encodeToString(lastKey.getBytes()));
    addProperty(propertiesBlockBuilder, "ldb.table.smallest_key", firstKey == null ? "" : java.util.Base64.getEncoder().encodeToString(firstKey.getBytes()));
    return writeBlock(propertiesBlockBuilder);
  }

  private void addProperty(BlockBuilder propertiesBlockBuilder, String key, String value) {
    propertiesBlockBuilder.add(Slices.utf8Slice(key), Slices.utf8Slice(value == null ? "" : value));
  }

  private String compatibleFeatures(BlockHandle filterBlockHandle) {
    List<String> features = new ArrayList<>();
    features.add("table.properties");
    features.add("block.trailer.crc32c");
    features.add("index.single-level");
    if (filterBlockHandle != null) {
      features.add("filter.full-key");
    }
    if (dataCompressionTypes.contains(CompressionType.LZ4)) {
      features.add("compression.lz4-block");
    }
    return join(features);
  }

  private String compressionTypes() {
    if (dataCompressionTypes.isEmpty()) {
      return "";
    }
    List<String> values = new ArrayList<>();
    for (CompressionType type : dataCompressionTypes) {
      values.add(type.name().toLowerCase(java.util.Locale.US));
    }
    return join(values);
  }

  private String join(List<String> values) {
    StringBuilder builder = new StringBuilder();
    for (String value : values) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(value);
    }
    return builder.toString();
  }

  private BlockHandle writeRawBlock(Slice blockContents) throws IOException {
    CompressionType blockCompressionType = CompressionType.NONE;

    BlockTrailer blockTrailer = new BlockTrailer(
        blockCompressionType,
        crc32c(blockContents, blockCompressionType)
    );
    Slice trailer = BlockTrailer.writeBlockTrailer(blockTrailer);

    BlockHandle blockHandle = new BlockHandle(position, blockContents.length());
    position += fileChannel.write(new ByteBuffer[]{
        blockContents.toByteBuffer(),
        trailer.toByteBuffer()
    });
    return blockHandle;
  }

  public void abandon() {
    checkState(!closed, "table is finished");
    closed = true;
  }

  public static int crc32c(Slice data, CompressionType type) {
    PureJavaCrc32C crc32c = new PureJavaCrc32C();
    crc32c.update(data.getRawArray(), data.getRawOffset(), data.length());
    crc32c.update(type.persistentId() & 0xFF);
    return crc32c.getMaskedValue();
  }

  public void ensureCompressedOutputCapacity(int capacity) {
    if (compressedOutput != null && compressedOutput.length() > capacity) {
      return;
    }
    compressedOutput = Slices.allocate(capacity);
  }
}
