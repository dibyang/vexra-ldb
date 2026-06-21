package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.InternalTableIterator;
import net.xdob.vexra.ldb.util.Level0Iterator;
import net.xdob.vexra.ldb.util.Slice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static net.xdob.vexra.ldb.impl.ValueType.VALUE;

// todo this class should be immutable
public class Level0
    implements SeekingIterable<InternalKey, Slice> {
  private final TableCache tableCache;
  private final InternalKeyComparator internalKeyComparator;
  private final List<FileMetaData> files;

  public static final Comparator<FileMetaData> NEWEST_FIRST = new Comparator<FileMetaData>() {
    @Override
    public int compare(FileMetaData fileMetaData, FileMetaData fileMetaData1) {
      return (int) (fileMetaData1.getNumber() - fileMetaData.getNumber());
    }
  };

  public Level0(List<FileMetaData> files, TableCache tableCache, InternalKeyComparator internalKeyComparator) {
    requireNonNull(files, "files is null");
    requireNonNull(tableCache, "tableCache is null");
    requireNonNull(internalKeyComparator, "internalKeyComparator is null");

    this.files = new ArrayList<>(files);
    this.tableCache = tableCache;
    this.internalKeyComparator = internalKeyComparator;
  }

  public int getLevelNumber() {
    return 0;
  }

  public List<FileMetaData> getFiles() {
    return files;
  }

  @Override
  public Level0Iterator iterator() {
    return new Level0Iterator(tableCache, files, internalKeyComparator);
  }

  public TableCache getTableCache() {
    return tableCache;
  }

  public InternalKeyComparator getInternalKeyComparator() {
    return internalKeyComparator;
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
    FileMetaData cachedFile = readContext == null ? null : readContext.findCoveredFile(0, key);
    if (cachedFile != null) {
      fileMetaDataList.add(cachedFile);
      contextHitFile = cachedFile;
      readStats.recordCandidateFile();
      readStats.recordPointReadContextFileHit();
    }
    for (FileMetaData fileMetaData : files) {
      if (fileMetaData == cachedFile) {
        continue;
      }
      if (internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) >= 0 &&
          internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getLargest().getUserKey()) <= 0) {
        fileMetaDataList.add(fileMetaData);
        readStats.recordCandidateFile();
      }
    }
    if (readContext != null && cachedFile == null && !fileMetaDataList.isEmpty()) {
      readStats.recordPointReadContextFileMiss();
    }

    Collections.sort(fileMetaDataList, NEWEST_FIRST);

    Slice userKey = key.getUserKey();
    for (FileMetaData fileMetaData : fileMetaDataList) {
      if (fileMetaData != contextHitFile && !tableCache.mayContain(fileMetaData, userKey)) {
        readStats.recordFilterSkip();
        continue; // 这个 table 一定没有这个 userKey
      }
      readStats.recordTableRead();
      if (readContext != null) {
        readContext.remember(0, fileMetaData);
      }

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

      if (readStats.getSeekFile() == null) {
        // We have had more than one seek for this read.  Charge the first file.
        readStats.setSeekFile(fileMetaData);
        readStats.setSeekFileLevel(0);
      }
    }

    return null;
  }

  public List<LookupResult> get(List<LookupKey> keys, ReadStats readStats) {
    readStats.clear();
    List<LookupResult> results = new ArrayList<LookupResult>(Collections.nCopies(keys.size(), (LookupResult) null));
    if (files.isEmpty() || keys.isEmpty()) {
      return results;
    }

    List<FileMetaData> fileMetaDataList = new ArrayList<>(files);
    Collections.sort(fileMetaDataList, NEWEST_FIRST);
    boolean[] resolved = new boolean[keys.size()];
    UserComparator userComparator = internalKeyComparator.getUserComparator();

    for (FileMetaData fileMetaData : fileMetaDataList) {
      List<Integer> candidateIndexes = new ArrayList<Integer>();
      for (int i = 0; i < keys.size(); i++) {
        if (resolved[i]) {
          continue;
        }
        LookupKey key = keys.get(i);
        if (userComparator.compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) >= 0
            && userComparator.compare(key.getUserKey(), fileMetaData.getLargest().getUserKey()) <= 0) {
          readStats.recordCandidateFile();
          if (tableCache.mayContain(fileMetaData, key.getUserKey())) {
            candidateIndexes.add(i);
          } else {
            readStats.recordFilterSkip();
          }
        }
      }
      if (candidateIndexes.isEmpty()) {
        continue;
      }

      readStats.recordTableRead();
      List<LookupKey> fileKeys = new ArrayList<LookupKey>(candidateIndexes.size());
      for (Integer index : candidateIndexes) {
        LookupKey key = keys.get(index);
        fileKeys.add(key);
      }

      List<Entry<Slice, Slice>> entries = tableCache.get(fileMetaData, fileKeys);
      for (int i = 0; i < fileKeys.size(); i++) {
        LookupResult lookupResult = getFromTableEntry(fileMetaData, fileKeys.get(i), entries.get(i), readStats);
        if (lookupResult != null) {
          int originalIndex = candidateIndexes.get(i);
          results.set(originalIndex, lookupResult);
          resolved[originalIndex] = true;
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
    final StringBuilder sb = new StringBuilder();
    sb.append("Level0");
    sb.append("{files=").append(files);
    sb.append('}');
    return sb.toString();
  }
}
