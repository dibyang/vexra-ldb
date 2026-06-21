package net.xdob.vexra.ldb.table;

import com.google.common.primitives.Ints;
import net.xdob.vexra.ldb.util.DynamicSliceOutput;
import net.xdob.vexra.ldb.util.IntVector;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.VariableLengthQuantity;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.*;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_INT;

public class BlockBuilder {
  private final int blockRestartInterval;
  private final boolean writeInlineSeekIndex;
  private final int inlineSeekIndexInterval;
  private final int inlineSeekIndexAdmissionMinAnchors;
  private final IntVector restartPositions;
  private final Comparator<Slice> comparator;

  private int entryCount;
  private int restartBlockEntryCount;

  private boolean finished;
  private final DynamicSliceOutput block;
  private Slice lastKey;
  private final List<InlineSeekAnchor> inlineSeekAnchors;
  private int lastInlineSeekIndexBytes;
  private int lastInlineSeekIndexAnchorCount;

  public BlockBuilder(int estimatedSize, int blockRestartInterval, Comparator<Slice> comparator) {
    this(estimatedSize, blockRestartInterval, comparator, false, 4, 2);
  }

  public BlockBuilder(int estimatedSize,
                      int blockRestartInterval,
                      Comparator<Slice> comparator,
                      boolean writeInlineSeekIndex,
                      int inlineSeekIndexInterval,
                      int inlineSeekIndexAdmissionMinAnchors) {
    checkArgument(estimatedSize >= 0, "estimatedSize is negative");
    checkArgument(blockRestartInterval >= 0, "blockRestartInterval is negative");
    checkArgument(inlineSeekIndexInterval > 0, "inlineSeekIndexInterval must be > 0");
    checkArgument(inlineSeekIndexAdmissionMinAnchors > 0, "inlineSeekIndexAdmissionMinAnchors must be > 0");
    requireNonNull(comparator, "comparator is null");

    this.block = new DynamicSliceOutput(estimatedSize);
    this.blockRestartInterval = blockRestartInterval;
    this.writeInlineSeekIndex = writeInlineSeekIndex;
    this.inlineSeekIndexInterval = inlineSeekIndexInterval;
    this.inlineSeekIndexAdmissionMinAnchors = inlineSeekIndexAdmissionMinAnchors;
    this.comparator = comparator;

    restartPositions = new IntVector(32);
    restartPositions.add(0);  // first restart point must be 0
    inlineSeekAnchors = new ArrayList<>();
  }

  public void reset() {
    block.reset();
    entryCount = 0;
    restartPositions.clear();
    restartPositions.add(0); // first restart point must be 0
    restartBlockEntryCount = 0;
    lastKey = null;
    inlineSeekAnchors.clear();
    lastInlineSeekIndexBytes = 0;
    lastInlineSeekIndexAnchorCount = 0;
    finished = false;
  }

  public int getEntryCount() {
    return entryCount;
  }

  public boolean isEmpty() {
    return entryCount == 0;
  }

  public int currentSizeEstimate() {
    // no need to estimate if closed
    if (finished) {
      return block.size();
    }

    // no records is just a single int
    if (block.size() == 0) {
      return SIZE_OF_INT;
    }

    return block.size() +                          // raw data buffer
        inlineSeekIndexSizeEstimate() +            // optional inline seek index
        restartPositions.size() * SIZE_OF_INT +    // restart positions
        restartTrailerSizeEstimate();              // restart count and optional inline metadata
  }

  public void add(BlockEntry blockEntry) {
    requireNonNull(blockEntry, "blockEntry is null");
    add(blockEntry.getKey(), blockEntry.getValue());
  }

  public void add(Slice key, Slice value) {
    requireNonNull(key, "key is null");
    requireNonNull(value, "value is null");
    checkState(!finished, "block is finished");
    checkPositionIndex(restartBlockEntryCount, blockRestartInterval);

    checkArgument(lastKey == null || comparator.compare(key, lastKey) > 0, "key must be greater than last key");

    int sharedKeyBytes = 0;
    if (restartBlockEntryCount < blockRestartInterval) {
      sharedKeyBytes = calculateSharedBytes(key, lastKey);
    } else {
      // restart prefix compression
      restartPositions.add(block.size());
      restartBlockEntryCount = 0;
    }
    int entryOffset = block.size();
    int restartIndex = restartPositions.size() - 1;
    if (writeInlineSeekIndex
        && entryCount > 0
        && entryCount % inlineSeekIndexInterval == 0
        && lastKey != null) {
      inlineSeekAnchors.add(new InlineSeekAnchor(key, entryOffset, lastKey, restartIndex));
    }

    int nonSharedKeyBytes = key.length() - sharedKeyBytes;

    // write "<shared><non_shared><value_size>"
    VariableLengthQuantity.writeVariableLengthInt(sharedKeyBytes, block);
    VariableLengthQuantity.writeVariableLengthInt(nonSharedKeyBytes, block);
    VariableLengthQuantity.writeVariableLengthInt(value.length(), block);

    // write non-shared key bytes
    block.writeBytes(key, sharedKeyBytes, nonSharedKeyBytes);

    // write value bytes
    block.writeBytes(value, 0, value.length());

    // update last key
    lastKey = key;

    // update state
    entryCount++;
    restartBlockEntryCount++;
  }

