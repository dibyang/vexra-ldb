package net.xdob.vexra.ldb.longrun.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 有限大小最近操作账本。
 */
public final class Ledger {
  public enum Kind {
    READ,
    WRITE,
    REMOVE
  }

  public static final class Entry {
    private final Kind kind;
    private final long keyId;
    private final long sequence;

    Entry(Kind kind, long keyId, long sequence) {
      this.kind = kind;
      this.keyId = keyId;
      this.sequence = sequence;
    }

    public Kind kind() {
      return kind;
    }

    public long keyId() {
      return keyId;
    }

    public long sequence() {
      return sequence;
    }
  }

  private final int limit;
  private final ArrayDeque<Entry> entries = new ArrayDeque<>();

  public Ledger(int limit) {
    this.limit = limit;
  }

  public void add(Kind kind, long keyId, long sequence) {
    entries.addLast(new Entry(kind, keyId, sequence));
    while (entries.size() > limit) {
      entries.removeFirst();
    }
  }

  public List<Entry> entries() {
    return new ArrayList<>(entries);
  }
}
