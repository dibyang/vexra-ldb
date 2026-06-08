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
   * 返回插件描述信息，用于运行时观测、失败策略和后续排序评估。
   */
  default LdbPluginDescriptor descriptor() {
    return LdbPluginDescriptor.of(getClass().getName());
  }

  /**
   * 返回被包装的真实插件实例。
   *
   * <p>普通插件保持默认返回自身；排序、隔离、治理等包装器应覆盖该方法，
   * 便于核心运行时判断真实插件是否实现了特定生命周期方法。
   */
  default LdbPlugin unwrap() {
    return this;
  }

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
   * 写入 WAL 和 MemTable 前调用的只读事件入口。
   *
   * 默认实现委托到旧的可变 batch 回调，以保持已有插件兼容；新插件优先实现该方法，
   * 避免无意修改调用方提交的写批次。
   */
  default void beforeWrite(WriteEvent event) throws DBException {
    beforeWrite((LdbWriteBatch) event.getBatch(), event.getWriteOptions());
  }

  /**
   * 写入完成后调用。
   */
  default void afterWrite(LdbWriteBatch updates, WriteOptions options, Snapshot snapshot) throws DBException {
  }

  /**
   * 写入完成后的只读事件入口。
   *
   * 该回调发生在数据已提交之后；抛出异常只表示后置通知失败，不会回滚 WAL 或 MemTable。
   */
  default void afterWrite(WriteEvent event) throws DBException {
    afterWrite((LdbWriteBatch) event.getBatch(), event.getWriteOptions(), event.getSnapshot());
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
