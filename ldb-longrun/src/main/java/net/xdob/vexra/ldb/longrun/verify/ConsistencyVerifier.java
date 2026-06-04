package net.xdob.vexra.ldb.longrun.verify;

import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.longrun.model.CommittedState;
import net.xdob.vexra.ldb.longrun.model.Ledger;
import net.xdob.vexra.ldb.longrun.model.ValueModel;

import java.util.Map;

/**
 * longrun 一致性校验器。
 */
public final class ConsistencyVerifier {
  /**
   * 校验当前 active key 集合。
   *
   * @param db 数据库实例
   * @param state 已提交状态
   */
  public void verifyActive(LDB db, CommittedState state) {
    for (Map.Entry<Long, Long> entry : state.active().entrySet()) {
      byte[] value = db.get(ValueModel.key(entry.getKey()));
      ValueModel.verify(value, entry.getKey(), entry.getValue());
    }
  }

  /**
   * 校验最近账本。
   *
   * @param db 数据库实例
   * @param state 已提交状态
   * @param ledger 最近操作账本
   */
  public void verifyLedger(LDB db, CommittedState state, Ledger ledger) {
    for (Ledger.Entry entry : ledger.entries()) {
      if (entry.kind() == Ledger.Kind.WRITE) {
        Long current = state.active().get(entry.keyId());
        if (current != null && current == entry.sequence()) {
          ValueModel.verify(db.get(ValueModel.key(entry.keyId())), entry.keyId(), entry.sequence());
        }
      } else if (entry.kind() == Ledger.Kind.REMOVE
          && !state.active().containsKey(entry.keyId())
          && db.get(ValueModel.key(entry.keyId())) != null) {
        throw new IllegalStateException("removed key still exists: " + entry.keyId());
      }
    }
  }
}
