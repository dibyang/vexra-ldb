package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.impl.SeekingIterable;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.SliceInput;
import net.xdob.vexra.ldb.util.SliceOutput;
import net.xdob.vexra.ldb.util.Slices;
import net.xdob.vexra.ldb.util.VariableLengthQuantity;

import java.util.Comparator;
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
    } else {
      data = Slices.EMPTY_SLICE;
      restartPositions = Slices.EMPTY_SLICE;
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
    int left = 0;
    int right = restartCount - 1;

    while (left < right) {
      int mid = (left + right + 1) / 2;
      BlockEntry restartEntry = readRestartEntry(input, mid, restartCount);
      if (comparator.compare(restartEntry.getKey(), targetKey) < 0) {
        left = mid;
      } else {
        right = mid - 1;
      }
    }

    input.setPosition(restartOffset(left, restartCount));
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

  private BlockEntry readRestartEntry(SliceInput input, int restartPosition, int restartCount) {
    input.setPosition(restartOffset(restartPosition, restartCount));
    return readEntry(input, null);
  }

  private int restartOffset(int restartPosition, int restartCount) {
    checkPositionIndex(restartPosition, restartCount, "restartPosition");
    return restartPositions.getInt(restartPosition * SIZE_OF_INT);
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
