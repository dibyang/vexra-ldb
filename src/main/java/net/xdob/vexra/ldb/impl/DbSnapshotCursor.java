package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.RawCursor;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.util.Slice;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

public final class DbSnapshotCursor implements SnapshotCursor {
  private final RawCursor rawCursor;
  private final SnapshotImpl snapshot;
  private final java.util.Comparator<Slice> userComparator;

  private boolean valid;
  private byte[] currentKey;
  private byte[] currentValue;
  private boolean closed;
  private final Runnable closeListener;
  private final List<RangeTombstone> rangeTombstones = new ArrayList<>();
  private List<VisibleEntry> reverseEntries;
  private int reverseIndex = -1;

  public DbSnapshotCursor(RawCursor rawCursor,
                          SnapshotImpl snapshot,
                          InternalKeyComparator comparator) {
    this(rawCursor, snapshot, comparator, null);
  }

  public DbSnapshotCursor(RawCursor rawCursor,
                          SnapshotImpl snapshot,
                          InternalKeyComparator comparator,
                          Runnable closeListener) {
    this.rawCursor = requireNonNull(rawCursor, "rawCursor is null");
    requireNonNull(comparator, "comparator is null");
    this.userComparator = comparator.getUserComparator();
    this.snapshot = requireNonNull(snapshot, "snapshot is null");
    this.closeListener = closeListener;
    this.snapshot.getVersion().retain();
    this.valid = false;
    this.closed = false;
  }

  @Override
  public boolean isValid() {
    return valid;
  }

  @Override
  public void seekToFirst() {
    clearReverseState();
    rangeTombstones.clear();
    rawCursor.seekToFirst();
    positionToVisible(null);
  }

  @Override
  public void seekToLast() {
    buildReverseEntries();
    reverseIndex = reverseEntries.size() - 1;
    positionToReverseEntry();
  }

  @Override
  public void seek(byte[] target) {
    requireNonNull(target, "target is null");
    clearReverseState();
    rangeTombstones.clear();
    rawCursor.seekToFirst();
    positionToVisible(new Slice(target));
  }

  @Override
  public void seekForPrev(byte[] target) {
    requireNonNull(target, "target is null");
    Slice targetKey = new Slice(target);
    buildReverseEntries();
    reverseIndex = -1;
    for (int i = reverseEntries.size() - 1; i >= 0; i--) {
      if (userComparator.compare(new Slice(reverseEntries.get(i).key), targetKey) <= 0) {
        reverseIndex = i;
        break;
      }
    }
    positionToReverseEntry();
  }

  @Override
  public void next() {
    if (!valid) {
      throw new NoSuchElementException("Cursor is not valid");
    }
    if (reverseEntries != null) {
      reverseIndex++;
      positionToReverseEntry();
      return;
    }
    clearReverseState();
    positionToVisible(null);
  }

  @Override
  public void prev() {
    if (!valid) {
      throw new NoSuchElementException("Cursor is not valid");
    }
    if (reverseEntries == null) {
      buildReverseEntries();
      reverseIndex = findReverseIndex(currentKey) - 1;
    } else {
      reverseIndex--;
    }
    positionToReverseEntry();
  }

  @Override
  public byte[] key() {
    if (!valid) {
      throw new NoSuchElementException("Cursor is not valid");
    }
    return currentKey;
  }

