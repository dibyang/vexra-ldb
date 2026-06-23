package net.xdob.vexra.ldb.impl;

import com.google.common.base.Joiner;
import com.google.common.collect.*;
import com.google.common.io.Files;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.table.TableProperties;
import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.InternalIterator;
import net.xdob.vexra.ldb.util.Level0Iterator;
import net.xdob.vexra.ldb.util.MergingIterator;
import net.xdob.vexra.ldb.util.Slice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.impl.DbConstants.NUM_LEVELS;
import static net.xdob.vexra.ldb.impl.LogMonitors.throwExceptionMonitor;

public class VersionSet implements SeekingIterable<InternalKey, Slice> {

  public static final int TARGET_FILE_SIZE = 2 * 1048576;

  // Maximum bytes of overlaps in grandparent (i.e., level+2) before we
  // stop building a single file in a level.level+1 compaction.
  public static final long MAX_GRAND_PARENT_OVERLAP_BYTES = 10 * TARGET_FILE_SIZE;

  private final AtomicLong nextFileNumber = new AtomicLong(2);
  private long manifestFileNumber = 1;
  private Version current;
  private long lastSequence;
  private long logNumber;
  private long prevLogNumber;

  private final Map<Version, Object> activeVersions = new MapMaker().weakKeys().makeMap();
  private final File databaseDir;
  private final TableCache tableCache;
  private final InternalKeyComparator internalKeyComparator;
  private final ThreadLocal<PointReadContext> pointReadContexts = new ThreadLocal<PointReadContext>();

  private LogWriter descriptorLog;
  private final Options options;
  /**
   * compaction pointer 现在必须按 cfId + level 区分
   */
  private final Map<VersionEdit.CfLevel, InternalKey> compactPointers = new TreeMap<>();

  public VersionSet(File databaseDir, TableCache tableCache, InternalKeyComparator internalKeyComparator, Options options)
      throws IOException {
    this.databaseDir = databaseDir;
    this.tableCache = tableCache;
    this.internalKeyComparator = internalKeyComparator;
    this.options = options;
    appendVersion(new Version(this));

    initializeIfNeeded();
  }

  private void initializeIfNeeded() throws IOException {
    if (options.readOnly()) {
      return;
    }

    File currentFile = new File(databaseDir, Filename.currentFileName());

    if (!currentFile.exists()) {
      VersionEdit edit = new VersionEdit();
      edit.setComparatorName(internalKeyComparator.name());
      edit.setLogNumber(prevLogNumber);
      edit.setNextFileNumber(nextFileNumber.get());
      edit.setLastSequenceNumber(lastSequence);

      LogWriter log = Logs.createLogWriter(
          new File(databaseDir, Filename.descriptorFileName(manifestFileNumber)),
          manifestFileNumber, options);
      try {
        writeSnapshot(log);
        log.addRecord(edit.encode(), false);
      } finally {
        log.close();
      }

      Filename.setCurrentFile(databaseDir, log.getFileNumber());
    }
  }

  public void destroy() throws IOException {
    if (descriptorLog != null) {
      descriptorLog.close();
      descriptorLog = null;
    }

    Version t = current;
    if (t != null) {
      current = null;
      t.release();
    }
  }

  private void appendVersion(Version version) {
    requireNonNull(version, "version is null");
    checkArgument(version != current, "version is the current version");
    Version previous = current;
    current = version;
    activeVersions.put(version, new Object());
    if (previous != null) {
      previous.release();
    }
  }

  public void removeVersion(Version version) {
    requireNonNull(version, "version is null");
    checkArgument(version != current, "version is the current version");
    boolean removed = activeVersions.remove(version) != null;
    assert removed : "Expected the version to still be in the active set";
  }

  public InternalKeyComparator getInternalKeyComparator() {
    return internalKeyComparator;
  }

  public TableCache getTableCache() {
    return tableCache;
  }

  public Version getCurrent() {
    return current;
  }

  public long getManifestFileNumber() {
    return manifestFileNumber;
  }

