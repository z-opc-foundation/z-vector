package com.zifang.z.vector.core.distance;

import com.zifang.z.vector.api.DistanceMetric;

/**
 * 欧氏距离（L2 / Euclidean Distance）— 两个向量在欧氏空间中的直线距离。
 *
 * <h2>数学定义</h2>
 * <pre>{@code
 * L2(a, b) = sqrt(Σ (a_i - b_i)^2)
 * }</pre>
 *
 * <h2>特性</h2>
 * <ul>
 *   <li>范围: [0, +∞)，0 表示完全相同</li>
 *   <li>对称性: L2(a, b) = L2(b, a)</li>
 *   <li>三角不等式: L2(a, c) ≤ L2(a, b) + L2(b, c)</li>
 * </ul>
 */
public class L2Distance implements Distance {

    @Override
    public float compute(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + a.length + " vs " + b.length);
        }
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    @Override
    public DistanceMetric metric() { return DistanceMetric.L2; }
}