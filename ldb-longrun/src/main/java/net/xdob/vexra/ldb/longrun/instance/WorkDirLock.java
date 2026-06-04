package net.xdob.vexra.ldb.longrun.instance;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * workDir 独占锁。
 *
 * <p>该锁用于阻止两个 longrun 实例同时写同一个工作目录。锁文件保留在 workDir 中，
 * 进程退出或 close 后释放 OS 文件锁。
 */
public final class WorkDirLock implements Closeable {
  private final RandomAccessFile file;
  private final FileChannel channel;
  private final FileLock lock;

  private WorkDirLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
    this.file = file;
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * 尝试获取 workDir 锁。
   *
   * @param workDir 工作目录
   * @return 锁对象
   * @throws IOException 目录创建、锁文件打开或锁竞争失败时抛出
   */
  public static WorkDirLock acquire(File workDir) throws IOException {
    if (!workDir.exists() && !workDir.mkdirs()) {
      throw new IOException("failed to create workDir: " + workDir);
    }
    File lockFile = new File(workDir, ".longrun.lock");
    RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
    FileChannel channel = raf.getChannel();
    FileLock lock = channel.tryLock();
    if (lock == null) {
      channel.close();
      raf.close();
      throw new IOException("workDir is already locked: " + workDir.getAbsolutePath());
    }
    raf.setLength(0);
    raf.writeBytes("pid=" + currentPid() + "\n");
    return new WorkDirLock(raf, channel, lock);
  }

  private static String currentPid() {
    String runtime = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    int at = runtime.indexOf('@');
    return at >= 0 ? runtime.substring(0, at) : runtime;
  }

  @Override
  public void close() throws IOException {
    lock.release();
    channel.close();
    file.close();
  }
}
