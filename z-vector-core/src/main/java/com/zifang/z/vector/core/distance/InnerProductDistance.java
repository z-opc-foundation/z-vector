package com.zifang.z.vector.core.distance;

import com.zifang.z.vector.api.DistanceMetric;

/**
 * 内积距离（Inner Product / Dot Product）— 用于捕捉向量方向相似度。
 *
 * <h2>数学定义</h2>
 * <pre>{@code
 * IP(a, b) = Σ (a_i * b_i)
 *
 * 取负后作为距离: d = -IP(a, b)
 * 这样"越大越近"转换为"越小越近"，与其他度量保持一致。
 * }</pre>
 *
 * <h2>适用场景</h2>
 * <ul>
 *   <li>已归一化向量（||a|| = ||b|| = 1）的检索（此时 IP = COS）</li>
 *   <li>推荐系统中的向量匹配</li>
 *   <li>需要最大化点积的场景</li>
 * </ul>
 */
public class InnerProductDistance implements Distance {

    @Override
    public float compute(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + a.length + " vs " + b.length);
        }
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        // 取负，使"越大越近"变为"越小越近"
        return -sum;
    }

    @Override
    public DistanceMetric metric() { return DistanceMetric.IP; }
}