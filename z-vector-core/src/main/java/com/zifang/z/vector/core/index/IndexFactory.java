package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.core.distance.Distance;
import com.zifang.z.vector.core.distance.DistanceFactory;

import java.util.Map;
import java.util.Objects;

/**
 * ANN 索引工厂 — 根据 IndexType 和参数创建具体索引实例。
 * <p>
 * 设计参考 zvec 的 {@code IndexFactory} + Faiss 的 {@code index_factory} 函数。
 *
 * <h2>索引选择策略</h2>
 * <pre>{@code
 * N < 10K:        IndexType.FLAT          → FlatIndex（精确）
 * 10K ~ 1M:       IndexType.HNSW          → HnswIndex（推荐默认）
 * 1M ~ 10M:       IndexType.HNSW / IVF    → 根据数据分布选择
 * > 10M:          IndexType.IVF + 量化    → IvfIndex（待加 PQ）
 * }</pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 默认参数 HNSW
 * Index idx = IndexFactory.create(IndexType.HNSW, DistanceMetric.COSINE, 768);
 *
 * // 自定义 HNSW 参数
 * Index idx = IndexFactory.create(IndexType.HNSW, DistanceMetric.COSINE, 768,
 *         Map.of("M", 32, "efConstruction", 400, "efSearch", 100));
 *
 * // IVF 自定义 nlist/nprobe
 * Index idx = IndexFactory.create(IndexType.IVF, DistanceMetric.L2, 768,
 *         Map.of("nlist", 256, "nprobe", 16));
 * }</pre>
 */
public final class IndexFactory {

    private IndexFactory() {}

    /** 使用默认参数创建索引 */
    public static Index create(IndexType type, DistanceMetric metric, int dimension) {
        return create(type, metric, dimension, null);
    }

    /** 带自定义参数创建索引 */
    public static Index create(IndexType type, DistanceMetric metric, int dimension,
                               Map<String, Object> params) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(metric, "metric");
        Distance distance = DistanceFactory.create(metric);

        switch (type) {
            case FLAT:
                return new FlatIndex(distance, dimension);
            case HNSW:
                return createHnsw(distance, dimension, params);
            case IVF:
                return createIvf(distance, dimension, params);
            default:
                throw new IllegalArgumentException("Unsupported index type: " + type);
        }
    }

    private static Index createHnsw(Distance distance, int dimension, Map<String, Object> params) {
        int M = HnswIndex.DEFAULT_M;
        int efConstruction = HnswIndex.DEFAULT_EF_CONSTRUCTION;
        int efSearch = HnswIndex.DEFAULT_EF_SEARCH;
        if (params != null) {
            M = intParam(params, "M", M);
            efConstruction = intParam(params, "efConstruction", efConstruction);
            efSearch = intParam(params, "efSearch", efSearch);
        }
        return new HnswIndex(distance, dimension, M, efConstruction, efSearch);
    }

    private static Index createIvf(Distance distance, int dimension, Map<String, Object> params) {
        int nlist = IvfIndex.DEFAULT_NLIST;
        int nprobe = IvfIndex.DEFAULT_NPROBE;
        int maxIter = IvfIndex.DEFAULT_MAX_ITER;
        if (params != null) {
            nlist = intParam(params, "nlist", nlist);
            nprobe = intParam(params, "nprobe", nprobe);
            maxIter = intParam(params, "maxIter", maxIter);
        }
        return new IvfIndex(distance, dimension, nlist, nprobe, maxIter);
    }

    private static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return defaultValue;
    }
}