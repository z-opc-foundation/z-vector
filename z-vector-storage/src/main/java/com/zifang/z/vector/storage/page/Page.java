package com.zifang.z.vector.storage.page;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 页面 — 固定大小（{@value #DEFAULT_PAGE_SIZE} 字节）的逻辑块，内部 payload 由
 * {@link PageType} 决定语义（向量/payload/索引/位图）。
 * <p>
 * <h2>页面布局</h2>
 * <pre>
 * ┌──────────────────────────────────────┐
 * │ magic     "ZVP1"  (4B)               │   偏移 0
 * │ pageType  (1B)                       │   偏移 4
 * │ collectionId (4B)                    │   偏移 5
 * │ pageNo    (4B)                       │   偏移 9
 * │ payloadLen (4B)                      │   偏移 13
 * │ payload   (payloadLen B)             │   偏移 17
 * │ padding   (...→ pageSize)            │   填充至 64KB
 * │ crc32     (4B)                       │   偏移 pageSize - 4
 * └──────────────────────────────────────┘
 * </pre>
 * <p>
 * 之所以预留 4 字节 CRC32 在末尾：写入后立刻校验（catch 截断/部分写），崩溃恢复时
 * 也能识别无效页。
 * <p>
 * <h2>线程安全</h2>
 * Page 是<b>不可变</b>的快照对象（payload 在构造时拷贝），多个线程可同时持有同一 Page
 * 引用。要修改必须 {@code newPage()} 返回新对象。
 * <p>
 * <h2>脏标记</h2>
 * {@link #dirty} 字段仅在 BufferPool 中有意义的语义（写回磁盘后清 dirty）。
 */
public final class Page {

    /** 默认页面大小：64 KB（与 RocksDB block cache 默认大小一致）。 */
    public static final int DEFAULT_PAGE_SIZE = 64 * 1024;

    /** 页面 magic — 用于识别有效页。 */
    public static final byte[] MAGIC = {'Z', 'V', 'P', '1'};

    /** Header 大小（不含末尾 CRC32） */
    public static final int HEADER_SIZE = 17;

    /** CRC32 末尾占用 */
    public static final int CRC_SIZE = 4;

    /** Payload 区域最大大小（默认 64KB - HEADER - CRC，留 16 字节给对齐） */
    public static final int MAX_PAYLOAD_SIZE = DEFAULT_PAGE_SIZE - HEADER_SIZE - CRC_SIZE - 16;

    private final PageId id;
    private final int pageSize;
    private final byte[] payload;
    private final int payloadLen;
    private final AtomicLong version = new AtomicLong(0);

    /** BufferPool 内部使用：是否脏（需写回磁盘）。 */
    public volatile boolean dirty = false;

    public Page(PageId id, byte[] payload) {
        this(id, payload, payload.length, DEFAULT_PAGE_SIZE);
    }

    public Page(PageId id, byte[] payload, int pageSize) {
        this(id, payload, payload.length, pageSize);
    }

    public Page(PageId id, byte[] payload, int payloadLen, int pageSize) {
        if (id == null) throw new IllegalArgumentException("id is null");
        if (payload == null) throw new IllegalArgumentException("payload is null");
        if (pageSize < HEADER_SIZE + payloadLen + CRC_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize too small: " + pageSize + " for payload " + payloadLen);
        }
        this.id = id;
        this.pageSize = pageSize;
        this.payload = payload;
        this.payloadLen = payloadLen;
    }

    /** 用空 payload 构造（未写入数据的空页）。 */
    public static Page empty(PageId id, int pageSize) {
        return new Page(id, new byte[0], 0, pageSize);
    }

    public PageId id() { return id; }
    public int pageSize() { return pageSize; }
    public byte[] payload() { return payload; }
    public int payloadLen() { return payloadLen; }

    /** 当前版本号（每次修改+1，用于乐观锁 / MVCC）。 */
    public long version() { return version.get(); }
    public void bumpVersion() { version.incrementAndGet(); }

    // ==================== 序列化 ====================

    /** 把 Page 序列化为 byte[]（用于落盘）。 */
    public byte[] serialize() {
        byte[] out = new byte[pageSize];
        // magic
        System.arraycopy(MAGIC, 0, out, 0, 4);
        // type
        out[4] = id.type().code();
        // collectionId
        writeInt(out, 5, id.collectionId());
        // pageNo
        writeInt(out, 9, id.pageNo());
        // payloadLen
        writeInt(out, 13, payloadLen);
        // payload
        System.arraycopy(payload, 0, out, HEADER_SIZE, payloadLen);
        // CRC32（覆盖末尾 4B）
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(out, 0, pageSize - CRC_SIZE);
        writeInt(out, pageSize - CRC_SIZE, (int) crc.getValue());
        return out;
    }

    /**
     * 用新的 PageId 序列化（用于 PageStore 复用 free slot 时改写 pageNo）。
     */
    public byte[] serializeWithId(PageId newId) {
        if (newId.collectionId() != id.collectionId() || newId.type() != id.type()) {
            throw new IllegalArgumentException("Cannot change collectionId/type via serializeWithId: "
                    + id + " → " + newId);
        }
        Page original = this;
        if (newId.pageNo() != id.pageNo()) {
            original = new Page(newId, payload, payloadLen, pageSize);
        }
        return original.serialize();
    }

    /** 从 byte[] 反序列化 Page（用于读盘）。 */
    public static Page deserialize(byte[] bytes, int pageSize) {
        if (bytes == null || bytes.length < pageSize) {
            throw new IllegalArgumentException("byte array too small: " +
                    (bytes == null ? 0 : bytes.length));
        }
        // magic
        for (int i = 0; i < 4; i++) {
            if (bytes[i] != MAGIC[i]) {
                throw new IllegalArgumentException("Invalid page magic at offset " + i);
            }
        }
        PageType type = PageType.fromCode(bytes[4]);
        int collectionId = readInt(bytes, 5);
        int pageNo = readInt(bytes, 9);
        int payloadLen = readInt(bytes, 13);
        // CRC32 校验
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, pageSize - CRC_SIZE);
        int expected = (int) crc.getValue();
        int actual = readInt(bytes, pageSize - CRC_SIZE);
        if (expected != actual) {
            throw new IllegalArgumentException("Page CRC mismatch (expected="
                    + expected + ", actual=" + actual + ") — corrupted page");
        }
        byte[] payload = new byte[payloadLen];
        System.arraycopy(bytes, HEADER_SIZE, payload, 0, payloadLen);
        PageId id = new PageId(collectionId, type, pageNo);
        return new Page(id, payload, payloadLen, pageSize);
    }

    /** 序列化为堆外 ByteBuffer（便于 mmap write）。 */
    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(serialize());
    }

    // ==================== 工具 ====================

    private static void writeInt(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >>> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readInt(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }
}
