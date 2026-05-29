package net.xdob.vexra.ldb.impl;

import com.google.common.collect.Maps;
import net.xdob.vexra.ldb.util.AbstractSeekingIterator;
import net.xdob.vexra.ldb.util.DbIterator;
import net.xdob.vexra.ldb.util.Slice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

public final class SnapshotSeekingIterator
    extends AbstractSeekingIterator<Slice, Slice> implements AutoCloseable {
  private final DbIterator iterator;
  private final SnapshotImpl snapshot;
  private final Comparator<Slice> userComparator;
  private final List<RangeTombstone> rangeTombstones = new ArrayList<>();

  public SnapshotSeekingIterator(DbIterator iterator, SnapshotImpl snapshot, Comparator<Slice> userComparator) {
    this.iterator = iterator;
    this.snapshot = snapshot;
    this.userComparator = userComparator;
    this.snapshot.getVersion().retain();
  }

  @Override
  public void close() {
    this.snapshot.getVersion().release();
    this.iterator.close();
  }

  @Override
  protected void seekToFirstInternal() {
    rangeTombstones.clear();
    iterator.seekToFirst();
    findNextUserEntry(null, null);
  }

  @Override
  protected void seekInternal(Slice targetKey) {
    rangeTombstones.clear();
    iterator.seekToFirst();
    findNextUserEntry(null, targetKey);
  }

  @Override
  protected Entry<Slice, Slice> getNextElement() {
    if (!iterator.hasNext()) {
      return null;
    }

    Entry<InternalKey, Slice> next = iterator.next();

    // find the next user entry after the key we are about to return
    findNextUserEntry(next.getKey().getUserKey(), null);

    return Maps.immutableEntry(next.getKey().getUserKey(), next.getValue());
  }

  private void findNextUserEntry(Slice deletedKey, Slice lowerBound) {
    // if there are no more entries, we are done
    if (!iterator.hasNext()) {
      return;
    }

    do {
      // Peek the next entry and parse the key
      InternalKey internalKey = iterator.peek().getKey();

      // skip entries created after our snapshot
      if (internalKey.getSequenceNumber() > snapshot.getLastSequence()) {
        iterator.next();
        continue;
      }

      // if the next entry is a deletion, skip all subsequent entries for that key
      if (internalKey.getValueType() == ValueType.DELETE_RANGE) {
        rangeTombstones.add(new RangeTombstone(
            internalKey.getUserKey(), iterator.peek().getValue(), internalKey.getSequenceNumber()));
        iterator.next();
        continue;
      }

      if (internalKey.getValueType() == ValueType.DELETION) {
        deletedKey = internalKey.getUserKey();
      } else if (internalKey.getValueType() == ValueType.VALUE) {
        // is this value masked by a prior deletion record?
        if ((deletedKey == null || userComparator.compare(internalKey.getUserKey(), deletedKey) > 0)
            && (lowerBound == null || userComparator.compare(internalKey.getUserKey(), lowerBound) >= 0)
            && !isCoveredByRangeTombstone(internalKey.getUserKey(), internalKey.getSequenceNumber())) {
          return;
        }
      }
      iterator.next();
    } while (iterator.hasNext());
  }

  private boolean isCoveredByRangeTombstone(Slice userKey, long valueSequence) {
    Iterator<RangeTombstone> iterator = rangeTombstones.iterator();
    while (iterator.hasNext()) {
      RangeTombstone tombstone = iterator.next();
      if (userComparator.compare(userKey, tombstone.endKey) >= 0) {
        iterator.remove();
        continue;
      }
      if (tombstone.sequenceNumber > valueSequence
          && userComparator.compare(tombstone.beginKey, userKey) <= 0
          && userComparator.compare(userKey, tombstone.endKey) < 0) {
        return true;
      }
    }
    return false;
  }

  private static final class RangeTombstone {
    private final Slice beginKey;
    private final Slice endKey;
    private final long sequenceNumber;

    private RangeTombstone(Slice beginKey, Slice endKey, long sequenceNumber) {
      this.beginKey = beginKey;
      this.endKey = endKey;
      this.sequenceNumber = sequenceNumber;
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("SnapshotSeekingIterator");
    sb.append("{snapshot=").append(snapshot);
    sb.append(", iterator=").append(iterator);
    sb.append('}');
    return sb.toString();
  }
}
