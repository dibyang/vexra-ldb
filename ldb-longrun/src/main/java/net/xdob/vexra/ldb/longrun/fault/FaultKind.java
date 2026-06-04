package net.xdob.vexra.ldb.longrun.fault;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件损坏类型。
 */
public enum FaultKind {
  TRUNCATE("truncate"),
  BIT_FLIP("bit-flip"),
  ZERO_RANGE("zero-range"),
  RANDOM_RANGE("random-range"),
  PARTIAL_PAGE("partial-page");

  private final String text;

  FaultKind(String text) {
    this.text = text;
  }

  public String text() {
    return text;
  }

  public static FaultKind parse(String value) {
    for (FaultKind kind : values()) {
      if (kind.text.equals(value)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unknown fault kind: " + value);
  }

  public static List<FaultKind> parseList(String value) {
    List<FaultKind> kinds = new ArrayList<>();
    if (value == null || value.trim().isEmpty()) {
      return kinds;
    }
    String[] parts = value.split(",");
    for (String part : parts) {
      kinds.add(parse(part.trim()));
    }
    return kinds;
  }
}
