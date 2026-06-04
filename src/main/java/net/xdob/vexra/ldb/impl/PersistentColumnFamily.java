package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.LdbColumnFamily;

import static java.util.Objects.requireNonNull;

/**
 * 运行时创建列族的轻量定义。
 *
 * 该对象只承载列族 id/name，不持有文件、锁或 MemTable；持久化由
 * `ColumnFamilyRegistry` 负责，打开数据库后再由 `LDbImpl` 创建对应
 * `ColumnFamilyState`。
 */
final class PersistentColumnFamily implements LdbColumnFamily {
  private final int id;
  private final String name;

  PersistentColumnFamily(int id, String name) {
    if (id <= 0) {
      throw new IllegalArgumentException("Column family id must be > 0");
    }
    this.id = id;
    this.name = requireNonNull(name, "name is null");
    if (name.trim().isEmpty()) {
      throw new IllegalArgumentException("Column family name must not be empty");
    }
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return id + ":" + name;
  }
}
