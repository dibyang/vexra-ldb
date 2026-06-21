package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.FilterPolicy;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.SeekingIterable;
import net.xdob.vexra.ldb.util.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public abstract class Table implements SeekingIterable<Slice, Slice> {
  private static final int BLOCK_LOCAL_INDEX_MIN_LOOKUPS = 8;

  protected final String name;
  protected final FileChannel fileChannel;
  protected final Comparator<Slice> comparator;
  protected final boolean verifyChecksums;
  protected final Block indexBlock;
  protected final BlockHandle metaindexBlockHandle;
  protected final FilterPolicy filterPolicy;
  protected final Slice filterBlock;
  protected final TableProperties properties;
  private final BlockHandle blockLocalIndexDirectoryHandle;
  private volatile Map<BlockHandle, BlockHandle> blockLocalIndexDirectory;
  private long blockLocalIndexSeekCount;
  private long blockLocalIndexHitCount;
  private long blockLocalIndexFallbackCount;
  private long tableIndexSeekCount;
  private long tableDataBlockOpenCount;
  private long tableDataBlockSeekCount;

  protected final BlockCache blockCache;
  private final Options options;

  public Table(String name,
               FileChannel fileChannel,
               Comparator<Slice> comparator,
               boolean verifyChecksums,
               Options options,
               BlockCache blockCache) throws IOException {
    requireNonNull(name, "name is null");
    requireNonNull(fileChannel, "fileChannel is null");
    requireNonNull(comparator, "comparator is null");

    long size = fileChannel.size();
    checkArgument(size >= Footer.ENCODED_LENGTH,
        "File is corrupt: size must be at least %s bytes", Footer.ENCODED_LENGTH);

    this.name = name;
    this.fileChannel = fileChannel;
    this.verifyChecksums = verifyChecksums;
    this.comparator = comparator;
    this.filterPolicy = options == null ? null : options.filterPolicy();
    this.options = options;

    this.blockCache = blockCache;

    Block loadedIndexBlock;
    BlockHandle loadedMetaindexBlockHandle;
    Slice loadedFilterBlock;
    TableProperties loadedProperties;
    BlockHandle loadedBlockLocalIndexDirectoryHandle;
    try {
      Footer footer = init();
      loadedIndexBlock = openBlock(footer.getIndexBlockHandle());
      loadedMetaindexBlockHandle = footer.getMetaindexBlockHandle();
      loadedFilterBlock = readFilterBlock(loadedMetaindexBlockHandle);
      loadedProperties = readPropertiesBlock(loadedMetaindexBlockHandle);
      loadedBlockLocalIndexDirectoryHandle =
          readBlockLocalIndexDirectoryHandle(loadedMetaindexBlockHandle, loadedProperties);
      if (loadedProperties.isLegacy()
          && options != null
          && !options.allowLegacyTableFormat()) {
        throw new IOException("Legacy table format is not allowed: " + name);
      }
      loadedProperties.validateReadable(
          options == null || options.failOnUnknownTableFeature(),
          name);
    } catch (IOException e) {
      closeAfterConstructorFailure();
      throw e;
    } catch (RuntimeException e) {
      closeAfterConstructorFailure();
      throw e;
    }

    this.indexBlock = loadedIndexBlock;
    this.metaindexBlockHandle = loadedMetaindexBlockHandle;
    this.filterBlock = loadedFilterBlock;
    this.properties = loadedProperties;
    this.blockLocalIndexDirectoryHandle = loadedBlockLocalIndexDirectoryHandle;
    this.blockLocalIndexDirectory = null;
  }

  /**
   * 构造 Table 过程中失败时释放已经获取的文件资源。
   *
   * 子类如果持有 mmap 等额外资源，需要覆盖该方法并先释放子类资源，再关闭 channel。
   */
  protected void closeAfterConstructorFailure() {
    Closeables.closeQuietly(fileChannel);
  }

  /**
   * 校验 SST block trailer 中保存的 CRC，避免损坏 block 被当作正常数据返回。
   *
   * @param blockData block 原始持久化内容，压缩场景下仍为压缩后的字节
   * @param trailer block trailer，包含压缩类型和写入时计算的 masked CRC32C
   * @param blockHandle block 在 SST 文件中的位置，用于失败时输出诊断上下文
   * @throws IOException 当校验失败时抛出，调用方应停止使用该 SST block
   */
  protected void verifyBlockChecksum(Slice blockData, BlockTrailer trailer, BlockHandle blockHandle)
      throws IOException {
    if (!verifyChecksums) {
      return;
    }

    int actual = TableBuilder.crc32c(blockData, trailer.getCompressionType());
    if (actual != trailer.getCrc32c()) {
      throw new IOException("Invalid block checksum in " + name
          + ", offset=" + blockHandle.getOffset()
          + ", size=" + blockHandle.getDataSize()
          + ", expected=0x" + Integer.toHexString(trailer.getCrc32c())
          + ", actual=0x" + Integer.toHexString(actual));
    }
  }


  private Slice readFilterBlock(BlockHandle metaindexBlockHandle) throws IOException {
    if (filterPolicy == null) {
      return null;
    }

    Block metaIndexBlock = openBlock(metaindexBlockHandle);
    BlockIterator iterator = metaIndexBlock.iterator();

    String filterKey = "filter." + filterPolicy.name();
    Slice target = Slices.wrappedBuffer(filterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    while (iterator.hasNext()) {
      BlockEntry entry = iterator.next();
      if (entry.getKey().equals(target)) {
        BlockHandle handle = BlockHandle.readBlockHandle(entry.getValue().input());
        return readRawBlock(handle);
      }
    }
    return null;
  }

  /**
   * 从 metaindex 中读取 table properties block。
   *
   * 旧格式 SST 没有 `properties` entry，此时返回 v1 legacy 视图；新格式 SST
   * 则在打开 table 阶段解析属性并执行 feature set 校验，避免热路径重复解码。
   */
  private TableProperties readPropertiesBlock(BlockHandle metaindexBlockHandle) throws IOException {
    Block metaIndexBlock = openBlock(metaindexBlockHandle);
    BlockIterator iterator = metaIndexBlock.iterator();
    Slice target = Slices.utf8Slice(TableProperties.META_INDEX_KEY);

    while (iterator.hasNext()) {
      BlockEntry entry = iterator.next();
      if (entry.getKey().equals(target)) {
        BlockHandle handle = BlockHandle.readBlockHandle(entry.getValue().input());
        return TableProperties.read(openBlock(handle));
      }
    }
    return TableProperties.legacy();
  }

  private BlockHandle readBlockLocalIndexDirectoryHandle(
      BlockHandle metaindexBlockHandle,
      TableProperties properties) throws IOException {
    boolean declared = "true".equals(properties.get(TableProperties.BLOCK_LOCAL_INDEX_KEY))
        || properties.getIncompatibleFeatures().contains(TableProperties.BLOCK_LOCAL_INDEX_FEATURE);
    if (!declared) {
      return null;
    }

    Block metaIndexBlock = openBlock(metaindexBlockHandle);
    BlockIterator iterator = metaIndexBlock.iterator();
    Slice target = Slices.utf8Slice(TableProperties.BLOCK_LOCAL_INDEX_META_INDEX_KEY);

    while (iterator.hasNext()) {
      BlockEntry entry = iterator.next();
      if (entry.getKey().equals(target)) {
        return BlockHandle.readBlockHandle(entry.getValue().input());
      }
    }
    throw new IOException("Table " + name
        + " declares " + TableProperties.BLOCK_LOCAL_INDEX_FEATURE
        + " but metaindex entry " + TableProperties.BLOCK_LOCAL_INDEX_META_INDEX_KEY
        + " is missing: BLOCK_LOCAL_INDEX_DIRECTORY_MISSING");
  }

  private BlockHandle parseBlockHandleDirectoryKey(Slice key) {
    String value = new String(key.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
    int separator = value.indexOf(':');
    if (separator <= 0 || separator == value.length() - 1) {
      throw new IllegalArgumentException("Invalid block-local index directory key: " + value);
    }
    long offset = Long.parseLong(value.substring(0, separator));
    int dataSize = Integer.parseInt(value.substring(separator + 1));
    return new BlockHandle(offset, dataSize);
  }

  private Map<BlockHandle, BlockHandle> loadBlockLocalIndexDirectory() {
    if (blockLocalIndexDirectoryHandle == null) {
      return Collections.emptyMap();
    }
    Map<BlockHandle, BlockHandle> directory = blockLocalIndexDirectory;
    if (directory != null) {
      return directory;
    }
    synchronized (this) {
      directory = blockLocalIndexDirectory;
      if (directory == null) {
        Block directoryBlock = openBlock(blockLocalIndexDirectoryHandle);
        Map<BlockHandle, BlockHandle> loaded = new LinkedHashMap<BlockHandle, BlockHandle>();
        BlockIterator directoryIterator = directoryBlock.iterator();
        while (directoryIterator.hasNext()) {
          BlockEntry directoryEntry = directoryIterator.next();
          loaded.put(
              parseBlockHandleDirectoryKey(directoryEntry.getKey()),
              BlockHandle.readBlockHandle(directoryEntry.getValue().input()));
        }
        directory = Collections.unmodifiableMap(loaded);
        blockLocalIndexDirectory = directory;
      }
      return directory;
    }
  }

  protected abstract Slice readRawBlock(BlockHandle blockHandle) throws IOException;

  public boolean mayContain(Slice userKey) {
    if (filterPolicy == null || filterBlock == null) {
      return true;
    }
    return filterPolicy.keyMayMatch(userKey, filterBlock);
  }

  /**
   * 按内部 key 执行 SST 点查。
   *
   * <p>该方法只负责在 table 层定位 index block 和 data block，并返回 data block 中 seek 到的候选 entry。
   * sequence、value type、user key 是否匹配等 LSM 语义仍由 impl 层判断，避免 table 包依赖更高层读取语义。</p>
   *
   * @param internalKey 已编码的内部 key
   * @return seek 到的候选 entry；如果目标 key 超出 table/block 范围则返回 null
   */
  public Entry<Slice, Slice> get(Slice internalKey) {
    tableIndexSeekCount++;
    Entry<Slice, Slice> indexEntry = indexBlock.seek(internalKey);
    if (indexEntry == null) {
      return null;
    }
    BlockHandle blockHandle = BlockHandle.readBlockHandle(indexEntry.getValue().input());
    tableDataBlockOpenCount++;
    Block dataBlock = openBlockForDirectRead(blockHandle);
    tableDataBlockSeekCount++;
    Entry<Slice, Slice> candidate = dataBlock.seek(internalKey);
    if (candidate == null) {
      return null;
    }
    if (comparator.compare(candidate.getKey(), internalKey) < 0) {
      return null;
    }
    return candidate;
  }

  /**
   * 按内部 key 批量执行 SST 点查。
   *
   * <p>该方法先通过 index block 把 key 分组到 data block handle，再对同一个 data block 执行批量 seek。
   * 返回值顺序与输入 key 顺序一致，LSM 语义仍由 impl 层判断。</p>
   */
  public List<Entry<Slice, Slice>> get(List<Slice> internalKeys) {
    List<Entry<Slice, Slice>> results = new ArrayList<Entry<Slice, Slice>>(
        Collections.nCopies(internalKeys.size(), (Entry<Slice, Slice>) null));
    if (internalKeys.isEmpty()) {
      return results;
    }

    Map<BlockHandle, List<TableLookup>> blockLookups = new LinkedHashMap<BlockHandle, List<TableLookup>>();
    for (int i = 0; i < internalKeys.size(); i++) {
      Slice internalKey = internalKeys.get(i);
      tableIndexSeekCount++;
      Entry<Slice, Slice> indexEntry = indexBlock.seek(internalKey);
      if (indexEntry == null) {
        continue;
      }
      BlockHandle blockHandle = BlockHandle.readBlockHandle(indexEntry.getValue().input());
      List<TableLookup> lookups = blockLookups.get(blockHandle);
      if (lookups == null) {
        lookups = new ArrayList<TableLookup>();
        blockLookups.put(blockHandle, lookups);
      }
      lookups.add(new TableLookup(i, internalKey));
    }

    for (Entry<BlockHandle, List<TableLookup>> group : blockLookups.entrySet()) {
      tableDataBlockOpenCount++;
      Block dataBlock = openBlockForDirectRead(group.getKey());
      for (TableLookup lookup : group.getValue()) {
        tableDataBlockSeekCount++;
        Entry<Slice, Slice> candidate = seekDataBlock(
            group.getKey(),
            dataBlock,
            lookup.internalKey,
            group.getValue().size() >= BLOCK_LOCAL_INDEX_MIN_LOOKUPS);
        if (candidate != null
            && comparator.compare(candidate.getKey(), lookup.internalKey) >= 0) {
          results.set(lookup.index, candidate);
        }
      }
    }
    return results;
  }

  private Entry<Slice, Slice> seekDataBlock(
      BlockHandle dataBlockHandle,
      Block dataBlock,
      Slice internalKey,
      boolean allowLocalIndex) {
    if (!allowLocalIndex) {
      blockLocalIndexFallbackCount++;
      return dataBlock.seek(internalKey);
    }
    BlockHandle localIndexHandle = loadBlockLocalIndexDirectory().get(dataBlockHandle);
    if (localIndexHandle == null) {
      blockLocalIndexFallbackCount++;
      return dataBlock.seek(internalKey);
    }

    blockLocalIndexSeekCount++;
    Block localIndexBlock = openBlock(localIndexHandle);
    Entry<Slice, Slice> anchor = localIndexBlock.floor(internalKey);
    if (anchor == null) {
      blockLocalIndexFallbackCount++;
      return dataBlock.seek(internalKey);
    }
    blockLocalIndexHitCount++;
    return dataBlock.seekFromOffset(internalKey, parseBlockLocalIndexAnchorOffset(anchor.getValue()));
  }

  private int parseBlockLocalIndexAnchorOffset(Slice value) {
    String text = new String(value.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
    int separator = text.indexOf(':');
    if (separator <= 0 || separator == text.length() - 1) {
      throw new IllegalArgumentException("Invalid block-local index anchor value: " + text);
    }
    return Integer.parseInt(text.substring(separator + 1));
  }

  /**
   * 对同一 data block 内足够密集的 key 预留顺序批量 seek 能力。
   */
  private void seekDenseBlock(Block dataBlock, List<TableLookup> lookups, List<Entry<Slice, Slice>> results) {
    Collections.sort(lookups, new Comparator<TableLookup>() {
      @Override
      public int compare(TableLookup left, TableLookup right) {
        return comparator.compare(left.internalKey, right.internalKey);
      }
    });

    List<Slice> sortedKeys = new ArrayList<Slice>(lookups.size());
      for (TableLookup lookup : lookups) {
        sortedKeys.add(lookup.internalKey);
      }

    List<Entry<Slice, Slice>> candidates = dataBlock.seekAll(sortedKeys);
    for (int i = 0; i < candidates.size(); i++) {
      Entry<Slice, Slice> candidate = candidates.get(i);
      if (candidate != null
          && comparator.compare(candidate.getKey(), lookups.get(i).internalKey) >= 0) {
        results.set(lookups.get(i).index, candidate);
      }
    }
  }

  protected abstract Footer init() throws IOException;

  @Override
  public TableIterator iterator() {
    return new TableIterator(this, indexBlock.iterator());
  }

  public TableProperties getProperties() {
    return properties;
  }

  public Map<BlockHandle, BlockHandle> getBlockLocalIndexDirectory() {
    return loadBlockLocalIndexDirectory();
  }

  /**
   * 生成离线 check/repair 使用的 block-local index 格式证据。
   *
   * <p>该方法只在诊断路径调用，会检查 directory 覆盖数、handle 边界和 local index block
   * 可读性；普通 get/iterator 不调用它，避免把自检成本带入热路径。</p>
   */
  public String getBlockLocalIndexFormatEvidence() {
    boolean declared = isBlockLocalIndexDeclared();
    Map<BlockHandle, BlockHandle> directory = loadBlockLocalIndexDirectory();
    long expectedCoveredBlocks = parseLong(properties.get(TableProperties.BLOCK_LOCAL_INDEX_COVERED_BLOCKS_KEY));
    long expectedBytes = parseLong(properties.get(TableProperties.BLOCK_LOCAL_INDEX_BYTES_KEY));
    List<String> failures = getBlockLocalIndexFormatFailures();
    return "declared=" + declared
        + ",directoryPresent=" + (!declared || !directory.isEmpty())
        + ",directoryEntries=" + directory.size()
        + ",expectedCoveredBlocks=" + expectedCoveredBlocks
        + ",expectedBytes=" + expectedBytes
        + ",coverageMatches=" + (!declared || expectedCoveredBlocks == directory.size())
        + ",handlesInRange=" + !containsFailure(failures, "BLOCK_LOCAL_INDEX_HANDLE_OUT_OF_RANGE")
        + ",blocksReadable=" + !containsFailure(failures, "BLOCK_LOCAL_INDEX_BLOCK_CORRUPT")
        + ",failureCount=" + failures.size();
  }

  /**
   * 返回离线格式自检发现的 block-local index 损坏分类。
   */
  public List<String> getBlockLocalIndexFormatFailures() {
    if (!isBlockLocalIndexDeclared()) {
      return Collections.emptyList();
    }
    Map<BlockHandle, BlockHandle> directory = loadBlockLocalIndexDirectory();
    List<String> failures = new ArrayList<String>();
    long expectedCoveredBlocks = parseLong(properties.get(TableProperties.BLOCK_LOCAL_INDEX_COVERED_BLOCKS_KEY));
    if (directory.isEmpty()) {
      failures.add("BLOCK_LOCAL_INDEX_DIRECTORY_MISSING");
    }
    if (expectedCoveredBlocks != directory.size()) {
      failures.add("BLOCK_LOCAL_INDEX_COVERAGE_MISMATCH expected=" + expectedCoveredBlocks
          + " actual=" + directory.size());
    }
    long fileSize;
    try {
      fileSize = fileChannel.size();
    } catch (IOException e) {
      failures.add("BLOCK_LOCAL_INDEX_FILE_SIZE_UNREADABLE " + e.getMessage());
      return Collections.unmodifiableList(failures);
    }
    for (BlockHandle handle : directory.values()) {
      if (!isBlockHandleInRange(handle, fileSize)) {
        failures.add("BLOCK_LOCAL_INDEX_HANDLE_OUT_OF_RANGE offset=" + handle.getOffset()
            + " size=" + handle.getDataSize());
        continue;
      }
      try {
        openBlock(handle);
      } catch (RuntimeException e) {
        failures.add("BLOCK_LOCAL_INDEX_BLOCK_CORRUPT offset=" + handle.getOffset()
            + " size=" + handle.getDataSize());
      }
    }
    return Collections.unmodifiableList(failures);
  }

  public String getBlockLocalIndexStats() {
    return "declared=" + isBlockLocalIndexDeclared()
        + ",directoryHandlePresent=" + (blockLocalIndexDirectoryHandle != null)
        + ",directoryLoaded=" + (blockLocalIndexDirectory != null)
        + ",directoryEntries=" + (blockLocalIndexDirectory == null ? 0 : blockLocalIndexDirectory.size())
        + ",seekCount=" + blockLocalIndexSeekCount
        + ",hitCount=" + blockLocalIndexHitCount
        + ",fallbackCount=" + blockLocalIndexFallbackCount;
  }

  public boolean isBlockLocalIndexDeclaredForStats() {
    return isBlockLocalIndexDeclared();
  }

  public boolean isBlockLocalIndexDirectoryLoadedForStats() {
    return blockLocalIndexDirectory != null;
  }

  public int getBlockLocalIndexDirectoryEntriesForStats() {
    return blockLocalIndexDirectory == null ? 0 : blockLocalIndexDirectory.size();
  }

  public long getBlockLocalIndexSeekCountForStats() {
    return blockLocalIndexSeekCount;
  }

  public long getBlockLocalIndexHitCountForStats() {
    return blockLocalIndexHitCount;
  }

  public long getBlockLocalIndexFallbackCountForStats() {
    return blockLocalIndexFallbackCount;
  }

  public long getTableIndexSeekCountForStats() {
    return tableIndexSeekCount;
  }

  public long getTableDataBlockOpenCountForStats() {
    return tableDataBlockOpenCount;
  }

  public long getTableDataBlockSeekCountForStats() {
    return tableDataBlockSeekCount;
  }

  /**
   * 预热当前 table 的所有 data block。
   */
  public int warmDataBlocks() {
    int warmed = 0;
    BlockIterator iterator = indexBlock.iterator();
    while (iterator.hasNext()) {
      BlockEntry entry = iterator.next();
      BlockHandle blockHandle = BlockHandle.readBlockHandle(entry.getValue().input());
      openBlock(blockHandle);
      warmed++;
    }
    return warmed;
  }

  public Block openBlock(Slice blockEntry) {
    BlockHandle blockHandle = BlockHandle.readBlockHandle(blockEntry.input());
    return openBlock(blockHandle);
  }

  public Block openBlock(BlockHandle blockHandle) {
    return openBlock(blockHandle, true);
  }

  private Block openBlockForDirectRead(BlockHandle blockHandle) {
    return openBlock(blockHandle, false);
  }

  private Block openBlock(BlockHandle blockHandle, boolean forceCacheOnMiss) {
    try {
      if (blockCache == null) {
        return readBlock(blockHandle);
      }
      BlockCache.Key key = new BlockCache.Key(
          name,
          blockHandle.getOffset(),
          blockHandle.getDataSize()
      );

      Block cached = blockCache.get(key);
      if (cached != null) {
        return cached;
      }

      Block block = readBlock(blockHandle);
      if (forceCacheOnMiss) {
        blockCache.put(key, block);
      } else {
        blockCache.putIfAdmitted(key, block, blockCacheAdmissionMinReads());
      }
      return block;
    } catch (IOException e) {
      throw new RuntimeException("Failed to open block " + blockHandle + " in " + name, e);
    }
  }

  private int blockCacheAdmissionMinReads() {
    return options == null ? 1 : options.blockCacheAdmissionMinReads();
  }

  protected abstract Block readBlock(BlockHandle blockHandle) throws IOException;

  protected int uncompressedLength(ByteBuffer data) throws IOException {
    return VariableLengthQuantity.readVariableLengthInt(data.duplicate());
  }

  public long getApproximateOffsetOf(Slice key) {
    BlockIterator iterator = indexBlock.iterator();
    iterator.seek(key);
    if (iterator.hasNext()) {
      BlockHandle blockHandle = BlockHandle.readBlockHandle(iterator.next().getValue().input());
      return blockHandle.getOffset();
    }
    return metaindexBlockHandle.getOffset();
  }

  private boolean isBlockLocalIndexDeclared() {
    return "true".equals(properties.get(TableProperties.BLOCK_LOCAL_INDEX_KEY))
        || properties.getIncompatibleFeatures().contains(TableProperties.BLOCK_LOCAL_INDEX_FEATURE);
  }

  private boolean isBlockHandleInRange(BlockHandle handle, long fileSize) {
    if (handle.getOffset() < 0 || handle.getDataSize() < 0) {
      return false;
    }
    long end = handle.getOffset() + handle.getFullBlockSize();
    return end >= handle.getOffset() && end <= fileSize;
  }

  private static long parseLong(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    return Long.parseLong(value.trim());
  }

  private static boolean containsFailure(List<String> failures, String marker) {
    for (String failure : failures) {
      if (failure.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return "Table{" +
        "name='" + name + '\'' +
        ", comparator=" + comparator +
        ", verifyChecksums=" + verifyChecksums +
        '}';
  }

  public Callable<?> closer() {
    return new Closer(fileChannel, blockCache, name);
  }

  private static class Closer implements Callable<Void> {
    private final Closeable closeable;
    private final BlockCache blockCache;
    private final String tableName;

    private Closer(Closeable closeable, BlockCache blockCache, String tableName) {
      this.closeable = closeable;
      this.blockCache = blockCache;
      this.tableName = tableName;
    }

    @Override
    public Void call() {
      if (blockCache != null) {
        blockCache.invalidateTable(tableName);
      }
      Closeables.closeQuietly(closeable);
      return null;
    }
  }

  private static final class TableLookup {
    private final int index;
    private final Slice internalKey;

    private TableLookup(int index, Slice internalKey) {
      this.index = index;
      this.internalKey = internalKey;
    }
  }
}
