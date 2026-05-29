package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.util.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LDB 进程级 crash 恢复测试。
 *
 * 用例通过子进程执行写入并直接 Runtime.halt，避免正常 close 干扰 WAL/SST 恢复语义。
 * 测试只覆盖本地文件系统，不涉及远程调用、网络、事务或 Raft。
 */
public class LdbCrashRecoveryTest {
  private static final int CRASH_EXIT_CODE = 77;
  private static final LdbColumnFamily CRASH_CF = new TestColumnFamily(29, "crash-meta");

  @TempDir
  File tempDir;

  /**
   * 验证子进程强退后，已 sync 的 WAL batch 可以通过重放恢复。
   */
  @Test
  public void shouldRecoverSyncedWalAfterProcessHalt() throws Exception {
    File dbDir = new File(tempDir, "wal-process-crash");

    runCrashWriter("wal", dbDir);

    try (LDB db = LDBFactory.factory.open(dbDir, reopenOptions())) {
      assertArrayEquals(bytes("wal-default"), db.get(bytes("wal:default")));
      assertArrayEquals(bytes("wal-meta"), db.get(CRASH_CF, bytes("wal:meta")));
      assertEquals(11L, Utils.decodeLong(db.get(CRASH_CF, bytes("wal:counter"))).get().longValue());
      assertEquals("3", db.getProperty("ldb.lastSequence"));
    }
  }

  /**
   * 验证子进程在 flush/compact 完成后强退，重启仍能通过 MANIFEST/SST 读取数据。
   */
  @Test
  public void shouldRecoverCompactedSstAfterProcessHalt() throws Exception {
    File dbDir = new File(tempDir, "sst-process-crash");

    runCrashWriter("sst", dbDir);

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("sst-0"), db.get(bytes("sst:000")));
      assertArrayEquals(bytes("sst-79"), db.get(bytes("sst:079")));
      assertNull(db.get(bytes("sst:deleted")));
      assertTrue(db.getProperty("ldb.levelFiles").contains("1:0="));
    }
  }

  private static Options reopenOptions() {
    return new Options()
        .createIfMissing(false)
        .addColumnFamily(CRASH_CF);
  }

  private void runCrashWriter(String mode, File dbDir) throws Exception {
    File output = new File(tempDir, mode + "-crash-writer.log");
    ProcessBuilder builder = new ProcessBuilder(
        javaCommand(),
        "-cp",
        System.getProperty("java.class.path"),
        CrashWriter.class.getName(),
        mode,
        dbDir.getAbsolutePath());
    builder.redirectErrorStream(true);
    builder.redirectOutput(output);

    Process process = builder.start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail("Crash writer timed out. output=" + output.getAbsolutePath());
    }
    assertEquals(CRASH_EXIT_CODE, process.exitValue(),
        "Crash writer exited unexpectedly. output=" + output.getAbsolutePath());
  }

  private static String javaCommand() {
    return new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  /**
   * 子进程入口：执行写入后直接 halt，刻意不释放 LDB 资源。
   */
  public static class CrashWriter {
    public static void main(String[] args) throws Exception {
      String mode = args[0];
      File dbDir = new File(args[1]);
      if ("wal".equals(mode)) {
        writeWalAndHalt(dbDir);
      } else if ("sst".equals(mode)) {
        writeSstAndHalt(dbDir);
      } else {
        throw new IllegalArgumentException("Unknown crash writer mode: " + mode);
      }
    }

    private static void writeWalAndHalt(File dbDir) throws Exception {
      LDB db = LDBFactory.factory.open(dbDir, new Options()
          .createIfMissing(true)
          .addColumnFamily(CRASH_CF));
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.put(bytes("wal:default"), bytes("wal-default"));
        batch.put(CRASH_CF, bytes("wal:meta"), bytes("wal-meta"));
        batch.addLong(CRASH_CF, bytes("wal:counter"), 11L);
        db.write(batch, new WriteOptions().sync(true));
      }
      Runtime.getRuntime().halt(CRASH_EXIT_CODE);
    }

    private static void writeSstAndHalt(File dbDir) throws Exception {
      LDB db = LDBFactory.factory.open(dbDir, new Options()
          .createIfMissing(true)
          .writeBufferSize(128)
          .forceSstOnFlush(true));
      for (int i = 0; i < 80; i++) {
        db.put(bytes(String.format("sst:%03d", i)), bytes("sst-" + i));
      }
      db.put(bytes("sst:deleted"), bytes("gone"));
      db.delete(bytes("sst:deleted"));
      db.compactRange(bytes("sst:000"), bytes("sst:999"));
      Runtime.getRuntime().halt(CRASH_EXIT_CODE);
    }
  }

  /**
   * crash 测试专用列族，固定 id 用于跨进程 reopen 验证。
   */
  private static class TestColumnFamily implements LdbColumnFamily {
    private final int id;
    private final String name;

    private TestColumnFamily(int id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public String getName() {
      return name;
    }
  }
}
