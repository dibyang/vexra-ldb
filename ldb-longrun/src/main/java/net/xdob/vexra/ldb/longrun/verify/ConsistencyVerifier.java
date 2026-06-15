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
  private static final long VERIFY_PROGRESS_INTERVAL_MILLIS = 10_000L;
  private static final long VERIFY_PROGRESS_KEYS = 10_000L;

  /**
   * 最终一致性校验进度回调。
   */
  public interface ProgressListener {
    /**
     * 报告当前 active key 校验进度。
     *
     * @param verified 已完成校验的 key 数
     * @param total 需要校验的 key 总数
     * @param elapsedMillis 本轮校验已耗时毫秒数
     */
    void onProgress(long verified, long total, long elapsedMillis);
  }
  /**
   * 校验当前 active key 集合。
   *
   * @param db 数据库实例
   * @param state 已提交状态
   */
  public void verifyActive(LDB db, CommittedState state) {
    verifyActive(db, state, null);
  }

  /**
   * 校验当前 active key 集合，并按固定间隔报告进度。
   *
   * @param db 数据库实例
   * @param state 已提交状态
   * @param listener 进度回调，可以为空
   */
  public void verifyActive(LDB db, CommittedState state, ProgressListener listener) {
    long total = state.active().size();
    long verified = 0;
    long startMillis = System.currentTimeMillis();
    long lastReportMillis = startMillis;
    long lastReportedVerified = -1;
    if (listener != null) {
      listener.onProgress(verified, total, 0);
      lastReportedVerified = verified;
    }
    for (Map.Entry<Long, Long> entry : state.active().entrySet()) {
      byte[] value = db.get(ValueModel.key(entry.getKey()));
      ValueModel.verify(value, entry.getKey(), entry.getValue());
      verified++;
      if (listener != null) {
        long now = System.currentTimeMillis();
        if (shouldReportProgress(verified, total, now - lastReportMillis)) {
          listener.onProgress(verified, total, now - startMillis);
          lastReportMillis = now;
          lastReportedVerified = verified;
        }
      }
    }
    if (listener != null && lastReportedVerified != verified) {
      listener.onProgress(verified, total, System.currentTimeMillis() - startMillis);
    }
  }

  private static boolean shouldReportProgress(long verified, long total,
                                              long elapsedSinceLastReportMillis) {
    return verified == total
        || verified % VERIFY_PROGRESS_KEYS == 0
        || elapsedSinceLastReportMillis >= VERIFY_PROGRESS_INTERVAL_MILLIS;
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
