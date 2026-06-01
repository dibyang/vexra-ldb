package net.xdob.vexra.ldb;

import java.util.Set;

/**
 * 写批次只读视图，供插件观察本次写入涉及的列族和操作数量。
 *
 * 该接口不暴露 put/delete 等修改方法，用于后续把默认插件能力收敛为只读观察。
 */
public interface WriteBatchView {
  Set<LdbColumnFamily> getColumnFamilies();

  boolean isEmpty();

  int size();
}
