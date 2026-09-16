package com.zifang.z.vector.storage.page;

/**
 * 页面类型 — 决定 {@link Page} 的逻辑语义。
 * <p>
 * z-vector 的本地存储把不同类型的数据放在不同 page 中，方便 BufferPool 按类型做
 * 缓存策略，也方便增量 snapshot 只持久化 dirty 页。
 */
public enum PageType {
    /** 集合的元数据（schema/dimension/metric/indexType/indexParams） */
    META(0x01),
    /** 向量数据 + payload（一页装多条） */
    DATA(0x02),
    /** HNSW 索引节点（一页装一个图节点或若干邻居关系） */
    INDEX(0x03),
    /** Bloom filter 的位图（每个集合独立一个 page） */
    BLOOM(0x04),
    /** Payload 倒排索引（保留给未来使用） */
    PAYLOAD_INDEX(0x05);

    private final byte code;

    PageType(int code) {
        this.code = (byte) code;
    }

    public byte code() { return code; }

    public static PageType fromCode(byte code) {
        for (PageType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("Unknown PageType code: " + code);
    }
}
