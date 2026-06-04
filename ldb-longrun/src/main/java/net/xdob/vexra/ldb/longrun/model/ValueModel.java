package net.xdob.vexra.ldb.longrun.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * longrun 可校验 key/value 编码。
 */
public final class ValueModel {
  private static final int MAGIC = 0x4c524e31;
  private static final int HEADER_BYTES = 4 + 8 + 8 + 4 + 4;

  private ValueModel() {
  }

  /**
   * 生成测试 key。
   *
   * @param keyId key 编号
   * @return UTF-8 key
   */
  public static byte[] key(long keyId) {
    return String.format("k:%020d", keyId).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 编码 value，并在尾部写入 CRC32。
   *
   * @param seed profile seed
   * @param keyId key 编号
   * @param sequence 业务序号
   * @param size value 大小
   * @return 可校验 value
   */
  public static byte[] encode(long seed, long keyId, long sequence, int size) {
    int actualSize = Math.max(size, HEADER_BYTES);
    byte[] value = new byte[actualSize];
    ByteBuffer buffer = ByteBuffer.wrap(value);
    buffer.putInt(MAGIC);
    buffer.putLong(keyId);
    buffer.putLong(sequence);
    buffer.putInt(actualSize);
    buffer.putInt(0);
    for (int i = HEADER_BYTES; i < actualSize; i++) {
      value[i] = (byte) ((seed + keyId * 31 + sequence * 17 + i) & 0xff);
    }
    int checksum = checksumValue(value);
    ByteBuffer.wrap(value, HEADER_BYTES - 4, 4).putInt(checksum);
    return value;
  }

  /**
   * 校验 value 是否匹配预期 key 和 sequence。
   *
   * @param value 实际读取到的 value
   * @param keyId 预期 key 编号
   * @param sequence 预期序号
   */
  public static void verify(byte[] value, long keyId, long sequence) {
    if (value == null) {
      throw new IllegalStateException("missing key " + keyId + " sequence " + sequence);
    }
    if (value.length < HEADER_BYTES) {
      throw new IllegalStateException("value too small for key " + keyId);
    }
    ByteBuffer buffer = ByteBuffer.wrap(value);
    int magic = buffer.getInt();
    long actualKeyId = buffer.getLong();
    long actualSequence = buffer.getLong();
    int size = buffer.getInt();
    int expectedChecksum = buffer.getInt();
    int actualChecksum = checksumValue(value);
    if (magic != MAGIC || actualKeyId != keyId || actualSequence != sequence
        || size != value.length || expectedChecksum != actualChecksum) {
      throw new IllegalStateException("checksum mismatch for key " + keyId);
    }
  }

  private static int checksumValue(byte[] value) {
    CRC32 crc32 = new CRC32();
    crc32.update(value, 0, HEADER_BYTES - 4);
    crc32.update(value, HEADER_BYTES, value.length - HEADER_BYTES);
    return (int) crc32.getValue();
  }

  private static int checksum(byte[] bytes, int offset, int length) {
    CRC32 crc32 = new CRC32();
    crc32.update(bytes, offset, length);
    return (int) crc32.getValue();
  }
}
