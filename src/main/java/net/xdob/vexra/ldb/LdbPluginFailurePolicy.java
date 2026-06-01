package net.xdob.vexra.ldb;

/**
 * 插件回调失败策略。
 *
 * 写前、打开前等会影响数据正确性的阶段始终 fail-fast；`RECORD_AND_CONTINUE` 仅用于
 * `afterWrite`、`afterCheckpoint` 等提交后通知阶段，避免插件通知失败误伤已提交数据。
 */
public enum LdbPluginFailurePolicy {
  FAIL_FAST,
  RECORD_AND_CONTINUE
}