  public static int calculateSharedBytes(Slice leftKey, Slice rightKey) {
    int sharedKeyBytes = 0;

    if (leftKey != null && rightKey != null) {
      int minSharedKeyBytes = Ints.min(leftKey.length(), rightKey.length());
      while (sharedKeyBytes < minSharedKeyBytes && leftKey.getByte(sharedKeyBytes) == rightKey.getByte(sharedKeyBytes)) {
        sharedKeyBytes++;
      }
    }

    return sharedKeyBytes;
  }

  public Slice finish() {
    if (!finished) {
      finished = true;
      lastInlineSeekIndexBytes = 0;
      lastInlineSeekIndexAnchorCount = 0;

      if (entryCount > 0) {
        int inlineIndexOffset = writeInlineSeekIndexIfNeeded();
        restartPositions.write(block);
        block.writeInt(restartPositions.size());
        if (lastInlineSeekIndexAnchorCount > 0) {
          block.writeInt(inlineIndexOffset);
          block.writeInt(Block.INLINE_SEEK_INDEX_BLOCK_MAGIC);
        }
      } else {
        block.writeInt(0);
      }
    }
    return block.slice();
  }

  public int getLastInlineSeekIndexBytes() {
    return lastInlineSeekIndexBytes;
  }

  public int getLastInlineSeekIndexAnchorCount() {
    return lastInlineSeekIndexAnchorCount;
  }

  private int writeInlineSeekIndexIfNeeded() {
    if (!writeInlineSeekIndex || inlineSeekAnchors.size() < inlineSeekIndexAdmissionMinAnchors) {
      return block.size();
    }
    int inlineIndexOffset = block.size();
    VariableLengthQuantity.writeVariableLengthInt(inlineSeekAnchors.size(), block);
    for (InlineSeekAnchor anchor : inlineSeekAnchors) {
      VariableLengthQuantity.writeVariableLengthInt(anchor.restartIndex, block);
      VariableLengthQuantity.writeVariableLengthInt(anchor.offset, block);
      VariableLengthQuantity.writeVariableLengthInt(anchor.key.length(), block);
      block.writeBytes(anchor.key, 0, anchor.key.length());
      VariableLengthQuantity.writeVariableLengthInt(anchor.previousKey.length(), block);
      block.writeBytes(anchor.previousKey, 0, anchor.previousKey.length());
    }
    lastInlineSeekIndexBytes = block.size() - inlineIndexOffset;
    lastInlineSeekIndexAnchorCount = inlineSeekAnchors.size();
    return inlineIndexOffset;
  }

  private int inlineSeekIndexSizeEstimate() {
    if (!writeInlineSeekIndex || inlineSeekAnchors.size() < inlineSeekIndexAdmissionMinAnchors) {
      return 0;
    }
    int size = 5; // anchor count varint upper bound
    for (InlineSeekAnchor anchor : inlineSeekAnchors) {
      size += 5; // restart index varint upper bound
      size += 5; // entry offset varint upper bound
      size += 5; // key length varint upper bound
      size += anchor.key.length();
      size += 5; // previous key length varint upper bound
      size += anchor.previousKey.length();
    }
    return size;
  }

  private int restartTrailerSizeEstimate() {
    if (!writeInlineSeekIndex || inlineSeekAnchors.size() < inlineSeekIndexAdmissionMinAnchors) {
      return SIZE_OF_INT;
    }
    return 3 * SIZE_OF_INT;
  }

  private static final class InlineSeekAnchor {
    private final Slice key;
    private final int offset;
    private final Slice previousKey;
    private final int restartIndex;

    private InlineSeekAnchor(Slice key, int offset, Slice previousKey, int restartIndex) {
      this.key = key;
      this.offset = offset;
      this.previousKey = previousKey;
      this.restartIndex = restartIndex;
    }
  }
}
