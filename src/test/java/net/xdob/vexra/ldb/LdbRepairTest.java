package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.Filename;
import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.util.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Comparator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LDB repair 最小闭环测试。
 *
 * 当前阶段验证 repair 可以基于可读 SST 重建 default CF 的 MANIFEST/CURRENT，并隔离损坏 SST。
 */
class LdbRepairTest {
  private static final LdbColumnFamily REPAIR_CF = new TestColumnFamily(41, "repair-meta");

  @TempDir
  File tempDir;

  /**
   * 验证 CURRENT/MANIFEST 丢失后，repair 可以从现有 SST 重建元数据并重新打开数据库。
   */
  @Test
  void shouldRepairMissingManifestFromReadableSst() throws Exception {
    File dbDir = new File(tempDir, "missing-manifest");
    createCompactedDefaultDb(dbDir);
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());

    LDBFactory.factory.repair(dbDir, new Options());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-0"), db.get(bytes("k000")));
      assertArrayEquals(bytes("value-39"), db.get(bytes("k039")));
      assertNull(db.get(bytes("deleted")));
    }
  }

  /**
   * 验证 repair 会隔离无法读取的 SST，避免坏文件进入重建后的 MANIFEST。
   */
  @Test
  void shouldQuarantineCorruptSstAndRepairFromReadableSst() throws Exception {
    File dbDir = new File(tempDir, "corrupt-sst-repair");
    createCompactedDefaultDb(dbDir);
    File corrupt = new File(dbDir, Filename.tableFileName(999999));
    try (FileOutputStream out = new FileOutputStream(corrupt)) {
      out.write(bytes("not-a-valid-sst"));
    }
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());
    assertTrue(new File(dbDir, "COLUMN-FAMILIES").delete());

    LDBFactory.factory.repair(dbDir, new Options());

    assertTrue(new File(new File(dbDir, "corrupt"), "corrupt-" + corrupt.getName()).isFile());
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-1"), db.get(bytes("k001")));
      assertNull(db.get(bytes("deleted")));
    }
  }

  /**
   * 验证只有 WAL 可用且 CURRENT/MANIFEST 丢失时，repair 可以重放 WAL 并生成可打开的 SST。
   */
  @Test
  void shouldRepairMissingManifestFromWalOnly() throws Exception {
    File dbDir = new File(tempDir, "wal-only-repair");
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("stable"), bytes("v1"), new WriteOptions().sync(true));
      db.put(bytes("gone"), bytes("delete-me"), new WriteOptions().sync(true));
      db.delete(bytes("gone"), new WriteOptions().sync(true));
    }
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());
    deleteFilesWithSuffix(dbDir, ".sst");

    LDBFactory.factory.repair(dbDir, new Options());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("v1"), db.get(bytes("stable")));
      assertNull(db.get(bytes("gone")));
      assertEquals("3", db.getProperty("ldb.lastSequence"));
    }
  }

  /**
   * 验证 repair WAL replay 会按调用方注册的列族恢复数据和计数器。
   */
  @Test
  void shouldRepairWalOnlyWithConfiguredColumnFamily() throws Exception {
    File dbDir = new File(tempDir, "wal-only-cf-repair");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).addColumnFamily(REPAIR_CF))) {
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.put(bytes("default"), bytes("default-value"));
        batch.put(REPAIR_CF, bytes("meta"), bytes("meta-value"));
        batch.addLong(REPAIR_CF, bytes("counter"), 9L);
        db.write(batch, new WriteOptions().sync(true));
      }
    }
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());
    deleteFilesWithSuffix(dbDir, ".sst");

    LDBFactory.factory.repair(dbDir, new Options().addColumnFamily(REPAIR_CF));

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).addColumnFamily(REPAIR_CF))) {
      assertArrayEquals(bytes("default-value"), db.get(bytes("default")));
      assertArrayEquals(bytes("meta-value"), db.get(REPAIR_CF, bytes("meta")));
      assertEquals(9L, Utils.decodeLong(db.get(REPAIR_CF, bytes("counter"))).get().longValue());
      assertEquals("3", db.getProperty("ldb.lastSequence"));
    }
  }

  /**
   * 验证 repair 会输出结构化报告，便于排查恢复了哪些文件以及重建后的元数据。
   */
  @Test
  void shouldWriteRepairReport() throws Exception {
    File dbDir = new File(tempDir, "repair-report");
    createCompactedDefaultDb(dbDir);
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());

    LDBFactory.factory.repair(dbDir, new Options());

    String report = readText(new File(dbDir, "REPAIR-REPORT.json"));
    assertTrue(report.contains("\"recoveredSstFiles\""));
    assertTrue(report.contains("\"replayedWalFiles\""));
    assertTrue(report.contains("\"quarantinedFiles\""));
    assertTrue(report.contains("\"manifestFileNumber\""));
    assertTrue(report.contains("\"lastSequence\""));
  }

  @Test
  void shouldReportV2TableFormatWhenRepairingFromReadableSst() throws Exception {
    File dbDir = new File(tempDir, "repair-v2-table-format");
    createCompactedV2Db(dbDir);
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());

    LDBFactory.factory.repair(dbDir, new Options().tableFormatVersion(2));

    String report = readText(new File(dbDir, "REPAIR-REPORT.json"));
    assertTrue(report.contains("\"storageFormat\""), report);
    assertTrue(report.contains("\"tableFormats\""), report);
    assertTrue(report.contains("formatVersion=2"), report);
    assertTrue(report.contains("table.properties"), report);
    assertTrue(report.contains("\"v2Tables\": 1"), report);

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("v2-value-0"), db.get(bytes("v2-k000")));
      assertArrayEquals(bytes("v2-value-39"), db.get(bytes("v2-k039")));
    }
  }

  @Test
  void shouldReportV3BlockLocalIndexWhenRepairingFromReadableSst() throws Exception {
    File dbDir = new File(tempDir, "repair-v3-block-local-index-format");
    createCompactedV3BlockLocalIndexDb(dbDir);
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());

    LDBFactory.factory.repair(dbDir, new Options().tableFormatVersion(3).writeBlockLocalIndex(true));

    String report = readText(new File(dbDir, "REPAIR-REPORT.json"));
    assertTrue(report.contains("\"storageFormat\""), report);
    assertTrue(report.contains("\"tableFormats\""), report);
    assertTrue(report.contains("formatVersion=3"), report);
    assertTrue(report.contains("blockLocalIndex=true"), report);
    assertTrue(report.contains("blockLocalIndexPolicy=restart-anchor"), report);
    assertTrue(report.contains("coverageMatches=true"), report);
    assertTrue(report.contains("handlesInRange=true"), report);
    assertTrue(report.contains("blocksReadable=true"), report);
    assertTrue(report.contains("\"v3Tables\": 1"), report);
    assertTrue(report.contains("\"blockLocalIndexTables\": 1"), report);
    assertTrue(report.contains("block.local_index.v1"), report);

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("v3-value-0"), db.get(bytes("v3-k000")));
      assertArrayEquals(bytes("v3-value-39"), db.get(bytes("v3-k039")));
    }
  }

  @Test
  void shouldPlanRepairWithoutModifyingDatabaseDirectory() throws Exception {
    File dbDir = new File(tempDir, "repair-plan");
    createCompactedDefaultDb(dbDir);
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());
    String[] before = sortedFileNames(dbDir);

    String json = LDBFactory.factory.planRepair(dbDir, new Options());
    assertTrue(json.contains("\"dryRun\": true"), json);
    assertTrue(json.contains("\"recoveredSstFiles\""), json);
    assertTrue(json.contains("\"manifestFileNumber\""), json);
    assertFalse(new File(dbDir, "REPAIR-REPORT.json").exists());
    assertArrayEquals(before, sortedFileNames(dbDir));
  }

  /**
   * 验证 repair 遇到调用方未注册列族的 WAL 时会隔离该 WAL，并继续用可读 SST 修复。
   */
  @Test
  void shouldQuarantineWalWithUnknownColumnFamilyAndRepairFromSst() throws Exception {
    File dbDir = new File(tempDir, "unknown-cf-wal-repair");
    createCompactedDefaultDb(dbDir);
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).addColumnFamily(REPAIR_CF))) {
      db.put(REPAIR_CF, bytes("unknown-cf-key"), bytes("unknown-cf-value"), new WriteOptions().sync(true));
    }
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());
    assertTrue(new File(dbDir, "COLUMN-FAMILIES").delete());

    LDBFactory.factory.repair(dbDir, new Options());

    File[] quarantinedLogs = new File(dbDir, "corrupt").listFiles((d, name) -> name.startsWith("corrupt-")
        && name.endsWith(".log"));
    assertNotNull(quarantinedLogs);
    assertTrue(quarantinedLogs.length > 0, "Expected unknown-CF WAL to be quarantined");
    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value-1"), db.get(bytes("k001")));
    }
    assertTrue(readText(new File(dbDir, "REPAIR-REPORT.json")).contains("quarantinedFiles"));
  }

  /**
   * 验证 repair 可以把已恢复 SST 作为基线，再重放后续 WAL 中的 addLong。
   */
  @Test
  void shouldRepairFromReadableSstAndWalCounterDelta() throws Exception {
    File dbDir = new File(tempDir, "sst-wal-counter-repair");
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      db.put(bytes("counter"), Utils.encodeLong(5L), new WriteOptions().sync(true));
      for (int i = 0; i < 40; i++) {
        db.put(bytes(String.format("base-%03d", i)), bytes("base-" + i));
      }
      db.compactRange(bytes("a"), bytes("z"));
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.addLong(bytes("counter"), 7L);
        db.write(batch, new WriteOptions().sync(true));
      }
    }
    deleteFilesWithPrefix(dbDir, "MANIFEST-");
    assertTrue(new File(dbDir, Filename.currentFileName()).delete());

    LDBFactory.factory.repair(dbDir, new Options());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertEquals(12L, Utils.decodeLong(db.get(bytes("counter"))).get().longValue());
      assertArrayEquals(bytes("base-1"), db.get(bytes("base-001")));
    }
  }

  private static void createCompactedDefaultDb(File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int i = 0; i < 40; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.put(bytes("deleted"), bytes("gone"));
      db.delete(bytes("deleted"));
      db.compactRange(bytes("a"), bytes("z"));
    }
  }

  private static void createCompactedV2Db(File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true).tableFormatVersion(2))) {
      for (int i = 0; i < 40; i++) {
        db.put(bytes(String.format("v2-k%03d", i)), bytes("v2-value-" + i));
      }
      db.compactRange(bytes("v2-k000"), bytes("v2-k999"));
    }
  }

  private static void createCompactedV3BlockLocalIndexDb(File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options()
            .createIfMissing(true)
            .writeBufferSize(128)
            .forceSstOnFlush(true)
            .tableFormatVersion(3)
            .writeTableProperties(true)
            .writeBlockLocalIndex(true)
            .blockRestartInterval(1)
            .blockLocalIndexInterval(1))) {
      for (int i = 0; i < 40; i++) {
        db.put(bytes(String.format("v3-k%03d", i)), bytes("v3-value-" + i));
      }
      db.compactRange(bytes("v3-k000"), bytes("v3-k999"));
    }
  }

  private static void deleteFilesWithPrefix(File dir, String prefix) {
    File[] files = dir.listFiles((d, name) -> name.startsWith(prefix));
    assertNotNull(files);
    Arrays.sort(files, Comparator.comparing(File::getName));
    for (File file : files) {
      assertTrue(file.delete(), "Failed to delete " + file);
    }
  }

  private static void deleteFilesWithSuffix(File dir, String suffix) {
    File[] files = dir.listFiles((d, name) -> name.endsWith(suffix));
    assertNotNull(files);
    Arrays.sort(files, Comparator.comparing(File::getName));
    for (File file : files) {
      assertTrue(file.delete(), "Failed to delete " + file);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static String readText(File file) throws Exception {
    assertTrue(file.isFile(), "Missing file " + file);
    byte[] bytes = new byte[(int) file.length()];
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.readFully(bytes);
    }
    return new String(bytes, UTF_8);
  }

  private static String[] sortedFileNames(File dir) {
    String[] names = dir.list();
    assertNotNull(names);
    Arrays.sort(names);
    return names;
  }

  /**
   * repair 测试专用列族，固定 id 用于跨 reopen 验证。
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
