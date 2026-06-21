package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.util.Slice;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * SST/table 自描述属性。
 *
 * 该类是 0.8.0 文件格式演进的读侧入口：旧格式 SST 缺少 properties block
 * 时会被识别为 v1 legacy；新格式 SST 如果提供 properties block，则在打开
 * table 时解析 format version 与 feature set。解析结果只在 table 打开阶段
 * 使用，不进入每次 get/iterator 的热路径。
 */
public final class TableProperties {
  public static final String META_INDEX_KEY = "properties";
  public static final String FORMAT_VERSION_KEY = "ldb.format.table.version";
  public static final String COMPATIBLE_FEATURES_KEY = "ldb.format.compatible_features";
  public static final String INCOMPATIBLE_FEATURES_KEY = "ldb.format.incompatible_features";
  public static final String BLOCK_LOCAL_INDEX_FEATURE = "block.local_index.v1";
  public static final String BLOCK_LOCAL_INDEX_META_INDEX_KEY = "block_local_index";
  public static final String BLOCK_LOCAL_INDEX_KEY = "ldb.table.block_local_index";
  public static final String BLOCK_LOCAL_INDEX_VERSION_KEY = "ldb.table.block_local_index.version";
  public static final String BLOCK_LOCAL_INDEX_POLICY_KEY = "ldb.table.block_local_index.policy";
  public static final String BLOCK_LOCAL_INDEX_INTERVAL_KEY = "ldb.table.block_local_index.interval";
  public static final String BLOCK_LOCAL_INDEX_BYTES_KEY = "ldb.table.block_local_index.bytes";
  public static final String BLOCK_LOCAL_INDEX_COVERED_BLOCKS_KEY = "ldb.table.block_local_index.covered_blocks";
  public static final String ENTRY_ANCHOR_INDEX_FEATURE = "block.entry_anchor_index.v1";
  public static final String ENTRY_ANCHOR_INDEX_META_INDEX_KEY = "entry_anchor_index";
  public static final String ENTRY_ANCHOR_INDEX_KEY = "ldb.table.entry_anchor_index";
  public static final String ENTRY_ANCHOR_INDEX_VERSION_KEY = "ldb.table.entry_anchor_index.version";
  public static final String ENTRY_ANCHOR_INDEX_POLICY_KEY = "ldb.table.entry_anchor_index.policy";
  public static final String ENTRY_ANCHOR_INDEX_INTERVAL_KEY = "ldb.table.entry_anchor_index.interval";
  public static final String ENTRY_ANCHOR_INDEX_BYTES_KEY = "ldb.table.entry_anchor_index.bytes";
  public static final String ENTRY_ANCHOR_INDEX_COVERED_BLOCKS_KEY = "ldb.table.entry_anchor_index.covered_blocks";
  public static final String ENTRY_ANCHOR_INDEX_ANCHOR_COUNT_KEY = "ldb.table.entry_anchor_index.anchor_count";
  public static final String INLINE_BLOCK_SEEK_INDEX_FEATURE = "block.inline_seek_index.v1";
  public static final String INLINE_BLOCK_SEEK_INDEX_KEY = "ldb.table.inline_block_seek_index";
  public static final String INLINE_BLOCK_SEEK_INDEX_VERSION_KEY = "ldb.table.inline_block_seek_index.version";
  public static final String INLINE_BLOCK_SEEK_INDEX_POLICY_KEY = "ldb.table.inline_block_seek_index.policy";
  public static final String INLINE_BLOCK_SEEK_INDEX_INTERVAL_KEY = "ldb.table.inline_block_seek_index.interval";
  public static final String INLINE_BLOCK_SEEK_INDEX_BYTES_KEY = "ldb.table.inline_block_seek_index.bytes";
  public static final String INLINE_BLOCK_SEEK_INDEX_COVERED_BLOCKS_KEY = "ldb.table.inline_block_seek_index.covered_blocks";
  public static final String INLINE_BLOCK_SEEK_INDEX_ANCHOR_COUNT_KEY = "ldb.table.inline_block_seek_index.anchor_count";
  public static final String FILTER_POLICY_KEY = "ldb.table.filter_policy";
  public static final String FILTER_SCOPE_KEY = "ldb.table.filter_scope";
  public static final String FILTER_KEY_COUNT_KEY = "ldb.table.filter.key_count";
  public static final String FILTER_BLOCK_BYTES_KEY = "ldb.table.filter.block_bytes";
  public static final String FILTER_BITS_PER_KEY_KEY = "ldb.table.filter.bits_per_key";

  private static final int LEGACY_FORMAT_VERSION = 1;
  private static final int CURRENT_FORMAT_VERSION = 4;

  private final int formatVersion;
  private final boolean legacy;
  private final Map<String, String> values;
  private final Set<String> compatibleFeatures;
  private final Set<String> incompatibleFeatures;

