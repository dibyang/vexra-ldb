package net.xdob.vexra.ldb.impl;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import net.xdob.vexra.ldb.util.InternalIterator;
import net.xdob.vexra.ldb.util.Slice;

import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_LONG;

public class MemTable
    implements SeekingIterable<InternalKey, Slice> {
  private final InternalKeyComparator internalKeyComparator;
  private final ConcurrentSkipListMap<InternalKey, Slice> table;
  private final ConcurrentSkipListMap<InternalKey, Slice> rangeTombstones;
  private final AtomicLong approximateMemoryUsage = new AtomicLong();
  private final AtomicLong rangeTombstoneCount = new AtomicLong();

  public MemTable(InternalKeyComparator internalKeyComparator) {
    this.internalKeyComparator = internalKeyComparator;
    table = new ConcurrentSkipListMap<>(internalKeyComparator);
    rangeTombstones = new ConcurrentSkipListMap<>(internalKeyComparator);
  }

  public boolean isEmpty() {
    return table.isEmpty();
  }

  public long approximateMemoryUsage() {
    return approximateMemoryUsage.get();
  }

  public void add(long sequenceNumber, ValueType valueType, Slice key, Slice value) {
    requireNonNull(valueType, "valueType is null");
    requireNonNull(key, "key is null");
    requireNonNull(valueType, "valueType is null");

    InternalKey internalKey = new InternalKey(key, sequenceNumber, valueType);
    table.put(internalKey, value);
    if (valueType == ValueType.DELETE_RANGE) {
      rangeTombstones.put(internalKey, value);
      rangeTombstoneCount.incrementAndGet();
    }

    approximateMemoryUsage.addAndGet(key.length() + SIZE_OF_LONG + value.length());
  }

  public LookupResult get(LookupKey key) {
    requireNonNull(key, "key is null");

    InternalKey internalKey = key.getInternalKey();
    Entry<InternalKey, Slice> pointEntry = null;
    for (Entry<InternalKey, Slice> entry : table.tailMap(internalKey).entrySet()) {
      InternalKey entryKey = entry.getKey();
      if (!entryKey.getUserKey().equals(key.getUserKey())) {
        break;
      }
      if (entryKey.getValueType() == ValueType.VALUE || entryKey.getValueType() == ValueType.DELETION) {
        pointEntry = entry;
        break;
      }
    }

    long pointSequence = pointEntry == null ? -1 : pointEntry.getKey().getSequenceNumber();
    long rangeDeleteSequence = newestCoveringRangeDelete(key, pointSequence);
    if (rangeDeleteSequence >= 0) {
      return LookupResult.deleted(key, rangeDeleteSequence);
    }

    if (pointEntry == null) {
      return null;
    }

    InternalKey entryKey = pointEntry.getKey();
    if (entryKey.getValueType() == ValueType.DELETION) {
      return LookupResult.deleted(key, entryKey.getSequenceNumber());
    }
    return LookupResult.ok(key, pointEntry.getValue(), entryKey.getSequenceNumber());
  }

  private long newestCoveringRangeDelete(LookupKey key, long newerThanSequence) {
    if (rangeTombstoneCount.get() == 0) {
      return -1;
    }
    long newest = -1;
    for (Entry<InternalKey, Slice> entry : rangeTombstones.entrySet()) {
      InternalKey tombstone = entry.getKey();
      if (internalKeyComparator.getUserComparator().compare(tombstone.getUserKey(), key.getUserKey()) > 0) {
        break;
      }
      long tombstoneSequence = tombstone.getSequenceNumber();
      if (tombstoneSequence > key.getInternalKey().getSequenceNumber()
          || tombstoneSequence <= newerThanSequence) {
        continue;
      }
      if (covers(tombstone.getUserKey(), entry.getValue(), key.getUserKey())) {
        newest = Math.max(newest, tombstoneSequence);
      }
    }
    return newest;
  }

  private boolean covers(Slice beginKey, Slice endKey, Slice userKey) {
    return internalKeyComparator.getUserComparator().compare(beginKey, userKey) <= 0
        && internalKeyComparator.getUserComparator().compare(userKey, endKey) < 0;
  }

  @Override
  public MemTableIterator iterator() {
    return new MemTableIterator();
  }

  public class MemTableIterator
      implements InternalIterator {
    private PeekingIterator<Entry<InternalKey, Slice>> iterator;

    public MemTableIterator() {
      iterator = Iterators.peekingIterator(table.entrySet().iterator());
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public void seekToFirst() {
      iterator = Iterators.peekingIterator(table.entrySet().iterator());
    }

    @Override
    public void seek(InternalKey targetKey) {
      iterator = Iterators.peekingIterator(table.tailMap(targetKey).entrySet().iterator());
    }

    @Override
    public InternalEntry peek() {
      Entry<InternalKey, Slice> entry = iterator.peek();
      return new InternalEntry(entry.getKey(), entry.getValue());
    }

    @Override
    public InternalEntry next() {
      Entry<InternalKey, Slice> entry = iterator.next();
      return new InternalEntry(entry.getKey(), entry.getValue());
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}
