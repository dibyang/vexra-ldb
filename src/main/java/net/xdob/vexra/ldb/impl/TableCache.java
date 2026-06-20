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
            return new TableAndFile(
                databaseDir,
                fileNumber,
                userComparator,
                verifyChecksums,
                options,
                blockCache
            );
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
    return "tableRequests=" + tableRequestCount.get()
        + ",tableLoads=" + tableLoadCount.get()
        + ",iteratorRequests=" + iteratorRequestCount.get()
        + ",mayContainRequests=" + mayContainRequestCount.get()
        + ",mayContainTrue=" + mayContainTrueCount.get()
        + ",mayContainFalse=" + mayContainFalseCount.get()
        + ",approximateOffsetRequests=" + approximateOffsetRequestCount.get();
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
