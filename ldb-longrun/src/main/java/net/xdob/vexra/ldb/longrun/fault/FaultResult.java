package net.xdob.vexra.ldb.longrun.fault;

/**
 * 单次 fault injection 结果。
 */
public final class FaultResult {
  private final long eventId;
  private final FaultKind kind;
  private final String status;
  private final String message;
  private final long offset;
  private final long length;
  private final long beforeSize;
  private final long afterSize;
  private final String filePath;

  public FaultResult(long eventId, FaultKind kind, String status, String message,
                     long offset, long length, long beforeSize, long afterSize, String filePath) {
    this.eventId = eventId;
    this.kind = kind;
    this.status = status;
    this.message = message;
    this.offset = offset;
    this.length = length;
    this.beforeSize = beforeSize;
    this.afterSize = afterSize;
    this.filePath = filePath;
  }

  public long eventId() {
    return eventId;
  }

  public FaultKind kind() {
    return kind;
  }

  public String status() {
    return status;
  }

  public String message() {
    return message;
  }

  public long offset() {
    return offset;
  }

  public long length() {
    return length;
  }

  public long beforeSize() {
    return beforeSize;
  }

  public long afterSize() {
    return afterSize;
  }

  public String filePath() {
    return filePath;
  }
}
