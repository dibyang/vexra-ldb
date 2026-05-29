package net.xdob.vexra.ldb;

import java.io.Closeable;
import java.io.File;

/**
 * LDB 插件扩展点，用于让上层模块在不修改 LDB 核心实现的情况下接入列族、
 * 写入、checkpoint 和关闭流程。
 *
 * 实现约束：
 * 1. 回调必须保持轻量，不能执行长时间阻塞的网络或远程调用。
 * 2. 回调抛出的异常会中断对应 LDB 操作，并保留原始 cause。
 * 3. 插件不拥有 LDB 生命周期，只能通过 {@link LdbPluginContext} 使用受控能力。
 */
public interface LdbPlugin extends Closeable {

  /**
   * 在插件加入 {@link Options} 时调用，用于声明列族等打开数据库前必须完成的配置。
   */
  default void configure(Options options) throws DBException {
  }

  /**
   * 在 LDB 打开完成后调用，插件可保存上下文用于后续受控访问。
   */
  default void onOpen(LdbPluginContext context) throws DBException {
  }

  /**
   * 写入 WAL 和 MemTable 前调用。
   */
  default void beforeWrite(LdbWriteBatch updates, WriteOptions options) throws DBException {
  }

  /**
   * 写入完成后调用。
   */
  default void afterWrite(LdbWriteBatch updates, WriteOptions options, Snapshot snapshot) throws DBException {
  }

  /**
   * checkpoint 开始前调用。
   */
  default void beforeCheckpoint(File targetDir) throws DBException {
  }

  /**
   * checkpoint 完成后调用。
   */
  default void afterCheckpoint(File targetDir) throws DBException {
  }

  /**
   * LDB 关闭前调用。
   */
  default void beforeClose() throws DBException {
  }

  @Override
  default void close() {
  }
}
