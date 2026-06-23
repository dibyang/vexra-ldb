package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.impl.SeekingIterable;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.SliceInput;
import net.xdob.vexra.ldb.util.Slices;
import net.xdob.vexra.ldb.util.VariableLengthQuantity;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_INT;

/**
 * Binary Structure
 * <table summary="record format">
 * <tbody>
 * <thead>
 * <tr>
 * <th>name</th>
 * <th>offset</th>
 * <th>length</th>
 * <th>description</th>
 * </tr>
 * </thead>
 * <p/>
 * <tr>
 * <td>entries</td>
 * <td>4</td>
 * <td>vary</td>
 * <td>Entries in order by key</td>
 * </tr>
 * <tr>
 * <td>restart index</td>
 * <td>vary</td>
 * <td>4 * restart count</td>
 * <td>Index of prefix compression restarts</td>
 * </tr>
 * <tr>
 * <td>restart count</td>
 * <td>0</td>
 * <td>4</td>
 * <td>Number of prefix compression restarts (used as index into entries)</td>
 * </tr>
 * </tbody>
 * </table>
 */
public class Block
    implements SeekingIterable<Slice, Slice> {
  private static final int SEEK_ANCHOR_INTERVAL = 2;
  static final int INLINE_SEEK_INDEX_BLOCK_MAGIC = 0x4C424958; // LBIX

  private final Slice block;
  private final Comparator<Slice> comparator;

  private final Slice data;
  private final Slice restartPositions;
  private final Slice[] restartKeys;
  private final SeekAnchor[][] seekAnchors;

  public Block(Slice block, Comparator<Slice> comparator) {
    requireNonNull(block, "block is null");
    checkArgument(block.length() >= SIZE_OF_INT, "Block is corrupt: size must be at least %s block", SIZE_OF_INT);
    requireNonNull(comparator, "comparator is null");

    block = block.slice();
    this.block = block;
    this.comparator = comparator;

    // Keys are prefix compressed.  Every once in a while the prefix compression is restarted and the full key is written.
    // These "restart" locations are written at the end of the file, so you can seek to key without having to read the
    // entire file sequentially.

    // key restart count is the last int of the block
    boolean hasInlineSeekIndex = block.getInt(block.length() - SIZE_OF_INT) == INLINE_SEEK_INDEX_BLOCK_MAGIC;
    int restartCountOffset = hasInlineSeekIndex
        ? block.length() - (3 * SIZE_OF_INT)
        : block.length() - SIZE_OF_INT;
    int restartCount = block.getInt(restartCountOffset);

    if (restartCount > 0) {
      // restarts are written at the end of the block
      int restartTrailerSize = hasInlineSeekIndex ? 3 * SIZE_OF_INT : SIZE_OF_INT;
      int restartOffset = block.length() - restartTrailerSize - restartCount * SIZE_OF_INT;
      int dataLimit = hasInlineSeekIndex ? block.getInt(block.length() - (2 * SIZE_OF_INT)) : restartOffset;
      checkArgument(restartOffset < restartCountOffset, "Block is corrupt: restart offset count is greater than block size");
      checkArgument(dataLimit >= 0 && dataLimit <= restartOffset, "Block is corrupt: inline seek index offset is invalid");
      restartPositions = block.slice(restartOffset, restartCount * SIZE_OF_INT);

      // data starts at 0 and extends to the restart index
      data = block.slice(0, dataLimit);
      restartKeys = readRestartKeys(data, restartPositions, restartCount);
      if (hasInlineSeekIndex && dataLimit < restartOffset) {
        seekAnchors = readInlineSeekAnchors(block.slice(dataLimit, restartOffset - dataLimit), restartCount);
      } else {
        seekAnchors = readSeekAnchors(data, restartPositions, restartCount);
      }
    } else {
      data = Slices.EMPTY_SLICE;
      restartPositions = Slices.EMPTY_SLICE;
      restartKeys = new Slice[0];
      seekAnchors = new SeekAnchor[0][];
    }
  }

  public Slice getRawData() {
    return data;
  }

  public long size() {
    return block.length();
  }

  @Override
  public BlockIterator iterator() {
    return new BlockIterator(data, restartPositions, comparator);
  }

  /**
   * 直接在 block 内 seek 到第一个大于等于目标 key 的 entry。
   *
   * <p>点查热路径不需要完整 iterator 生命周期；直接 seek 可以避免 iterator 构造阶段的首条 entry 预读，
   * 并减少 table/index/data block 之间的临时对象。</p>
   *
   * @param targetKey 目标 key
   * @return 第一个大于等于目标 key 的 entry；block 为空或没有候选 entry 时返回 null
   */
  public Entry<Slice, Slice> seek(Slice targetKey) {
    return seekInternal(targetKey).getEntry();
  }

  SeekResult seekWithIndex(Slice targetKey) {
    if (!hasSeekIndex()) {
      return SeekResult.fallback(null);
    }
    return seekInternal(targetKey);
  }

  boolean hasSeekIndex() {
    int restartCount = restartPositions.length() / SIZE_OF_INT;
    return restartCount > 0;
  }

  private SeekResult seekInternal(Slice targetKey) {
    int restartCount = restartPositions.length() / SIZE_OF_INT;
    if (restartCount == 0) {
      return SeekResult.miss(null);
    }
    int restartPosition = restartIndexBefore(targetKey, restartCount);
    SeekAnchor anchor = seekAnchorBefore(restartPosition, targetKey);
    Slice previousKey = null;
    int restartLimit = restartPosition + 1 < restartCount
        ? restartOffset(restartPosition + 1, restartCount)
        : data.length();
    SliceInput input = data.input();
    if (anchor == null) {
      input.setPosition(restartOffset(restartPosition, restartCount));
    } else {
      input.setPosition(anchor.offset);
      previousKey = anchor.previousKey;
    }
    SeekResult result = seekWithValueOnMatch(input, previousKey, targetKey, restartLimit);
    if (restartPosition + 1 >= restartCount) {
      return result;
    }
    if (result.getEntry() != null) {
      return result;
    }

    // 如果当前 restart 区间没有候选，按照 restart 二分语义，候选最多是下一个 restart 的首条 entry。
    // 继续扫描后续 restart 区间只会增加纯随机点查的线性解码成本。
    input.setPosition(restartLimit);
    SeekResult nextResult = readCandidate(input, null, targetKey);
    return nextResult.withAdditionalStats(result);
  }

  Entry<Slice, Slice> seekFromOffset(Slice targetKey, int offset) {
    return seekFromOffset(targetKey, offset, null);
  }

  Entry<Slice, Slice> seekFromOffset(Slice targetKey, int offset, Slice previousKey) {
    checkPositionIndex(offset, data.length(), "offset");
    SliceInput input = data.input();
    input.setPosition(offset);

    return seekWithValueOnMatch(input, previousKey, targetKey).getEntry();
  }

  Entry<Slice, Slice> floor(Slice targetKey) {
    int restartCount = restartPositions.length() / SIZE_OF_INT;
    if (restartCount == 0) {
      return null;
    }

    SliceInput input = data.input();
    int restartPosition = restartIndexBefore(targetKey, restartCount);
    input.setPosition(restartOffset(restartPosition, restartCount));

    BlockEntry previousEntry = null;
    while (input.isReadable()) {
      BlockEntry entry = readEntry(input, previousEntry);
      if (comparator.compare(entry.getKey(), targetKey) > 0) {
        return previousEntry;
      }
      previousEntry = entry;
    }
    return previousEntry;
  }

  /**
   * 对同一个 data block 内的多个目标 key 执行批量 seek。
   *
   * <p>调用方必须按 block comparator 对目标 key 升序排序。方法会从第一个目标 key 所在的 restart 区间开始
   * 顺序解码 entry，并把每个目标 key 映射到第一个大于等于它的候选 entry。这样 MultiGet 命中同一 data block 时，
   * 可以避免为每个 key 重复执行 restart 二分和区间内线性扫描。</p>
   *
   * @param sortedTargetKeys 已按 internal key 升序排列的目标 key
   * @return 与输入顺序一致的候选 entry 列表；没有候选时对应位置为 null
   */
  public List<Entry<Slice, Slice>> seekAll(List<Slice> sortedTargetKeys) {
    List<Entry<Slice, Slice>> results = new ArrayList<Entry<Slice, Slice>>(
        Collections.nCopies(sortedTargetKeys.size(), (Entry<Slice, Slice>) null));
    if (sortedTargetKeys.isEmpty()) {
      return results;
    }

    int restartCount = restartPositions.length() / SIZE_OF_INT;
    if (restartCount == 0) {
      return results;
    }

    SliceInput input = data.input();
    int restartPosition = restartIndexBefore(sortedTargetKeys.get(0), restartCount);
    input.setPosition(restartOffset(restartPosition, restartCount));

    int targetIndex = 0;
    BlockEntry previousEntry = null;
    while (input.isReadable() && targetIndex < sortedTargetKeys.size()) {
      BlockEntry entry = readEntry(input, previousEntry);
      if (comparator.compare(entry.getKey(), sortedTargetKeys.get(targetIndex)) < 0) {
        previousEntry = entry;
        continue;
      }

      while (targetIndex < sortedTargetKeys.size()
          && comparator.compare(entry.getKey(), sortedTargetKeys.get(targetIndex)) >= 0) {
        results.set(targetIndex, entry);
        targetIndex++;
      }
      previousEntry = entry;
    }
    return results;
  }

  private int restartIndexBefore(Slice targetKey, int restartCount) {
    int left = 0;
    int right = restartCount - 1;
    while (left < right) {
      int mid = (left + right + 1) / 2;
      if (comparator.compare(restartKeys[mid], targetKey) < 0) {
        left = mid;
      } else {
        right = mid - 1;
      }
    }
    return left;
  }

  private int restartOffset(int restartPosition, int restartCount) {
    checkPositionIndex(restartPosition, restartCount, "restartPosition");
    return restartPositions.getInt(restartPosition * SIZE_OF_INT);
  }

  int restartCount() {
    return restartPositions.length() / SIZE_OF_INT;
  }

  Slice restartKey(int restartPosition) {
    checkPositionIndex(restartPosition, restartCount(), "restartPosition");
    return restartKeys[restartPosition];
  }

  int restartOffset(int restartPosition) {
    return restartOffset(restartPosition, restartCount());
  }

  int seekAnchorCount() {
    int count = 0;
    for (SeekAnchor[] restartAnchors : seekAnchors) {
      count += restartAnchors.length;
    }
    return count;
  }

  List<EntryAnchor> entryAnchors(int interval) {
    checkArgument(interval > 0, "interval must be > 0");
    int restartCount = restartCount();
    if (restartCount == 0) {
      return Collections.emptyList();
    }
    List<EntryAnchor> anchors = new ArrayList<EntryAnchor>();
    SliceInput input = data.input();
    for (int restartIndex = 0; restartIndex < restartCount; restartIndex++) {
      int restartOffset = restartPositions.getInt(restartIndex * SIZE_OF_INT);
      int limit = restartIndex + 1 < restartCount
          ? restartPositions.getInt((restartIndex + 1) * SIZE_OF_INT)
          : data.length();
      input.setPosition(restartOffset);
      Slice previousKey = null;
      int entryIndex = 0;
      while (input.position() < limit) {
        int entryOffset = input.position();
        int sharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
        int nonSharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
        int valueLength = VariableLengthQuantity.readVariableLengthInt(input);
        Slice key = readKey(input, previousKey, sharedKeyLength, nonSharedKeyLength);
        if (entryIndex > 0 && entryIndex % interval == 0) {
          anchors.add(new EntryAnchor(key, entryOffset, previousKey, restartIndex));
        }
        input.skipBytes(valueLength);
        previousKey = key;
        entryIndex++;
      }
    }
    return Collections.unmodifiableList(anchors);
  }

  private SeekAnchor seekAnchorBefore(int restartPosition, Slice targetKey) {
    SeekAnchor[] anchors = seekAnchors[restartPosition];
    if (anchors.length == 0) {
      return null;
    }
    int left = 0;
    int right = anchors.length - 1;
    SeekAnchor result = null;
    while (left <= right) {
      int mid = (left + right) >>> 1;
      if (comparator.compare(anchors[mid].key, targetKey) <= 0) {
        result = anchors[mid];
        left = mid + 1;
      } else {
        right = mid - 1;
      }
    }
    return result;
  }

  private static Slice[] readRestartKeys(Slice data, Slice restartPositions, int restartCount) {
    Slice[] keys = new Slice[restartCount];
    SliceInput input = data.input();
    for (int i = 0; i < restartCount; i++) {
      input.setPosition(restartPositions.getInt(i * SIZE_OF_INT));
      keys[i] = readEntry(input, null).getKey();
    }
    return keys;
  }

  private static SeekAnchor[][] readSeekAnchors(Slice data, Slice restartPositions, int restartCount) {
    SeekAnchor[][] anchors = new SeekAnchor[restartCount][];
    SliceInput input = data.input();
    for (int restartIndex = 0; restartIndex < restartCount; restartIndex++) {
      int restartOffset = restartPositions.getInt(restartIndex * SIZE_OF_INT);
      int limit = restartIndex + 1 < restartCount
          ? restartPositions.getInt((restartIndex + 1) * SIZE_OF_INT)
          : data.length();
      input.setPosition(restartOffset);
      List<SeekAnchor> restartAnchors = new ArrayList<SeekAnchor>();
      Slice previousKey = null;
      int entryIndex = 0;
      while (input.position() < limit) {
        int entryOffset = input.position();
        int sharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
        int nonSharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
        int valueLength = VariableLengthQuantity.readVariableLengthInt(input);
        Slice key = readKey(input, previousKey, sharedKeyLength, nonSharedKeyLength);
        if (entryIndex > 0 && entryIndex % SEEK_ANCHOR_INTERVAL == 0) {
          restartAnchors.add(new SeekAnchor(key, entryOffset, previousKey));
        }
        input.skipBytes(valueLength);
        previousKey = key;
        entryIndex++;
      }
      anchors[restartIndex] = restartAnchors.toArray(new SeekAnchor[restartAnchors.size()]);
    }
    return anchors;
  }

  private static SeekAnchor[][] readInlineSeekAnchors(Slice inlineIndex, int restartCount) {
    List<List<SeekAnchor>> grouped = new ArrayList<List<SeekAnchor>>(restartCount);
    for (int i = 0; i < restartCount; i++) {
      grouped.add(new ArrayList<SeekAnchor>());
    }
    SliceInput input = inlineIndex.input();
    int anchorCount = VariableLengthQuantity.readVariableLengthInt(input);
    for (int i = 0; i < anchorCount; i++) {
      int restartIndex = VariableLengthQuantity.readVariableLengthInt(input);
      int offset = VariableLengthQuantity.readVariableLengthInt(input);
      int keyLength = VariableLengthQuantity.readVariableLengthInt(input);
      Slice key = input.readSlice(keyLength);
      int previousKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
      Slice previousKey = input.readSlice(previousKeyLength);
      checkPositionIndex(restartIndex, restartCount, "restartIndex");
      grouped.get(restartIndex).add(new SeekAnchor(key, offset, previousKey));
    }
    SeekAnchor[][] anchors = new SeekAnchor[restartCount][];
    for (int i = 0; i < restartCount; i++) {
      List<SeekAnchor> restartAnchors = grouped.get(i);
      anchors[i] = restartAnchors.toArray(new SeekAnchor[restartAnchors.size()]);
    }
    return anchors;
  }

  private static BlockEntry readEntry(SliceInput input, BlockEntry previousEntry) {
    requireNonNull(input, "input is null");

    int sharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
    int nonSharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
    int valueLength = VariableLengthQuantity.readVariableLengthInt(input);

    Slice previousKey = previousEntry == null ? null : previousEntry.getKey();
    Slice key = readKey(input, previousKey, sharedKeyLength, nonSharedKeyLength);

    Slice value = input.readSlice(valueLength);
    return new BlockEntry(key, value);
  }

  private SeekResult seekWithValueOnMatch(SliceInput input, Slice previousKey, Slice targetKey) {
    return seekWithValueOnMatch(input, previousKey, targetKey, data.length());
  }

  private SeekResult seekWithValueOnMatch(SliceInput input, Slice previousKey, Slice targetKey, int limit) {
    int decodedEntries = 0;
    long sharedKeyRebuilds = 0;
    long sharedKeyRebuiltBytes = 0;
    Slice firstKeyBuffer = null;
    Slice secondKeyBuffer = null;
    boolean useFirstKeyBuffer = true;
    while (input.position() < limit) {
      int sharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
      int nonSharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
      int valueLength = VariableLengthQuantity.readVariableLengthInt(input);
      Slice key;
      if (sharedKeyLength == 0) {
        key = input.readSlice(nonSharedKeyLength);
      } else if (useFirstKeyBuffer) {
        firstKeyBuffer = ensureKeyBuffer(firstKeyBuffer, sharedKeyLength + nonSharedKeyLength);
        key = readSharedKey(input, previousKey, sharedKeyLength, nonSharedKeyLength, firstKeyBuffer);
        useFirstKeyBuffer = false;
      } else {
        secondKeyBuffer = ensureKeyBuffer(secondKeyBuffer, sharedKeyLength + nonSharedKeyLength);
        key = readSharedKey(input, previousKey, sharedKeyLength, nonSharedKeyLength, secondKeyBuffer);
        useFirstKeyBuffer = true;
      }
      decodedEntries++;
      if (sharedKeyLength > 0) {
        sharedKeyRebuilds++;
        sharedKeyRebuiltBytes += key.length();
      }
      if (comparator.compare(key, targetKey) >= 0) {
        return SeekResult.hit(new BlockEntry(key, input.readSlice(valueLength)), decodedEntries, sharedKeyRebuilds, sharedKeyRebuiltBytes);
      }
      input.skipBytes(valueLength);
      previousKey = key;
    }
    return SeekResult.miss(null, decodedEntries, sharedKeyRebuilds, sharedKeyRebuiltBytes);
  }

  private SeekResult readCandidate(SliceInput input, Slice previousKey, Slice targetKey) {
    if (!input.isReadable()) {
      return SeekResult.miss(null);
    }
    int sharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
    int nonSharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
    int valueLength = VariableLengthQuantity.readVariableLengthInt(input);
    Slice key = readKey(input, previousKey, sharedKeyLength, nonSharedKeyLength);
    if (comparator.compare(key, targetKey) >= 0) {
      return SeekResult.hit(new BlockEntry(key, input.readSlice(valueLength)), 1, sharedKeyLength > 0 ? 1 : 0, sharedKeyLength > 0 ? key.length() : 0);
    }
    input.skipBytes(valueLength);
    return SeekResult.miss(null, 1, sharedKeyLength > 0 ? 1 : 0, sharedKeyLength > 0 ? key.length() : 0);
  }

  private static Slice ensureKeyBuffer(Slice keyBuffer, int keyLength) {
    if (keyBuffer == null || keyBuffer.length() < keyLength) {
      return Slices.allocate(keyLength);
    }
    return keyBuffer;
  }

  private static Slice readSharedKey(SliceInput input, Slice previousKey, int sharedKeyLength, int nonSharedKeyLength, Slice keyBuffer) {
    checkState(previousKey != null, "Entry has a shared key but no previous key was provided");
    keyBuffer.setBytes(0, previousKey, 0, sharedKeyLength);
    input.readBytes(keyBuffer, sharedKeyLength, nonSharedKeyLength);
    return keyBuffer.slice(0, sharedKeyLength + nonSharedKeyLength);
  }
  private static Slice readKey(SliceInput input, Slice previousKey, int sharedKeyLength, int nonSharedKeyLength) {
    if (sharedKeyLength > 0) {
      Slice key = Slices.allocate(sharedKeyLength + nonSharedKeyLength);
      checkState(previousKey != null, "Entry has a shared key but no previous key was provided");
      key.setBytes(0, previousKey, 0, sharedKeyLength);
      input.readBytes(key, sharedKeyLength, nonSharedKeyLength);
      return key;
    }
    return input.readSlice(nonSharedKeyLength);
  }

  private static final class SeekAnchor {
    private final Slice key;
    private final int offset;
    private final Slice previousKey;

    private SeekAnchor(Slice key, int offset, Slice previousKey) {
      this.key = key;
      this.offset = offset;
      this.previousKey = previousKey;
    }
  }

  static final class EntryAnchor {
    private final Slice key;
    private final int offset;
    private final Slice previousKey;
    private final int restartIndex;

    private EntryAnchor(Slice key, int offset, Slice previousKey, int restartIndex) {
      this.key = key;
      this.offset = offset;
      this.previousKey = previousKey;
      this.restartIndex = restartIndex;
    }

    Slice getKey() {
      return key;
    }

    int getOffset() {
      return offset;
    }

    Slice getPreviousKey() {
      return previousKey;
    }

    int getRestartIndex() {
      return restartIndex;
    }
  }

  static final class SeekResult {
    private enum Status {
      HIT,
      MISS,
      FALLBACK
    }

    private final Entry<Slice, Slice> entry;
    private final Status status;
    private final int decodedEntries;
    private final long sharedKeyRebuilds;
    private final long sharedKeyRebuiltBytes;

    private SeekResult(Entry<Slice, Slice> entry, Status status) {
      this(entry, status, 0, 0, 0);
    }

    private SeekResult(Entry<Slice, Slice> entry, Status status, int decodedEntries) {
      this(entry, status, decodedEntries, 0, 0);
    }

    private SeekResult(Entry<Slice, Slice> entry, Status status, int decodedEntries, long sharedKeyRebuilds, long sharedKeyRebuiltBytes) {
      this.entry = entry;
      this.status = status;
      this.decodedEntries = decodedEntries;
      this.sharedKeyRebuilds = sharedKeyRebuilds;
      this.sharedKeyRebuiltBytes = sharedKeyRebuiltBytes;
    }

    private static SeekResult fallback(Entry<Slice, Slice> entry) {
      return new SeekResult(entry, Status.FALLBACK);
    }

    private static SeekResult hit(Entry<Slice, Slice> entry) {
      return new SeekResult(entry, Status.HIT);
    }

    private static SeekResult hit(Entry<Slice, Slice> entry, int decodedEntries) {
      return new SeekResult(entry, Status.HIT, decodedEntries);
    }

    private static SeekResult hit(Entry<Slice, Slice> entry, int decodedEntries, long sharedKeyRebuilds, long sharedKeyRebuiltBytes) {
      return new SeekResult(entry, Status.HIT, decodedEntries, sharedKeyRebuilds, sharedKeyRebuiltBytes);
    }

    private static SeekResult miss(Entry<Slice, Slice> entry) {
      return new SeekResult(entry, Status.MISS);
    }

    private static SeekResult miss(Entry<Slice, Slice> entry, int decodedEntries) {
      return new SeekResult(entry, Status.MISS, decodedEntries);
    }

    private static SeekResult miss(Entry<Slice, Slice> entry, int decodedEntries, long sharedKeyRebuilds, long sharedKeyRebuiltBytes) {
      return new SeekResult(entry, Status.MISS, decodedEntries, sharedKeyRebuilds, sharedKeyRebuiltBytes);
    }

    Entry<Slice, Slice> getEntry() {
      return entry;
    }

    boolean isHit() {
      return status == Status.HIT;
    }

    boolean isMiss() {
      return status == Status.MISS;
    }

    boolean isFallback() {
      return status == Status.FALLBACK;
    }

    int getDecodedEntries() {
      return decodedEntries;
    }

    long getSharedKeyRebuilds() {
      return sharedKeyRebuilds;
    }

    long getSharedKeyRebuiltBytes() {
      return sharedKeyRebuiltBytes;
    }

    private SeekResult withAdditionalStats(SeekResult additional) {
      if (additional.decodedEntries == 0 && additional.sharedKeyRebuilds == 0 && additional.sharedKeyRebuiltBytes == 0) {
        return this;
      }
      return new SeekResult(entry, status,
          decodedEntries + additional.decodedEntries,
          sharedKeyRebuilds + additional.sharedKeyRebuilds,
          sharedKeyRebuiltBytes + additional.sharedKeyRebuiltBytes);
    }
  }
}
