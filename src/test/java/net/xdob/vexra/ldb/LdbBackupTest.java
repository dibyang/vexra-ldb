package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十二阶段离线备份与恢复测试。
 *
 * 当前增量覆盖全量备份、备份校验报告和恢复后重开；增量备份和旧版本清理留给后续阶段。
 */
class LdbBackupTest {
  @TempDir
  File tempDir;

  @Test
  void shouldCreateFullBackupAndRestore() throws Exception {
    File dbDir = new File(tempDir, "source-db");
    File backupRoot = new File(tempDir, "backups");
    File restoreDir = new File(tempDir, "restore-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 24; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());
    File backupDir = backup.getTargetDir();
    assertTrue(backupDir.isDirectory(), backup.toString());
    assertTrue(new File(backupDir, "BACKUP-REPORT.json").isFile());
    assertTrue(backup.getCopiedFiles().stream().anyMatch(name -> name.endsWith(".sst")), backup.toString());

    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backupDir, restoreDir, new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());
    assertTrue(new File(restoreDir, "RESTORE-REPORT.json").isFile());

    try (LDB restored = LDBFactory.factory.open(restoreDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), restored.get(bytes("k000")));
      assertArrayEquals(bytes("value-23"), restored.get(bytes("k023")));
    }
  }

  @Test
  void shouldRejectCorruptSourceBackup() throws Exception {
    File dbDir = new File(tempDir, "corrupt-source-db");
    File backupRoot = new File(tempDir, "corrupt-backups");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }
    corruptByte(firstFileEndingWith(dbDir, ".sst"));

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertFalse(backup.isOk(), backup.toString());
    assertFalse(backupRoot.exists(), "corrupt source should not publish a backup root");
    assertTrue(backup.getFailures().stream().anyMatch(failure -> failure.contains(".sst")),
        backup.toString());
  }

  @Test
  void shouldRejectCorruptBackupRestore() throws Exception {
    File dbDir = new File(tempDir, "restore-corrupt-source-db");
    File backupRoot = new File(tempDir, "restore-corrupt-backups");
    File restoreDir = new File(tempDir, "restore-corrupt-target");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());
    corruptByte(firstFileEndingWith(backup.getTargetDir(), ".sst"));

    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertFalse(restore.isOk(), restore.toString());
    assertTrue(restore.getFailures().stream().anyMatch(failure -> failure.contains(".sst")),
        restore.toString());
    assertFalse(restoreDir.exists(), "corrupt backup should not create restore target");
  }

  @Test
  void shouldPurgeOldBackupsAndKeepLatestRestorable() throws Exception {
    File dbDir = new File(tempDir, "purge-source-db");
    File backupRoot = new File(tempDir, "purge-backups");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int version = 0; version < 3; version++) {
        db.put(bytes("version"), bytes("v" + version));
        db.compactRange(bytes("a"), bytes("z"));
        LDBFactory.BackupReport backup =
            LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
        assertTrue(backup.isOk(), backup.toString());
      }
    }

    LDBFactory.BackupCleanupReport cleanup = LDBFactory.factory.purgeOldBackups(backupRoot, 2);
    assertTrue(cleanup.isOk(), cleanup.toString());
    assertEquals(1, cleanup.getDeletedBackups().size(), cleanup.toString());
    assertEquals(2, cleanup.getRetainedBackups().size(), cleanup.toString());
    assertFalse(new File(backupRoot, "backup-000001").exists());
    assertTrue(new File(backupRoot, "backup-000002").isDirectory());
    assertTrue(new File(backupRoot, "backup-000003").isDirectory());

    File restoreDir = new File(tempDir, "purge-restore-db");
    LDBFactory.BackupReport restore = LDBFactory.factory.restoreBackup(
        new File(backupRoot, "backup-000003"),
        restoreDir,
        new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());
    try (LDB restored = LDBFactory.factory.open(restoreDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("v2"), restored.get(bytes("version")));
    }
  }

  @Test
  void shouldMaintainObjectRefsAndPlanBackupPurge() throws Exception {
    File dbDir = new File(tempDir, "object-store-source-db");
    File backupRoot = new File(tempDir, "object-store-backups");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int version = 0; version < 3; version++) {
        db.put(bytes("version"), bytes("v" + version), new WriteOptions().sync(true));
        db.compactRange(bytes("a"), bytes("z"));
        LDBFactory.BackupReport backup =
            LDBFactory.factory.createIncrementalBackup(dbDir, backupRoot, new Options().createIfMissing(false));
        assertTrue(backup.isOk(), backup.toString());
      }
    }

    File objectsDir = new File(backupRoot, "objects");
    File refsFile = new File(backupRoot, "OBJECT-REFS.json");
    assertTrue(objectsDir.isDirectory());
    assertTrue(refsFile.isFile());
    String refsBefore = new String(Files.readAllBytes(refsFile.toPath()), UTF_8);
    assertTrue(refsBefore.contains("\"refCount\""), refsBefore);
    assertTrue(refsBefore.contains("backup-000003"), refsBefore);

    LDBFactory.BackupCleanupReport plan = LDBFactory.factory.planPurgeBackups(backupRoot, 1);
    assertTrue(plan.isOk(), plan.toString());
    assertEquals(2, plan.getDeletedBackups().size(), plan.toString());
    assertEquals(1, plan.getRetainedBackups().size(), plan.toString());
    assertTrue(new File(backupRoot, "backup-000001").isDirectory(), "dry-run must not delete backup");

    LDBFactory.BackupCleanupReport cleanup = LDBFactory.factory.purgeOldBackups(backupRoot, 1);
    assertTrue(cleanup.isOk(), cleanup.toString());
    assertEquals(plan.getPlannedDeletedObjects(), cleanup.getDeletedObjects(), cleanup.toString());
    assertFalse(new File(backupRoot, "backup-000001").exists());
    assertFalse(new File(backupRoot, "backup-000002").exists());
    assertTrue(new File(backupRoot, "backup-000003").isDirectory());
    String refsAfter = new String(Files.readAllBytes(refsFile.toPath()), UTF_8);
    assertFalse(refsAfter.contains("backup-000001"), refsAfter);
    assertTrue(refsAfter.contains("backup-000003"), refsAfter);
  }

  @Test
  void shouldCreateIncrementalBackupManifestAndRestoreLatestView() throws Exception {
    File dbDir = new File(tempDir, "incremental-source-db");
    File backupRoot = new File(tempDir, "incremental-backups");
    File restoreDir = new File(tempDir, "incremental-restore-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int i = 0; i < 32; i++) {
        db.put(bytes(String.format("base:%03d", i)), bytes("base-" + i));
      }
      db.compactRange(bytes("base:000"), bytes("base:999"));
    }

    LDBFactory.BackupReport first =
        LDBFactory.factory.createIncrementalBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(first.isOk(), first.toString());
    assertTrue(new File(first.getTargetDir(), "BACKUP-MANIFEST.json").isFile());
    assertTrue(first.getCopiedFiles().stream().anyMatch(name -> name.endsWith(".sst")), first.toString());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      db.put(bytes("after"), bytes("incremental"), new WriteOptions().sync(true));
    }

    LDBFactory.BackupReport second =
        LDBFactory.factory.createIncrementalBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(second.isOk(), second.toString());
    assertTrue(new File(second.getTargetDir(), "BACKUP-MANIFEST.json").isFile());
    assertFalse(second.getReusedFiles().isEmpty(), second.toString());
    assertTrue(LDBFactory.factory.checkBackup(second.getTargetDir(), new Options().createIfMissing(false)).isOk());

    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(second.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());
    try (LDB restored = LDBFactory.factory.open(restoreDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("base-0"), restored.get(bytes("base:000")));
      assertArrayEquals(bytes("incremental"), restored.get(bytes("after")));
    }
  }

  private static File firstFileEndingWith(File dir, String suffix) {
    File[] files = dir.listFiles((ignored, name) -> name.endsWith(suffix));
    assertNotNull(files);
    assertTrue(files.length > 0, "No file ending with " + suffix);
    return files[0];
  }

  private static void corruptByte(File file) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      long position = Math.max(0, raf.length() / 2);
      raf.seek(position);
      int value = raf.read();
      raf.seek(position);
      raf.write(value ^ 0x7f);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
