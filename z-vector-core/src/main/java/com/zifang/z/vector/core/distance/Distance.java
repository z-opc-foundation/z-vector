package com.zifang.z.vector.core.distance;

import com.zifang.z.vector.api.DistanceMetric;

/**
 * 距离度量计算器 — 统一"越小越近"语义。
 * <p>
 * 设计参考 zvec 的 ailego/math 距离模块 + Faiss 的 MetricType。
 *
 * <h2>距离语义统一</h2>
 * <pre>{@code
 * L2:     d = sqrt(Σ (a_i - b_i)^2)            // 越小越近
 * IP:     d = -Σ (a_i * b_i)                  // 取负使"越大越近"变为"越小越近"
 * COSINE: d = 1 - Σ(a_i*b_i) / (||a|| * ||b||) // 转距离
 * HAMMING: d = popcount(a XOR b)               // 不同位数
 * }</pre>
 */
public interface Distance {

    /**
     * 计算两个向量的距离
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 距离（统一为越小越近）
     */
    float compute(float[] a, float[] b);

    /** 对应的距离度量类型 */
    DistanceMetric metric();
}