package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.Slice;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_LONG;

public class InternalUserComparator
    implements UserComparator {
  private final InternalKeyComparator internalKeyComparator;

  public InternalUserComparator(InternalKeyComparator internalKeyComparator) {
    this.internalKeyComparator = internalKeyComparator;
  }

  @Override
  public int compare(Slice left, Slice right) {
    checkArgument(left.length() >= SIZE_OF_LONG, "left must be at least %s bytes", SIZE_OF_LONG);
    checkArgument(right.length() >= SIZE_OF_LONG, "right must be at least %s bytes", SIZE_OF_LONG);

    int userKeyCompare = internalKeyComparator.getUserComparator().compare(
        left.slice(0, left.length() - SIZE_OF_LONG),
        right.slice(0, right.length() - SIZE_OF_LONG));
    if (userKeyCompare != 0) {
      return userKeyCompare;
    }

    long leftSequence = SequenceNumber.unpackSequenceNumber(left.getLong(left.length() - SIZE_OF_LONG));
    long rightSequence = SequenceNumber.unpackSequenceNumber(right.getLong(right.length() - SIZE_OF_LONG));
    return Long.compare(rightSequence, leftSequence);
  }

  @Override
  public String name() {
    return internalKeyComparator.name();
  }

  @Override
  public Slice findShortestSeparator(
      Slice start,
      Slice limit) {
    // Attempt to shorten the user portion of the key
    Slice startUserKey = new InternalKey(start).getUserKey();
    Slice limitUserKey = new InternalKey(limit).getUserKey();

    Slice shortestSeparator = internalKeyComparator.getUserComparator().findShortestSeparator(startUserKey, limitUserKey);

    if (internalKeyComparator.getUserComparator().compare(startUserKey, shortestSeparator) < 0) {
      // User key has become larger.  Tack on the earliest possible
      // number to the shortened user key.
      InternalKey newInternalKey = new InternalKey(shortestSeparator, MAX_SEQUENCE_NUMBER, ValueType.VALUE);
      checkState(compare(start, newInternalKey.encode()) < 0); // todo
      checkState(compare(newInternalKey.encode(), limit) < 0); // todo

      return newInternalKey.encode();
    }

    return start;
  }

  @Override
  public Slice findShortSuccessor(Slice key) {
    Slice userKey = new InternalKey(key).getUserKey();
    Slice shortSuccessor = internalKeyComparator.getUserComparator().findShortSuccessor(userKey);

    if (internalKeyComparator.getUserComparator().compare(userKey, shortSuccessor) < 0) {
      // User key has become larger.  Tack on the earliest possible
      // number to the shortened user key.
      InternalKey newInternalKey = new InternalKey(shortSuccessor, MAX_SEQUENCE_NUMBER, ValueType.VALUE);
      checkState(compare(key, newInternalKey.encode()) < 0); // todo

      return newInternalKey.encode();
    }

    return key;
  }
}
