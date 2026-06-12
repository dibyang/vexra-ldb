package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十八阶段列族 tombstone 长生命周期压测。
 *
 * 该用例把 rename、非空 drop、长生命周期 snapshot cursor、reopen、backup/restore
 * 和 repair 串成一条链路，证明 dropped cfId 不会被复用，也不会被运维工具复活。
 */
class LdbColumnFamilyTombstoneLongStressTest {
  private static final int CF_ID = 61;

  @TempDir
  File tempDir;

  @Test
  void shouldKeepTombstoneAcrossSnapshotCompactionBackupRestoreAndRepair() throws Exception {
    File dbDir = new File(tempDir, "tombstone-long-db");
    File backupRoot = new File(tempDir, "tombstone-backups");
    File restoreDir = new File(tempDir, "tombstone-restore");
    File repairDir = new File(tempDir, "tombstone-repair");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      LdbColumnFamily cf = db.createColumnFamily(CF_ID, "before-tombstone");
      for (int i = 0; i < 48; i++) {
        db.put(cf, key("cf", i), bytes("value-" + i), new WriteOptions().sync(i % 8 == 0));
      }
      db.compactRange(cf, bytes("cf:000"), bytes("cf:999"));

      try (SnapshotCursor cursor = db.newSnapshotCursor(cf)) {
        cursor.seek(key("cf", 0));
        assertTrue(cursor.isValid());
        assertArrayEquals(bytes("value-0"), cursor.value());

        LdbColumnFamily renamed = db.renameColumnFamily(cf, "after-tombstone-rename");
        db.put(renamed, bytes("cf:after-rename"), bytes("rename-visible"), new WriteOptions().sync(true));
        db.dropColumnFamily(renamed);

        cursor.seek(key("cf", 47));
        assertTrue(cursor.isValid(), "snapshot cursor should keep the pre-drop view alive");
        assertArrayEquals(bytes("value-47"), cursor.value());
      }

      for (int i = 0; i < 32; i++) {
        db.put(key("default", i), bytes("default-" + i), new WriteOptions().sync(i % 8 == 0));
      }
      db.compactRange(bytes("default:000"), bytes("default:999"));
      assertThrows(IllegalArgumentException.class, () -> db.getColumnFamily(CF_ID));
      assertThrows(DBException.class, () -> db.createColumnFamily(CF_ID, "reused-after-drop"));
    }

    assertRegistryHasDroppedTombstone(dbDir);
    assertDroppedColumnFamilyDoesNotReopen(dbDir);

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());
    assertTrue(LDBFactory.factory.checkBackup(backup.getTargetDir(), new Options().createIfMissing(false)).isOk());

    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());
    assertRegistryHasDroppedTombstone(restoreDir);
    assertDroppedColumnFamilyDoesNotReopen(restoreDir);

    copyDirectory(restoreDir, repairDir);
    LDBFactory.factory.repair(repairDir, new Options().createIfMissing(false));
    assertRegistryHasDroppedTombstone(repairDir);
    assertDroppedColumnFamilyDoesNotReopen(repairDir);
  }

  private static void assertRegistryHasDroppedTombstone(File dbDir) throws Exception {
    String registry = new String(Files.readAllBytes(new File(dbDir, "COLUMN-FAMILIES").toPath()), UTF_8);
    assertTrue(registry.contains("D\t" + CF_ID + "\tafter-tombstone-rename"), registry);
  }

  private static void assertDroppedColumnFamilyDoesNotReopen(File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("default-0"), db.get(key("default", 0)));
      assertThrows(IllegalArgumentException.class, () -> db.getColumnFamily(CF_ID));
      assertThrows(DBException.class, () -> db.createColumnFamily(CF_ID, "reused-after-reopen"));
    }
  }

  private static void copyDirectory(File source, File target) throws IOException {
    if (!target.mkdirs()) {
      throw new IOException("Unable to create target: " + target);
    }
    File[] children = source.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      File dst = new File(target, child.getName());
      if (child.isDirectory()) {
        copyDirectory(child, dst);
      } else {
        Files.copy(child.toPath(), dst.toPath());
      }
    }
  }

  private static byte[] key(String prefix, int index) {
    return bytes(String.format("%s:%03d", prefix, index));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