  @Override
  public byte[] value() {
    if (!valid) {
      throw new NoSuchElementException("Cursor is not valid");
    }
    return currentValue;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      rawCursor.close();
    } finally {
      try {
        snapshot.getVersion().release();
      } finally {
        if (closeListener != null) {
          closeListener.run();
        }
      }
    }
  }

  private void clearReverseState() {
    reverseEntries = null;
    reverseIndex = -1;
  }

  private void buildReverseEntries() {
    List<VisibleEntry> entries = new ArrayList<>();
    rangeTombstones.clear();
    rawCursor.seekToFirst();
    while (true) {
      positionToVisible(null);
      if (!valid) {
        break;
      }
      entries.add(new VisibleEntry(currentKey, currentValue));
    }
    reverseEntries = entries;
  }

  private int findReverseIndex(byte[] key) {
    Slice target = new Slice(key);
    for (int i = 0; i < reverseEntries.size(); i++) {
      if (userComparator.compare(new Slice(reverseEntries.get(i).key), target) == 0) {
        return i;
      }
    }
    return -1;
  }

  private void positionToReverseEntry() {
    if (reverseEntries == null || reverseIndex < 0 || reverseIndex >= reverseEntries.size()) {
      valid = false;
      currentKey = null;
      currentValue = null;
      return;
    }
    VisibleEntry entry = reverseEntries.get(reverseIndex);
    currentKey = Arrays.copyOf(entry.key, entry.key.length);
    currentValue = Arrays.copyOf(entry.value, entry.value.length);
    valid = true;
  }

  private void positionToVisible(Slice lowerBound) {
    valid = false;
    currentKey = null;
    currentValue = null;

    while (rawCursor.isValid()) {
      InternalKey ik = rawCursor.key();
      Slice userKey = ik.getUserKey();

      // 先在当前 userKey 范围内，跳过所有 snapshot 之后的版本
      while (rawCursor.isValid()) {
        ik = rawCursor.key();
        if (userComparator.compare(ik.getUserKey(), userKey) != 0) {
          break;
        }
        if (ik.getSequenceNumber() <= snapshot.getLastSequence()) {
          break;
        }
        rawCursor.next();
      }

      if (!rawCursor.isValid()) {
        return;
      }

      ik = rawCursor.key();

      // 如果已经切到下一个 userKey，重新走下一轮
      if (userComparator.compare(ik.getUserKey(), userKey) != 0) {
        continue;
      }

      if (ik.getValueType() == ValueType.DELETE_RANGE) {
        rangeTombstones.add(new RangeTombstone(ik.getUserKey(), rawCursor.value(), ik.getSequenceNumber()));
        rawCursor.next();
        continue;
      }

      // 现在 ik 是这个 userKey 的第一个 <= snapshot 的版本
      if (ik.getValueType() == ValueType.DELETION) {
        skipAllVersionsOfCurrentUserKey(userKey);
        continue;
      }

      if (lowerBound != null && userComparator.compare(userKey, lowerBound) < 0) {
        skipAllVersionsOfCurrentUserKey(userKey);
        continue;
      }

      if (isCoveredByRangeTombstone(userKey, ik.getSequenceNumber())) {
        skipAllVersionsOfCurrentUserKey(userKey);
        continue;
      }

      byte[] k = ik.getUserKey().getBytes();
      byte[] v = rawCursor.value().getBytes();
      currentKey = Arrays.copyOf(k, k.length);
      currentValue = Arrays.copyOf(v, v.length);

      skipAllVersionsOfCurrentUserKey(userKey);

      valid = true;
      return;
    }
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

  private void skipAllVersionsOfCurrentUserKey(Slice userKey) {
    while (rawCursor.isValid()) {
      InternalKey ik = rawCursor.key();
      if (userComparator.compare(ik.getUserKey(), userKey) != 0) {
        return;
      }
      if (ik.getSequenceNumber() <= snapshot.getLastSequence()
          && ik.getValueType() == ValueType.DELETE_RANGE) {
        rangeTombstones.add(new RangeTombstone(ik.getUserKey(), rawCursor.value(), ik.getSequenceNumber()));
      }
      rawCursor.next();
    }
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

  private static final class VisibleEntry {
    private final byte[] key;
    private final byte[] value;

    private VisibleEntry(byte[] key, byte[] value) {
      this.key = Arrays.copyOf(key, key.length);
      this.value = Arrays.copyOf(value, value.length);
    }
  }
}
