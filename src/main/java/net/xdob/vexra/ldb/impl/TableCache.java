package net.xdob.vexra.ldb.impl;

import com.google.common.cache.*;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.table.BlockCache;
import net.xdob.vexra.ldb.table.FileChannelTable;
import net.xdob.vexra.ldb.table.MMapTable;
import net.xdob.vexra.ldb.table.Table;
import net.xdob.vexra.ldb.table.TableProperties;
import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.Closeables;
import net.xdob.vexra.ldb.util.Finalizer;
import net.xdob.vexra.ldb.util.InternalTableIterator;
import net.xdob.vexra.ldb.util.Slice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

public class TableCache {
  private final LoadingCache<Long, TableAndFile> cache;
  private final Finalizer<Table> finalizer = new Finalizer<>(1);

  // 新增：全局共享 block cache
  private final BlockCache blockCache;
  private final AtomicLong tableRequestCount = new AtomicLong();
  private final AtomicLong tableLoadCount = new AtomicLong();
  private final AtomicLong iteratorRequestCount = new AtomicLong();
  private final AtomicLong directGetRequestCount = new AtomicLong();
  private final AtomicLong directGetHitCount = new AtomicLong();
  private final AtomicLong directGetMissCount = new AtomicLong();
  private final AtomicLong directGetBatchRequestCount = new AtomicLong();
  private final AtomicLong directGetBatchKeyCount = new AtomicLong();
  private final AtomicLong blockCacheWarmupTableCount = new AtomicLong();
  private final AtomicLong blockCacheWarmupBlockCount = new AtomicLong();
  private final AtomicLong mayContainRequestCount = new AtomicLong();
  private final AtomicLong mayContainTrueCount = new AtomicLong();
  private final AtomicLong mayContainFalseCount = new AtomicLong();
  private final AtomicLong approximateOffsetRequestCount = new AtomicLong();

  public TableCache(final File databaseDir,
                    int tableCacheSize,
                    final UserComparator userComparator,
                    final boolean verifyChecksums,
                    Options options) {
    requireNonNull(databaseDir, "databaseName is null");

    this.blockCache = options.cacheBlocks() ? new BlockCache(options.blockCacheSize()) : null;

    cache = CacheBuilder.newBuilder()
        .maximumSize(tableCacheSize)
        .removalListener(new RemovalListener<Long, TableAndFile>() {
          @Override
          public void onRemoval(RemovalNotification<Long, TableAndFile> notification) {
            TableAndFile value = notification.getValue();
            if (value != null) {
              Table table = value.getTable();
              finalizer.addCleanup(table, table.closer());
            }
          }
        })
        .build(new CacheLoader<Long, TableAndFile>() {
          @Override
          public TableAndFile load(Long fileNumber) throws IOException {
            tableLoadCount.incrementAndGet();
            TableAndFile tableAndFile = new TableAndFile(
                databaseDir,
                fileNumber,
                userComparator,
                verifyChecksums,
                options,
                blockCache
            );
            if (blockCache != null && options.blockCacheWarmOnOpen()) {
              int warmed = tableAndFile.getTable().warmDataBlocks();
              blockCacheWarmupTableCount.incrementAndGet();
              blockCacheWarmupBlockCount.addAndGet(warmed);
            }
            return tableAndFile;
          }
        });
  }

  public InternalTableIterator newIterator(FileMetaData file) {
    return newIterator(file.getNumber());
  }

  public InternalTableIterator newIterator(long number) {
    iteratorRequestCount.incrementAndGet();
    return new InternalTableIterator(getTable(number).iterator());
  }

  /**
   * 通过 table 层 direct get 读取单个内部 key。
   *
   * <p>该入口用于随机点查和 MultiGet，避免创建完整的 table/internal iterator 链。返回值仍是 SST 中 seek 到的候选 entry，
   * 上层负责判断 user key、sequence 和 value type 语义。</p>
   */
  public Entry<Slice, Slice> get(FileMetaData file, LookupKey key) {
    return get(file.getNumber(), key);
  }

  /**
   * 通过 table 层 direct get 读取单个内部 key。
   */
  public Entry<Slice, Slice> get(long number, InternalKey internalKey) {
    return getEncoded(number, internalKey.encode());
  }

  public Entry<Slice, Slice> get(long number, LookupKey key) {
    return getEncoded(number, key.getEncodedInternalKey());
  }

  private Entry<Slice, Slice> getEncoded(long number, Slice encodedInternalKey) {
    directGetRequestCount.incrementAndGet();
    Entry<Slice, Slice> result = getTable(number).get(encodedInternalKey);
    if (result == null) {
      directGetMissCount.incrementAndGet();
    } else {
      directGetHitCount.incrementAndGet();
    }
    return result;
  }

