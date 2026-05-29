package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Comparator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十四阶段 WAL 生命周期测试。
 *
 * 这些用例先约束当前全局 WAL 方案的可观测边界：现存 WAL、被 MANIFEST 或
 * immutable memtable 引用的 WAL、可回收 WAL 以及当前 WAL 必须可诊断。测试只使用
 * 本地临时目录，不涉及远程调用、网络、事务或 Raft。
 */
class LdbWalLifecycleTest {
  @TempDir
  File tempDir;

  /**
   * 验证 WAL 生命周期属性在写入、flush/compact 和 reopen 后稳定可读，并且不破坏恢复。
   */
  @Test
  void shouldExposeWalLifecycleAcrossFlushCompactionAndReopen() throws Exception {
    File dbDir = new File(tempDir, "wal-lifecycle-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(256)
            .level0CompactionTrigger(3)
            .level0SlowdownWritesTrigger(6)
            .level0StopWritesTrigger(12))) {
      for (int i = 0; i < 64; i++) {
        db.put(bytes(String.format("wal:%03d", i)), bytes("value-" + i));
      }

      assertCurrentWalIsObservable(db);
      assertPropertyContains(db, "ldb.walLifecycle", "referenced=");
      assertPropertyContains(db, "ldb.walLifecycle", "recyclable=");
      assertPropertyContains(db, "ldb.walPolicy", "scheme=global");
      assertPropertyContains(db, "ldb.walPolicy", "groupCommit=disabled");
      assertEquals("false", db.getProperty("ldb.walArchiveEnabled"));
      assertEquals("false", db.getProperty("ldb.walGroupCommitEnabled"));
      assertEquals("write-stall", db.getProperty("ldb.walWriteThrottlePolicy"));
      assertNotNull(db.getProperty("ldb.recyclableLogNumbers"));

      db.compactRange(bytes("wal:000"), bytes("wal:999"));

      assertCurrentWalIsObservable(db);
      assertArrayEquals(bytes("value-0"), db.get(bytes("wal:000")));
      assertArrayEquals(bytes("value-63"), db.get(bytes("wal:063")));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), reopened.get(bytes("wal:000")));
      assertArrayEquals(bytes("value-63"), reopened.get(bytes("wal:063")));
      assertCurrentWalIsObservable(reopened);
      assertPropertyContains(reopened, "ldb.walLifecycle", "files=");
    }
  }

  /**
   * 验证 WAL record header 被截断时，只丢弃尾部不完整记录，此前完整记录仍可恢复。
   */
  @Test
  void shouldRecoverCompleteRecordsWhenWalHeaderTailIsTruncated() throws Exception {
    File dbDir = new File(tempDir, "wal-header-tail-truncated-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("stable"), bytes("v1"), new WriteOptions().sync(true));
      db.put(bytes("tail"), bytes("v2"), new WriteOptions().sync(true));
    }

    File latestLog = latestFile(dbDir, ".log");
    truncateFile(latestLog, latestLog.length() - 3);

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("v1"), db.get(bytes("stable")));
      assertNull(db.get(bytes("tail")));
    }
  }

  /**
   * 验证 WAL checksum 错误时，恢复流程会跳过损坏 record，并保留此前完整 record。
   */
  @Test
  void shouldSkipWalRecordWithChecksumError() throws Exception {
    File dbDir = new File(tempDir, "wal-checksum-error-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("stable"), bytes("v1"), new WriteOptions().sync(true));
      db.put(bytes("corrupt"), bytes("v2"), new WriteOptions().sync(true));
    }

    File latestLog = latestFile(dbDir, ".log");
    corruptByte(latestLog, latestLog.length() - 1);

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("v1"), db.get(bytes("stable")));
      assertNull(db.get(bytes("corrupt")));
    }
  }

  /**
   * 验证跨多个 WAL 文件时，最后一个 WAL 的尾部截断不会影响此前 WAL/SST 中的完整数据。
   */
  @Test
  void shouldRecoverAcrossMultipleWalFilesWhenLatestTailIsTruncated() throws Exception {
    File dbDir = new File(tempDir, "multi-wal-tail-truncated-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int i = 0; i < 80; i++) {
        db.put(bytes(String.format("multi:%03d", i)), bytes("value-" + i), new WriteOptions().sync(true));
      }
      db.put(bytes("tail"), bytes("lost"), new WriteOptions().sync(true));
      assertTrue(Integer.parseInt(db.getProperty("ldb.walFileCount")) >= 1);
    }

    File latestLog = latestFile(dbDir, ".log");
    truncateFile(latestLog, Math.max(0, latestLog.length() - 4));

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), db.get(bytes("multi:000")));
      assertArrayEquals(bytes("value-79"), db.get(bytes("multi:079")));
      assertNull(db.get(bytes("tail")));
    }
  }

  /**
   * 验证 WAL 清理后，reopen、backup/restore 和 repair 仍可通过 SST/MANIFEST 读取完整数据。
   */
  @Test
  void shouldKeepReopenBackupAndRepairValidAfterWalCleanup() throws Exception {
    File dbDir = new File(tempDir, "wal-cleanup-acceptance-db");
    File backupRoot = new File(tempDir, "wal-cleanup-backups");
    File restoreDir = new File(tempDir, "wal-cleanup-restore");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256).forceSstOnFlush(true))) {
      for (int i = 0; i < 48; i++) {
        db.put(bytes(String.format("clean:%03d", i)), bytes("value-" + i), new WriteOptions().sync(true));
      }
      db.compactRange(bytes("clean:000"), bytes("clean:999"));
      assertPropertyContains(db, "ldb.walLifecycle", "files=");
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), reopened.get(bytes("clean:000")));
      assertArrayEquals(bytes("value-47"), reopened.get(bytes("clean:047")));
      assertTrue(Long.parseLong(reopened.getProperty("ldb.walFileCount")) >= 1);
    }

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());
    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());
    try (LDB restored = LDBFactory.factory.open(restoreDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-47"), restored.get(bytes("clean:047")));
    }

    LDBFactory.factory.repair(dbDir, new Options());
    try (LDB repaired = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), repaired.get(bytes("clean:000")));
      assertArrayEquals(bytes("value-47"), repaired.get(bytes("clean:047")));
    }
  }

  private static void assertCurrentWalIsObservable(LDB db) {
    String current = db.getProperty("ldb.currentLogNumber");
    assertNotNull(current);
    assertFalse(current.isEmpty());
    assertPropertyContains(db, "ldb.walFiles", current);
    assertPropertyContains(db, "ldb.referencedLogNumbers", current);
    assertTrue(Long.parseLong(db.getProperty("ldb.walFileCount")) >= 1);
    assertPropertyContains(db, "ldb.walLifecycle", "current=" + current);
  }

  private static void assertPropertyContains(LDB db, String property, String expected) {
    String value = db.getProperty(property);
    assertNotNull(value, property);
    assertTrue(value.contains(expected), property + "=" + value);
  }

  private static File latestFile(File dir, String suffix) {
    File[] files = filesWithSuffix(dir, suffix);
    return files[files.length - 1];
  }

  private static File[] filesWithSuffix(File dir, String suffix) {
    File[] files = dir.listFiles((d, name) -> name.endsWith(suffix));
    assertNotNull(files);
    assertTrue(files.length > 0, "No " + suffix + " files under " + dir);
    Arrays.sort(files, Comparator.comparing(File::getName));
    return files;
  }

  private static void truncateFile(File file, long size) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.setLength(size);
    }
  }

  private static void corruptByte(File file, long offset) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.seek(offset);
      int value = raf.read();
      assertTrue(value >= 0, "Missing byte in " + file);
      raf.seek(offset);
      raf.write(value ^ 0x01);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
