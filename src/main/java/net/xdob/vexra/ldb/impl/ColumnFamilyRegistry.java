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

  private ColumnFamilyRegistry() {
  }

  static List<LdbColumnFamily> load(File databaseDir, Options options) throws IOException {
    LinkedHashMap<Integer, LdbColumnFamily> result = new LinkedHashMap<>();
    for (LdbColumnFamily cf : options.getColumnFamilies()) {
      putOrValidate(result, cf);
    }

    File registry = new File(databaseDir, FILE_NAME);
    if (!registry.isFile()) {
      return sorted(result);
    }

    String text = com.google.common.io.Files.toString(registry, UTF_8);
    String[] lines = text.split("\\r?\\n");
    for (String line : lines) {
      if (line.trim().isEmpty() || line.startsWith("#")) {
        continue;
      }
      int tab = line.indexOf('\t');
      if (tab <= 0 || tab == line.length() - 1) {
        throw new IOException("Invalid column family registry line: " + line);
      }
      int id = Integer.parseInt(line.substring(0, tab));
      LdbColumnFamily cf = new PersistentColumnFamily(id, unescape(line.substring(tab + 1)));
      putOrValidate(result, cf);
    }
    return sorted(result);
  }

  static void store(File databaseDir, Iterable<LdbColumnFamily> columnFamilies) throws IOException {
    List<LdbColumnFamily> sorted = sorted(columnFamilies);
    File target = new File(databaseDir, FILE_NAME);
    File temp = new File(databaseDir, FILE_NAME + ".tmp");
    try (FileOutputStream output = new FileOutputStream(temp)) {
      for (LdbColumnFamily cf : sorted) {
        output.write((cf.getId() + "\t" + escape(cf.getName()) + "\n").getBytes(UTF_8));
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

  private static void putOrValidate(Map<Integer, LdbColumnFamily> result, LdbColumnFamily cf) {
    LdbColumnFamily existing = result.get(cf.getId());
    if (existing != null) {
      if (!existing.getName().equals(cf.getName())) {
        throw new IllegalArgumentException("Column family id " + cf.getId()
            + " has conflicting names: " + existing.getName() + " vs " + cf.getName());
      }
      return;
    }
    result.put(cf.getId(), cf);
  }

  private static List<LdbColumnFamily> sorted(Iterable<LdbColumnFamily> columnFamilies) {
    List<LdbColumnFamily> result = new ArrayList<>();
    for (LdbColumnFamily cf : columnFamilies) {
      result.add(cf);
    }
    Collections.sort(result, (left, right) -> Integer.compare(left.getId(), right.getId()));
    return result;
  }

  private static List<LdbColumnFamily> sorted(Map<Integer, LdbColumnFamily> columnFamilies) {
    return sorted(columnFamilies.values());
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
}
