package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.util.Slice;

public class LookupKey {
  private final InternalKey key;
  private Slice encodedInternalKey;

  public LookupKey(Slice userKey, long sequenceNumber) {
    key = new InternalKey(userKey, sequenceNumber, ValueType.VALUE);
  }

  public InternalKey getInternalKey() {
    return key;
  }

  /**
   * 返回 SST 查询使用的 encoded internal key。
   *
   * <p>点查在 memtable 命中时不需要编码 internal key，因此这里保持懒加载；
   * 一旦查询落到 SST，同一个 LookupKey 在 bloom、block index 和 table get
   * 之间可以复用这份编码结果，避免 readrandom/MultiGet 热路径重复分配 Slice。</p>
   */
  public Slice getEncodedInternalKey() {
    if (encodedInternalKey == null) {
      encodedInternalKey = key.encode();
    }
    return encodedInternalKey;
  }

  public Slice getUserKey() {
    return key.getUserKey();
  }

  @Override
  public String toString() {
    return key.toString();
  }
}
