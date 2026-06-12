package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行时列族生命周期测试。
 *
 * 这些用例覆盖最小 create/list/drop 实现：动态列族会写入本地注册表，重开、备份和 checkpoint
 * 必须能在不重复传入 Options.addColumnFamily 的情况下恢复列族定义。
 */
class LdbColumnFamilyLifecycleTest {
  @TempDir
  File tempDir;

  @Test
  void shouldCreateListAndReopenRuntimeColumnFamily() throws Exception {
    File dbDir = new File(tempDir, "runtime-cf-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      LdbColumnFamily cf = db.createColumnFamily(21, "runtime");
      db.put(cf, bytes("k"), bytes("v"), new WriteOptions().sync(true));

      assertTrue(containsColumnFamily(db.listColumnFamilies(), 21, "runtime"));
      assertTrue(db.getProperty("ldb.columnFamilies").contains("21:runtime"));
      assertArrayEquals(bytes("v"), db.get(cf, bytes("k")));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      LdbColumnFamily cf = reopened.getColumnFamily(21);
      assertEquals("runtime", cf.getName());
      assertArrayEquals(bytes("v"), reopened.get(cf, bytes("k")));
    }
  }

  @Test
  void shouldDropOnlyEmptyRuntimeColumnFamily() throws Exception {
    File dbDir = new File(tempDir, "drop-empty-cf-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      LdbColumnFamily cf = db.createColumnFamily(22, "empty-runtime");
      assertTrue(containsColumnFamily(db.listColumnFamilies(), 22, "empty-runtime"));

      db.dropColumnFamily(cf);
      assertFalse(containsColumnFamily(db.listColumnFamilies(), 22, "empty-runtime"));
      assertThrows(IllegalArgumentException.class, () -> db.getColumnFamily(22));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertThrows(IllegalArgumentException.class, () -> reopened.getColumnFamily(22));
    }
  }

  @Test
  void shouldRejectDefaultAndTombstoneNonEmptyDrop() throws Exception {
    File dbDir = new File(tempDir, "reject-drop-cf-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      LdbColumnFamily cf = db.createColumnFamily(23, "non-empty-runtime");
      db.put(cf, bytes("k"), bytes("v"));

      assertThrows(DBException.class, () -> db.dropColumnFamily(LdbColumnFamily.DEFAULT));
      db.dropColumnFamily(cf);
      assertThrows(IllegalArgumentException.class, () -> db.getColumnFamily(23));
      assertFalse(containsColumnFamily(db.listColumnFamilies(), 23, "non-empty-runtime"));
      assertTrue(db.getProperty("ldb.api.supportedFeatures").contains("runtimeColumnFamilyDropNonEmpty"));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertThrows(IllegalArgumentException.class, () -> reopened.getColumnFamily(23));
      assertFalse(containsColumnFamily(reopened.listColumnFamilies(), 23, "non-empty-runtime"));
      assertThrows(DBException.class, () -> reopened.createColumnFamily(23, "reused-id"));
    }
  }

  @Test
  void shouldRenameRuntimeColumnFamilyAndKeepStableId() throws Exception {
    File dbDir = new File(tempDir, "rename-cf-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      LdbColumnFamily cf = db.createColumnFamily(25, "before-rename");
      db.put(cf, bytes("k"), bytes("v"), new WriteOptions().sync(true));

      LdbColumnFamily renamed = db.renameColumnFamily(cf, "after-rename");
      assertEquals(25, renamed.getId());
      assertEquals("after-rename", renamed.getName());
      assertArrayEquals(bytes("v"), db.get(renamed, bytes("k")));
      assertTrue(containsColumnFamily(db.listColumnFamilies(), 25, "after-rename"));
      assertFalse(containsColumnFamily(db.listColumnFamilies(), 25, "before-rename"));
      assertTrue(db.getProperty("ldb.api.supportedFeatures").contains("runtimeColumnFamilyRename"));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      LdbColumnFamily cf = reopened.getColumnFamily(25);
      assertEquals("after-rename", cf.getName());
      assertArrayEquals(bytes("v"), reopened.get(cf, bytes("k")));
    }
  }

  @Test
  void shouldCarryRuntimeColumnFamiliesThroughBackupAndCheckpoint() throws Exception {
    File dbDir = new File(tempDir, "source-db");
    File backupRoot = new File(tempDir, "backups");
    File restoreDir = new File(tempDir, "restore-db");
    File checkpointDir = new File(tempDir, "checkpoint-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true).writeBufferSize(256))) {
      LdbColumnFamily cf = db.createColumnFamily(24, "backup-runtime");
      db.put(cf, bytes("k"), bytes("v"));
      db.compactRange(cf, bytes("a"), bytes("z"));
      db.checkpoint(checkpointDir.getAbsolutePath());
    }

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());
    assertTrue(backup.getCopiedFiles().contains("COLUMN-FAMILIES"), backup.toString());

    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());

    try (LDB restored = LDBFactory.factory.open(restoreDir, new Options().createIfMissing(false));
         LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      LdbColumnFamily restoredCf = restored.getColumnFamily(24);
      LdbColumnFamily checkpointCf = checkpoint.getColumnFamily(24);
      assertEquals("backup-runtime", restoredCf.getName());
      assertEquals("backup-runtime", checkpointCf.getName());
      assertArrayEquals(bytes("v"), restored.get(restoredCf, bytes("k")));
      assertArrayEquals(bytes("v"), checkpoint.get(checkpointCf, bytes("k")));
    }
  }

  private static boolean containsColumnFamily(List<LdbColumnFamily> columnFamilies, int id, String name) {
    for (LdbColumnFamily cf : columnFamilies) {
      if (cf.getId() == id && cf.getName().equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
