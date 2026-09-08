package com.zifang.z.vector.core.distance;

import com.zifang.z.vector.api.DistanceMetric;

/**
 * 距离度量工厂 — 根据 DistanceMetric 创建对应的 Distance 实现。
 */
public final class DistanceFactory {

    private DistanceFactory() {}

    /** 已注册的实例（缓存避免重复创建） */
    private static final L2Distance L2 = new L2Distance();
    private static final InnerProductDistance IP = new InnerProductDistance();
    private static final CosineDistance COSINE = new CosineDistance();
    private static final HammingDistance HAMMING = new HammingDistance();

    public static Distance create(DistanceMetric metric) {
        switch (metric) {
            case L2:      return L2;
            case IP:      return IP;
            case COSINE:  return COSINE;
            case HAMMING: return HAMMING;
            default:
                throw new IllegalArgumentException("Unsupported metric: " + metric);
        }
    }
}