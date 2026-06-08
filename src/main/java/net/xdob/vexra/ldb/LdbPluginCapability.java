package net.xdob.vexra.ldb;

/**
 * LDB 插件能力声明。
 *
 * <p>能力声明用于运行时观测、调度和后续兼容收紧，不作为安全边界。
 */
public enum LdbPluginCapability {
  /**
   * 插件只观察写入事件，不修改写批次。
   */
  OBSERVE_WRITE,

  /**
   * 插件允许在提交前修改写批次。
   */
  MUTATE_WRITE_BATCH,

  /**
   * 插件参与 checkpoint 前后回调。
   */
  CHECKPOINT_HOOK,

  /**
   * 插件读取属性、列族或数据库目录等元数据。
   */
  METADATA_READ
}
