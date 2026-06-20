package net.xdob.vexra.ldb.table;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class BlockCache {

  public static final class Key {
    private final String tableName;
    private final long offset;
    private final int dataSize;

    public Key(String tableName, long offset, int dataSize) {
      this.tableName = Objects.requireNonNull(tableName, "tableName == null");
      this.offset = offset;
      this.dataSize = dataSize;
    }

    public String getTableName() {
      return tableName;
    }

    public long getOffset() {
      return offset;
    }

    public int getDataSize() {
      return dataSize;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key)) {
        return false;
      }
      Key key = (Key) o;
      return offset == key.offset
          && dataSize == key.dataSize
          && tableName.equals(key.tableName);
    }

    @Override
    public int hashCode() {
      int result = tableName.hashCode();
      result = 31 * result + Long.hashCode(offset);
      result = 31 * result + Integer.hashCode(dataSize);
      return result;
    }

    @Override
    public String toString() {
      return "Key{"
          + "tableName='" + tableName + '\''
          + ", offset=" + offset
          + ", dataSize=" + dataSize
          + '}';
    }
  }

  private final int maxEntries;
  private final LinkedHashMap<Key, Block> lru;
  private final LinkedHashMap<Key, Integer> admission;
  private final AtomicLong hitCount = new AtomicLong();
  private final AtomicLong missCount = new AtomicLong();
  private final AtomicLong putCount = new AtomicLong();
  private final AtomicLong evictionCount = new AtomicLong();
  private final AtomicLong admissionRequestCount = new AtomicLong();
  private final AtomicLong admissionSkipCount = new AtomicLong();
  private final AtomicLong admissionAdmitCount = new AtomicLong();

  public BlockCache(int maxEntries) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be > 0");
    }
    this.maxEntries = maxEntries;
    this.lru = new LinkedHashMap<Key, Block>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Key, Block> eldest) {
        boolean evict = size() > BlockCache.this.maxEntries;
        if (evict) {
          evictionCount.incrementAndGet();
        }
        return evict;
      }
    };
    this.admission = new LinkedHashMap<Key, Integer>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Key, Integer> eldest) {
        return size() > BlockCache.this.maxEntries * 2;
      }
    };
  }

  public Block get(Key key) {
    synchronized (lru) {
      Block block = lru.get(key);
      if (block == null) {
        missCount.incrementAndGet();
      } else {
        hitCount.incrementAndGet();
      }
      return block;
    }
  }

  public void put(Key key, Block block) {
    synchronized (lru) {
      lru.put(key, block);
      admission.remove(key);
      putCount.incrementAndGet();
    }
  }

  public boolean putIfAdmitted(Key key, Block block, int minReads) {
    if (minReads <= 1) {
      put(key, block);
      return true;
    }
    synchronized (lru) {
      admissionRequestCount.incrementAndGet();
      Integer previous = admission.get(key);
      int reads = previous == null ? 1 : previous + 1;
      if (reads < minReads) {
        admission.put(key, reads);
        admissionSkipCount.incrementAndGet();
        return false;
      }
      admission.remove(key);
      lru.put(key, block);
      putCount.incrementAndGet();
      admissionAdmitCount.incrementAndGet();
      return true;
    }
  }

  public void invalidateTable(String tableName) {
    synchronized (lru) {
      lru.entrySet().removeIf(e -> e.getKey().getTableName().equals(tableName));
      admission.entrySet().removeIf(e -> e.getKey().getTableName().equals(tableName));
    }
  }

  public void invalidateAll() {
    synchronized (lru) {
      lru.clear();
      admission.clear();
    }
  }

  public int size() {
    synchronized (lru) {
      return lru.size();
    }
  }

  public int maxEntries() {
    return maxEntries;
  }

  public long hitCount() {
    return hitCount.get();
  }

  public long missCount() {
    return missCount.get();
  }

  public long putCount() {
    return putCount.get();
  }

  public long evictionCount() {
    return evictionCount.get();
  }

  public long admissionRequestCount() {
    return admissionRequestCount.get();
  }

  public long admissionSkipCount() {
    return admissionSkipCount.get();
  }

  public long admissionAdmitCount() {
    return admissionAdmitCount.get();
  }

  public String stats() {
    return "enabled=true"
        + ",maxEntries=" + maxEntries()
        + ",size=" + size()
        + ",hits=" + hitCount()
        + ",misses=" + missCount()
        + ",puts=" + putCount()
        + ",evictions=" + evictionCount()
        + ",admissionRequests=" + admissionRequestCount()
        + ",admissionSkips=" + admissionSkipCount()
        + ",admissionAdmits=" + admissionAdmitCount();
  }
}
