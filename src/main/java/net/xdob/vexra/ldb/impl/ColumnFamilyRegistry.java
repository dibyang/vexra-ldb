package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.LdbColumnFamily;
import net.xdob.vexra.ldb.Options;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 列族注册表文件读写工具。
 *
 * 最小 runtime create/drop 不改变 WAL、SST 或 MANIFEST 格式，而是把列族
 * id/name 写入独立的 `COLUMN-FAMILIES` 文件。旧库没有该文件时仍按
 * `Options.addColumnFamily` 的静态声明打开；新库备份和 checkpoint 会携带该文件。
 */
final class ColumnFamilyRegistry {
  static final String FILE_NAME = "COLUMN-FAMILIES";
  private static final String ACTIVE = "A";
  private static final String DROPPED = "D";

  private ColumnFamilyRegistry() {
  }

  static List<LdbColumnFamily> load(File databaseDir, Options options) throws IOException {
    List<LdbColumnFamily> result = new ArrayList<>();
    for (Record record : loadRecords(databaseDir, options)) {
      if (record.isActive()) {
        result.add(record.getColumnFamily());
      }
    }
    return sorted(result);
  }

  static List<Record> loadRecords(File databaseDir, Options options) throws IOException {
    LinkedHashMap<Integer, Record> result = new LinkedHashMap<>();
    for (LdbColumnFamily cf : options.getColumnFamilies()) {
      putOrValidate(result, new Record(cf, true));
    }

    File registry = new File(databaseDir, FILE_NAME);
    if (!registry.isFile()) {
      return sortedRecords(result);
    }

    String text = com.google.common.io.Files.toString(registry, UTF_8);
    String[] lines = text.split("\\r?\\n");
    for (String line : lines) {
      if (line.trim().isEmpty() || line.startsWith("#")) {
        continue;
      }
      putOrValidate(result, parseRecord(line));
    }
    return sortedRecords(result);
  }

  static void store(File databaseDir, Iterable<LdbColumnFamily> columnFamilies) throws IOException {
    List<LdbColumnFamily> sorted = sorted(columnFamilies);
    List<Record> records = new ArrayList<>();
    for (LdbColumnFamily cf : sorted) {
      records.add(new Record(cf, true));
    }
    storeRecords(databaseDir, records);
  }

  static void storeRecords(File databaseDir, Iterable<Record> records) throws IOException {
    List<Record> sorted = sortedRecords(records);
    File target = new File(databaseDir, FILE_NAME);
    File temp = new File(databaseDir, FILE_NAME + ".tmp");
    try (FileOutputStream output = new FileOutputStream(temp)) {
      for (Record record : sorted) {
        LdbColumnFamily cf = record.getColumnFamily();
        String state = record.isActive() ? ACTIVE : DROPPED;
        output.write((state + "\t" + cf.getId() + "\t" + escape(cf.getName()) + "\n").getBytes(UTF_8));
      }
      output.flush();
      output.getFD().sync();
    }
    if (target.exists() && !target.delete()) {
      throw new IOException("Unable to replace column family registry: " + target);
    }
    if (!temp.renameTo(target)) {
      throw new IOException("Unable to publish column family registry: " + temp);
    }
  }

  private static Record parseRecord(String line) throws IOException {
    String[] parts = line.split("\t", -1);
    if (parts.length == 2) {
      int id = Integer.parseInt(parts[0]);
      return new Record(new PersistentColumnFamily(id, unescape(parts[1])), true);
    }
    if (parts.length == 3 && (ACTIVE.equals(parts[0]) || DROPPED.equals(parts[0]))) {
      int id = Integer.parseInt(parts[1]);
      return new Record(new PersistentColumnFamily(id, unescape(parts[2])), ACTIVE.equals(parts[0]));
    }
    throw new IOException("Invalid column family registry line: " + line);
  }

  private static void putOrValidate(Map<Integer, Record> result, Record record) {
    LdbColumnFamily cf = record.getColumnFamily();
    Record existingRecord = result.get(cf.getId());
    LdbColumnFamily existing = existingRecord == null ? null : existingRecord.getColumnFamily();
    if (existing != null) {
      if (existingRecord.isActive() != record.isActive() || !existing.getName().equals(cf.getName())) {
        throw new IllegalArgumentException("Column family id " + cf.getId()
            + " has conflicting registry records");
      }
      return;
    }
    result.put(cf.getId(), record);
  }

  private static List<LdbColumnFamily> sorted(Iterable<LdbColumnFamily> columnFamilies) {
    List<LdbColumnFamily> result = new ArrayList<>();
    for (LdbColumnFamily cf : columnFamilies) {
      result.add(cf);
    }
    Collections.sort(result, (left, right) -> Integer.compare(left.getId(), right.getId()));
    return result;
  }

  private static List<Record> sortedRecords(Iterable<Record> records) {
    List<Record> result = new ArrayList<>();
    for (Record record : records) {
      result.add(record);
    }
    Collections.sort(result, (left, right) -> Integer.compare(
        left.getColumnFamily().getId(), right.getColumnFamily().getId()));
    return result;
  }

  private static List<Record> sortedRecords(Map<Integer, Record> columnFamilies) {
    return sortedRecords(columnFamilies.values());
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static String unescape(String value) {
    StringBuilder builder = new StringBuilder();
    boolean escaping = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!escaping) {
        if (c == '\\') {
          escaping = true;
        } else {
          builder.append(c);
        }
        continue;
      }
      if (c == 't') {
        builder.append('\t');
      } else if (c == 'n') {
        builder.append('\n');
      } else if (c == 'r') {
        builder.append('\r');
      } else {
        builder.append(c);
      }
      escaping = false;
    }
    if (escaping) {
      builder.append('\\');
    }
    return builder.toString();
  }

  static final class Record {
    private final LdbColumnFamily columnFamily;
    private final boolean active;

    Record(LdbColumnFamily columnFamily, boolean active) {
      this.columnFamily = columnFamily;
      this.active = active;
    }

    LdbColumnFamily getColumnFamily() {
      return columnFamily;
    }

    boolean isActive() {
      return active;
    }

    Record dropped() {
      return new Record(columnFamily, false);
    }
  }
}
