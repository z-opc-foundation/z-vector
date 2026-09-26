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
 * // 自定义 HNSW 参数（本库 target 是 Java 8，参数表用普通 Map 装）
 * Map<String, Object> hnsw = new HashMap<String, Object>();
 * hnsw.put("M", 32);
 * hnsw.put("efConstruction", 400);
 * hnsw.put("efSearch", 100);
 * Index idx = IndexFactory.create(IndexType.HNSW, DistanceMetric.COSINE, 768, hnsw);
 *
 * // IVF 自定义 nlist/nprobe
 * Map<String, Object> ivf = new HashMap<String, Object>();
 * ivf.put("nlist", 256);
 * ivf.put("nprobe", 16);
 * Index idx = IndexFactory.create(IndexType.IVF, DistanceMetric.L2, 768, ivf);
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

    /**
     * 解析整型索引参数。
     * <p>
     * 必须同时接受 {@link Number} 和数字 {@link String}：持久化层（v1 JSON 与 v2 页快照）
     * 把 indexParams 的值统一序列化成字符串，恢复时读回来就是 {@code "32"} 而不是 {@code 32}。
     * 只认 Number 会让 M / efConstruction / efSearch / nlist / nprobe 在每次重启后
     * 静默回落到默认值（M=32 → 16），召回率与时延随之漂移且不报任何错误。
     */
    private static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof CharSequence) {
            String s = v.toString().trim();
            if (s.isEmpty()) return defaultValue;
            try {
                // 兼容 "16" / "16.0" 两种字符串形态
                return (int) (s.indexOf('.') >= 0 ? Double.parseDouble(s) : Long.parseLong(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Index param '" + key + "' is not numeric: \"" + s + "\"", e);
            }
        }
        return defaultValue;
    }
}