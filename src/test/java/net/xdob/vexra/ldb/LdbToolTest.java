package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.tool.LdbTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.RandomAccessFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十七阶段 LDB 工具命令入口测试。
 *
 * 当前只覆盖无破坏性的 `check` 和 `properties`，确保命令入口不会在只读诊断场景中
 * 创建 WAL 或修改 MANIFEST；后续写命令需要单独补充副作用和锁语义测试。
 */
class LdbToolTest {
  @TempDir
  File tempDir;

  @Test
  void shouldCheckHealthyDatabaseWithJsonOutput() throws Exception {
    File dbDir = new File(tempDir, "healthy-db");
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }

    ToolResult result = run("check", dbDir.getAbsolutePath());

    assertEquals(0, result.exitCode);
    assertTrue(result.out.contains("\"ok\": true"), result.out);
    assertTrue(result.out.contains("\"checkedFiles\""), result.out);
    assertEquals("", result.err);
  }

  @Test
  void shouldReturnCheckFailureExitCodeForBrokenDatabase() {
    File dbDir = new File(tempDir, "broken-db");
    assertTrue(dbDir.mkdirs());

    ToolResult result = run("check", dbDir.getAbsolutePath());

    assertEquals(2, result.exitCode);
    assertTrue(result.out.contains("\"ok\": false"), result.out);
    assertTrue(result.out.contains("CURRENT file is missing"), result.out);
  }

  @Test
  void shouldPrintDefaultReadOnlyProperties() throws Exception {
    File dbDir = new File(tempDir, "properties-db");
    String[] beforeFiles;
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
      beforeFiles = sortedFileNames(dbDir);
    }

    ToolResult result = run("properties", dbDir.getAbsolutePath());

    assertEquals(0, result.exitCode);
    assertTrue(result.out.contains("\"ldb.api.compatibility\""), result.out);
    assertTrue(result.out.contains("\"ldb.api.unsupportedFeatures\""), result.out);
    assertTrue(result.out.contains("\"ldb.api.ecosystemGaps\""), result.out);
    assertTrue(result.out.contains("\"ldb.walPolicy\""), result.out);
    assertEquals("", result.err);
    assertArrayEquals(beforeFiles, sortedFileNames(dbDir));
  }

  @Test
  void shouldPrintExplicitProperty() throws Exception {
    File dbDir = new File(tempDir, "explicit-property-db");
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }

    ToolResult result = run("properties", dbDir.getAbsolutePath(), "ldb.api.optionsMapping");

    assertEquals(0, result.exitCode);
    assertTrue(result.out.contains("\"ldb.api.optionsMapping\""), result.out);
    assertTrue(result.out.contains("mergeOperator=unsupported"), result.out);
  }

  @Test
  void shouldRejectUnknownPropertyAndBadArguments() throws Exception {
    File dbDir = new File(tempDir, "unknown-property-db");
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }

    ToolResult unknown = run("properties", dbDir.getAbsolutePath(), "ldb.noSuchProperty");
    assertEquals(1, unknown.exitCode);
    assertTrue(unknown.err.contains("Unknown property: ldb.noSuchProperty"), unknown.err);

    ToolResult noArgs = run();
    assertEquals(1, noArgs.exitCode);
    assertTrue(noArgs.err.contains("Usage:"), noArgs.err);

    ToolResult badCommand = run("destroy", dbDir.getAbsolutePath());
    assertEquals(1, badCommand.exitCode);
    assertTrue(badCommand.err.contains("Unknown command"), badCommand.err);

    ToolResult badRepair = run("repair");
    assertEquals(1, badRepair.exitCode);
    assertTrue(badRepair.err.contains("repair requires exactly one database directory"), badRepair.err);

    ToolResult badRepairPlan = run("repair-plan");
    assertEquals(1, badRepairPlan.exitCode);
    assertTrue(badRepairPlan.err.contains("repair-plan requires exactly one database directory"),
        badRepairPlan.err);

    ToolResult badBackup = run("backup", dbDir.getAbsolutePath());
    assertEquals(1, badBackup.exitCode);
    assertTrue(badBackup.err.contains("backup requires a database directory and a backup root"), badBackup.err);

    ToolResult badIncrementalBackup = run("incremental-backup", dbDir.getAbsolutePath());
    assertEquals(1, badIncrementalBackup.exitCode);
    assertTrue(badIncrementalBackup.err.contains("incremental-backup requires a database directory and a backup root"),
        badIncrementalBackup.err);

    ToolResult badCheckBackup = run("check-backup");
    assertEquals(1, badCheckBackup.exitCode);
    assertTrue(badCheckBackup.err.contains("check-backup requires exactly one backup directory"), badCheckBackup.err);

    ToolResult badRestore = run("restore", dbDir.getAbsolutePath());
    assertEquals(1, badRestore.exitCode);
    assertTrue(badRestore.err.contains("restore requires a backup directory and a target directory"), badRestore.err);

    ToolResult badCheckpoint = run("checkpoint", dbDir.getAbsolutePath());
    assertEquals(1, badCheckpoint.exitCode);
    assertTrue(badCheckpoint.err.contains("checkpoint requires a database directory and a target directory"),
        badCheckpoint.err);
  }

  @Test
  void shouldRepairMissingCurrentAndPrintReport() throws Exception {
    File dbDir = new File(tempDir, "repair-command-db");
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("repair-key"), bytes("repair-value"), new WriteOptions().sync(true));
    }
    assertTrue(new File(dbDir, "CURRENT").delete());

    ToolResult result = run("repair", dbDir.getAbsolutePath());

    assertEquals(0, result.exitCode);
    assertEquals("", result.err);
    assertTrue(result.out.contains("\"databaseDir\""), result.out);
    assertTrue(result.out.contains("\"replayedWalFiles\""), result.out);
    assertTrue(result.out.contains("\"manifestFileNumber\""), result.out);

    try (LDB repaired = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("repair-value"), repaired.get(bytes("repair-key")));
    }
  }

  @Test
  void shouldPrintRepairPlanWithoutWritingReport() throws Exception {
    File dbDir = new File(tempDir, "repair-plan-command-db");
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("repair-plan-key"), bytes("repair-plan-value"), new WriteOptions().sync(true));
    }
    assertTrue(new File(dbDir, "CURRENT").delete());

    ToolResult result = run("repair-plan", dbDir.getAbsolutePath());

    assertEquals(0, result.exitCode);
    assertEquals("", result.err);
    assertTrue(result.out.contains("\"dryRun\": true"), result.out);
    assertTrue(result.out.contains("\"replayedWalFiles\""), result.out);
    assertFalse(new File(dbDir, "REPAIR-REPORT.json").exists());
  }

  @Test
  void shouldBackupAndRestoreDatabase() throws Exception {
    File dbDir = new File(tempDir, "backup-command-db");
    File backupRoot = new File(tempDir, "backup-root");
    File restoreDir = new File(tempDir, "restore-command-db");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 16; i++) {
        db.put(bytes(String.format("tool:%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("tool:000"), bytes("tool:999"));
    }

    ToolResult backup = run("backup", dbDir.getAbsolutePath(), backupRoot.getAbsolutePath());

    assertEquals(0, backup.exitCode);
    assertEquals("", backup.err);
    assertTrue(backup.out.contains("\"action\": \"backup\""), backup.out);
    assertTrue(backup.out.contains("\"ok\": true"), backup.out);
    File backupDir = new File(backupRoot, "backup-000001");
    assertTrue(backupDir.isDirectory());

    ToolResult restore = run("restore", backupDir.getAbsolutePath(), restoreDir.getAbsolutePath());

    assertEquals(0, restore.exitCode);
    assertEquals("", restore.err);
    assertTrue(restore.out.contains("\"action\": \"restore\""), restore.out);
    assertTrue(restore.out.contains("\"ok\": true"), restore.out);
    try (LDB restored = LDBFactory.factory.open(restoreDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), restored.get(bytes("tool:000")));
      assertArrayEquals(bytes("value-15"), restored.get(bytes("tool:015")));
    }
  }

  @Test
  void shouldCreateIncrementalBackupAndCheckItFromTool() throws Exception {
    File dbDir = new File(tempDir, "incremental-backup-command-db");
    File backupRoot = new File(tempDir, "incremental-backup-root");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int i = 0; i < 24; i++) {
        db.put(bytes(String.format("inc:%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("inc:000"), bytes("inc:999"));
    }

    ToolResult first = run("incremental-backup", dbDir.getAbsolutePath(), backupRoot.getAbsolutePath());

    assertEquals(0, first.exitCode);
    assertEquals("", first.err);
    assertTrue(first.out.contains("\"action\": \"incremental-backup\""), first.out);
    assertTrue(new File(backupRoot, "backup-000001").isDirectory());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      db.put(bytes("inc:latest"), bytes("latest"), new WriteOptions().sync(true));
    }

    ToolResult second = run("incremental-backup", dbDir.getAbsolutePath(), backupRoot.getAbsolutePath());

    assertEquals(0, second.exitCode);
    assertTrue(second.out.contains("\"reusedFiles\""), second.out);
    File secondBackup = new File(backupRoot, "backup-000002");
    assertTrue(new File(secondBackup, "BACKUP-MANIFEST.json").isFile());

    ToolResult check = run("check-backup", secondBackup.getAbsolutePath());

    assertEquals(0, check.exitCode);
    assertEquals("", check.err);
    assertTrue(check.out.contains("\"ok\": true"), check.out);
  }

  @Test
  void shouldReturnCheckFailureForCorruptBackupSource() throws Exception {
    File dbDir = new File(tempDir, "corrupt-backup-command-db");
    File backupRoot = new File(tempDir, "corrupt-backup-root");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 16; i++) {
        db.put(bytes(String.format("corrupt:%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("corrupt:000"), bytes("corrupt:999"));
    }
    corruptByte(firstFileEndingWith(dbDir, ".sst"));

    ToolResult backup = run("backup", dbDir.getAbsolutePath(), backupRoot.getAbsolutePath());

    assertEquals(2, backup.exitCode);
    assertTrue(backup.out.contains("\"action\": \"backup\""), backup.out);
    assertTrue(backup.out.contains("\"ok\": false"), backup.out);
    assertFalse(backupRoot.exists());
  }

  @Test
  void shouldCreateCheckpointAndPrintReport() throws Exception {
    File dbDir = new File(tempDir, "checkpoint-command-db");
    File checkpointDir = new File(tempDir, "checkpoint-target-db");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 12; i++) {
        db.put(bytes(String.format("checkpoint:%03d", i)), bytes("value-" + i));
      }
    }

    ToolResult checkpoint = run("checkpoint", dbDir.getAbsolutePath(), checkpointDir.getAbsolutePath());

    assertEquals(0, checkpoint.exitCode);
    assertEquals("", checkpoint.err);
    assertTrue(checkpoint.out.contains("\"ok\": true"), checkpoint.out);
    assertTrue(checkpoint.out.contains("\"checkedFiles\""), checkpoint.out);
    assertTrue(new File(checkpointDir, "CHECKPOINT-REPORT.json").isFile());
    try (LDB db = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), db.get(bytes("checkpoint:000")));
      assertArrayEquals(bytes("value-11"), db.get(bytes("checkpoint:011")));
    }
  }

  @Test
  void shouldFailCheckpointWhenTargetIsNotEmpty() throws Exception {
    File dbDir = new File(tempDir, "checkpoint-non-empty-source-db");
    File checkpointDir = new File(tempDir, "checkpoint-non-empty-target-db");
    assertTrue(checkpointDir.mkdirs());
    assertTrue(new File(checkpointDir, "marker").createNewFile());
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }

    ToolResult checkpoint = run("checkpoint", dbDir.getAbsolutePath(), checkpointDir.getAbsolutePath());

    assertEquals(4, checkpoint.exitCode);
    assertTrue(checkpoint.err.contains("LDB tool failed"), checkpoint.err);
    assertTrue(new File(checkpointDir, "marker").isFile());
  }

  private static ToolResult run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exitCode = LdbTool.run(
        args,
        new PrintStream(out, true),
        new PrintStream(err, true));
    return new ToolResult(exitCode, new String(out.toByteArray(), UTF_8), new String(err.toByteArray(), UTF_8));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static String[] sortedFileNames(File dir) {
    String[] names = dir.list();
    assertNotNull(names);
    java.util.Arrays.sort(names);
    return names;
  }

  private static File firstFileEndingWith(File dir, String suffix) {
    File[] files = dir.listFiles((ignored, name) -> name.endsWith(suffix));
    assertNotNull(files);
    assertTrue(files.length > 0, "No file ending with " + suffix);
    return files[0];
  }

  private static void corruptByte(File file) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      long position = Math.max(0, raf.length() / 2);
      raf.seek(position);
      int value = raf.read();
      raf.seek(position);
      raf.write(value ^ 0x7f);
    }
  }

  private static final class ToolResult {
    private final int exitCode;
    private final String out;
    private final String err;

    private ToolResult(int exitCode, String out, String err) {
      this.exitCode = exitCode;
      this.out = out;
      this.err = err;
    }
  }
}
