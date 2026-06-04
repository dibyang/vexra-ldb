package net.xdob.vexra.ldb.longrun.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FaultKindTest {
  @Test
  void parsesKindList() {
    assertEquals(2, FaultKind.parseList("truncate,bit-flip").size());
    assertEquals(FaultKind.ZERO_RANGE, FaultKind.parse("zero-range"));
  }

  @Test
  void rejectsUnknownKind() {
    assertThrows(IllegalArgumentException.class, () -> FaultKind.parse("bad"));
  }
}
