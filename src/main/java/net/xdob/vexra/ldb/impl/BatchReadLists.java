package net.xdob.vexra.ldb.impl;

import java.util.ArrayList;

/**
 * 批量读热路径使用的列表创建工具。
 *
 * <p>MultiGet 需要按输入 key 的原始下标回填结果，因此结果列表必须在创建时已经
 * 具备目标 size。这里直接填充 null，避免通过 {@code Collections.nCopies} 创建额外的
 * 中间列表对象。</p>
 */
final class BatchReadLists {
  private BatchReadLists() {
  }

  /**
   * 创建一个指定大小、内容全部为 null 的 {@link ArrayList}。
   */
  static <T> ArrayList<T> newNullArrayList(int size) {
    ArrayList<T> list = new ArrayList<T>(size);
    for (int i = 0; i < size; i++) {
      list.add(null);
    }
    return list;
  }
}
