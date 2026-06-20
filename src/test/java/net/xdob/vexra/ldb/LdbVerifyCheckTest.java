package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十一阶段离线 verify/check 入口测试。
 *
 * 这些用例只验证离线扫描报告，不改变数据库打开流程；启动时全量校验和 checkpoint 校验报告
 * 留给后续增量。
 */
class LdbVerifyCheckTest {
  @TempDir
  File tempDir;

  @Test
  void shouldCheckCleanDatabase() throws Exception {
    File dbDir = new File(tempDir, "clean-check-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertTrue(report.isOk(), report.toString());
    assertTrue(report.getCheckedFiles().contains("CURRENT"));
    assertTrue(report.getCheckedFiles().stream().anyMatch(name -> name.startsWith("MANIFEST-")));
    assertTrue(report.getCheckedFiles().stream().anyMatch(name -> name.endsWith(".sst")));
    assertTrue(report.getManifestRecords() > 0, report.toString());
    assertTrue(report.getSstEntries() > 0, report.toString());
    assertTrue(report.getFailures().isEmpty(), report.toString());
  }

  @Test
  void shouldIncludeStorageFormatEvidenceInCheckReport() throws Exception {
    File dbDir = new File(tempDir, "storage-format-check-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256).tableFormatVersion(2))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("fmt%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("fmt000"), bytes("fmt999"));
    }

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertTrue(report.isOk(), report.toString());
    assertFalse(report.getTableFormats().isEmpty(), report.toString());
    assertTrue(report.getTableFormats().stream().anyMatch(value -> value.contains("formatVersion=2")),
        report.toString());

    String json = report.toJson();
    assertTrue(json.contains("\"storageFormat\""), json);
    assertTrue(json.contains("\"tableFormats\""), json);
    assertTrue(json.contains("\"v2Tables\""), json);
    assertTrue(json.contains("table.properties"), json);
  }