  private TableProperties(int formatVersion,
                          boolean legacy,
                          Map<String, String> values,
                          Set<String> compatibleFeatures,
                          Set<String> incompatibleFeatures) {
    this.formatVersion = formatVersion;
    this.legacy = legacy;
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    this.compatibleFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(compatibleFeatures));
    this.incompatibleFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(incompatibleFeatures));
  }

  /**
   * 返回旧格式 table 的属性视图。
   *
   * 旧 SST 没有 properties block，但读侧仍需要一个稳定对象表示“v1 legacy”，
   * 这样 check/report 和后续 release gate 可以用同一个模型处理新旧格式。
   */
  public static TableProperties legacy() {
    return new TableProperties(
        LEGACY_FORMAT_VERSION,
        true,
        Collections.<String, String>emptyMap(),
        Collections.<String>emptySet(),
        Collections.<String>emptySet());
  }

  /**
   * 从 properties block 解析 table 属性。
   *
   * properties block 沿用普通 block 的 key/value 编码；key/value 首版均按
   * UTF-8 文本解析，便于工具和人工诊断。后续如果引入二进制 value，需要通过
   * format version 或 feature set 显式声明。
   */
  public static TableProperties read(Block block) {
    Map<String, String> values = new LinkedHashMap<>();
    BlockIterator iterator = block.iterator();
    while (iterator.hasNext()) {
      BlockEntry entry = iterator.next();
      values.put(toUtf8(entry.getKey()), toUtf8(entry.getValue()));
    }

    int formatVersion = parseInt(values.get(FORMAT_VERSION_KEY), LEGACY_FORMAT_VERSION);
    return new TableProperties(
        formatVersion,
        false,
        values,
        parseFeatures(values.get(COMPATIBLE_FEATURES_KEY)),
        parseFeatures(values.get(INCOMPATIBLE_FEATURES_KEY)));
  }

  public int getFormatVersion() {
    return formatVersion;
  }

  public boolean isLegacy() {
    return legacy;
  }

  public Map<String, String> getValues() {
    return values;
  }

  public Set<String> getCompatibleFeatures() {
    return compatibleFeatures;
  }

  public Set<String> getIncompatibleFeatures() {
    return incompatibleFeatures;
  }

  public String get(String key) {
    return values.get(key);
  }

  /**
   * 校验当前 reader 是否可以安全读取该 table。
   *
   * 0.8.0 首批尚未支持任何不兼容 table feature；因此只要 properties 中出现
   * incompatible feature，就必须 fail-fast，避免旧读路径静默误读新编码。
   */
  public void validateReadable(boolean failOnUnknownTableFeature, String tableName) {
    if (!failOnUnknownTableFeature) {
      return;
    }
    if (formatVersion > CURRENT_FORMAT_VERSION) {
      throw new IllegalArgumentException("Table " + tableName
          + " uses unsupported table format version " + formatVersion
          + ", current reader supports up to " + CURRENT_FORMAT_VERSION);
    }
    LinkedHashSet<String> unsupportedFeatures = new LinkedHashSet<>(incompatibleFeatures);
    unsupportedFeatures.remove(BLOCK_LOCAL_INDEX_FEATURE);
    unsupportedFeatures.remove(ENTRY_ANCHOR_INDEX_FEATURE);
    unsupportedFeatures.remove(INLINE_BLOCK_SEEK_INDEX_FEATURE);
    if (!unsupportedFeatures.isEmpty()) {
      throw new IllegalArgumentException("Table " + tableName
          + " contains unsupported incompatible features: " + unsupportedFeatures);
    }
  }

  @Override
  public String toString() {
    return "TableProperties{"
        + "formatVersion=" + formatVersion
        + ", legacy=" + legacy
        + ", compatibleFeatures=" + compatibleFeatures
        + ", incompatibleFeatures=" + incompatibleFeatures
        + ", values=" + values
        + '}';
  }

  private static String toUtf8(Slice slice) {
    return new String(slice.getBytes(), StandardCharsets.UTF_8);
  }

  private static int parseInt(String value, int defaultValue) {
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    String trimmed = value.trim();
    try {
      int parsed = Integer.parseInt(trimmed);
      if (parsed < LEGACY_FORMAT_VERSION) {
        throw new IllegalArgumentException("Invalid table format version: " + trimmed);
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid table format version: " + trimmed, e);
    }
  }

  private static Set<String> parseFeatures(String value) {
    LinkedHashSet<String> features = new LinkedHashSet<>();
    if (value == null || value.trim().isEmpty()) {
      return features;
    }
    String[] parts = value.split(",");
    for (String part : parts) {
      String feature = part.trim();
      if (!feature.isEmpty()) {
        features.add(feature);
      }
    }
    return features;
  }
}
