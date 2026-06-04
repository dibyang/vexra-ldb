package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * group commit 写入聚合回归测试。
 *
 * 这些测试只约束默认关闭和开启后的提交/恢复语义，不设置固定吞吐阈值，避免 CI 环境波动。
 */
class LdbGroupCommitTest {
  @TempDir
  File tempDir;

  @Test
  void shouldGroupConcurrentWritesAndRecoverThem() throws Exception {
    File dbDir = new File(tempDir, "group-commit-db");
    int writers = 4;
    int writesPerThread = 24;
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .groupCommitEnabled(true)
        .groupCommitMaxDelayNanos(2_000_000)
        .groupCommitMaxBatchBytes(64 * 1024))) {
      for (int writer = 0; writer < writers; writer++) {
        final int writerId = writer;
        Thread thread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              start.await();
              for (int i = 0; i < writesPerThread; i++) {
                db.put(bytes(String.format("gc:%02d:%03d", writerId, i)),
                    bytes("value-" + writerId + "-" + i),
                    new WriteOptions().sync(i % 7 == 0));
              }
            } catch (Throwable e) {
              failure.compareAndSet(null, e);
            }
          }
        }, "group-commit-writer-" + writer);
        threads.add(thread);
        thread.start();
      }
      start.countDown();
      for (Thread thread : threads) {
        thread.join();
      }
      if (failure.get() != null) {
        throw new AssertionError(failure.get());
      }

      assertEquals("true", db.getProperty("ldb.walGroupCommitEnabled"));
      assertTrue(db.getProperty("ldb.walPolicy").contains("groupCommit=enabled"));
      assertPropertyAtLeast(db, "ldb.groupCommitStats", "requests=", writers * writesPerThread);
      assertTrue(db.getProperty("ldb.groupCommitStats").contains("syncGroups="));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0-0"), reopened.get(bytes("gc:00:000")));
      assertArrayEquals(bytes("value-3-23"), reopened.get(bytes("gc:03:023")));
    }
  }

  private static void assertPropertyAtLeast(LDB db, String property, String key, long min) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    int start = value.indexOf(key);
    assertTrue(start >= 0, value);
    int valueStart = start + key.length();
    int end = value.indexOf(',', valueStart);
    long parsed = Long.parseLong(end < 0 ? value.substring(valueStart) : value.substring(valueStart, end));
    assertTrue(parsed >= min, property + "=" + value);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