  public long getNextFileNumber() {
    return nextFileNumber.getAndIncrement();
  }

  public long getLogNumber() {
    return logNumber;
  }

  public long getPrevLogNumber() {
    return prevLogNumber;
  }

  @Override
  public MergingIterator iterator() {
    return current.iterator();
  }

  public MergingIterator iterator(int cfId) {
    return current.iterator(cfId);
  }

  public MergingIterator makeInputIterator(Compaction c) {
    List<InternalIterator> list = new ArrayList<>();
    for (int which = 0; which < 2; which++) {
      if (!c.getInputs()[which].isEmpty()) {
        if (c.getLevel() + which == 0) {
          List<FileMetaData> files = c.getInputs()[which];
          list.add(new Level0Iterator(tableCache, files, internalKeyComparator));
        } else {
          list.add(Level.createLevelConcatIterator(tableCache, c.getInputs()[which], internalKeyComparator));
        }
      }
    }
    return new MergingIterator(list, internalKeyComparator);
  }

  public LookupResult get(int cfId, LookupKey key) {
    PointReadContext readContext = pointReadContexts.get();
    if (readContext == null) {
      readContext = new PointReadContext(internalKeyComparator.getUserComparator());
      pointReadContexts.set(readContext);
    }
    readContext.prepare(current, cfId);
    return current.get(cfId, key, readContext);
  }

  public List<LookupResult> get(int cfId, List<LookupKey> keys) {
    return current.get(cfId, keys);
  }

  public String sstReadStats() {
    return current.sstReadStats() + "," + tableCache.readStats();
  }