  @Test
  void shouldReportMixedV1AndV2TableFormatsInCheckReport() throws Exception {
    File dbDir = new File(tempDir, "mixed-storage-format-check-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256).forceSstOnFlush(true))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("legacy%03d", i)), bytes("legacy-" + i));
      }
      db.compactRange(bytes("legacy000"), bytes("legacy999"));
    }

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).writeBufferSize(256).forceSstOnFlush(true).tableFormatVersion(2))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("v2%03d", i)), bytes("v2-" + i));
      }
      db.compactRange(bytes("v2000"), bytes("v2999"));
    }

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertTrue(report.isOk(), report.toString());
    assertTrue(report.getTableFormats().stream().anyMatch(value -> value.contains("formatVersion=1")),
        report.toString());
    assertTrue(report.getTableFormats().stream().anyMatch(value -> value.contains("formatVersion=2")),
        report.toString());

    String json = report.toJson();
    assertTrue(json.contains("\"legacyTables\": 1"), json);
    assertTrue(json.matches("(?s).*\"v2Tables\": [1-9][0-9]*.*"), json);
    assertTrue(json.contains("table.properties"), json);
  }

  @Test
  void shouldIncludeV3BlockLocalIndexEvidenceInCheckReport() throws Exception {
    File dbDir = new File(tempDir, "v3-block-local-index-check-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(256)
            .forceSstOnFlush(true)
            .tableFormatVersion(3)
            .writeTableProperties(true)
            .writeBlockLocalIndex(true)
            .blockLocalIndexInterval(1))) {
      for (int i = 0; i < 40; i++) {
        db.put(bytes(String.format("v3%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("v3000"), bytes("v3999"));
    }

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertTrue(report.isOk(), report.toString());
    assertTrue(report.getTableFormats().stream().anyMatch(value -> value.contains("formatVersion=3")
            && value.contains("blockLocalIndex=true")
            && value.contains("blockLocalIndexPolicy=restart-anchor")
            && value.contains("coverageMatches=true")
            && value.contains("handlesInRange=true")
            && value.contains("blocksReadable=true")),
        report.toString());

    String json = report.toJson();
    assertTrue(json.contains("\"v3Tables\""), json);
    assertTrue(json.contains("\"blockLocalIndexTables\""), json);
    assertTrue(json.matches("(?s).*\"blockLocalIndexTables\": [1-9][0-9]*.*"), json);
    assertTrue(json.matches("(?s).*\"blockLocalIndexCoveredBlocks\": [1-9][0-9]*.*"), json);
    assertTrue(json.contains("block.local_index.v1"), json);
  }

  @Test
  void shouldReportCorruptSst() throws Exception {
    File dbDir = new File(tempDir, "corrupt-sst-check-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }

    corruptByte(firstFileEndingWith(dbDir, ".sst"));

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertFalse(report.isOk(), report.toString());
    assertTrue(report.getFailures().stream().anyMatch(failure -> failure.contains(".sst")),
        report.toString());
  }

  @Test
  void shouldReportCorruptManifest() throws Exception {
    File dbDir = new File(tempDir, "corrupt-manifest-check-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"));
    }

    corruptByte(firstFileStartingWith(dbDir, "MANIFEST-"));

    LDBFactory.CheckReport report = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertFalse(report.isOk(), report.toString());
    assertTrue(report.getFailures().stream().anyMatch(failure -> failure.contains("MANIFEST-")),
        report.toString());
  }

  @Test
  void shouldRejectCorruptDatabaseWhenVerifyOnOpenEnabled() throws Exception {
    File dbDir = new File(tempDir, "verify-on-open-db");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(256))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }

    corruptByte(firstFileEndingWith(dbDir, ".sst"));

    DBException error = assertThrows(DBException.class,
        () -> LDBFactory.factory.open(dbDir, new Options()
            .createIfMissing(false)
            .verifyOnOpen(true)));
    assertTrue(error.getMessage().contains("startup verification failed"), error.getMessage());
  }

  @Test
  void shouldWriteCheckpointVerificationReport() throws Exception {
    File dbDir = new File(tempDir, "checkpoint-report-source");
    File checkpointDir = new File(tempDir, "checkpoint-report-target");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("before"), bytes("checkpoint"));
      db.checkpoint(checkpointDir.getAbsolutePath());
    }

    File reportFile = new File(checkpointDir, "CHECKPOINT-REPORT.json");
    assertTrue(reportFile.isFile(), "checkpoint report missing");
    String reportJson = new String(java.nio.file.Files.readAllBytes(reportFile.toPath()), UTF_8);
    assertTrue(reportJson.contains("\"ok\": true"), reportJson);
    assertTrue(reportJson.contains("\"checkedFiles\""), reportJson);
    assertTrue(reportJson.contains("\"checkpointTotalFiles\""), reportJson);
    assertTrue(reportJson.contains("\"checkpointHardLinkedFiles\""), reportJson);
    assertTrue(reportJson.contains("\"checkpointCopiedFiles\""), reportJson);
    assertTrue(reportJson.contains("\"checkpointTotalBytes\""), reportJson);
    assertTrue(reportJson.contains("\"checkpointCheckMicros\""), reportJson);
    assertTrue(reportJson.contains("\"checkpointCopyRateLimitBytesPerSecond\""), reportJson);
    assertFalse(hasCheckpointTempSibling(checkpointDir), "checkpoint temp directory leaked");

    LDBFactory.CheckReport report = LDBFactory.factory.check(checkpointDir, new Options().createIfMissing(false));
    assertTrue(report.isOk(), report.toString());
  }

  @Test
  void shouldPublishCheckpointIntoExistingEmptyDirectory() throws Exception {
    File dbDir = new File(tempDir, "checkpoint-existing-empty-source");
    File checkpointDir = new File(tempDir, "checkpoint-existing-empty-target");
    assertTrue(checkpointDir.mkdirs());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .checkpointCopyRateLimitBytesPerSecond(1024 * 1024))) {
      db.put(bytes("before"), bytes("checkpoint"));
      db.checkpoint(checkpointDir.getAbsolutePath());
      assertTrue(db.getProperty("ldb.checkpointStats").contains("copyRateLimitBytesPerSecond=1048576"));
      assertTrue(db.getProperty("ldb.checkpoint.last").contains("files="));
    }

    assertTrue(new File(checkpointDir, "CHECKPOINT-REPORT.json").isFile());
    assertFalse(hasCheckpointTempSibling(checkpointDir), "checkpoint temp directory leaked");
    try (LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("checkpoint"), checkpoint.get(bytes("before")));
    }
  }

  private static File firstFileEndingWith(File dir, String suffix) {
    File[] files = dir.listFiles((ignored, name) -> name.endsWith(suffix));
    assertNotNull(files);
    assertTrue(files.length > 0, "No file ending with " + suffix);
    return files[0];
  }

  private static boolean hasCheckpointTempSibling(File checkpointDir) {
    File parent = checkpointDir.getAbsoluteFile().getParentFile();
    File[] siblings = parent == null ? null : parent.listFiles((ignored, name) ->
        name.startsWith(checkpointDir.getName() + ".tmp-"));
    return siblings != null && siblings.length > 0;
  }

  private static File firstFileStartingWith(File dir, String prefix) {
    File[] files = dir.listFiles((ignored, name) -> name.startsWith(prefix));
    assertNotNull(files);
    assertTrue(files.length > 0, "No file starting with " + prefix);
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
