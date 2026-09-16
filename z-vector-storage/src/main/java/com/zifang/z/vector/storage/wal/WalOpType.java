package com.zifang.z.vector.storage.wal;

/**
 * WAL 操作类型 — 决定每条记录的语义。
 * <p>
 * 设计参考 zvec 的 LocalWalFile + SQLite 的 WAL journal format.
 */
public enum WalOpType {
    /** 创建集合 */
    CREATE_COLLECTION((byte) 1),
    /** 删除集合 */
    DELETE_COLLECTION((byte) 2),
    /** 插入/更新向量点 */
    UPSERT_POINT((byte) 3),
    /** 删除向量点 */
    DELETE_POINT((byte) 4),
    /** 批量操作开始 */
    BATCH_BEGIN((byte) 5),
    /** 批量操作结束 */
    BATCH_COMMIT((byte) 6),
    /** 检查点（snapshot 标记） */
    CHECKPOINT((byte) 7);

    private final byte code;

    WalOpType(byte code) { this.code = code; }

    public byte code() { return code; }

    public static WalOpType fromCode(byte code) {
        for (WalOpType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("Unknown WAL op code: " + code);
    }
}