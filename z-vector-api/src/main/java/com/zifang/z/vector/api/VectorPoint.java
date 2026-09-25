package com.zifang.z.vector.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 向量点 — 一个带 id 的浮点向量 + 可选标量 payload。
 * <p>
 * 对应 Milvus 的 InsertField / Qdrant 的 Point 结构 / zvec 的 Doc 结构。
 */
public class VectorPoint {

    private final String id;
    private final float[] vector;
    private final Map<String, Object> payload;

    public VectorPoint(String id, float[] vector) {
        this(id, vector, new LinkedHashMap<>());
    }

    public VectorPoint(String id, float[] vector, Map<String, Object> payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.vector = Objects.requireNonNull(vector, "vector").clone();
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public String getId() { return id; }
    public float[] getVector() { return vector.clone(); }
    public int getDimension() { return vector.length; }
    public Map<String, Object> getPayload() { return Collections.unmodifiableMap(payload); }

    /**
     * 内部引用版访问器 —— <b>不拷贝</b>，仅供索引扫描这类热路径使用。
     * <p>
     * {@link #getVector()} 每次返回一份克隆：n=5000、dim=64 的暴力扫单是一次查询要拷
     * 1.25MB、外加 5000 个数组对象（实测 1.9MB/查询，见 AnnBench 的 bytes_per_query）。
     * <b>调用方约定</b>：拿到的数组/map 不得修改、不得保存到 point 之外（逃逸）——
     * 它就是存储本体。对外 API 仍走 {@link #getVector()} / {@link #getPayload()}。
     */
    public float[] vectorRef() { return vector; }

    /** 内部引用版 payload 访问器，约定同上（不包装、不拷贝）。 */
    public Map<String, Object> payloadRef() { return payload; }

    public Object getPayload(String key) { return payload.get(key); }

    public VectorPoint setPayload(String key, Object value) {
        payload.put(key, value);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VectorPoint)) return false;
        VectorPoint p = (VectorPoint) o;
        return id.equals(p.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() {
        return String.format("VectorPoint{id='%s', dim=%d, payload=%s}", id, vector.length, payload);
    }
}