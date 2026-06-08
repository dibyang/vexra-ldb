package net.xdob.vexra.ldb;

import java.util.Map;

/**
 * classpath 插件 provider 入口。
 *
 * <p>provider 由 {@link java.util.ServiceLoader} 发现，但发现本身不代表启用；调用方必须显式选择
 * provider 名称后才会创建插件实例。
 */
public interface LdbPluginProvider {
  /**
   * 返回 provider 暴露给配置层使用的稳定名称。
   */
  String name();

  /**
   * 返回 provider 版本，用于诊断和升级排查。
   */
  String version();

  /**
   * 根据受控配置创建插件实例。
   *
   * @param config 插件私有配置，不包含全局 profile
   * @return 新插件实例
   */
  LdbPlugin create(Map<String, String> config);
}