  /**
   * 汇总当前 Version 中所有 SST 的 table format 信息。
   *
   * 该属性服务 0.8.0 文件格式演进的诊断和 release gate：旧 SST 会被统计为
   * legacy v1；显式写入 v2 properties block 的 SST 会展示 format version 与
   * feature set。读取 properties 只发生在诊断入口，不影响普通 get 热路径。
   */
  public String tableFormatStats() {
    int total = 0;
    int legacy = 0;
    int v2 = 0;
    int v3 = 0;
    int incompatible = 0;
    long dataBlockCount = 0;
    long inlineBlockSeekIndexBytes = 0;
    long inlineBlockSeekIndexCoveredBlocks = 0;
    long inlineBlockSeekIndexAnchorCount = 0;
    long blockLocalIndexBytes = 0;
    long blockLocalIndexCoveredBlocks = 0;
    long blockLocalIndexDataBlockBytes = 0;
    long blockLocalIndexCandidateBlocks = 0;
    long blockLocalIndexSkippedBlocks = 0;
    long entryAnchorIndexBytes = 0;
    long entryAnchorIndexCoveredBlocks = 0;
    long entryAnchorIndexAnchorCount = 0;
    Set<Integer> versions = new TreeSet<>();
    StringBuilder files = new StringBuilder();
    for (Entry<VersionEdit.CfLevel, FileMetaData> entry : current.getFiles().entries()) {
      FileMetaData file = entry.getValue();
      TableProperties properties = tableCache.getTableProperties(file.getNumber());
      total++;
      versions.add(properties.getFormatVersion());
      if (properties.isLegacy()) {
        legacy++;
      }
      if (properties.getFormatVersion() == 2) {
        v2++;
      }
      if (properties.getFormatVersion() == 3) {
        v3++;
      }
      if (!properties.getIncompatibleFeatures().isEmpty()) {
        incompatible++;
      }
      dataBlockCount += propertyLong(properties, "ldb.table.data_block_count");
      inlineBlockSeekIndexBytes += propertyLong(properties, TableProperties.INLINE_BLOCK_SEEK_INDEX_BYTES_KEY);
      inlineBlockSeekIndexCoveredBlocks += propertyLong(properties, TableProperties.INLINE_BLOCK_SEEK_INDEX_COVERED_BLOCKS_KEY);
      inlineBlockSeekIndexAnchorCount += propertyLong(properties, TableProperties.INLINE_BLOCK_SEEK_INDEX_ANCHOR_COUNT_KEY);
      blockLocalIndexBytes += propertyLong(properties, TableProperties.BLOCK_LOCAL_INDEX_BYTES_KEY);
      blockLocalIndexCoveredBlocks += propertyLong(properties, TableProperties.BLOCK_LOCAL_INDEX_COVERED_BLOCKS_KEY);
      blockLocalIndexDataBlockBytes += propertyLong(properties, TableProperties.BLOCK_LOCAL_INDEX_DATA_BLOCK_BYTES_KEY);
      blockLocalIndexCandidateBlocks += propertyLong(properties, TableProperties.BLOCK_LOCAL_INDEX_CANDIDATE_BLOCKS_KEY);
      blockLocalIndexSkippedBlocks += propertyLong(properties, TableProperties.BLOCK_LOCAL_INDEX_SKIPPED_BLOCKS_KEY);
      entryAnchorIndexBytes += propertyLong(properties, TableProperties.ENTRY_ANCHOR_INDEX_BYTES_KEY);
      entryAnchorIndexCoveredBlocks += propertyLong(properties, TableProperties.ENTRY_ANCHOR_INDEX_COVERED_BLOCKS_KEY);
      entryAnchorIndexAnchorCount += propertyLong(properties, TableProperties.ENTRY_ANCHOR_INDEX_ANCHOR_COUNT_KEY);
      if (files.length() > 0) {
        files.append(';');
      }
      files.append("cf=").append(entry.getKey().getCfId())
          .append("|level=").append(entry.getKey().getLevel())
          .append("|file=").append(file.getNumber())
          .append("|formatVersion=").append(properties.getFormatVersion())
          .append("|legacy=").append(properties.isLegacy())
          .append("|compatible=").append(properties.getCompatibleFeatures())
          .append("|incompatible=").append(properties.getIncompatibleFeatures())
          .append("|blockLocalIndexSpaceAmplificationPpm=")
          .append(propertyLong(properties, TableProperties.BLOCK_LOCAL_INDEX_SPACE_AMPLIFICATION_PPM_KEY))
          .append("|blockLocalIndexAdmissionPolicy=")
          .append(valueOrEmpty(properties.get(TableProperties.BLOCK_LOCAL_INDEX_ADMISSION_POLICY_KEY)));
    }
    return "tables=" + total
        + ",legacy=" + legacy
        + ",v2=" + v2
        + ",v3=" + v3
        + ",incompatible=" + incompatible
        + ",formatVersions=" + versions
        + ",dataBlockCount=" + dataBlockCount
        + ",inlineBlockSeekIndexBytes=" + inlineBlockSeekIndexBytes
        + ",inlineBlockSeekIndexCoveredBlocks=" + inlineBlockSeekIndexCoveredBlocks
        + ",inlineBlockSeekIndexAnchorCount=" + inlineBlockSeekIndexAnchorCount
        + ",blockLocalIndexBytes=" + blockLocalIndexBytes
        + ",blockLocalIndexCoveredBlocks=" + blockLocalIndexCoveredBlocks
        + ",blockLocalIndexDataBlockBytes=" + blockLocalIndexDataBlockBytes
        + ",blockLocalIndexCandidateBlocks=" + blockLocalIndexCandidateBlocks
        + ",blockLocalIndexSkippedBlocks=" + blockLocalIndexSkippedBlocks
        + ",blockLocalIndexSpaceAmplificationPpm="
        + blockLocalIndexSpaceAmplificationPpm(blockLocalIndexBytes, blockLocalIndexDataBlockBytes)
        + ",entryAnchorIndexBytes=" + entryAnchorIndexBytes
        + ",entryAnchorIndexCoveredBlocks=" + entryAnchorIndexCoveredBlocks
        + ",entryAnchorIndexAnchorCount=" + entryAnchorIndexAnchorCount
        + ",files=[" + files + "]";
  }

  private static long propertyLong(TableProperties properties, String key) {
    String value = properties.get(key);
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    return Long.parseLong(value.trim());
  }

