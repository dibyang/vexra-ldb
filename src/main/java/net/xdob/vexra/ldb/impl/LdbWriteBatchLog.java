package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.LdbColumnFamily;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.SliceInput;

import java.io.IOException;

import static net.xdob.vexra.ldb.impl.ValueType.ADD_LONG;
import static net.xdob.vexra.ldb.impl.ValueType.DELETION;
import static net.xdob.vexra.ldb.impl.ValueType.DELETE_RANGE;
import static net.xdob.vexra.ldb.impl.ValueType.VALUE;
import static net.xdob.vexra.ldb.util.Slices.readLengthPrefixedBytes;

/**
 * WAL 中 write batch 记录的解码工具。
 *
 * 该类集中维护 WAL batch 的磁盘格式解析，避免正常恢复和 repair 路径各自复制格式细节。
 */
final class LdbWriteBatchLog {
  private LdbWriteBatchLog() {
  }

  /**
   * 从 WAL record 剩余内容中读取一个 write batch。
   *
   * @param record record 中 sequence/updateSize 之后的输入
   * @param updateSize record 声明的操作数量
   * @param resolver 根据 cfId 解析列族，repair 路径可按需只支持 default CF
   * @return 解码后的 batch
   * @throws IOException 当 record 操作数量不一致时抛出
   */
  static LdbWriteBatchImpl readWriteBatch(SliceInput record, int updateSize, ColumnFamilyResolver resolver)
      throws IOException {
    LdbWriteBatchImpl writeBatch = new LdbWriteBatchImpl();
    int entries = 0;
    while (record.isReadable()) {
      entries++;
      ValueType valueType = ValueType.getValueTypeByPersistentId(record.readByte());
      int cfId = record.readInt();
      LdbColumnFamily cf = resolver.getColumnFamily(cfId);
      if (valueType == VALUE) {
        Slice key = readLengthPrefixedBytes(record);
        Slice value = readLengthPrefixedBytes(record);
        writeBatch.put(cf, key, value);
      } else if (valueType == DELETION) {
        Slice key = readLengthPrefixedBytes(record);
        writeBatch.delete(cf, key);
      } else if (valueType == DELETE_RANGE) {
        Slice beginKey = readLengthPrefixedBytes(record);
        Slice endKey = readLengthPrefixedBytes(record);
        writeBatch.deleteRange(cf, beginKey, endKey);
      } else if (valueType == ADD_LONG) {
        Slice key = readLengthPrefixedBytes(record);
        Slice deltaSlice = readLengthPrefixedBytes(record);
        writeBatch.addLong(cf, key, deltaSlice);
      } else {
        throw new IllegalStateException("Unexpected value type " + valueType);
      }
    }

    if (entries != updateSize) {
      throw new IOException(String.format(
          "Expected %d entries in log record but found %s entries", updateSize, entries));
    }
    return writeBatch;
  }

  interface ColumnFamilyResolver {
    LdbColumnFamily getColumnFamily(int cfId);
  }
}
