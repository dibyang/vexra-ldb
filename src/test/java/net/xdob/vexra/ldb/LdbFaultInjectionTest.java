package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.Filename;
import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.table.BlockHandle;
import net.xdob.vexra.ldb.table.Footer;
import net.xdob.vexra.ldb.util.Slices;
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
 * LDB 文件级故障注入测试。
 *
 * 当前阶段只做本地文件破坏，不启动额外进程。用例覆盖 WAL 尾部截断、
 * MANIFEST 损坏和 SST 截断，目标是约束恢复边界和错误可诊断性。
 */
class LdbFaultInjectionTest {
  @TempDir
  File tempDir;

  /**
   * 验证 WAL 尾部不完整记录会被丢弃，已经完整 sync 的记录仍能恢复。
   */
  @Test
  void shouldIgnoreTruncatedWalTailAndRecoverSyncedRecords() throws Exception {
    File dbDir = new File(tempDir, "truncated-wal-tail");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("stable"), bytes("v1"), new WriteOptions().sync(true));
      db.put(bytes("tail"), bytes("v2"), new WriteOptions().sync(true));
    }

    File latestLog = latestFile(dbDir, ".log");
    truncateFile(latestLog, Math.max(0, latestLog.length() - 4));

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("v1"), db.get(bytes("stable")));
      assertNull(db.get(bytes("tail")));
      assertEquals("1", db.getProperty("ldb.lastSequence"));
    }
  }

  /**
   * 验证包含 DELETE_RANGE 的 WAL 尾部记录被截断时，恢复会丢弃不完整 tombstone，保留此前完整记录。
   */
  @Test
  void shouldIgnoreTruncatedRangeDeleteWalTail() throws Exception {
    File dbDir = new File(tempDir, "truncated-range-delete-wal-tail");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("b"), bytes("stable"), new WriteOptions().sync(true));
      try (LdbWriteBatch batch = db.createWriteBatch()) {
        batch.deleteRange(bytes("a"), bytes("z"));
        db.write(batch, new WriteOptions().sync(true));
      }
    }

    File latestLog = latestFile(dbDir, ".log");
    truncateFile(latestLog, Math.max(0, latestLog.length() - 4));

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("stable"), db.get(bytes("b")));
      assertEquals("1", db.getProperty("ldb.lastSequence"));
    }
  }


  /**
   * 验证 MANIFEST 损坏会在 open 阶段显式失败，避免以未知元数据状态继续启动。
   */
  @Test
  void shouldFailOpenWhenManifestIsCorrupted() throws Exception {
    File dbDir = new File(tempDir, "corrupt-manifest");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true))) {
      db.put(bytes("k"), bytes("v"), new WriteOptions().sync(true));
    }

    File manifest = currentManifest(dbDir);
    try (FileOutputStream out = new FileOutputStream(manifest, false)) {
      out.write(bytes("not-a-valid-manifest-record"));
    }

    RuntimeException error = assertThrows(RuntimeException.class,
        () -> LDBFactory.factory.open(dbDir, new Options().createIfMissing(false)));

    assertNotNull(error.getMessage());
  }

  /**
   * 验证 SST 被截断后，读取路径会显式失败并保留底层 cause。
   */
  @Test
  void shouldFailReadWhenSstIsTruncated() throws Exception {
    File dbDir = new File(tempDir, "truncated-sst");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int i = 0; i < 50; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }

    deleteFilesWithSuffix(dbDir, ".log");
    for (File sst : filesWithSuffix(dbDir, ".sst")) {
      truncateFile(sst, Math.max(1, sst.length() / 2));
    }

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      RuntimeException error = assertThrows(RuntimeException.class,
          () -> db.get(bytes("k000")));

      assertNotNull(error.getCause());
    }
  }

  /**
   * 验证 SST block trailer CRC 损坏后，默认开启的校验会在读取 SST 时明确失败。
   */
  @Test
  void shouldFailReadWhenSstBlockChecksumIsCorrupted() throws Exception {
    File dbDir = new File(tempDir, "corrupt-sst-checksum");

    createCompactedSst(dbDir);
    corruptIndexBlockChecksum(latestFile(dbDir, ".sst"));
    deleteFilesWithSuffix(dbDir, ".log");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      RuntimeException error = assertThrows(RuntimeException.class,
          () -> db.get(bytes("k001")));

      assertTrue(hasMessage(error, "Invalid block checksum"));
    }
  }

  /**
   * 验证关闭 checksum 校验时，单纯 trailer CRC 损坏不会阻止读取未损坏的 block 内容。
   */
  @Test
  void shouldReadWhenOnlySstChecksumIsCorruptedButVerificationIsDisabled() throws Exception {
    File dbDir = new File(tempDir, "corrupt-sst-checksum-disabled");

    createCompactedSst(dbDir);
    corruptIndexBlockChecksum(latestFile(dbDir, ".sst"));
    deleteFilesWithSuffix(dbDir, ".log");

    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(false).verifyChecksums(false))) {
      assertArrayEquals(bytes("value-1"), db.get(bytes("k001")));
    }
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

  private static void deleteFilesWithSuffix(File dir, String suffix) {
    for (File file : filesWithSuffix(dir, suffix)) {
      assertTrue(file.delete(), "Failed to delete " + file);
    }
  }

  private static void createCompactedSst(File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir,
        new Options().createIfMissing(true).writeBufferSize(128).forceSstOnFlush(true))) {
      for (int i = 0; i < 20; i++) {
        db.put(bytes(String.format("k%03d", i)), bytes("value-" + i));
      }
      db.compactRange(bytes("k000"), bytes("k999"));
    }
  }

  private static void corruptIndexBlockChecksum(File sst) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(sst, "rw")) {
      raf.seek(sst.length() - Footer.ENCODED_LENGTH);
      byte[] footerBytes = new byte[Footer.ENCODED_LENGTH];
      raf.readFully(footerBytes);
      Footer footer = Footer.readFooter(Slices.wrappedBuffer(footerBytes));
      BlockHandle indexHandle = footer.getIndexBlockHandle();
      long crcOffset = indexHandle.getOffset() + indexHandle.getDataSize() + 1;
      raf.seek(crcOffset);
      int value = raf.read();
      assertTrue(value >= 0, "Missing checksum byte in " + sst);
      raf.seek(crcOffset);
      raf.write(value ^ 0x01);
    }
  }

  private static boolean hasMessage(Throwable error, String expected) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current.getMessage() != null && current.getMessage().contains(expected)) {
        return true;
      }
    }
    return false;
  }

  private static File currentManifest(File dir) throws Exception {
    File current = new File(dir, Filename.currentFileName());
    byte[] bytes = new byte[(int) current.length()];
    try (RandomAccessFile raf = new RandomAccessFile(current, "r")) {
      raf.readFully(bytes);
    }
    String manifestName = new String(bytes, UTF_8).trim();
    File manifest = new File(dir, manifestName);
    assertTrue(manifest.isFile(), "Missing manifest " + manifest);
    return manifest;
  }

  private static void truncateFile(File file, long size) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.setLength(size);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
