package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.Filename;
import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件级损坏注入矩阵。
 *
 * 该矩阵补充已有 WAL/SST/MANIFEST 单点测试，重点验证列族注册表、备份恢复和 repair
 * 在 runtime column family 场景下的可观测失败与恢复边界。
 */
class LdbCorruptionMatrixTest {
  @TempDir
  File tempDir;

  @Test
  void shouldReportCorruptColumnFamilyRegistryAndRejectBackup() throws Exception {
    File dbDir = new File(tempDir, "corrupt-registry-db");
    File backupRoot = new File(tempDir, "corrupt-registry-backups");

    createRuntimeColumnFamilyWalDb(dbDir, 31, "matrix-runtime");
    writeText(new File(dbDir, "COLUMN-FAMILIES"), "not-a-valid-registry-line\n");

    LDBFactory.CheckReport check = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertFalse(check.isOk(), check.toString());
    assertTrue(check.getFailures().stream().anyMatch(failure -> failure.contains("COLUMN-FAMILIES")),
        check.toString());

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertFalse(backup.isOk(), backup.toString());
    assertFalse(backupRoot.exists(), "corrupt source should not publish backup root");
  }

  @Test
  void shouldRequireRegistryForRuntimeColumnFamilyWalCheck() throws Exception {
    File dbDir = new File(tempDir, "missing-registry-wal-db");
    createRuntimeColumnFamilyWalDb(dbDir, 32, "wal-runtime");

    LDBFactory.CheckReport healthy = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertTrue(healthy.isOk(), healthy.toString());

    assertTrue(new File(dbDir, "COLUMN-FAMILIES").delete());
    LDBFactory.CheckReport missingRegistry =
        LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertFalse(missingRegistry.isOk(), missingRegistry.toString());
    assertTrue(missingRegistry.getFailures().stream()
            .anyMatch(failure -> failure.contains("Unknown column family id in WAL: 32")),
        missingRegistry.toString());
  }

  @Test
  void shouldReportManifestColumnFamilyNotRegisteredByRegistry() throws Exception {
    File dbDir = new File(tempDir, "missing-registry-manifest-db");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      LdbColumnFamily cf = db.createColumnFamily(35, "manifest-runtime");
      db.put(cf, bytes("manifest-key"), bytes("manifest-value"), new WriteOptions().sync(true));
      db.compactRange(cf, bytes("a"), bytes("z"));
    }
    assertTrue(new File(dbDir, "COLUMN-FAMILIES").delete());

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertFalse(report.isOk(), report.toString());
    assertTrue(report.getFailures().stream()
            .anyMatch(failure -> failure.contains("Unknown column family id in MANIFEST: 35")),
        report.toString());
  }

  @Test
  void shouldReportCurrentPointingToMissingManifest() throws Exception {
    File dbDir = new File(tempDir, "bad-current-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }
    writeText(new File(dbDir, Filename.currentFileName()), "MANIFEST-999999\n");

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertFalse(report.isOk(), report.toString());
    assertTrue(report.getFailures().stream().anyMatch(failure -> failure.contains("missing manifest")),
        report.toString());
  }

  @Test
  void shouldReportCurrentPointingToInvalidManifestName() throws Exception {
    File dbDir = new File(tempDir, "invalid-current-name-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }
    writeText(new File(dbDir, Filename.currentFileName()), "MANIFEST-not-a-number\n");

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertFalse(report.isOk(), report.toString());
    assertTrue(report.getFailures().stream()
            .anyMatch(failure -> failure.contains("invalid manifest file name")),
        report.toString());
  }

  @Test
  void shouldFailOpenWhenCurrentContainsPathSeparator() throws Exception {
    File dbDir = new File(tempDir, "current-path-separator-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }
    writeText(new File(dbDir, Filename.currentFileName()), "../MANIFEST-000001\n");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> LDBFactory.factory.open(dbDir, new Options().createIfMissing(false)));
    assertTrue(error.getMessage().contains("path separators"), error.getMessage());
  }

  @Test
  void shouldRejectRestoreWhenBackupRegistryIsCorrupt() throws Exception {
    File dbDir = new File(tempDir, "backup-source-db");
    File backupRoot = new File(tempDir, "backup-root");
    File restoreDir = new File(tempDir, "restore-target-db");

    createRuntimeColumnFamilyWalDb(dbDir, 33, "backup-runtime");
    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());

    writeText(new File(backup.getTargetDir(), "COLUMN-FAMILIES"), "33\n");

    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertFalse(restore.isOk(), restore.toString());
    assertFalse(restoreDir.exists(), "corrupt backup should not create restore target");
  }

  @Test
  void shouldRepairRuntimeColumnFamilyWalOnlyUsingRegistry() throws Exception {
    File dbDir = new File(tempDir, "runtime-cf-wal-repair-db");
    createRuntimeColumnFamilyWalDb(dbDir, 34, "repair-runtime");
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());
    deleteFilesWithSuffix(dbDir, ".sst");

    LDBFactory.factory.repair(dbDir, new Options());

    try (LDB repaired = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      LdbColumnFamily cf = repaired.getColumnFamily(34);
      assertEquals("repair-runtime", cf.getName());
      assertArrayEquals(bytes("runtime-value"), repaired.get(cf, bytes("runtime-key")));
    }
  }

  private static void createRuntimeColumnFamilyWalDb(File dbDir, int cfId, String name) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      LdbColumnFamily cf = db.createColumnFamily(cfId, name);
      db.put(cf, bytes("runtime-key"), bytes("runtime-value"), new WriteOptions().sync(true));
    }
  }

  private static void deleteFilesWithPrefix(File dir, String prefix) {
    File[] files = dir.listFiles((ignored, name) -> name.startsWith(prefix));
    assertNotNull(files);
    for (File file : files) {
      assertTrue(file.delete(), "Failed to delete " + file);
    }
  }

  private static void deleteFilesWithSuffix(File dir, String suffix) {
    File[] files = dir.listFiles((ignored, name) -> name.endsWith(suffix));
    assertNotNull(files);
    for (File file : files) {
      assertTrue(file.delete(), "Failed to delete " + file);
    }
  }

  private static void writeText(File file, String text) throws Exception {
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(text.getBytes(UTF_8));
      output.flush();
      output.getFD().sync();
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
