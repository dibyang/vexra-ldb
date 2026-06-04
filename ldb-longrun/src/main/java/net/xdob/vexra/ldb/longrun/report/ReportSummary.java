package net.xdob.vexra.ldb.longrun.report;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * longrun 报告摘要。
 */
public final class ReportSummary {
  private final Map<String, String> values = new LinkedHashMap<>();

  public void put(String key, Object value) {
    values.put(key, String.valueOf(value));
  }

  public String get(String key) {
    return values.get(key);
  }

  public Map<String, String> values() {
    return values;
  }
}
