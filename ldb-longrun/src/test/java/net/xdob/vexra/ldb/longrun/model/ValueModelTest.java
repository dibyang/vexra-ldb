package net.xdob.vexra.ldb.longrun.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ValueModelTest {
  @Test
  void verifiesEncodedValue() {
    byte[] value = ValueModel.encode(7, 1, 2, 64);
    ValueModel.verify(value, 1, 2);
  }

  @Test
  void detectsCorruption() {
    byte[] value = ValueModel.encode(7, 1, 2, 64);
    value[value.length - 1] ^= 1;
    assertThrows(IllegalStateException.class, () -> ValueModel.verify(value, 1, 2));
  }
}
