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

    /**
     * CREATE_COLLECTION 的 WAL 记录。
     * <p>
     * {@code indexParams} 必须进 payload：重放侧的 {@code applyCreateCollection} 一直在读
     * {@code index_params} 这个键，而写侧从来没写过 ⇒ 显式带参建的集合一重启参数就没了
     * （写进去时 {@code getConfig()} 有 {@code M=7}，重放后是空 map）。
     * <p>
     * 字符串一律经 {@link #jsonString}：此前是 {@code String.format} 把 name 直接拼进 JSON，
     * 集合名里带一个 {@code "} 就写出一条结构上非法的 payload —— 记录的 CRC 是**对的**，
     * 所以重放时炸在 parseJson，而不是被当成损坏记录跳过，整库启不来。
     */
    public static WalRecord createCollection(String name, int dimension,
                                            DistanceMetric metric, IndexType indexType,
                                            java.util.Map<String, Object> indexParams) {
        String payload = "{\"name\":" + jsonString(name)
                + ",\"dimension\":" + dimension
                + ",\"metric\":" + jsonString(metric.name())
                + ",\"index_type\":" + jsonString(indexType.name())
                + ",\"index_params\":" + jsonMap(indexParams)
                + "}";
        return new WalRecord(WalOpType.CREATE_COLLECTION, name, payload);
    }

    public static WalRecord deleteCollection(String name) {
        return new WalRecord(WalOpType.DELETE_COLLECTION, name, "{}");
    }

    public static WalRecord upsertPoint(String collection, VectorPoint point) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(jsonString(point.getId()))
                .append(",\"vector\":[");
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
                "{\"id\":" + jsonString(id) + "}");
    }

    public static WalRecord checkpoint(long sequenceNumber) {
        return new WalRecord(WalOpType.CHECKPOINT, "_checkpoint",
                "{\"seq\":" + sequenceNumber + "}");
    }

    /**
     * 带引号的 JSON 字符串字面量：转义 {@code "}{@code \} 和控制字符，其余按 UTF-8 原样写。
     * null 写成 {@code null}（不是 {@code "null"}），让读侧拿到的是缺字段而不是一个叫
     * {@code "null"} 的 id。
     */
    static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * payload 的 JSON 编码。递归：此前非 Number/Boolean 的值一律被写成 {@code "toString()"}，
     * 于是嵌套 map（REST 侧完全允许 {@code {"filter":{"range":{"gt":3}}}} 这种形状）被写成
     * {@code "{a={b=1}}"} —— 不是合法 JSON，同样是 CRC 正确而重放即炸。
     */
    private static String jsonMap(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append(jsonString(e.getKey())).append(":");
            appendJsonValue(sb, e.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /** 单个值的 JSON 编码：数字/布尔原样，字符串转义，Map/List 递归，其余按字符串处理。 */
    private static StringBuilder appendJsonValue(StringBuilder sb, Object v) {
        if (v == null) return sb.append("null");
        if (v instanceof Number || v instanceof Boolean) return sb.append(v);
        if (v instanceof java.util.Map) {
            sb.append('{');
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(",");
                sb.append(jsonString(String.valueOf(e.getKey()))).append(":");
                appendJsonValue(sb, e.getValue());
                first = false;
            }
            return sb.append('}');
        }
        if (v instanceof java.util.Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (java.util.Collection<?>) v) {
                if (!first) sb.append(",");
                appendJsonValue(sb, o);
                first = false;
            }
            return sb.append(']');
        }
        return sb.append(jsonString(v.toString()));
    }
}