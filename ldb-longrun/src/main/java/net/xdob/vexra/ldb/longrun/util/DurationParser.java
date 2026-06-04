package net.xdob.vexra.ldb.longrun.util;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 解析 longrun profile 中的持续时间。
 */
public final class DurationParser {
  private DurationParser() {
  }

  /**
   * 将 `5m`、`12h`、`7d` 这类配置解析为毫秒。
   *
   * @param value 配置文本
   * @return 毫秒数
   */
  public static long parseMillis(String value) {
    if (value == null) {
      throw new IllegalArgumentException("duration must not be null");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("duration must not be empty");
    }
    int split = normalized.length() - 1;
    char unit = normalized.charAt(split);
    long multiplier;
    if (unit >= '0' && unit <= '9') {
      split = normalized.length();
      multiplier = 1L;
    } else if (unit == 's') {
      multiplier = TimeUnit.SECONDS.toMillis(1);
    } else if (unit == 'm') {
      multiplier = TimeUnit.MINUTES.toMillis(1);
    } else if (unit == 'h') {
      multiplier = TimeUnit.HOURS.toMillis(1);
    } else if (unit == 'd') {
      multiplier = TimeUnit.DAYS.toMillis(1);
    } else {
      throw new IllegalArgumentException("unsupported duration unit: " + value);
    }
    long amount = Long.parseLong(normalized.substring(0, split));
    if (amount < 0) {
      throw new IllegalArgumentException("duration must be >= 0: " + value);
    }
    return amount * multiplier;
  }
}