  /**
   * 通过 table 层 batch direct get 读取一批内部 key。
   *
   * <p>该入口服务 MultiGet：table 层会把 key 按 data block handle 分组，减少同一 SST/data block
   * 内的重复 block 打开和重复 seek 成本。返回顺序与输入 key 顺序一致。</p>
   */
  public List<Entry<Slice, Slice>> get(FileMetaData file, List<LookupKey> keys) {
    if (keys.isEmpty()) {
      return Collections.emptyList();
    }

    directGetBatchRequestCount.incrementAndGet();
    directGetBatchKeyCount.addAndGet(keys.size());
    directGetRequestCount.addAndGet(keys.size());

    List<Slice> encodedKeys = new ArrayList<Slice>(keys.size());
    for (LookupKey key : keys) {
      encodedKeys.add(key.getEncodedInternalKey());
    }

    List<Entry<Slice, Slice>> results = getTable(file.getNumber()).get(encodedKeys);
    for (Entry<Slice, Slice> result : results) {
      if (result == null) {
        directGetMissCount.incrementAndGet();
      } else {
        directGetHitCount.incrementAndGet();
      }
    }
    return results;
  }

  public long getApproximateOffsetOf(FileMetaData file, Slice key) {
    approximateOffsetRequestCount.incrementAndGet();
    return getTable(file.getNumber()).getApproximateOffsetOf(key);
  }

  public boolean mayContain(FileMetaData fileMetaData, Slice userKey) {
    mayContainRequestCount.incrementAndGet();
    boolean result = getTable(fileMetaData.getNumber()).mayContain(userKey);
    if (result) {
      mayContainTrueCount.incrementAndGet();
    } else {
      mayContainFalseCount.incrementAndGet();
    }
    return result;
  }

  /**
   * 返回指定 SST 的 table properties。
   *
   * 该入口服务 0.8.0 文件格式演进中的 check/repair/report，不参与普通 get
   * 热路径；旧格式 SST 会返回 v1 legacy 属性视图。
   */
  public TableProperties getTableProperties(long number) {
    return getTable(number).getProperties();
  }

  /**
   * 返回指定 SST 的 block-local index 离线格式证据。
   *
   * <p>该入口只服务 check/repair/report，不参与普通读热路径。</p>
   */
  public String getBlockLocalIndexFormatEvidence(long number) {
    return getTable(number).getBlockLocalIndexFormatEvidence();
  }

  /**
   * 返回指定 SST 的 block-local index 离线损坏分类。
   */
  public List<String> getBlockLocalIndexFormatFailures(long number) {
    return getTable(number).getBlockLocalIndexFormatFailures();
  }

  private Table getTable(long number) {
    tableRequestCount.incrementAndGet();
    try {
      return cache.get(number).getTable();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new RuntimeException("Could not open table " + number, cause);
    }
  }

  public void close() {
    cache.invalidateAll();
    cache.cleanUp();
    if (blockCache != null) {
      blockCache.invalidateAll();
    }
    finalizer.destroy();
  }

  public void evict(long number) {
    cache.invalidate(number);
  }

  public String blockCacheStats() {
    if (blockCache == null) {
      return "enabled=false";
    }
    return blockCache.stats();
  }

