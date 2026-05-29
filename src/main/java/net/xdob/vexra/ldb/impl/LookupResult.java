package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.util.Slice;

import static java.util.Objects.requireNonNull;

public class LookupResult {
  public static LookupResult ok(LookupKey key, Slice value) {
    return new LookupResult(key, value, false, -1);
  }

  public static LookupResult ok(LookupKey key, Slice value, long sequenceNumber) {
    return new LookupResult(key, value, false, sequenceNumber);
  }

  public static LookupResult deleted(LookupKey key) {
    return new LookupResult(key, null, true, -1);
  }

  public static LookupResult deleted(LookupKey key, long sequenceNumber) {
    return new LookupResult(key, null, true, sequenceNumber);
  }

  private final LookupKey key;
  private final Slice value;
  private final boolean deleted;
  private final long sequenceNumber;

  private LookupResult(LookupKey key, Slice value, boolean deleted, long sequenceNumber) {
    requireNonNull(key, "key is null");
    this.key = key;
    if (value != null) {
      this.value = value.slice();
    } else {
      this.value = null;
    }
    this.deleted = deleted;
    this.sequenceNumber = sequenceNumber;
  }

  public LookupKey getKey() {
    return key;
  }

  public Slice getValue() {
    if (value == null) {
      return null;
    }
    return value;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public long getSequenceNumber() {
    return sequenceNumber;
  }
}
