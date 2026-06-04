package net.xdob.vexra.ldb.impl;

import java.util.concurrent.atomic.AtomicInteger;

public class FileMetaData {
  private final int cfId;
  private final long number;

  /**
   * File size in bytes
   */
  private final long fileSize;

  /**
   * Smallest internal key served by table
   */
  private final InternalKey smallest;

  /**
   * Largest internal key served by table
   */
  private final InternalKey largest;

  /**
   * Whether this table may contain range tombstones.
   */
  private final boolean hasRangeDeletes;

  /**
   * Seeks allowed until compaction
   */
  // todo this mutable state should be moved elsewhere
  private final AtomicInteger allowedSeeks = new AtomicInteger(1 << 30);

  public FileMetaData(int cfId, long number, long fileSize, InternalKey smallest, InternalKey largest) {
    this(cfId, number, fileSize, smallest, largest, true);
  }

  public FileMetaData(int cfId, long number, long fileSize, InternalKey smallest,
                      InternalKey largest, boolean hasRangeDeletes) {
    this.cfId = cfId;
    this.number = number;
    this.fileSize = fileSize;
    this.smallest = smallest;
    this.largest = largest;
    this.hasRangeDeletes = hasRangeDeletes;
  }

  public int getCfId() {
    return cfId;
  }

  public long getFileSize() {
    return fileSize;
  }

  public long getNumber() {
    return number;
  }

  public InternalKey getSmallest() {
    return smallest;
  }

  public InternalKey getLargest() {
    return largest;
  }

  public boolean hasRangeDeletes() {
    return hasRangeDeletes;
  }

  public int getAllowedSeeks() {
    return allowedSeeks.get();
  }

  public void setAllowedSeeks(int allowedSeeks) {
    this.allowedSeeks.set(allowedSeeks);
  }

  public void decrementAllowedSeeks() {
    allowedSeeks.getAndDecrement();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("FileMetaData");
    sb.append("{number=").append(number);
    sb.append(", fileSize=").append(fileSize);
    sb.append(", smallest=").append(smallest);
    sb.append(", largest=").append(largest);
    sb.append(", hasRangeDeletes=").append(hasRangeDeletes);
    sb.append(", allowedSeeks=").append(allowedSeeks);
    sb.append('}');
    return sb.toString();
  }
}
