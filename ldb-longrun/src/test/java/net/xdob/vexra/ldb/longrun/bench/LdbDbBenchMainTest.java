package net.xdob.vexra.ldb.longrun.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class LdbDbBenchMainTest {
  @Test
  void writesBlockLocalIndexBenchmarkConfigurationToReports() throws Exception {
    File root = Files.createTempDirectory("ldb-db-bench-block-local-index").toFile();
    File output = new File(root, "out");
    File db = new File(root, "db");
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    int exit = LdbDbBenchMain.run(new String[] {
        "--output", output.getAbsolutePath(),
        "--db", db.getAbsolutePath(),
        "--benchmarks", "fillseq,warm_readrandom,readrandom_hit,readrandom_sameblock,readrandom_burst,readrandom_miss,readrandom_mixed,multiget_random,multiget_mixed,multiget_sameblock,scan",
        "--num", "128",
        "--reads", "128",
        "--value_size", "32",
        "--read_profile", "read_optimized",
        "--table_format_version", "3",
        "--write_table_properties", "true",
        "--write_block_local_index", "true",
        "--block_local_index_interval", "1",
        "--batch_size", "16"
    }, new PrintStream(outBytes, true, "UTF-8"), new PrintStream(errBytes, true, "UTF-8"));

    assertEquals(0, exit, new String(errBytes.toByteArray(), StandardCharsets.UTF_8));
    String json = new String(Files.readAllBytes(new File(output, "ldb-db-bench-summary.json").toPath()),
        StandardCharsets.UTF_8);
    assertTrue(json.contains("\"tableFormatVersion\": 3"), json);
    assertTrue(json.contains("\"writeTableProperties\": true"), json);
    assertTrue(json.contains("\"writeBlockLocalIndex\": true"), json);
    assertTrue(json.contains("\"blockLocalIndexInterval\": 1"), json);
    assertTrue(json.contains("\"name\": \"warm_readrandom\""), json);
    assertTrue(json.contains("\"name\": \"readrandom_hit\""), json);
    assertTrue(json.contains("\"name\": \"readrandom_sameblock\""), json);
    assertTrue(json.contains("\"name\": \"readrandom_burst\""), json);
    assertTrue(json.contains("\"name\": \"readrandom_miss\""), json);
    assertTrue(json.contains("\"name\": \"readrandom_mixed\""), json);
    assertTrue(json.contains("\"name\": \"multiget_random\""), json);
    assertTrue(json.contains("\"name\": \"multiget_mixed\""), json);
    assertTrue(json.contains("\"name\": \"multiget_sameblock\""), json);
    assertTrue(json.contains("\"name\": \"scan\""), json);
    assertTrue(json.contains("blockLocalIndexSeekCount="), json);
    assertTrue(json.contains("blockLocalIndexHitCount="), json);
    assertTrue(json.contains("blockLocalIndexDirectoryLoadedTables="), json);
    assertTrue(json.contains("tableIndexCacheHits="), json);
    assertTrue(json.contains("tableLastBlockHits="), json);
    assertTrue(json.contains("pointReadContextFileHits="), json);
    assertTrue(json.contains("blockSeekIndexHits="), json);
  }
}
