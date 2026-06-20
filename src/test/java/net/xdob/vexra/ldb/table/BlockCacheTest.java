package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.util.Slices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockCacheTest {

  @Test
  void shouldCacheImmediatelyWhenAdmissionMinReadsIsOne() {
    BlockCache cache = new BlockCache(4);
    BlockCache.Key key = new BlockCache.Key("table-a", 10, 20);
    Block block = block("a");

    assertTrue(cache.putIfAdmitted(key, block, 1));

    assertSame(block, cache.get(key));
    assertEquals(1, cache.putCount());
    assertEquals(0, cache.admissionRequestCount());
    assertEquals(0, cache.admissionSkipCount());
    assertEquals(0, cache.admissionAdmitCount());
  }

  @Test
  void shouldAdmitBlockOnlyAfterRepeatedDirectReads() {
    BlockCache cache = new BlockCache(4);
    BlockCache.Key key = new BlockCache.Key("table-a", 10, 20);
    Block firstRead = block("first");
    Block secondRead = block("second");

    assertFalse(cache.putIfAdmitted(key, firstRead, 2));
    assertNull(cache.get(key));
    assertTrue(cache.putIfAdmitted(key, secondRead, 2));

    assertSame(secondRead, cache.get(key));
    assertEquals(1, cache.putCount());
    assertEquals(2, cache.admissionRequestCount());
    assertEquals(1, cache.admissionSkipCount());
    assertEquals(1, cache.admissionAdmitCount());
    assertTrue(cache.stats().contains("admissionSkips=1"));
    assertTrue(cache.stats().contains("admissionAdmits=1"));
  }

  @Test
  void shouldRemoveAdmissionCandidatesWhenTableIsInvalidated() {
    BlockCache cache = new BlockCache(4);
    BlockCache.Key key = new BlockCache.Key("table-a", 10, 20);

    assertFalse(cache.putIfAdmitted(key, block("first"), 2));
    cache.invalidateTable("table-a");
    assertFalse(cache.putIfAdmitted(key, block("second"), 2));

    assertNull(cache.get(key));
    assertEquals(2, cache.admissionSkipCount());
    assertEquals(0, cache.admissionAdmitCount());
  }

  private static Block block(String value) {
    BlockBuilder builder = new BlockBuilder(64, 1, new BytewiseComparator());
    builder.add(Slices.utf8Slice(value), Slices.utf8Slice(value));
    return new Block(builder.finish(), new BytewiseComparator());
  }
}
