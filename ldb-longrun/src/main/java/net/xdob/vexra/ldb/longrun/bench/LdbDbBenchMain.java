package net.xdob.vexra.ldb.longrun.bench;

import net.xdob.vexra.ldb.LDB;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.WriteOptions;
import net.xdob.vexra.ldb.impl.BloomFilterPolicy;
import net.xdob.vexra.ldb.impl.LDBFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * LDB 与 RocksDB db_bench 对齐的轻量基准入口。
 *
 * <p>该入口只服务于本机性能画像和版本趋势记录，不作为发布阻断门禁。它刻意保持参数少而稳定，
 * 便于同一机器上用 RocksDB `db_bench` 复跑同名 workload 后比较比例。</p>
 */
public final class LdbDbBenchMain {
  private static final int DEFAULT_NUM = 200000;
  private static final int DEFAULT_READS = 200000;
  private static final int DEFAULT_VALUE_SIZE = 100;
  private static final String DEFAULT_BENCHMARKS = "fillseq,warm_readrandom,overwrite,readwhilewriting";

  private LdbDbBenchMain() {
  }

  /**
   * 执行 db_bench 风格基准并写出 JSON/CSV 报告。
   *
   * @param args 命令行参数，支持 `--db`、`--output`、`--benchmarks`、`--num`、`--reads`、
   *             `--value_size`、`--sync` 和 `--seed`
   */
  public static void main(String[] args) throws Exception {
    int exitCode = run(args, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /**
   * 运行基准并返回进程退出码。
   *
   * @param args 命令行参数
   * @param out 标准输出
   * @param err 标准错误
   * @return 0 表示成功
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      Config config = Config.parse(args);
      List<Result> results = runBenchmarks(config, out);
      writeReports(config, results);
      out.println("LDB_DB_BENCH status=PASS output=" + config.outputDir.getAbsolutePath());
      return 0;
    } catch (Exception e) {
      err.println("LDB_DB_BENCH status=FAIL reason=" + e.getMessage());
      e.printStackTrace(err);
      return 3;
    }
  }

  private static List<Result> runBenchmarks(Config config, PrintStream out) throws Exception {
    if (!config.outputDir.isDirectory() && !config.outputDir.mkdirs()) {
      throw new IOException("Failed to create output directory: " + config.outputDir);
    }
    List<Result> results = new ArrayList<>();
    for (String benchmark : config.benchmarks) {
      File dbDir = new File(config.dbDir, benchmark);
      deleteRecursively(dbDir);
      if ("fillseq".equals(benchmark)) {
        results.add(fillSeq(config, dbDir));
      } else if ("readrandom".equals(benchmark) || "warm_readrandom".equals(benchmark)) {
        results.add(warmReadRandom(config, dbDir, benchmark));
      } else if ("cold_readrandom".equals(benchmark)) {
        results.add(coldReadRandom(config, dbDir));
      } else if ("readrandom_hit".equals(benchmark)) {
        results.add(readRandomHit(config, dbDir));
      } else if ("readrandom_sameblock".equals(benchmark)) {
        results.add(readRandomSameBlock(config, dbDir));
      } else if ("readrandom_miss".equals(benchmark)) {
        results.add(readRandomMiss(config, dbDir));
      } else if ("readrandom_mixed".equals(benchmark)) {
        results.add(readRandomMixed(config, dbDir));
      } else if ("multiget_random".equals(benchmark)) {
        results.add(multiGetRandom(config, dbDir));
      } else if ("multiget_mixed".equals(benchmark)) {
        results.add(multiGetMixed(config, dbDir));
      } else if ("multiget_sameblock".equals(benchmark)) {
        results.add(multiGetSameBlock(config, dbDir));
      } else if ("scan".equals(benchmark)) {
        results.add(scan(config, dbDir));
      } else if ("overwrite".equals(benchmark)) {
        results.add(overwrite(config, dbDir));
      } else if ("readwhilewriting".equals(benchmark)) {
        results.add(readWhileWriting(config, dbDir));
      } else {
        throw new IllegalArgumentException("Unsupported benchmark: " + benchmark);
      }
      Result result = results.get(results.size() - 1);
      out.println(result.name + " ops=" + result.operations
          + " seconds=" + format(result.seconds)
          + " opsPerSecond=" + format(result.opsPerSecond));
    }
    return results;
  }

  private static Result fillSeq(Config config, File dbDir) throws Exception {
    long start = System.nanoTime();
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      for (int i = 0; i < config.num; i++) {
        db.put(key(i), value(i, config.valueSize), writeOptions(config));
      }
      return Result.of("fillseq", config.num, start, System.nanoTime(), db);
    }
  }

  private static Result warmReadRandom(Config config, File dbDir, String resultName) throws Exception {
    Random random = new Random(config.seed);
    long hits = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
      long start = System.nanoTime();
      for (int i = 0; i < config.reads; i++) {
        if (db.get(key(random.nextInt(config.num))) != null) {
          hits++;
        }
      }
      return Result.of(resultName, config.reads, hits, start, System.nanoTime(), db);
    }
  }

  private static Result coldReadRandom(Config config, File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
    }
    Random random = new Random(config.seed);
    long hits = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      long start = System.nanoTime();
      for (int i = 0; i < config.reads; i++) {
        if (db.get(key(random.nextInt(config.num))) != null) {
          hits++;
        }
      }
      return Result.of("cold_readrandom", config.reads, hits, start, System.nanoTime(), db);
    }
  }

  private static Result readRandomHit(Config config, File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
    }
    Random random = new Random(config.seed);
    long hits = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      long start = System.nanoTime();
      for (int i = 0; i < config.reads; i++) {
        if (db.get(key(random.nextInt(config.num))) != null) {
          hits++;
        }
      }
      return Result.of("readrandom_hit", config.reads, hits, start, System.nanoTime(), db);
    }
  }

  private static Result readRandomSameBlock(Config config, File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
    }
    Random random = new Random(config.seed);
    long hits = 0;
    int window = Math.max(1, Math.min(config.batchSize, config.num));
    int span = Math.max(1, config.num - window);
    int base = random.nextInt(span);
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      long start = System.nanoTime();
      for (int i = 0; i < config.reads; i++) {
        if (i % window == 0) {
          base = random.nextInt(span);
        }
        if (db.get(key(base + (i % window))) != null) {
          hits++;
        }
      }
      return Result.of("readrandom_sameblock", config.reads, hits, start, System.nanoTime(), db);
    }
  }

  private static Result readRandomMiss(Config config, File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
    }
    Random random = new Random(config.seed);
    long hits = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      long start = System.nanoTime();
      for (int i = 0; i < config.reads; i++) {
        if (db.get(missKey(random.nextInt(Math.max(1, config.num - 1)))) != null) {
          hits++;
        }
      }
      return Result.of("readrandom_miss", config.reads, hits, start, System.nanoTime(), db);
    }
  }

  private static Result readRandomMixed(Config config, File dbDir) throws Exception {
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
    }
    Random random = new Random(config.seed);
    long hits = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      long start = System.nanoTime();
      for (int i = 0; i < config.reads; i++) {
        byte[] lookupKey = (i & 1) == 0
            ? key(random.nextInt(config.num))
            : missKey(random.nextInt(Math.max(1, config.num - 1)));
        if (db.get(lookupKey) != null) {
          hits++;
        }
      }
      return Result.of("readrandom_mixed", config.reads, hits, start, System.nanoTime(), db);
    }
  }

  private static Result multiGetRandom(Config config, File dbDir) throws Exception {
    Random random = new Random(config.seed);
    long hits = 0;
    long operations = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
      db.compactRange(key(0), key(config.num));
      long start = System.nanoTime();
      while (operations < config.reads) {
        int batchSize = Math.min(config.batchSize, config.reads - (int) operations);
        List<byte[]> keys = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
          keys.add(key(random.nextInt(config.num)));
        }
        List<byte[]> values = db.get(keys);
        for (byte[] value : values) {
          if (value != null) {
            hits++;
          }
        }
        operations += batchSize;
      }
      return Result.of("multiget_random", operations, hits, start, System.nanoTime(), db);
    }
  }

  private static Result multiGetMixed(Config config, File dbDir) throws Exception {
    Random random = new Random(config.seed);
    long hits = 0;
    long operations = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
    }
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      long start = System.nanoTime();
      while (operations < config.reads) {
        int batchSize = Math.min(config.batchSize, config.reads - (int) operations);
        List<byte[]> keys = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
          keys.add(((operations + i) & 1) == 0
              ? key(random.nextInt(config.num))
              : missKey(random.nextInt(Math.max(1, config.num - 1))));
        }
        List<byte[]> values = db.get(keys);
        for (byte[] value : values) {
          if (value != null) {
            hits++;
          }
        }
        operations += batchSize;
      }
      return Result.of("multiget_mixed", operations, hits, start, System.nanoTime(), db);
    }
  }

  private static Result multiGetSameBlock(Config config, File dbDir) throws Exception {
    Random random = new Random(config.seed);
    long hits = 0;
    long operations = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
      db.compactRange(key(0), key(config.num));
      long start = System.nanoTime();
      while (operations < config.reads) {
        int batchSize = Math.min(config.batchSize, config.reads - (int) operations);
        int maxBase = Math.max(1, config.num - batchSize);
        int base = random.nextInt(maxBase);
        List<byte[]> keys = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
          keys.add(key(base + i));
        }
        List<byte[]> values = db.get(keys);
        for (byte[] value : values) {
          if (value != null) {
            hits++;
          }
        }
        operations += batchSize;
      }
      return Result.of("multiget_sameblock", operations, hits, start, System.nanoTime(), db);
    }
  }

  private static Result scan(Config config, File dbDir) throws Exception {
    long operations = 0;
    long hits = 0;
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
      db.compactRange(key(0), key(config.num));
      long start = System.nanoTime();
      try (SnapshotCursor cursor = db.newSnapshotCursor()) {
        cursor.seekToFirst();
        while (cursor.isValid() && operations < config.reads) {
          if (cursor.value() != null) {
            hits++;
          }
          operations++;
          cursor.next();
        }
      }
      return Result.of("scan", operations, hits, start, System.nanoTime(), db);
    }
  }

  private static Result overwrite(Config config, File dbDir) throws Exception {
    Random random = new Random(config.seed);
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
      long start = System.nanoTime();
      for (int i = 0; i < config.num; i++) {
        int keyIndex = random.nextInt(config.num);
        db.put(key(keyIndex), value(i, config.valueSize), writeOptions(config));
      }
      return Result.of("overwrite", config.num, start, System.nanoTime(), db);
    }
  }

  private static Result readWhileWriting(Config config, File dbDir) throws Exception {
    final CountDownLatch startGate = new CountDownLatch(1);
    final AtomicLong reads = new AtomicLong();
    final AtomicLong writes = new AtomicLong();
    final AtomicLong hits = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    try (LDB db = LDBFactory.factory.open(dbDir, options(config))) {
      prepareDb(config, db);
      Thread reader = new Thread(new Runnable() {
        @Override
        public void run() {
          Random random = new Random(config.seed + 1);
          await(startGate);
          for (int i = 0; i < config.reads; i++) {
            try {
              if (db.get(key(random.nextInt(config.num))) != null) {
                hits.incrementAndGet();
              }
              reads.incrementAndGet();
            } catch (RuntimeException e) {
              failures.incrementAndGet();
              break;
            }
          }
        }
      }, "ldb-db-bench-reader");
      Thread writer = new Thread(new Runnable() {
        @Override
        public void run() {
          Random random = new Random(config.seed + 2);
          WriteOptions writeOptions = writeOptions(config);
          await(startGate);
          for (int i = 0; i < config.num; i++) {
            try {
              int keyIndex = random.nextInt(config.num);
              db.put(key(keyIndex), value(i, config.valueSize), writeOptions);
              writes.incrementAndGet();
            } catch (RuntimeException e) {
              failures.incrementAndGet();
              break;
            }
          }
        }
      }, "ldb-db-bench-writer");
      long start = System.nanoTime();
      reader.start();
      writer.start();
      startGate.countDown();
      reader.join();
      writer.join();
      if (failures.get() > 0) {
        throw new IllegalStateException("readwhilewriting failed with " + failures.get() + " worker failure(s)");
      }
      return Result.of("readwhilewriting", reads.get() + writes.get(), hits.get(), start, System.nanoTime(), db);
    }
  }

  private static void prepareDb(Config config, LDB db) {
    WriteOptions writeOptions = writeOptions(config);
    for (int i = 0; i < config.num; i++) {
      db.put(key(i), value(i, config.valueSize), writeOptions);
    }
  }

  private static Options options(Config config) {
    Options options = new Options()
        .createIfMissing(true)
        .writeBufferSize(config.writeBufferSizeMb << 20)
        .groupCommitEnabled(config.groupCommit)
        .tableFormatVersion(config.tableFormatVersion)
        .writeTableProperties(config.writeTableProperties)
        .writeBlockLocalIndex(config.writeBlockLocalIndex)
        .blockLocalIndexInterval(config.blockLocalIndexInterval);
    if ("read_optimized".equals(config.readProfile)) {
      options
          .filterPolicy(new BloomFilterPolicy(10))
          .cacheBlocks(true)
          .blockCacheWarmOnOpen(config.blockCacheWarmOnOpen)
          .blockCacheSize(config.blockCacheSize)
          .blockCacheAdmissionMinReads(config.blockCacheAdmissionMinReads)
          .verifyChecksums(false);
    } else if ("default".equals(config.readProfile)) {
      options
          .cacheBlocks(config.cacheBlocks)
          .blockCacheSize(config.blockCacheSize)
          .blockCacheAdmissionMinReads(config.blockCacheAdmissionMinReads)
          .verifyChecksums(config.verifyChecksums);
    } else {
      throw new IllegalArgumentException("Unsupported read_profile: " + config.readProfile);
    }
    return options;
  }

  private static WriteOptions writeOptions(Config config) {
    return new WriteOptions().sync(config.sync);
  }

  private static byte[] key(int value) {
    return String.format(Locale.ROOT, "key%016d", value).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] missKey(int value) {
    return String.format(Locale.ROOT, "key%016d-miss", value).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] value(int index, int valueSize) {
    byte[] value = new byte[valueSize];
    int seed = index * 31;
    for (int i = 0; i < value.length; i++) {
      value[i] = (byte) ('a' + Math.floorMod(seed + i, 26));
    }
    return value;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for benchmark start", e);
    }
  }

  private static void writeReports(Config config, List<Result> results) throws IOException {
    writeJson(config, results);
    writeCsv(config, results);
  }

  private static void writeJson(Config config, List<Result> results) throws IOException {
    File file = new File(config.outputDir, "ldb-db-bench-summary.json");
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
      writer.write("{\n");
      field(writer, "engine", "ldb", true);
      field(writer, "dbDir", config.dbDir.getAbsolutePath(), true);
      field(writer, "num", config.num, true);
      field(writer, "reads", config.reads, true);
      field(writer, "valueSize", config.valueSize, true);
      field(writer, "sync", config.sync, true);
      field(writer, "groupCommit", config.groupCommit, true);
      field(writer, "writeBufferSizeMb", config.writeBufferSizeMb, true);
      field(writer, "readProfile", config.readProfile, true);
      field(writer, "cacheBlocks", "read_optimized".equals(config.readProfile) || config.cacheBlocks, true);
      field(writer, "blockCacheWarmOnOpen", config.blockCacheWarmOnOpen, true);
      field(writer, "blockCacheSize", config.blockCacheSize, true);
      field(writer, "blockCacheAdmissionMinReads", config.blockCacheAdmissionMinReads, true);
      field(writer, "tableFormatVersion", config.tableFormatVersion, true);
      field(writer, "writeTableProperties", config.writeTableProperties, true);
      field(writer, "writeBlockLocalIndex", config.writeBlockLocalIndex, true);
      field(writer, "blockLocalIndexInterval", config.blockLocalIndexInterval, true);
      field(writer, "verifyChecksums", "read_optimized".equals(config.readProfile) ? false : config.verifyChecksums, true);
      field(writer, "batchSize", config.batchSize, true);
      writer.write("  \"results\": [\n");
      for (int i = 0; i < results.size(); i++) {
        Result result = results.get(i);
        writer.write("    {");
        writer.write("\"name\": \"" + result.name + "\", ");
        writer.write("\"operations\": " + result.operations + ", ");
        writer.write("\"hits\": " + result.hits + ", ");
        writer.write("\"seconds\": " + format(result.seconds) + ", ");
        writer.write("\"opsPerSecond\": " + format(result.opsPerSecond) + ", ");
        writer.write("\"sstReadStats\": \"" + escape(result.sstReadStats) + "\", ");
        writer.write("\"blockCacheStats\": \"" + escape(result.blockCacheStats) + "\"");
        writer.write("}");
        if (i + 1 < results.size()) {
          writer.write(",");
        }
        writer.write("\n");
      }
      writer.write("  ]\n");
      writer.write("}\n");
    }
  }

  private static void writeCsv(Config config, List<Result> results) throws IOException {
    File file = new File(config.outputDir, "ldb-db-bench-summary.csv");
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
      writer.write("engine,benchmark,operations,hits,seconds,opsPerSecond,num,reads,valueSize,sync,groupCommit,writeBufferSizeMb,readProfile,blockCacheWarmOnOpen,blockCacheAdmissionMinReads,tableFormatVersion,writeBlockLocalIndex,blockLocalIndexInterval,batchSize,sstReadStats,blockCacheStats\n");
      for (Result result : results) {
        writer.write("ldb," + result.name + "," + result.operations + "," + result.hits + ","
            + format(result.seconds) + "," + format(result.opsPerSecond) + ","
            + config.num + "," + config.reads + "," + config.valueSize + ","
            + config.sync + "," + config.groupCommit + "," + config.writeBufferSizeMb + ","
            + config.readProfile + "," + config.blockCacheWarmOnOpen + "," + config.blockCacheAdmissionMinReads + ","
            + config.tableFormatVersion + "," + config.writeBlockLocalIndex + ","
            + config.blockLocalIndexInterval + "," + config.batchSize + ","
            + escapeCsv(result.sstReadStats) + "," + escapeCsv(result.blockCacheStats) + "\n");
      }
    }
  }

  private static void field(Writer writer, String name, String value, boolean comma) throws IOException {
    writer.write("  \"" + name + "\": \"" + escape(value) + "\"");
    writer.write(comma ? ",\n" : "\n");
  }

  private static void field(Writer writer, String name, long value, boolean comma) throws IOException {
    writer.write("  \"" + name + "\": " + value);
    writer.write(comma ? ",\n" : "\n");
  }

  private static void field(Writer writer, String name, boolean value, boolean comma) throws IOException {
    writer.write("  \"" + name + "\": " + value);
    writer.write(comma ? ",\n" : "\n");
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String escapeCsv(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  private static void deleteRecursively(File file) throws IOException {
    if (file == null || !file.exists()) {
      return;
    }
    try (Stream<Path> paths = Files.walk(file.toPath())) {
      paths.sorted((left, right) -> right.compareTo(left))
          .forEach(path -> {
            try {
              Files.delete(path);
            } catch (IOException e) {
              throw new IllegalStateException("Failed to delete " + path, e);
            }
          });
    }
  }

  private static final class Config {
    private final File dbDir;
    private final File outputDir;
    private final List<String> benchmarks;
    private final int num;
    private final int reads;
    private final int valueSize;
    private final boolean sync;
    private final boolean groupCommit;
    private final int writeBufferSizeMb;
    private final String readProfile;
    private final boolean cacheBlocks;
    private final boolean blockCacheWarmOnOpen;
    private final int blockCacheSize;
    private final int blockCacheAdmissionMinReads;
    private final int tableFormatVersion;
    private final boolean writeTableProperties;
    private final boolean writeBlockLocalIndex;
    private final int blockLocalIndexInterval;
    private final boolean verifyChecksums;
    private final int batchSize;
    private final long seed;

    private Config(Map<String, String> values) {
      this.outputDir = new File(values.getOrDefault("output", "build/reports/ldb-db-bench"));
      this.dbDir = new File(values.getOrDefault("db", new File(outputDir, "db").getPath()));
      this.benchmarks = split(values.getOrDefault("benchmarks", DEFAULT_BENCHMARKS));
      this.num = integer(values, "num", DEFAULT_NUM);
      this.reads = integer(values, "reads", DEFAULT_READS);
      this.valueSize = integer(values, "value_size", DEFAULT_VALUE_SIZE);
      this.sync = bool(values, "sync", false);
      this.groupCommit = bool(values, "group_commit", false);
      this.writeBufferSizeMb = integer(values, "write_buffer_size_mb", 512);
      this.readProfile = values.getOrDefault("read_profile", "default");
      this.cacheBlocks = bool(values, "cache_blocks", true);
      this.blockCacheWarmOnOpen = bool(values, "block_cache_warm_on_open", false);
      this.blockCacheSize = integer(values, "block_cache_size", 4096);
      this.blockCacheAdmissionMinReads = integer(values, "block_cache_admission_min_reads", 1);
      this.tableFormatVersion = integer(values, "table_format_version", 1);
      this.writeTableProperties = bool(values, "write_table_properties", true);
      this.writeBlockLocalIndex = bool(values, "write_block_local_index", false);
      this.blockLocalIndexInterval = integer(values, "block_local_index_interval", 4);
      this.verifyChecksums = bool(values, "verify_checksums", true);
      this.batchSize = integer(values, "batch_size", 64);
      this.seed = longValue(values, "seed", 20260619L);
      if (num <= 0 || reads <= 0 || valueSize <= 0 || writeBufferSizeMb <= 0
          || blockCacheSize <= 0 || blockCacheAdmissionMinReads <= 0
          || blockLocalIndexInterval <= 0 || batchSize <= 0) {
        throw new IllegalArgumentException("num, reads, value_size, write_buffer_size_mb, block_cache_size, block_cache_admission_min_reads, block_local_index_interval and batch_size must be > 0");
      }
      if (writeBlockLocalIndex && tableFormatVersion < 3) {
        throw new IllegalArgumentException("write_block_local_index requires table_format_version >= 3");
      }
    }

    private static Config parse(String[] args) {
      Map<String, String> values = new LinkedHashMap<>();
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if (!arg.startsWith("--")) {
          throw new IllegalArgumentException("Invalid argument: " + arg);
        }
        String option = arg.substring(2);
        int eq = option.indexOf('=');
        if (eq >= 0) {
          values.put(option.substring(0, eq), option.substring(eq + 1));
        } else {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for argument: " + arg);
          }
          values.put(option, args[++i]);
        }
      }
      return new Config(values);
    }

    private static List<String> split(String value) {
      List<String> result = new ArrayList<>();
      for (String item : value.split(",")) {
        String trimmed = item.trim();
        if (!trimmed.isEmpty()) {
          result.add(trimmed);
        }
      }
      if (result.isEmpty()) {
        throw new IllegalArgumentException("benchmarks must not be empty");
      }
      return result;
    }

    private static int integer(Map<String, String> values, String name, int defaultValue) {
      return Integer.parseInt(values.getOrDefault(name, Integer.toString(defaultValue)));
    }

    private static long longValue(Map<String, String> values, String name, long defaultValue) {
      return Long.parseLong(values.getOrDefault(name, Long.toString(defaultValue)));
    }

    private static boolean bool(Map<String, String> values, String name, boolean defaultValue) {
      return Boolean.parseBoolean(values.getOrDefault(name, Boolean.toString(defaultValue)));
    }
  }

  private static final class Result {
    private final String name;
    private final long operations;
    private final long hits;
    private final double seconds;
    private final double opsPerSecond;
    private final String sstReadStats;
    private final String blockCacheStats;

    private Result(String name, long operations, long hits, double seconds, double opsPerSecond,
                   String sstReadStats, String blockCacheStats) {
      this.name = name;
      this.operations = operations;
      this.hits = hits;
      this.seconds = seconds;
      this.opsPerSecond = opsPerSecond;
      this.sstReadStats = sstReadStats;
      this.blockCacheStats = blockCacheStats;
    }

    private static Result of(String name, long operations, long startNanos, long endNanos) {
      return of(name, operations, 0, startNanos, endNanos);
    }

    private static Result of(String name, long operations, long hits, long startNanos, long endNanos) {
      double seconds = Math.max(0.001, (endNanos - startNanos) / 1_000_000_000.0);
      return new Result(name, operations, hits, seconds, operations / seconds, "", "");
    }

    private static Result of(String name, long operations, long startNanos, long endNanos, LDB db) {
      return of(name, operations, 0, startNanos, endNanos, db);
    }

    private static Result of(String name, long operations, long hits, long startNanos, long endNanos, LDB db) {
      double seconds = Math.max(0.001, (endNanos - startNanos) / 1_000_000_000.0);
      return new Result(name, operations, hits, seconds, operations / seconds,
          valueOrEmpty(db.getProperty("ldb.sstReadStats")),
          valueOrEmpty(db.getProperty("ldb.blockCacheStats")));
    }

    private static String valueOrEmpty(String value) {
      return value == null ? "" : value;
    }
  }
}
