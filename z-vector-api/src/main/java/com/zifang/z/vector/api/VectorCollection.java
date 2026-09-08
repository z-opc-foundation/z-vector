package com.zifang.z.vector.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 向量集合 — 类似 Milvus 的 Collection / Qdrant 的 Collection / zvec 的 Collection.
 * <p>
 * 一个 Collection 包含同构向量点，每个点有:
 * <ul>
 *   <li>id: 字符串唯一标识</li>
 *   <li>vector: 浮点向量（维度固定）</li>
 *   <li>payload: 标量元数据（用于过滤）</li>
 * </ul>
 */
public class VectorCollection {

    private final String name;
    private final int dimension;
    private final DistanceMetric metric;
    private final IndexType indexType;
    private final Map<String, Object> config;
    private final long createdAt;

    public VectorCollection(String name, int dimension, DistanceMetric metric,
                            IndexType indexType, Map<String, Object> config) {
        this.name = Objects.requireNonNull(name, "name");
        this.dimension = dimension;
        this.metric = Objects.requireNonNull(metric, "metric");
        this.indexType = Objects.requireNonNull(indexType, "indexType");
        this.config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
        this.createdAt = System.currentTimeMillis();
    }

    public VectorCollection(String name, int dimension, DistanceMetric metric) {
        this(name, dimension, metric, IndexType.FLAT, Collections.emptyMap());
    }

    public String getName() { return name; }
    public int getDimension() { return dimension; }
    public DistanceMetric getMetric() { return metric; }
    public IndexType getIndexType() { return indexType; }
    public Map<String, Object> getConfig() { return Collections.unmodifiableMap(config); }
    public long getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VectorCollection)) return false;
        VectorCollection that = (VectorCollection) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() { return name.hashCode(); }

    @Override
    public String toString() {
        return String.format("VectorCollection{name='%s', dim=%d, metric=%s, index=%s}",
                name, dimension, metric, indexType);
    }
}