package net.xdob.vexra.ldb;

import java.io.File;
import java.util.List;

/**
 * LDB 插件上下文，向插件暴露受控的数据库能力。
 *
 * 插件不应保存或关闭底层 LDB 对象，也不应绕过该上下文直接修改核心状态。
 */
public interface LdbPluginContext {

  /**
   * 返回当前数据库目录。
   */
  File getDatabaseDir();

  /**
   * 返回当前数据库配置。
   */
  Options getOptions();

  /**
   * 返回当前数据库配置的只读快照。
   *
   * 新插件应优先使用该方法读取配置，避免通过 {@link #getOptions()} 误改运行时配置。
   */
  default OptionsView getOptionsView() {
    return getOptions();
  }

  /**
   * 返回已注册列族。
   */
  List<LdbColumnFamily> getColumnFamilies();

  /**
   * 按列族 id 获取列族定义。
   */
  LdbColumnFamily getColumnFamily(int cfId);

  /**
   * 创建新的写批次。
   */
  LdbWriteBatch createWriteBatch();

  /**
   * 为指定列族创建快照游标，调用方负责关闭返回对象。
   */
  SnapshotCursor newSnapshotCursor(LdbColumnFamily cf);

  /**
   * 查询 LDB 属性。
   */
  String getProperty(String name);
}