  public String readStats() {
    BlockLocalIndexStats blockLocalIndexStats = blockLocalIndexStats();
    return "tableRequests=" + tableRequestCount.get()
        + ",tableLoads=" + tableLoadCount.get()
        + ",iteratorRequests=" + iteratorRequestCount.get()
        + ",directGetRequests=" + directGetRequestCount.get()
        + ",directGetHits=" + directGetHitCount.get()
        + ",directGetMisses=" + directGetMissCount.get()
        + ",directGetBatchRequests=" + directGetBatchRequestCount.get()
        + ",directGetBatchKeys=" + directGetBatchKeyCount.get()
        + ",blockCacheWarmupTables=" + blockCacheWarmupTableCount.get()
        + ",blockCacheWarmupBlocks=" + blockCacheWarmupBlockCount.get()
        + ",mayContainRequests=" + mayContainRequestCount.get()
        + ",mayContainTrue=" + mayContainTrueCount.get()
        + ",mayContainFalse=" + mayContainFalseCount.get()
        + ",approximateOffsetRequests=" + approximateOffsetRequestCount.get()
        + ",tableIndexSeeks=" + blockLocalIndexStats.tableIndexSeeks
        + ",tableIndexCacheHits=" + blockLocalIndexStats.tableIndexCacheHits
        + ",tableIndexCacheMisses=" + blockLocalIndexStats.tableIndexCacheMisses
        + ",tableDataBlockOpens=" + blockLocalIndexStats.tableDataBlockOpens
        + ",tableLastBlockHits=" + blockLocalIndexStats.tableLastBlockHits
        + ",tableLastBlockMisses=" + blockLocalIndexStats.tableLastBlockMisses
        + ",tablePointGetLastBlockHits=" + blockLocalIndexStats.tablePointGetLastBlockHits
        + ",tablePointGetSlotHits=" + blockLocalIndexStats.tablePointGetSlotHits
        + ",tablePointGetSlotMisses=" + blockLocalIndexStats.tablePointGetSlotMisses
        + ",tablePointGetSlotCollisions=" + blockLocalIndexStats.tablePointGetSlotCollisions
        + ",tableBatchDataBlockGroups=" + blockLocalIndexStats.tableBatchDataBlockGroups
        + ",tableBatchDataBlockKeys=" + blockLocalIndexStats.tableBatchDataBlockKeys
        + ",tableBatchDenseBlockGroups=" + blockLocalIndexStats.tableBatchDenseBlockGroups
        + ",tableBatchDenseBlockKeys=" + blockLocalIndexStats.tableBatchDenseBlockKeys
        + ",tableBatchSeekAllCount=" + blockLocalIndexStats.tableBatchSeekAllCount
        + ",tableDirectReadBlockCacheHits=" + blockLocalIndexStats.tableDirectReadBlockCacheHits
        + ",tableDirectReadBlockCacheMisses=" + blockLocalIndexStats.tableDirectReadBlockCacheMisses
        + ",tableDirectReadBlockReads=" + blockLocalIndexStats.tableDirectReadBlockReads
        + ",tableDataBlockSeeks=" + blockLocalIndexStats.tableDataBlockSeeks
        + ",blockSeekIndexHits=" + blockLocalIndexStats.blockSeekIndexHits
        + ",blockSeekIndexMisses=" + blockLocalIndexStats.blockSeekIndexMisses
        + ",blockSeekIndexFallbacks=" + blockLocalIndexStats.blockSeekIndexFallbacks
        + ",blockLocalIndexTables=" + blockLocalIndexStats.declaredTables
        + ",blockLocalIndexDirectoryLoadedTables=" + blockLocalIndexStats.directoryLoadedTables
        + ",blockLocalIndexDirectoryEntries=" + blockLocalIndexStats.directoryEntries
        + ",blockLocalIndexSeekCount=" + blockLocalIndexStats.seekCount
        + ",blockLocalIndexHitCount=" + blockLocalIndexStats.hitCount
        + ",blockLocalIndexFallbackCount=" + blockLocalIndexStats.fallbackCount
        + ",entryAnchorIndexTables=" + blockLocalIndexStats.entryAnchorDeclaredTables
        + ",entryAnchorIndexDirectoryLoadedTables=" + blockLocalIndexStats.entryAnchorDirectoryLoadedTables
        + ",entryAnchorIndexDirectoryEntries=" + blockLocalIndexStats.entryAnchorDirectoryEntries
        + ",entryAnchorIndexSeekCount=" + blockLocalIndexStats.entryAnchorSeekCount
        + ",entryAnchorIndexHitCount=" + blockLocalIndexStats.entryAnchorHitCount
        + ",entryAnchorIndexFallbackCount=" + blockLocalIndexStats.entryAnchorFallbackCount;
  }

