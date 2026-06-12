package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十八阶段备份对象仓库损坏注入矩阵。
 *
 * 这些用例验证 `objects/`、`OBJECT-REFS.json` 和 `BACKUP-MANIFEST.json`
 * 的运维校验边界，并确保损坏备份不会恢复出半成品目标目录。
 */
class LdbBackupObjectStoreCorruptionTest {
  @TempDir
  File tempDir;

  @Test
  void shouldRejectMissingObjectFile() throws Exception {
    BackupFixture fixture = createBackupFixture("missing-object");
    File object = firstObjectFile(fixture.objectsDir);
    assertTrue(object.delete(), "Failed to delete object " + object);

    LDBFactory.CheckReport check =
        LDBFactory.factory.checkBackup(fixture.latestBackup, new Options().createIfMissing(false));
    assertFalse(check.isOk(), check.toString());
    assertTrue(check.getFailures().stream().anyMatch(failure -> failure.contains("Missing backup object")),
        check.toString());

    File restoreDir = new File(tempDir, "missing-object-restore");
    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(fixture.latestBackup, restoreDir, new Options().createIfMissing(false));
    assertFalse(restore.isOk(), restore.toString());
    assertFalse(restoreDir.exists(), "missing object must fail before restore target is created");
  }

  @Test
  void shouldRejectWrongObjectRefCount() throws Exception {
    BackupFixture fixture = createBackupFixture("wrong-ref-count");
    String refs = new String(Files.readAllBytes(fixture.refsFile.toPath()), UTF_8);
    writeText(fixture.refsFile, refs.replaceFirst("\"refCount\"\\s*:\\s*\\d+", "\"refCount\": 999"));

    LDBFactory.CheckReport check =
        LDBFactory.factory.checkBackup(fixture.latestBackup, new Options().createIfMissing(false));
    assertFalse(check.isOk(), check.toString());
    assertTrue(check.getFailures().stream().anyMatch(failure -> failure.contains("Wrong refCount")),
        check.toString());
  }

  @Test
  void shouldRejectMalformedObjectRefs() throws Exception {
    BackupFixture fixture = createBackupFixture("malformed-refs");
    writeText(fixture.refsFile, "not-json\n");

    LDBFactory.CheckReport check =
        LDBFactory.factory.checkBackup(fixture.latestBackup, new Options().createIfMissing(false));
    assertFalse(check.isOk(), check.toString());
    assertTrue(check.getFailures().stream().anyMatch(failure -> failure.contains("Malformed backup object refs")),
        check.toString());
  }

  @Test
  void shouldRejectOrphanObjectFile() throws Exception {
    BackupFixture fixture = createBackupFixture("orphan-object");
    writeText(new File(fixture.objectsDir, "orphan-object"), "orphan\n");

    LDBFactory.CheckReport check =
        LDBFactory.factory.checkBackup(fixture.latestBackup, new Options().createIfMissing(false));
    assertFalse(check.isOk(), check.toString());
    assertTrue(check.getFailures().stream().anyMatch(failure -> failure.contains("Orphan backup object")),
        check.toString());
  }

  @Test
  void shouldRejectCorruptBackupManifestBeforeRestore() throws Exception {
    BackupFixture fixture = createBackupFixture("corrupt-manifest");
    writeText(new File(fixture.latestBackup, "BACKUP-MANIFEST.json"), "{\"published\": false}\n");

    LDBFactory.CheckReport check =
        LDBFactory.factory.checkBackup(fixture.latestBackup, new Options().createIfMissing(false));
    assertFalse(check.isOk(), check.toString());
    assertTrue(check.getFailures().stream().anyMatch(failure -> failure.contains("Malformed backup manifest")),
        check.toString());

    File restoreDir = new File(tempDir, "corrupt-manifest-restore");
    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(fixture.latestBackup, restoreDir, new Options().createIfMissing(false));
    assertFalse(restore.isOk(), restore.toString());
    assertFalse(restoreDir.exists(), "corrupt manifest must fail before restore target is created");
  }

  private BackupFixture createBackupFixture(String name) throws Exception {
    File dbDir = new File(tempDir, name + "-db");
    File backupRoot = new File(tempDir, name + "-backups");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int i = 0; i < 16; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i), new WriteOptions().sync(true));
      }
      db.compactRange(bytes("a"), bytes("z"));
      LDBFactory.BackupReport first =
          LDBFactory.factory.createIncrementalBackup(dbDir, backupRoot, new Options().createIfMissing(false));
      assertTrue(first.isOk(), first.toString());
      db.put(bytes("after"), bytes(name), new WriteOptions().sync(true));
      LDBFactory.BackupReport second =
          LDBFactory.factory.createIncrementalBackup(dbDir, backupRoot, new Options().createIfMissing(false));
      assertTrue(second.isOk(), second.toString());
      return new BackupFixture(second.getTargetDir(), new File(backupRoot, "objects"),
          new File(backupRoot, "OBJECT-REFS.json"));
    }
  }

  private static File firstObjectFile(File objectsDir) {
    File[] files = objectsDir.listFiles(File::isFile);
    assertNotNull(files);
    assertTrue(files.length > 0, "object store should contain at least one object");
    return files[0];
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

  private static final class BackupFixture {
    private final File latestBackup;
    private final File objectsDir;
    private final File refsFile;

    private BackupFixture(File latestBackup, File objectsDir, File refsFile) {
      this.latestBackup = latestBackup;
      this.objectsDir = objectsDir;
      this.refsFile = refsFile;
    }
  }
}
