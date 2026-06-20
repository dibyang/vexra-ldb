package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.impl.SeekingIterable;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.SliceInput;
import net.xdob.vexra.ldb.util.SliceOutput;
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
  private final Slice block;
  private final Comparator<Slice> comparator;

  private final Slice data;
  private final Slice restartPositions;
  private final Slice[] restartKeys;

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
    int restartCount = block.getInt(block.length() - SIZE_OF_INT);

    if (restartCount > 0) {
      // restarts are written at the end of the block
      int restartOffset = block.length() - (1 + restartCount) * SIZE_OF_INT;
      checkArgument(restartOffset < block.length() - SIZE_OF_INT, "Block is corrupt: restart offset count is greater than block size");
      restartPositions = block.slice(restartOffset, restartCount * SIZE_OF_INT);

      // data starts at 0 and extends to the restart index
      data = block.slice(0, restartOffset);
      restartKeys = readRestartKeys(data, restartPositions, restartCount);
    } else {
      data = Slices.EMPTY_SLICE;
      restartPositions = Slices.EMPTY_SLICE;
      restartKeys = new Slice[0];
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
      if (comparator.compare(entry.getKey(), targetKey) >= 0) {
        return entry;
      }
      previousEntry = entry;
    }
    return null;
  }

  Entry<Slice, Slice> seekFromOffset(Slice targetKey, int offset) {
    checkPositionIndex(offset, data.length(), "offset");
    SliceInput input = data.input();
    input.setPosition(offset);

    BlockEntry previousEntry = null;
    while (input.isReadable()) {
      BlockEntry entry = readEntry(input, previousEntry);
      if (comparator.compare(entry.getKey(), targetKey) >= 0) {
        return entry;
      }
      previousEntry = entry;
    }
    return null;
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

  private static Slice[] readRestartKeys(Slice data, Slice restartPositions, int restartCount) {
    Slice[] keys = new Slice[restartCount];
    SliceInput input = data.input();
    for (int i = 0; i < restartCount; i++) {
      input.setPosition(restartPositions.getInt(i * SIZE_OF_INT));
      keys[i] = readEntry(input, null).getKey();
    }
    return keys;
  }

  private static BlockEntry readEntry(SliceInput input, BlockEntry previousEntry) {
    requireNonNull(input, "input is null");

    int sharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
    int nonSharedKeyLength = VariableLengthQuantity.readVariableLengthInt(input);
    int valueLength = VariableLengthQuantity.readVariableLengthInt(input);

    final Slice key;
    if (sharedKeyLength > 0) {
      key = Slices.allocate(sharedKeyLength + nonSharedKeyLength);
      SliceOutput sliceOutput = key.output();
      checkState(previousEntry != null, "Entry has a shared key but no previous entry was provided");
      sliceOutput.writeBytes(previousEntry.getKey(), 0, sharedKeyLength);
      sliceOutput.writeBytes(input, nonSharedKeyLength);
    } else {
      key = input.readSlice(nonSharedKeyLength);
    }

    Slice value = input.readSlice(valueLength);
    return new BlockEntry(key, value);
  }
}
