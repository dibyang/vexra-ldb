package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.Slices;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Block.seek 热路径 allocation micro-benchmark。
 *
 * <p>该入口专门构造带共享前缀的 block，并循环调用 {@link Block#seekWithIndex(Slice)}，
 * 输出吞吐、线程分配量、decoded entry 数量和 shared-key rebuild 数量。它用于在优化
 * Block 内部比较路径前后获得稳定的局部证据，不参与功能语义。</p>
 */
public final class BlockSeekMicroBenchMain {
  private static final int DEFAULT_ENTRIES = 8192;
  private static final int DEFAULT_READS = 200000;
  private static final int DEFAULT_RESTART_INTERVAL = 16;
  private static final int DEFAULT_VALUE_SIZE = 100;
  private static final int DEFAULT_SEED = 20260623;

  private BlockSeekMicroBenchMain() {
  }

  public static void main(String[] args) throws Exception {
    Config config = Config.parse(args);
    Result result = run(config);
    writeReports(config, result);
    PrintStream out = System.out;
    out.println("BLOCK_SEEK_MICRO_BENCH status=PASS output=" + config.outputDir.getAbsolutePath());
    out.println("block_seek ops=" + result.operations
        + " seconds=" + format(result.seconds)
        + " opsPerSecond=" + format(result.opsPerSecond)
        + " allocatedBytesDelta=" + result.allocatedBytesDelta
        + " bytesPerOp=" + format(result.bytesPerOp)
        + " decodedEntries=" + result.decodedEntries
        + " sharedKeyRebuilds=" + result.sharedKeyRebuilds);
  }

  private static Result run(Config config) {
    BytewiseComparator comparator = new BytewiseComparator();
    BlockBuilder builder = new BlockBuilder(config.entries * (config.valueSize + 32),
        config.restartInterval,
        comparator);
    Slice value = value(config.valueSize);
    Slice[] keys = new Slice[config.entries];
    for (int i = 0; i < config.entries; i++) {
      Slice key = key(i);
      keys[i] = key;
      builder.add(key, value);
    }
    Block block = new Block(builder.finish(), comparator);

    Random random = new Random(config.seed);
    Slice[] targets = new Slice[config.reads];
    for (int i = 0; i < config.reads; i++) {
      targets[i] = keys[random.nextInt(keys.length)];
    }

    AllocationTracker allocationTracker = AllocationTracker.currentThread();
    long allocatedBefore = allocationTracker.allocatedBytes();
    long start = System.nanoTime();
    long decodedEntries = 0;
    long sharedKeyRebuilds = 0;
    long sharedKeyRebuiltBytes = 0;
    int hits = 0;
    long checksum = 0;
    for (Slice target : targets) {
      Block.SeekResult seekResult = block.seekWithIndex(target);
      if (seekResult.getEntry() != null) {
        hits++;
        checksum += seekResult.getEntry().getKey().length();
        checksum += seekResult.getEntry().getValue().length();
      }
      decodedEntries += seekResult.getDecodedEntries();
      sharedKeyRebuilds += seekResult.getSharedKeyRebuilds();
      sharedKeyRebuiltBytes += seekResult.getSharedKeyRebuiltBytes();
    }
    long elapsedNanos = System.nanoTime() - start;
    long allocatedAfter = allocationTracker.allocatedBytes();
    long allocatedDelta = allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
        ? allocatedAfter - allocatedBefore
        : -1;

    return new Result(config.reads,
        hits,
        elapsedNanos / 1_000_000_000.0d,
        config.reads * 1_000_000_000.0d / elapsedNanos,
        allocatedDelta,
        allocatedDelta >= 0 ? allocatedDelta / (double) config.reads : -1.0d,
        decodedEntries,
        sharedKeyRebuilds,
        sharedKeyRebuiltBytes,
        checksum,
        block.size(),
        block.restartCount());
  }

  private static Slice key(int value) {
    byte[] key = new byte[24];
    key[0] = 'b';
    key[1] = 'l';
    key[2] = 'o';
    key[3] = 'c';
    key[4] = 'k';
    key[5] = '-';
    key[6] = 's';
    key[7] = 'e';
    key[8] = 'e';
    key[9] = 'k';
    key[10] = '-';
    int remaining = value;
    for (int i = key.length - 1; i >= 11; i--) {
      key[i] = (byte) ('0' + (remaining % 10));
      remaining /= 10;
    }
    return Slices.wrappedBuffer(key);
  }

  private static Slice value(int valueSize) {
    byte[] value = new byte[valueSize];
    for (int i = 0; i < value.length; i++) {
      value[i] = (byte) ('a' + (i % 26));
    }
    return Slices.wrappedBuffer(value);
  }

  private static void writeReports(Config config, Result result) throws IOException {
    if (!config.outputDir.isDirectory() && !config.outputDir.mkdirs()) {
      throw new IOException("Failed to create output directory: " + config.outputDir);
    }
    writeJson(new File(config.outputDir, "block-seek-micro-bench-summary.json"), config, result);
    writeCsv(new File(config.outputDir, "block-seek-micro-bench-summary.csv"), config, result);
  }

  private static void writeJson(File file, Config config, Result result) throws IOException {
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
      writer.write("{\n");
      writer.write("  \"entries\": " + config.entries + ",\n");
      writer.write("  \"reads\": " + config.reads + ",\n");
      writer.write("  \"restartInterval\": " + config.restartInterval + ",\n");
      writer.write("  \"valueSize\": " + config.valueSize + ",\n");
      writer.write("  \"seed\": " + config.seed + ",\n");
      writer.write("  \"operations\": " + result.operations + ",\n");
      writer.write("  \"hits\": " + result.hits + ",\n");
      writer.write("  \"seconds\": " + format(result.seconds) + ",\n");
      writer.write("  \"opsPerSecond\": " + format(result.opsPerSecond) + ",\n");
      writer.write("  \"allocatedBytesDelta\": " + result.allocatedBytesDelta + ",\n");
      writer.write("  \"bytesPerOp\": " + format(result.bytesPerOp) + ",\n");
      writer.write("  \"decodedEntries\": " + result.decodedEntries + ",\n");
      writer.write("  \"sharedKeyRebuilds\": " + result.sharedKeyRebuilds + ",\n");
      writer.write("  \"sharedKeyRebuiltBytes\": " + result.sharedKeyRebuiltBytes + ",\n");
      writer.write("  \"checksum\": " + result.checksum + ",\n");
      writer.write("  \"blockSize\": " + result.blockSize + ",\n");
      writer.write("  \"restartCount\": " + result.restartCount + "\n");
      writer.write("}\n");
    }
  }

  private static void writeCsv(File file, Config config, Result result) throws IOException {
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
      writer.write("entries,reads,restartInterval,valueSize,seed,operations,hits,seconds,opsPerSecond,allocatedBytesDelta,bytesPerOp,decodedEntries,sharedKeyRebuilds,sharedKeyRebuiltBytes,checksum,blockSize,restartCount\n");
      writer.write(config.entries + "," + config.reads + "," + config.restartInterval + ","
          + config.valueSize + "," + config.seed + "," + result.operations + "," + result.hits + ","
          + format(result.seconds) + "," + format(result.opsPerSecond) + "," + result.allocatedBytesDelta + ","
          + format(result.bytesPerOp) + "," + result.decodedEntries + "," + result.sharedKeyRebuilds + ","
          + result.sharedKeyRebuiltBytes + "," + result.checksum + "," + result.blockSize + "," + result.restartCount + "\n");
    }
  }

  private static String format(double value) {
    return String.format(java.util.Locale.ROOT, "%.3f", value);
  }

  private static final class AllocationTracker {
    private final com.sun.management.ThreadMXBean threadMXBean;
    private final long threadId;

    private AllocationTracker(com.sun.management.ThreadMXBean threadMXBean, long threadId) {
      this.threadMXBean = threadMXBean;
      this.threadId = threadId;
    }

    static AllocationTracker currentThread() {
      java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      if (bean instanceof com.sun.management.ThreadMXBean) {
        com.sun.management.ThreadMXBean allocationBean = (com.sun.management.ThreadMXBean) bean;
        if (allocationBean.isThreadAllocatedMemorySupported()) {
          if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
          }
          return new AllocationTracker(allocationBean, Thread.currentThread().getId());
        }
      }
      return new AllocationTracker(null, -1);
    }

    long allocatedBytes() {
      if (threadMXBean == null) {
        return -1;
      }
      return threadMXBean.getThreadAllocatedBytes(threadId);
    }
  }

  private static final class Config {
    private final File outputDir;
    private final int entries;
    private final int reads;
    private final int restartInterval;
    private final int valueSize;
    private final int seed;

    private Config(File outputDir, int entries, int reads, int restartInterval, int valueSize, int seed) {
      this.outputDir = outputDir;
      this.entries = entries;
      this.reads = reads;
      this.restartInterval = restartInterval;
      this.valueSize = valueSize;
      this.seed = seed;
    }

    static Config parse(String[] args) {
      Map<String, String> values = new LinkedHashMap<String, String>();
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if (!arg.startsWith("--")) {
          throw new IllegalArgumentException("Unexpected argument: " + arg);
        }
        String name = arg.substring(2);
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for argument: " + arg);
        }
        values.put(name, args[++i]);
      }
      File outputDir = new File(values.getOrDefault("output", "build/reports/block-seek-micro-bench"));
      int entries = integer(values, "entries", DEFAULT_ENTRIES);
      int reads = integer(values, "reads", DEFAULT_READS);
      int restartInterval = integer(values, "restart_interval", DEFAULT_RESTART_INTERVAL);
      int valueSize = integer(values, "value_size", DEFAULT_VALUE_SIZE);
      int seed = integer(values, "seed", DEFAULT_SEED);
      if (entries <= 0 || reads <= 0 || restartInterval <= 0 || valueSize < 0) {
        throw new IllegalArgumentException("entries/reads/restart_interval must be positive and value_size must be >= 0");
      }
      return new Config(outputDir, entries, reads, restartInterval, valueSize, seed);
    }

    private static int integer(Map<String, String> values, String name, int defaultValue) {
      String value = values.get(name);
      return value == null ? defaultValue : Integer.parseInt(value);
    }
  }

  private static final class Result {
    private final int operations;
    private final int hits;
    private final double seconds;
    private final double opsPerSecond;
    private final long allocatedBytesDelta;
    private final double bytesPerOp;
    private final long decodedEntries;
    private final long sharedKeyRebuilds;
    private final long sharedKeyRebuiltBytes;
    private final long checksum;
    private final long blockSize;
    private final int restartCount;

    private Result(int operations,
                   int hits,
                   double seconds,
                   double opsPerSecond,
                   long allocatedBytesDelta,
                   double bytesPerOp,
                   long decodedEntries,
                   long sharedKeyRebuilds,
                   long sharedKeyRebuiltBytes,
                   long checksum,
                   long blockSize,
                   int restartCount) {
      this.operations = operations;
      this.hits = hits;
      this.seconds = seconds;
      this.opsPerSecond = opsPerSecond;
      this.allocatedBytesDelta = allocatedBytesDelta;
      this.bytesPerOp = bytesPerOp;
      this.decodedEntries = decodedEntries;
      this.sharedKeyRebuilds = sharedKeyRebuilds;
      this.sharedKeyRebuiltBytes = sharedKeyRebuiltBytes;
      this.checksum = checksum;
      this.blockSize = blockSize;
      this.restartCount = restartCount;
    }
  }
}
