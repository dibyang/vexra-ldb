package net.xdob.vexra.ldb.longrun.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DurationParserTest {
  @Test
  void parsesUnits() {
    assertEquals(1000L, DurationParser.parseMillis("1s"));
    assertEquals(60_000L, DurationParser.parseMillis("1m"));
    assertEquals(3_600_000L, DurationParser.parseMillis("1h"));
    assertEquals(86_400_000L, DurationParser.parseMillis("1d"));
    assertEquals(15L, DurationParser.parseMillis("15"));
  }

  @Test
  void rejectsUnknownUnit() {
    assertThrows(IllegalArgumentException.class, () -> DurationParser.parseMillis("1w"));
  }
}
