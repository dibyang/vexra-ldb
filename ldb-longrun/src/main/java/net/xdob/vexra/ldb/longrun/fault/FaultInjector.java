package net.xdob.vexra.ldb.longrun.fault;

import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.longrun.config.LongRunConfig;
import net.xdob.vexra.ldb.longrun.model.CommittedState;
import net.xdob.vexra.ldb.longrun.verify.ConsistencyVerifier;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * copy-based 文件损坏注入器。
 */
public final class FaultInjector {
  private final ConsistencyVerifier verifier = new ConsistencyVerifier();

  /**
   * 复制整个 DB 目录并破坏副本，然后按打开和业务校验结果分类。
   */
  public FaultResult inject(LongRunConfig config, File dbDir, CommittedState state,
                            long eventId, Random random) throws IOException {
    List<FaultKind> kinds = FaultKind.parseList(config.faultKinds());
    if (kinds.isEmpty()) {
      throw new IllegalArgumentException("fault.kinds must not be empty when fault.enabled=true");
    }
    File faultRoot = new File(config.workDir(), "fault");
    if (!faultRoot.exists() && !faultRoot.mkdirs()) {
      throw new IOException("failed to create fault dir: " + faultRoot);
    }
    File copy = new File(faultRoot, "fault-" + eventId);
    copyDirectory(dbDir, copy);
    FaultKind kind = kinds.get((int) Math.floorMod(random.nextLong(), kinds.size()));
    File target = chooseTarget(copy);
    long before = target.length();
    Mutation mutation = corrupt(target, kind, config.faultMaxBytes(), random);
    String status;
    String message;
    try (LDB db = LDBFactory.factory.open(copy, new Options().createIfMissing(false).readOnly(true).verifyChecksums(true))) {
      try {
        verifier.verifyActive(db, state);
        status = "RECOVERED";
        message = "copy opened and verified";
      } catch (RuntimeException e) {
        status = "DETECTED_BY_VERIFY";
        message = e.getMessage();
      }
    } catch (Exception e) {
      status = "DETECTED";
      message = e.getMessage();
    }
    cleanupCopies(faultRoot, config.faultRetainedCopies());
    return new FaultResult(eventId, kind, status, message,
        mutation.offset, mutation.length, before, target.length(), target.getAbsolutePath());
  }

  private static File chooseTarget(File dir) throws IOException {
    List<File> files = new ArrayList<>();
    collectFiles(dir, files);
    for (File file : files) {
      if (file.length() > 0 && !"LOCK".equals(file.getName())) {
        return file;
      }
    }
    throw new IOException("no non-empty DB file found in copy: " + dir);
  }

  private static void collectFiles(File dir, List<File> files) {
    File[] children = dir.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      if (child.isDirectory()) {
        collectFiles(child, files);
      } else {
        files.add(child);
      }
    }
  }

  private static Mutation corrupt(File file, FaultKind kind, int maxBytes, Random random) throws IOException {
    long size = file.length();
    if (kind == FaultKind.TRUNCATE) {
      long newSize = Math.max(0, size - Math.max(1, Math.min(size, Math.max(1, maxBytes))));
      try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
        raf.setLength(newSize);
      }
      return new Mutation(newSize, size - newSize);
    }
    int length = (int) Math.max(1, Math.min(size, Math.max(1, maxBytes)));
    long offset = Math.floorMod(random.nextLong(), Math.max(1, size - length + 1));
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.seek(offset);
      if (kind == FaultKind.BIT_FLIP) {
        int current = raf.read();
        raf.seek(offset);
        raf.write(current ^ 0x01);
        return new Mutation(offset, 1);
      }
      byte[] bytes = new byte[length];
      if (kind == FaultKind.RANDOM_RANGE) {
        random.nextBytes(bytes);
      } else if (kind == FaultKind.PARTIAL_PAGE) {
        length = Math.min(length, 512);
        bytes = new byte[length];
        random.nextBytes(bytes);
      }
      raf.write(bytes);
      return new Mutation(offset, length);
    }
  }

  private static void copyDirectory(File source, File target) throws IOException {
    if (source.isDirectory()) {
      if (!target.exists() && !target.mkdirs()) {
        throw new IOException("failed to create copy dir: " + target);
      }
      File[] children = source.listFiles();
      if (children != null) {
        for (File child : children) {
          if (!".longrun.lock".equals(child.getName())) {
            copyDirectory(child, new File(target, child.getName()));
          }
        }
      }
    } else {
      Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void cleanupCopies(File faultRoot, int retained) {
    File[] copies = faultRoot.listFiles(file -> file.isDirectory() && file.getName().startsWith("fault-"));
    if (copies == null || retained < 0 || copies.length <= retained) {
      return;
    }
    java.util.Arrays.sort(copies, Comparator.comparing(File::getName));
    for (int i = 0; i < copies.length - retained; i++) {
      deleteRecursively(copies[i]);
    }
  }

  private static void deleteRecursively(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursively(child);
        }
      }
    }
    file.delete();
  }

  private static final class Mutation {
    private final long offset;
    private final long length;

    Mutation(long offset, long length) {
      this.offset = offset;
      this.length = length;
    }
  }
}
