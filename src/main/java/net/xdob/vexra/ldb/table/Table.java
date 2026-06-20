package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.FilterPolicy;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.SeekingIterable;
import net.xdob.vexra.ldb.util.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Comparator;
import java.util.concurrent.Callable;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public abstract class Table implements SeekingIterable<Slice, Slice> {
  protected final String name;
  protected final FileChannel fileChannel;
  protected final Comparator<Slice> comparator;
  protected final boolean verifyChecksums;
  protected final Block indexBlock;
  protected final BlockHandle metaindexBlockHandle;
  protected final FilterPolicy filterPolicy;
  protected final Slice filterBlock;
  protected final TableProperties properties;

  protected final BlockCache blockCache;

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

    this.blockCache = blockCache;

    Block loadedIndexBlock;
    BlockHandle loadedMetaindexBlockHandle;
    Slice loadedFilterBlock;
    TableProperties loadedProperties;
    try {
      Footer footer = init();
      loadedIndexBlock = openBlock(footer.getIndexBlockHandle());
      loadedMetaindexBlockHandle = footer.getMetaindexBlockHandle();
      loadedFilterBlock = readFilterBlock(loadedMetaindexBlockHandle);
      loadedProperties = readPropertiesBlock(loadedMetaindexBlockHandle);
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

  protected abstract Slice readRawBlock(BlockHandle blockHandle) throws IOException;

  public boolean mayContain(Slice userKey) {
    if (filterPolicy == null || filterBlock == null) {
      return true;
    }
    return filterPolicy.keyMayMatch(userKey, filterBlock);
  }

  protected abstract Footer init() throws IOException;

  @Override
  public TableIterator iterator() {
    return new TableIterator(this, indexBlock.iterator());
  }

  public TableProperties getProperties() {
    return properties;
  }

  public Block openBlock(Slice blockEntry) {
    BlockHandle blockHandle = BlockHandle.readBlockHandle(blockEntry.input());
    return openBlock(blockHandle);
  }

  public Block openBlock(BlockHandle blockHandle) {
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
      blockCache.put(key, block);
      return block;
    } catch (IOException e) {
      throw new RuntimeException("Failed to open block " + blockHandle + " in " + name, e);
    }
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
}
