package net.xdob.vexra.ldb.longrun.metrics;

import net.xdob.vexra.ldb.longrun.model.CommittedState;

/**
 * metrics 采样所需的运行统计快照。
 */
public final class RunStats {
  private final long operations;
  private final long reads;
  private final long writes;
  private final long removes;
  private final long commits;
  private final long reopenChecks;
  private final long recoveryChecks;

  public RunStats(long operations, long reads, long writes, long removes,
                  long commits, long reopenChecks, long recoveryChecks) {
    this.operations = operations;
    this.reads = reads;
    this.writes = writes;
    this.removes = removes;
    this.commits = commits;
    this.reopenChecks = reopenChecks;
    this.recoveryChecks = recoveryChecks;
  }

  public static RunStats fromState(CommittedState state) {
    return new RunStats(state.operations(), state.reads(), state.writes(), state.removes(),
        state.commits(), 0, 0);
  }

  public static RunStats fromState(CommittedState state, long reopenChecks, long recoveryChecks) {
    return new RunStats(state.operations(), state.reads(), state.writes(), state.removes(),
        state.commits(), reopenChecks, recoveryChecks);
  }

  public long operations() {
    return operations;
  }

  public long reads() {
    return reads;
  }

  public long writes() {
    return writes;
  }

  public long removes() {
    return removes;
  }

  public long commits() {
    return commits;
  }

  public long reopenChecks() {
    return reopenChecks;
  }

  public long recoveryChecks() {
    return recoveryChecks;
  }
}
