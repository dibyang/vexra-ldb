package net.xdob.vexra.ldb;

/**
 * 插件写入事件，只读描述一次写入回调的上下文。
 *
 * `committed=false` 表示写入尚未进入 WAL/MemTable，可用于写前校验；
 * `committed=true` 表示写入已经提交，插件失败不得被理解为数据回滚。
 */
public interface WriteEvent {
  WriteBatchView getBatch();

  WriteOptions getWriteOptions();

  Snapshot getSnapshot();

  boolean isCommitted();
}