  private BlockLocalIndexStats blockLocalIndexStats() {
    BlockLocalIndexStats stats = new BlockLocalIndexStats();
    for (TableAndFile tableAndFile : cache.asMap().values()) {
      Table table = tableAndFile.getTable();
      if (table.isBlockLocalIndexDeclaredForStats()) {
        stats.declaredTables++;
      }
      if (table.isBlockLocalIndexDirectoryLoadedForStats()) {
        stats.directoryLoadedTables++;
      }
      stats.directoryEntries += table.getBlockLocalIndexDirectoryEntriesForStats();
      stats.seekCount += table.getBlockLocalIndexSeekCountForStats();
      stats.hitCount += table.getBlockLocalIndexHitCountForStats();
      stats.fallbackCount += table.getBlockLocalIndexFallbackCountForStats();
      stats.tableIndexSeeks += table.getTableIndexSeekCountForStats();
      stats.tableDataBlockOpens += table.getTableDataBlockOpenCountForStats();
      stats.tableDataBlockSeeks += table.getTableDataBlockSeekCountForStats();
      stats.tableIndexCacheHits += table.getTableIndexCacheHitCountForStats();
      stats.tableIndexCacheMisses += table.getTableIndexCacheMissCountForStats();
      stats.tableLastBlockHits += table.getTableLastBlockHitCountForStats();
      stats.tableLastBlockMisses += table.getTableLastBlockMissCountForStats();
      stats.tablePointGetLastBlockHits += table.getTablePointGetLastBlockHitCountForStats();
      stats.tablePointGetSlotHits += table.getTablePointGetSlotHitCountForStats();
      stats.tablePointGetSlotMisses += table.getTablePointGetSlotMissCountForStats();
      stats.tablePointGetSlotCollisions += table.getTablePointGetSlotCollisionCountForStats();
      stats.tableBatchDataBlockGroups += table.getTableBatchDataBlockGroupCountForStats();
      stats.tableBatchDataBlockKeys += table.getTableBatchDataBlockKeyCountForStats();
      stats.tableBatchDenseBlockGroups += table.getTableBatchDenseBlockGroupCountForStats();
      stats.tableBatchDenseBlockKeys += table.getTableBatchDenseBlockKeyCountForStats();
      stats.tableBatchSeekAllCount += table.getTableBatchSeekAllCountForStats();
      stats.tableDirectReadBlockCacheHits += table.getTableDirectReadBlockCacheHitCountForStats();
      stats.tableDirectReadBlockCacheMisses += table.getTableDirectReadBlockCacheMissCountForStats();
      stats.tableDirectReadBlockReads += table.getTableDirectReadBlockReadCountForStats();
      stats.blockSeekIndexHits += table.getBlockSeekIndexHitCountForStats();
      stats.blockSeekIndexMisses += table.getBlockSeekIndexMissCountForStats();
      stats.blockSeekIndexFallbacks += table.getBlockSeekIndexFallbackCountForStats();
      if (table.isEntryAnchorIndexDeclaredForStats()) {
        stats.entryAnchorDeclaredTables++;
      }
      if (table.isEntryAnchorIndexDirectoryLoadedForStats()) {
        stats.entryAnchorDirectoryLoadedTables++;
      }
      stats.entryAnchorDirectoryEntries += table.getEntryAnchorIndexDirectoryEntriesForStats();
      stats.entryAnchorSeekCount += table.getEntryAnchorIndexSeekCountForStats();
      stats.entryAnchorHitCount += table.getEntryAnchorIndexHitCountForStats();
      stats.entryAnchorFallbackCount += table.getEntryAnchorIndexFallbackCountForStats();
    }
    return stats;
  }

  private static final class BlockLocalIndexStats {
    private long tableIndexSeeks;
    private long tableDataBlockOpens;
    private long tableDataBlockSeeks;
    private long tableIndexCacheHits;
    private long tableIndexCacheMisses;
    private long tableLastBlockHits;
    private long tableLastBlockMisses;
    private long tablePointGetLastBlockHits;
    private long tablePointGetSlotHits;
    private long tablePointGetSlotMisses;
    private long tablePointGetSlotCollisions;
    private long tableBatchDataBlockGroups;
    private long tableBatchDataBlockKeys;
    private long tableBatchDenseBlockGroups;
    private long tableBatchDenseBlockKeys;
    private long tableBatchSeekAllCount;
    private long tableDirectReadBlockCacheHits;
    private long tableDirectReadBlockCacheMisses;
    private long tableDirectReadBlockReads;
    private long blockSeekIndexHits;
    private long blockSeekIndexMisses;
    private long blockSeekIndexFallbacks;
    private long declaredTables;
    private long directoryLoadedTables;
    private long directoryEntries;
    private long seekCount;
    private long hitCount;
    private long fallbackCount;
    private long entryAnchorDeclaredTables;
    private long entryAnchorDirectoryLoadedTables;
    private long entryAnchorDirectoryEntries;
    private long entryAnchorSeekCount;
    private long entryAnchorHitCount;
    private long entryAnchorFallbackCount;
  }

  private static final class TableAndFile {
    private final Table table;

    private TableAndFile(File databaseDir,
                         long fileNumber,
                         UserComparator userComparator,
                         boolean verifyChecksums,
                         Options options,
                         BlockCache blockCache) throws IOException {
      String tableFileName = Filename.tableFileName(fileNumber);
      File tableFile = new File(databaseDir, tableFileName);

      FileInputStream fis = null;
      try {
        fis = new FileInputStream(tableFile);
        FileChannel fileChannel = fis.getChannel();

        if (LDBFactory.USE_MMAP) {
          table = new MMapTable(
              tableFile.getAbsolutePath(),
              fileChannel,
              userComparator,
              verifyChecksums,
              options,
              blockCache
          );
          Closeables.closeQuietly(fis);
        } else {
          table = new FileChannelTable(
              tableFile.getAbsolutePath(),
              fileChannel,
              userComparator,
              verifyChecksums,
              options,
              blockCache
          );
        }
      } catch (IOException ioe) {
        Closeables.closeQuietly(fis);
        throw ioe;
      } catch (RuntimeException e) {
        Closeables.closeQuietly(fis);
        throw e;
      }
    }

    public Table getTable() {
      return table;
    }
  }
}