  private static long blockLocalIndexSpaceAmplificationPpm(long blockLocalIndexBytes, long dataBlockBytes) {
    if (dataBlockBytes <= 0) {
      return 0;
    }
    return (blockLocalIndexBytes * 1_000_000L) / dataBlockBytes;
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * 返回当前数据库目录的存储格式摘要。
   *
   * 这里先把 table format 作为可执行证据接入，同时记录其它文件类型的当前
   * legacy/schema 事实，后续 MANIFEST/backup schema 加固可继续扩展该属性。
   */
  public String storageFormatStats() {
    return "table={" + tableFormatStats() + "}"
        + ",wal=log-v1"
        + ",manifest=version-edit-log-v1"
        + ",current=text-manifest-pointer-v1"
        + ",columnFamilies=text-registry-v1"
        + ",backupMetadata=json-v1+schema-v2";
  }

  public boolean overlapInLevel(int cfId, int level, Slice smallestUserKey, Slice largestUserKey) {
    return current.overlapInLevel(cfId, level, smallestUserKey, largestUserKey);
  }

  public int numberOfFilesInLevel(int cfId, int level) {
    return current.numberOfFilesInLevel(cfId, level);
  }

  public long numberOfBytesInLevel(int cfId, int level) {
    long total = 0;
    for (FileMetaData fileMetaData : current.getFiles(cfId, level)) {
      total += fileMetaData.getFileSize();
    }
    return total;
  }

  public long getLastSequence() {
    return lastSequence;
  }

  public void setLastSequence(long newLastSequence) {
    checkArgument(newLastSequence >= lastSequence,
        "Expected newLastSequence to be greater than or equal to current lastSequence");
    this.lastSequence = newLastSequence;
  }

  public void logAndApply(VersionEdit edit) throws IOException {
    if (edit.getLogNumber() != null) {
      checkArgument(edit.getLogNumber() >= logNumber);
      checkArgument(edit.getLogNumber() < nextFileNumber.get());
    } else {
      edit.setLogNumber(logNumber);
    }

    if (edit.getPreviousLogNumber() == null) {
      edit.setPreviousLogNumber(prevLogNumber);
    }

    edit.setNextFileNumber(nextFileNumber.get());
    edit.setLastSequenceNumber(lastSequence);

    Version version = new Version(this);
    Builder builder = new Builder(this, current);
    builder.apply(edit);
    builder.saveTo(version);

    finalizeVersion(version);

    boolean createdNewManifest = false;
    try {
      if (descriptorLog == null) {
        edit.setNextFileNumber(nextFileNumber.get());
        descriptorLog = Logs.createLogWriter(
            new File(databaseDir, Filename.descriptorFileName(manifestFileNumber)),
            manifestFileNumber, options);
        writeSnapshot(descriptorLog);
        createdNewManifest = true;
      }

      Slice record = edit.encode();
      descriptorLog.addRecord(record, true);

      if (createdNewManifest) {
        Filename.setCurrentFile(databaseDir, descriptorLog.getFileNumber());
      }
    } catch (IOException e) {
      if (createdNewManifest) {
        descriptorLog.close();
        new File(databaseDir, Filename.logFileName(descriptorLog.getFileNumber())).delete();
        descriptorLog = null;
      }
      throw e;
    }

    appendVersion(version);
    logNumber = edit.getLogNumber();
    prevLogNumber = edit.getPreviousLogNumber();
  }

  private void writeSnapshot(LogWriter log) throws IOException {
    VersionEdit edit = new VersionEdit();
    edit.setComparatorName(internalKeyComparator.name());

    edit.setCompactPointers(compactPointers);
    edit.addFiles(current.getFiles());

    Slice record = edit.encode();
    log.addRecord(record, false);
  }

  public void recover() throws IOException {
    File currentFile = new File(databaseDir, Filename.currentFileName());
    checkState(currentFile.exists(), "CURRENT file does not exist");

    String currentName = Files.toString(currentFile, UTF_8);
    if (currentName.isEmpty() || currentName.charAt(currentName.length() - 1) != '\n') {
      throw new IllegalStateException("CURRENT file does not end with newline");
    }
    currentName = currentName.substring(0, currentName.length() - 1);
    Filename.parseCurrentManifestFileName(currentName);

    try (FileInputStream fis = new FileInputStream(new File(databaseDir, currentName));
         FileChannel fileChannel = fis.getChannel()) {

      Long nextFileNumber = null;
      Long lastSequence = null;
      Long logNumber = null;
      Long prevLogNumber = null;
      Builder builder = new Builder(this, current);

      LogReader reader = new LogReader(fileChannel, throwExceptionMonitor(), true, 0);
      for (Slice record = reader.readRecord(); record != null; record = reader.readRecord()) {
        VersionEdit edit = new VersionEdit(record);

        String editComparator = edit.getComparatorName();
        String userComparator = internalKeyComparator.name();
        checkArgument(editComparator == null || editComparator.equals(userComparator),
            "Expected user comparator %s to match existing database comparator ",
            userComparator, editComparator);

        builder.apply(edit);

        logNumber = coalesce(edit.getLogNumber(), logNumber);
        prevLogNumber = coalesce(edit.getPreviousLogNumber(), prevLogNumber);
        nextFileNumber = coalesce(edit.getNextFileNumber(), nextFileNumber);
        lastSequence = coalesce(edit.getLastSequenceNumber(), lastSequence);
      }

      List<String> problems = new ArrayList<>();
      if (nextFileNumber == null) {
        problems.add("Descriptor does not contain a meta-nextfile entry");
      }
      if (logNumber == null) {
        problems.add("Descriptor does not contain a meta-lognumber entry");
      }
      if (lastSequence == null) {
        problems.add("Descriptor does not contain a last-sequence-number entry");
      }
      if (!problems.isEmpty()) {
        throw new RuntimeException("Corruption: \n\t" + Joiner.on("\n\t").join(problems));
      }

      if (prevLogNumber == null) {
        prevLogNumber = 0L;
      }

      Version newVersion = new Version(this);
      builder.saveTo(newVersion);

      finalizeVersion(newVersion);

      appendVersion(newVersion);
      manifestFileNumber = nextFileNumber;
      this.nextFileNumber.set(nextFileNumber + 1);
      this.lastSequence = lastSequence;
      this.logNumber = logNumber;
      this.prevLogNumber = prevLogNumber;
    }
  }

  /**
   * 这里暂时仍然只维护一组 compactionScore/compactionLevel，
   * 取所有 CF 中最“值得 compact”的那组。
   */
  private void finalizeVersion(Version version) {
    int bestCfId = -1;
    int bestLevel = -1;
    double bestScore = -1;

    for (Integer cfId : version.getColumnFamilyIds()) {
      for (int level = 0; level + 1 < version.numberOfLevels(); level++) {
        double score;
        if (level == 0) {
          score = 1.0 * version.numberOfFilesInLevel(cfId, level) / options.level0CompactionTrigger();
        } else {
          long levelBytes = 0;
          for (FileMetaData fileMetaData : version.getFiles(cfId, level)) {
            levelBytes += fileMetaData.getFileSize();
          }
          score = 1.0 * levelBytes / maxBytesForLevel(level);
        }

        if (score > bestScore) {
          bestScore = score;
          bestLevel = level;
          bestCfId = cfId;
        }
      }
    }

    version.setCompactionCfId(bestCfId);
    version.setCompactionLevel(bestLevel);
    version.setCompactionScore(bestScore);
  }

  private static <V> V coalesce(V... values) {
    for (V value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  public List<FileMetaData> getLiveFiles() {
    ImmutableList.Builder<FileMetaData> builder = ImmutableList.builder();
    for (Version activeVersion : activeVersions.keySet()) {
      builder.addAll(activeVersion.getFiles().values());
    }
    return builder.build();
  }

  private static double maxBytesForLevel(int level) {
    double result = 10 * 1048576.0;
    while (level > 1) {
      result *= 10;
      level--;
    }
    return result;
  }

  public static long maxFileSizeForLevel(int level) {
    return TARGET_FILE_SIZE;
  }

  public boolean needsCompaction() {
    return current.getCompactionScore() >= 1 || current.getFileToCompact() != null;
  }

  public Compaction compactRange(int cfId, int level, InternalKey begin, InternalKey end) {
    List<FileMetaData> levelInputs = getOverlappingInputs(cfId, level, begin, end);
    if (levelInputs.isEmpty()) {
      return null;
    }
    return setupOtherInputs(cfId, level, levelInputs);
  }

  public Compaction pickCompaction(int cfId) {
    boolean sizeCompaction =
        (current.getCompactionScore() >= 1 && current.getCompactionCfId() == cfId);
    boolean seekCompaction =
        (current.getFileToCompact() != null && current.getFileToCompactCfId() == cfId);

    int level;
    List<FileMetaData> levelInputs;
    if (sizeCompaction) {
      level = current.getCompactionLevel();
      checkState(level >= 0);
      checkState(level + 1 < NUM_LEVELS);

      levelInputs = new ArrayList<>();
      for (FileMetaData fileMetaData : current.getFiles(cfId, level)) {
        VersionEdit.CfLevel cfLevel = new VersionEdit.CfLevel(cfId, level);
        if (!compactPointers.containsKey(cfLevel)
            || internalKeyComparator.compare(fileMetaData.getLargest(), compactPointers.get(cfLevel)) > 0) {
          levelInputs.add(fileMetaData);
          break;
        }
      }
      if (levelInputs.isEmpty() && !current.getFiles(cfId, level).isEmpty()) {
        levelInputs.add(current.getFiles(cfId, level).get(0));
      }
    } else if (seekCompaction) {
      level = current.getFileToCompactLevel();
      levelInputs = ImmutableList.of(current.getFileToCompact());
    } else {
      return null;
    }

    if (levelInputs.isEmpty()) {
      return null;
    }

    if (level == 0) {
      Entry<InternalKey, InternalKey> range = getRange(levelInputs);
      levelInputs = getOverlappingInputs(cfId, 0, range.getKey(), range.getValue());
      checkState(!levelInputs.isEmpty());
    }

    return setupOtherInputs(cfId, level, levelInputs);
  }

  private Compaction setupOtherInputs(int cfId, int level, List<FileMetaData> levelInputs) {
    Entry<InternalKey, InternalKey> range = getRange(levelInputs);
    InternalKey smallest = range.getKey();
    InternalKey largest = range.getValue();

    List<FileMetaData> levelUpInputs = getOverlappingInputs(cfId, level + 1, smallest, largest);

    range = getRange(levelInputs, levelUpInputs);
    InternalKey allStart = range.getKey();
    InternalKey allLimit = range.getValue();

    if (!levelUpInputs.isEmpty()) {
      List<FileMetaData> expanded0 = getOverlappingInputs(cfId, level, allStart, allLimit);

      if (expanded0.size() > levelInputs.size()) {
        range = getRange(expanded0);
        InternalKey newStart = range.getKey();
        InternalKey newLimit = range.getValue();

        List<FileMetaData> expanded1 = getOverlappingInputs(cfId, level + 1, newStart, newLimit);
        if (expanded1.size() == levelUpInputs.size()) {
          smallest = newStart;
          largest = newLimit;
          levelInputs = expanded0;
          levelUpInputs = expanded1;

          range = getRange(levelInputs, levelUpInputs);
          allStart = range.getKey();
          allLimit = range.getValue();
        }
      }
    }

    List<FileMetaData> grandparents = ImmutableList.of();
    if (level + 2 < NUM_LEVELS) {
      grandparents = getOverlappingInputs(cfId, level + 2, allStart, allLimit);
    }

    Compaction compaction = new Compaction(current, level, levelInputs, levelUpInputs, grandparents);
    compaction.setCfId(cfId);

    VersionEdit.CfLevel cfLevel = new VersionEdit.CfLevel(cfId, level);
    compactPointers.put(cfLevel, largest);
    compaction.getEdit().setCompactPointer(cfId, level, largest);

    return compaction;
  }

  List<FileMetaData> getOverlappingInputs(int cfId, int level, InternalKey begin, InternalKey end) {
    ImmutableList.Builder<FileMetaData> files = ImmutableList.builder();
    Slice userBegin = begin.getUserKey();
    Slice userEnd = end.getUserKey();
    UserComparator userComparator = internalKeyComparator.getUserComparator();

    for (FileMetaData fileMetaData : current.getFiles(cfId, level)) {
      if (userComparator.compare(fileMetaData.getLargest().getUserKey(), userBegin) < 0
          || userComparator.compare(fileMetaData.getSmallest().getUserKey(), userEnd) > 0) {
        // skip
      } else {
        files.add(fileMetaData);
      }
    }
    return files.build();
  }

  private Entry<InternalKey, InternalKey> getRange(List<FileMetaData>... inputLists) {
    InternalKey smallest = null;
    InternalKey largest = null;
    for (List<FileMetaData> inputList : inputLists) {
      for (FileMetaData fileMetaData : inputList) {
        if (smallest == null) {
          smallest = fileMetaData.getSmallest();
          largest = fileMetaData.getLargest();
        } else {
          if (internalKeyComparator.compare(fileMetaData.getSmallest(), smallest) < 0) {
            smallest = fileMetaData.getSmallest();
          }
          if (internalKeyComparator.compare(fileMetaData.getLargest(), largest) > 0) {
            largest = fileMetaData.getLargest();
          }
        }
      }
    }
    return Maps.immutableEntry(smallest, largest);
  }

  public long getMaxNextLevelOverlappingBytes(int cfId) {
    long result = 0;
    for (int level = 1; level < NUM_LEVELS; level++) {
      for (FileMetaData fileMetaData : current.getFiles(cfId, level)) {
        List<FileMetaData> overlaps =
            getOverlappingInputs(cfId, level + 1, fileMetaData.getSmallest(), fileMetaData.getLargest());
        long totalSize = 0;
        for (FileMetaData overlap : overlaps) {
          totalSize += overlap.getFileSize();
        }
        result = Math.max(result, totalSize);
      }
    }
    return result;
  }

  /**
   * A helper class so we can efficiently apply a whole sequence
   * of edits to a particular state without creating intermediate
   * Versions that contain full copies of the intermediate state.
   */
  private static class Builder {
    private final VersionSet versionSet;
    private final Version baseVersion;

    /**
     * cfId -> levels
     */
    private final Map<Integer, List<LevelState>> cfLevels = new HashMap<>();

    private Builder(VersionSet versionSet, Version baseVersion) {
      this.versionSet = versionSet;
      this.baseVersion = baseVersion;

      for (Integer cfId : baseVersion.getColumnFamilyIds()) {
        cfLevels.put(cfId, newLevelStates(versionSet.internalKeyComparator));
      }
    }

    private static List<LevelState> newLevelStates(InternalKeyComparator comparator) {
      List<LevelState> levels = new ArrayList<>(NUM_LEVELS);
      for (int i = 0; i < NUM_LEVELS; i++) {
        levels.add(new LevelState(comparator));
      }
      return levels;
    }

    private List<LevelState> getOrCreateLevels(int cfId) {
      List<LevelState> levels = cfLevels.get(cfId);
      if (levels == null) {
        levels = newLevelStates(versionSet.internalKeyComparator);
        cfLevels.put(cfId, levels);
      }
      return levels;
    }

    public void apply(VersionEdit edit) {
      for (Entry<VersionEdit.CfLevel, InternalKey> entry : edit.getCompactPointers().entrySet()) {
        versionSet.compactPointers.put(entry.getKey(), entry.getValue());
      }

      for (Entry<VersionEdit.CfLevel, Long> entry : edit.getDeletedFiles().entries()) {
        int cfId = entry.getKey().getCfId();
        int level = entry.getKey().getLevel();
        long fileNumber = entry.getValue();
        getOrCreateLevels(cfId).get(level).deletedFiles.add(fileNumber);
      }

      for (Entry<VersionEdit.CfLevel, FileMetaData> entry : edit.getNewFiles().entries()) {
        int cfId = entry.getKey().getCfId();
        int level = entry.getKey().getLevel();
        FileMetaData fileMetaData = entry.getValue();

        int allowedSeeks = (int) (fileMetaData.getFileSize() / 16384);
        if (allowedSeeks < 100) {
          allowedSeeks = 100;
        }
        fileMetaData.setAllowedSeeks(allowedSeeks);

        List<LevelState> levels = getOrCreateLevels(cfId);
        levels.get(level).deletedFiles.remove(fileMetaData.getNumber());
        levels.get(level).addedFiles.add(fileMetaData);
      }
    }

    public void saveTo(Version version) throws IOException {
      FileMetaDataBySmallestKey cmp = new FileMetaDataBySmallestKey(versionSet.internalKeyComparator);

      Set<Integer> allCfIds = new TreeSet<>();
      allCfIds.addAll(baseVersion.getColumnFamilyIds());
      allCfIds.addAll(cfLevels.keySet());

      for (Integer cfId : allCfIds) {
        List<LevelState> levels = getOrCreateLevels(cfId);

        for (int level = 0; level < baseVersion.numberOfLevels(); level++) {
          Collection<FileMetaData> baseFiles = baseVersion.getFiles(cfId, level);
          if (baseFiles == null) {
            baseFiles = ImmutableList.of();
          }

          SortedSet<FileMetaData> addedFiles = levels.get(level).addedFiles;
          if (addedFiles == null) {
            addedFiles = ImmutableSortedSet.of();
          }

          ArrayList<FileMetaData> sortedFiles = new ArrayList<>(baseFiles.size() + addedFiles.size());
          sortedFiles.addAll(baseFiles);
          sortedFiles.addAll(addedFiles);
          Collections.sort(sortedFiles, cmp);

          for (FileMetaData fileMetaData : sortedFiles) {
            maybeAddFile(version, cfId, level, fileMetaData);
          }
        }
      }

      version.assertNoOverlappingFiles();
    }

    private void maybeAddFile(Version version, int cfId, int level, FileMetaData fileMetaData)
        throws IOException {
      if (getOrCreateLevels(cfId).get(level).deletedFiles.contains(fileMetaData.getNumber())) {
        return;
      }

      List<FileMetaData> files = version.getFiles(cfId, level);
      if (level > 0 && !files.isEmpty()) {
        boolean filesOverlap =
            versionSet.internalKeyComparator.compare(
                files.get(files.size() - 1).getLargest(),
                fileMetaData.getSmallest()) >= 0;
        if (filesOverlap) {
          throw new IOException(String.format(
              "Compaction is obsolete: Overlapping files %s and %s in cf %s level %s",
              files.get(files.size() - 1).getNumber(),
              fileMetaData.getNumber(),
              cfId,
              level));
        }
      }
      version.addFile(cfId, level, fileMetaData);
    }

    private static class FileMetaDataBySmallestKey implements Comparator<FileMetaData> {
      private final InternalKeyComparator internalKeyComparator;

      private FileMetaDataBySmallestKey(InternalKeyComparator internalKeyComparator) {
        this.internalKeyComparator = internalKeyComparator;
      }

      @Override
      public int compare(FileMetaData f1, FileMetaData f2) {
        return ComparisonChain
            .start()
            .compare(f1.getSmallest(), f2.getSmallest(), internalKeyComparator)
            .compare(f1.getNumber(), f2.getNumber())
            .result();
      }
    }

    private static class LevelState {
      private final SortedSet<FileMetaData> addedFiles;
      private final Set<Long> deletedFiles = new HashSet<>();

      public LevelState(InternalKeyComparator internalKeyComparator) {
        addedFiles = new TreeSet<>(new FileMetaDataBySmallestKey(internalKeyComparator));
      }

      @Override
      public String toString() {
        return "LevelState{" +
            "addedFiles=" + addedFiles +
            ", deletedFiles=" + deletedFiles +
            '}';
      }
    }
  }
}
