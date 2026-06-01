package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.Filename;
import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LDB 恢复闭环回归测试。
 *
 * 这些用例把 checkpoint、backup/restore、repair 和 check 串联起来，验证同一份数据在多条恢复路径下都能
 * 独立打开、校验和读取。测试只使用本地临时目录，不改变现有磁盘格式或公开 API。
 */
class LdbRecoveryLoopTest {
  @TempDir
  File tempDir;

  /**
   * 验证 checkpoint 捕获分叉前视图，backup/restore 捕获分叉后视图，repair 可以从恢复库的 SST 重建元数据。
   */
  @Test
  void shouldVerifyRecoveryLoopAcrossCheckpointBackupRestoreAndRepair() throws Exception {
    File sourceDir = new File(tempDir, "source-db");
    File checkpointDir = new File(tempDir, "checkpoint-db");
    File backupRoot = new File(tempDir, "backups");
    File restoreDir = new File(tempDir, "restore-db");
    File repairDir = new File(tempDir, "repair-db");

    try (LDB db = LDBFactory.factory.open(sourceDir, new Options()
        .createIfMissing(true)
        .writeBufferSize(128)
        .forceSstOnFlush(true))) {
      for (int i = 0; i < 64; i++) {
        db.put(bytes(String.format("base:%03d", i)), bytes("value-" + i));
      }
      db.delete(bytes("base:010"));
      db.compactRange(bytes("base:000"), bytes("base:999"));
      db.checkpoint(checkpointDir.getAbsolutePath());
      db.put(bytes("source:after-checkpoint"), bytes("source-only"), new WriteOptions().sync(true));
    }

    assertHealthyCheckpoint(checkpointDir);
    try (LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), checkpoint.get(bytes("base:000")));
      assertNull(checkpoint.get(bytes("base:010")));
      assertNull(checkpoint.get(bytes("source:after-checkpoint")));
    }

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(sourceDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());
    assertTrue(new File(backup.getTargetDir(), "BACKUP-REPORT.json").isFile(), backup.toString());
    assertTrue(backup.getCheckReport().isOk(), backup.toString());

    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());
    assertTrue(new File(restoreDir, "RESTORE-REPORT.json").isFile(), restore.toString());
    assertRestoredSourceView(restoreDir);

    LDBFactory.BackupReport repairSeed =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), repairDir, new Options().createIfMissing(false));
    assertTrue(repairSeed.isOk(), repairSeed.toString());
    deleteFilesWithPrefix(repairDir, "MANIFEST-");
    assertTrue(new File(repairDir, Filename.currentFileName()).delete());

    LDBFactory.factory.repair(repairDir, new Options().createIfMissing(false));
    String repairReport = new String(Files.readAllBytes(new File(repairDir, "REPAIR-REPORT.json").toPath()), UTF_8);
    assertTrue(repairReport.contains("\"recoveredSstFiles\""), repairReport);
    assertTrue(repairReport.contains("\"manifestFileNumber\""), repairReport);

    LDBFactory.CheckReport repairedCheck =
        LDBFactory.factory.check(repairDir, new Options().createIfMissing(false));
    assertTrue(repairedCheck.isOk(), repairedCheck.toString());
    assertRestoredSourceView(repairDir);
  }

  private static void assertHealthyCheckpoint(File checkpointDir) throws Exception {
    File reportFile = new File(checkpointDir, "CHECKPOINT-REPORT.json");
    assertTrue(reportFile.isFile(), "checkpoint report missing");
    String reportJson = new String(Files.readAllBytes(reportFile.toPath()), UTF_8);
    assertTrue(reportJson.contains("\"ok\": true"), reportJson);
    LDBFactory.CheckReport report = LDBFactory.factory.check(checkpointDir, new Options().createIfMissing(false));
    assertTrue(report.isOk(), report.toString());
  }

  private static void assertRestoredSourceView(File dbDir) throws Exception {
    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertTrue(report.isOk(), report.toString());
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), db.get(bytes("base:000")));
      assertArrayEquals(bytes("value-63"), db.get(bytes("base:063")));
      assertNull(db.get(bytes("base:010")));
      assertArrayEquals(bytes("source-only"), db.get(bytes("source:after-checkpoint")));
    }
  }

  private static void deleteFilesWithPrefix(File dir, String prefix) {
    File[] files = dir.listFiles((ignored, name) -> name.startsWith(prefix));
    assertNotNull(files);
    Arrays.sort(files, Comparator.comparing(File::getName));
    for (File file : files) {
      assertTrue(file.delete(), "Failed to delete " + file);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
