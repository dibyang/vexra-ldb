package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.InternalTableIterator;
import net.xdob.vexra.ldb.util.LevelIterator;
import net.xdob.vexra.ldb.util.Slice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static net.xdob.vexra.ldb.impl.ValueType.VALUE;

// todo this class should be immutable
public class Level
    implements SeekingIterable<InternalKey, Slice> {
  private final int levelNumber;
  private final TableCache tableCache;
  private final InternalKeyComparator internalKeyComparator;
  private final List<FileMetaData> files;

  public Level(int levelNumber, List<FileMetaData> files, TableCache tableCache, InternalKeyComparator internalKeyComparator) {
    checkArgument(levelNumber >= 0, "levelNumber is negative");
    requireNonNull(files, "files is null");
    requireNonNull(tableCache, "tableCache is null");
    requireNonNull(internalKeyComparator, "internalKeyComparator is null");

    this.files = new ArrayList<>(files);
    this.tableCache = tableCache;
    this.internalKeyComparator = internalKeyComparator;
    checkArgument(levelNumber >= 0, "levelNumber is negative");
    this.levelNumber = levelNumber;
  }

  public int getLevelNumber() {
    return levelNumber;
  }

  public List<FileMetaData> getFiles() {
    return files;
  }

  @Override
  public LevelIterator iterator() {
    return createLevelConcatIterator(tableCache, files, internalKeyComparator);
  }

  public static LevelIterator createLevelConcatIterator(TableCache tableCache, List<FileMetaData> files, InternalKeyComparator internalKeyComparator) {
    return new LevelIterator(tableCache, files, internalKeyComparator);
  }

  public LookupResult get(LookupKey key, ReadStats readStats) {
    return get(key, readStats, null);
  }

  public LookupResult get(LookupKey key, ReadStats readStats, PointReadContext readContext) {
    readStats.clear();
    if (files.isEmpty()) {
      return null;
    }

    List<FileMetaData> fileMetaDataList = new ArrayList<>(files.size());
    FileMetaData contextHitFile = null;
    if (levelNumber == 0) {
      for (FileMetaData fileMetaData : files) {
        if (internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) >= 0 &&
            internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getLargest().getUserKey()) <= 0) {
          fileMetaDataList.add(fileMetaData);
          readStats.recordCandidateFile();
        }
      }
    } else {
      FileMetaData cachedFile = readContext == null ? null : readContext.findCoveredFile(levelNumber, key);
      if (cachedFile != null) {
        fileMetaDataList.add(cachedFile);
        contextHitFile = cachedFile;
        readStats.recordCandidateFile();
        readStats.recordPointReadContextFileHit();
      } else {
        if (readContext != null) {
          readStats.recordPointReadContextFileMiss();
        }
      // Binary search to find earliest index whose largest key >= ikey.
      int index = ceilingFileIndex(files, key.getInternalKey(), internalKeyComparator);

      // did we find any files that could contain the key?
      if (index >= files.size()) {
        return null;
      }

      // check if the smallest user key in the file is less than the target user key
      FileMetaData fileMetaData = files.get(index);
      if (internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) < 0) {
        return null;
      }

      // search this file
      fileMetaDataList.add(fileMetaData);
      readStats.recordCandidateFile();
        if (readContext != null) {
          readContext.remember(levelNumber, fileMetaData);
        }
      }
    }

    FileMetaData lastFileRead = null;
    int lastFileReadLevel = -1;
    Slice userKey = key.getUserKey();
    for (FileMetaData fileMetaData : fileMetaDataList) {
      if (fileMetaData != contextHitFile && !tableCache.mayContain(fileMetaData, userKey)) {
        readStats.recordFilterSkip();
        continue; // 这个 table 一定没有这个 userKey
      }
      if (lastFileRead != null && readStats.getSeekFile() == null) {
        // We have had more than one seek for this read.  Charge the first file.
        readStats.setSeekFile(lastFileRead);
        readStats.setSeekFileLevel(lastFileReadLevel);
      }

      lastFileRead = fileMetaData;
      lastFileReadLevel = levelNumber;

      readStats.recordTableRead();

      LookupResult pointResult = null;
      long pointSequence = -1;
      Entry<Slice, Slice> entry = tableCache.get(fileMetaData, key);
      if (entry != null) {
        // parse the key in the block
        InternalKey internalKey = new InternalKey(entry.getKey());
        checkState(internalKey != null, "Corrupt key for %s", key.getUserKey().toString(UTF_8));

        // if this is a value key (not a delete) and the keys match, return the value
        if (key.getUserKey().equals(internalKey.getUserKey())) {
          if (internalKey.getValueType() == ValueType.DELETION) {
            pointResult = LookupResult.deleted(key, internalKey.getSequenceNumber());
            pointSequence = internalKey.getSequenceNumber();
          } else if (internalKey.getValueType() == VALUE) {
            pointResult = LookupResult.ok(key, entry.getValue(), internalKey.getSequenceNumber());
            pointSequence = internalKey.getSequenceNumber();
          }
        }
      }

      long rangeDeleteSequence = fileMetaData.hasRangeDeletes()
          ? newestCoveringRangeDelete(fileMetaData, key, pointSequence)
          : -1;
      if (rangeDeleteSequence >= 0) {
        return LookupResult.deleted(key, rangeDeleteSequence);
      }
      if (pointResult != null) {
        readStats.recordCandidateEntryHit();
        return pointResult;
      }
      readStats.recordCandidateEntryMiss();
      readStats.recordBloomFalsePositive();
    }

    return null;
  }

  public List<LookupResult> get(List<LookupKey> keys, ReadStats readStats) {
    readStats.clear();
    List<LookupResult> results = BatchReadLists.newNullArrayList(keys.size());
    if (files.isEmpty() || keys.isEmpty()) {
      return results;
    }

    List<List<Integer>> fileToKeyIndexes = new ArrayList<List<Integer>>(files.size());
    for (int i = 0; i < files.size(); i++) {
      fileToKeyIndexes.add(new ArrayList<Integer>());
    }
    UserComparator userComparator = internalKeyComparator.getUserComparator();
    for (int i = 0; i < keys.size(); i++) {
      LookupKey key = keys.get(i);
      int index = ceilingFileIndex(files, key.getInternalKey(), internalKeyComparator);
      if (index >= files.size()) {
        continue;
      }
      FileMetaData fileMetaData = files.get(index);
      if (userComparator.compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) < 0) {
        continue;
      }
      readStats.recordCandidateFile();
      if (tableCache.mayContain(fileMetaData, key.getUserKey())) {
        fileToKeyIndexes.get(index).add(i);
      } else {
        readStats.recordFilterSkip();
      }
    }

    for (int fileIndex = 0; fileIndex < fileToKeyIndexes.size(); fileIndex++) {
      List<Integer> keyIndexes = fileToKeyIndexes.get(fileIndex);
      if (keyIndexes.isEmpty()) {
        continue;
      }
      FileMetaData fileMetaData = files.get(fileIndex);
      readStats.recordTableRead();
      List<Entry<Slice, Slice>> entries = tableCache.get(fileMetaData, keys, keyIndexes);
      for (int i = 0; i < keyIndexes.size(); i++) {
        int keyIndex = keyIndexes.get(i);
        LookupResult lookupResult = getFromTableEntry(fileMetaData, keys.get(keyIndex), entries.get(i), readStats);
        if (lookupResult != null) {
          results.set(keyIndex, lookupResult);
        }
      }
    }

    return results;
  }

  private LookupResult getFromTableEntry(FileMetaData fileMetaData,
                                         LookupKey key,
                                         Entry<Slice, Slice> entry,
                                         ReadStats readStats) {
    LookupResult pointResult = null;
    long pointSequence = -1;
    if (entry != null) {
      InternalKey internalKey = new InternalKey(entry.getKey());
      checkState(internalKey != null, "Corrupt key for %s", key.getUserKey().toString(UTF_8));
      if (key.getUserKey().equals(internalKey.getUserKey())) {
        if (internalKey.getValueType() == ValueType.DELETION) {
          pointResult = LookupResult.deleted(key, internalKey.getSequenceNumber());
          pointSequence = internalKey.getSequenceNumber();
        } else if (internalKey.getValueType() == VALUE) {
          pointResult = LookupResult.ok(key, entry.getValue(), internalKey.getSequenceNumber());
          pointSequence = internalKey.getSequenceNumber();
        }
      }
    }

    long rangeDeleteSequence = fileMetaData.hasRangeDeletes()
        ? newestCoveringRangeDelete(fileMetaData, key, pointSequence)
        : -1;
    if (rangeDeleteSequence >= 0) {
      return LookupResult.deleted(key, rangeDeleteSequence);
    }
    if (pointResult != null) {
      readStats.recordCandidateEntryHit();
    } else {
      readStats.recordCandidateEntryMiss();
      readStats.recordBloomFalsePositive();
    }
    return pointResult;
  }

  private LookupResult getFromTable(FileMetaData fileMetaData, LookupKey key) {
    LookupResult pointResult = null;
    long pointSequence = -1;
    Entry<Slice, Slice> entry = tableCache.get(fileMetaData, key);
    if (entry != null) {
      InternalKey internalKey = new InternalKey(entry.getKey());
      checkState(internalKey != null, "Corrupt key for %s", key.getUserKey().toString(UTF_8));
      if (key.getUserKey().equals(internalKey.getUserKey())) {
        if (internalKey.getValueType() == ValueType.DELETION) {
          pointResult = LookupResult.deleted(key, internalKey.getSequenceNumber());
          pointSequence = internalKey.getSequenceNumber();
        } else if (internalKey.getValueType() == VALUE) {
          pointResult = LookupResult.ok(key, entry.getValue(), internalKey.getSequenceNumber());
          pointSequence = internalKey.getSequenceNumber();
        }
      }
    }

    long rangeDeleteSequence = fileMetaData.hasRangeDeletes()
        ? newestCoveringRangeDelete(fileMetaData, key, pointSequence)
        : -1;
    if (rangeDeleteSequence >= 0) {
      return LookupResult.deleted(key, rangeDeleteSequence);
    }
    return pointResult;
  }

  private long newestCoveringRangeDelete(FileMetaData fileMetaData, LookupKey key, long newerThanSequence) {
    InternalTableIterator iterator = tableCache.newIterator(fileMetaData);
    iterator.seekToFirst();
    long newest = -1;
    while (iterator.hasNext()) {
      Entry<InternalKey, Slice> entry = iterator.next();
      InternalKey internalKey = entry.getKey();
      if (internalKey.getValueType() != ValueType.DELETE_RANGE) {
        continue;
      }
      long tombstoneSequence = internalKey.getSequenceNumber();
      if (tombstoneSequence > key.getInternalKey().getSequenceNumber()
          || tombstoneSequence <= newerThanSequence) {
        continue;
      }
      if (covers(internalKey.getUserKey(), entry.getValue(), key.getUserKey())) {
        newest = Math.max(newest, tombstoneSequence);
      }
    }
    return newest;
  }

  private boolean covers(Slice beginKey, Slice endKey, Slice userKey) {
    UserComparator userComparator = internalKeyComparator.getUserComparator();
    return userComparator.compare(beginKey, userKey) <= 0
        && userComparator.compare(userKey, endKey) < 0;
  }

  private static int ceilingFileIndex(List<FileMetaData> files, InternalKey key, Comparator<InternalKey> comparator) {
    int left = 0;
    int right = files.size();
    while (left < right) {
      int mid = (left + right) >>> 1;
      if (comparator.compare(files.get(mid).getLargest(), key) < 0) {
        left = mid + 1;
      } else {
        right = mid;
      }
    }
    return left;
  }

  public boolean someFileOverlapsRange(Slice smallestUserKey, Slice largestUserKey) {
    InternalKey smallestInternalKey = new InternalKey(smallestUserKey, MAX_SEQUENCE_NUMBER, VALUE);
    int index = findFile(smallestInternalKey);

    UserComparator userComparator = internalKeyComparator.getUserComparator();
    return ((index < files.size()) &&
        userComparator.compare(largestUserKey, files.get(index).getSmallest().getUserKey()) >= 0);
  }

  private int findFile(InternalKey targetKey) {
    if (files.isEmpty()) {
      return files.size();
    }

    // todo replace with Collections.binarySearch
    int left = 0;
    int right = files.size() - 1;

    // binary search restart positions to find the restart position immediately before the targetKey
    while (left < right) {
      int mid = (left + right) / 2;

      if (internalKeyComparator.compare(files.get(mid).getLargest(), targetKey) < 0) {
        // Key at "mid.largest" is < "target".  Therefore all
        // files at or before "mid" are uninteresting.
        left = mid + 1;
      } else {
        // Key at "mid.largest" is >= "target".  Therefore all files
        // after "mid" are uninteresting.
        right = mid;
      }
    }
    return right;
  }

  public void addFile(FileMetaData fileMetaData) {
    // todo remove mutation
    files.add(fileMetaData);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Level");
    sb.append("{levelNumber=").append(levelNumber);
    sb.append(", files=").append(files);
    sb.append('}');
    return sb.toString();
  }
}
