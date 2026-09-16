package com.zifang.z.vector.storage.wal;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;

/**
 * WAL 记录 — 一条 write-ahead log 条目。
 * <p>
 * 每条记录包含:
 * <ul>
 *   <li>op: 操作类型</li>
 *   <li>collection: 集合名</li>
 *   <li>timestamp: 时间戳（用于排序）</li>
 *   <li>payload: 操作相关数据（VectorPoint / schema 等）</li>
 * </ul>
 *
 * <h2>序列化格式（二进制）</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────┐
 * │ magic (4B) "WAL1"                                │
 * │ op (1B)                                          │
 * │ timestamp (8B long)                              │
 * │ collectionNameLength (2B)                        │
 * │ collectionName (变长)                            │
 * │ payloadLength (4B)                               │
 * │ payload (变长 JSON)                              │
 * │ crc32 (4B)                                       │
 * └─────────────────────────────────────────────────┘
 * </pre>
 */
public class WalRecord {

    public static final String MAGIC = "WAL1";
    public static final int HEADER_SIZE = 4 + 1 + 8 + 2 + 4 + 4; // 23 bytes

    private final WalOpType op;
    private final long timestamp;
    private final String collection;
    private final String payload;

    public WalRecord(WalOpType op, String collection, String payload) {
        this.op = op;
        this.timestamp = System.currentTimeMillis();
        this.collection = collection;
        this.payload = payload;
    }

    public WalRecord(WalOpType op, long timestamp, String collection, String payload) {
        this.op = op;
        this.timestamp = timestamp;
        this.collection = collection;
        this.payload = payload;
    }

    public WalOpType getOp() { return op; }
    public long getTimestamp() { return timestamp; }
    public String getCollection() { return collection; }
    public String getPayload() { return payload; }

    // ================= =  便捷构造方法  =================

    public static WalRecord createCollection(String name, int dimension,
                                            DistanceMetric metric, IndexType indexType) {
        String payload = String.format(
                "{\"name\":\"%s\",\"dimension\":%d,\"metric\":\"%s\",\"index_type\":\"%s\"}",
                name, dimension, metric.name(), indexType.name());
        return new WalRecord(WalOpType.CREATE_COLLECTION, name, payload);
    }

    public static WalRecord deleteCollection(String name) {
        return new WalRecord(WalOpType.DELETE_COLLECTION, name, "{}");
    }

    public static WalRecord upsertPoint(String collection, VectorPoint point) {
        StringBuilder sb = new StringBuilder("{\"id\":\"").append(point.getId())
                .append("\",\"vector\":[");
        float[] v = point.getVector();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(v[i]);
        }
        sb.append("],\"payload\":");
        sb.append(jsonMap(point.getPayload()));
        sb.append("}");
        return new WalRecord(WalOpType.UPSERT_POINT, collection, sb.toString());
    }

    public static WalRecord deletePoint(String collection, String id) {
        return new WalRecord(WalOpType.DELETE_POINT, collection,
                "{\"id\":\"" + id + "\"}");
    }

    public static WalRecord checkpoint(long sequenceNumber) {
        return new WalRecord(WalOpType.CHECKPOINT, "_checkpoint",
                "{\"seq\":" + sequenceNumber + "}");
    }

    private static String jsonMap(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(v).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}