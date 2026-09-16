package com.zifang.z.vector.core.distance;

import com.zifang.z.vector.api.DistanceMetric;

/**
 * 汉明距离（Hamming Distance）— 用于二进制向量的差异度量。
 *
 * <h2>数学定义</h2>
 * <pre>{@code
 * Hamming(a, b) = Σ (a_i XOR b_i)  // 不同位的数量
 * }</pre>
 *
 * <h2>适用场景</h2>
 * <ul>
 *   <li>二进制量化向量（1-bit per dim）</li>
 *   <li>Locality-Sensitive Hashing (LSH)</li>
 *   <li>极低内存占用的粗筛场景</li>
 * </ul>
 *
 * <h2>注意</h2>
 * 当前实现将 float[] 中每个元素按位处理: float 转 long 后取低 32 位 XOR。
 * 实际使用中通常与 BINARY 量化器配套使用。
 */
public class HammingDistance implements Distance {

    @Override
    public float compute(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + a.length + " vs " + b.length);
        }
        long distance = 0;
        for (int i = 0; i < a.length; i++) {
            int ai = Float.floatToRawIntBits(a[i]);
            int bi = Float.floatToRawIntBits(b[i]);
            long xored = (ai ^ bi) & 0xFFFFFFFFL;
            distance += Long.bitCount(xored);
        }
        return distance;
    }

    @Override
    public DistanceMetric metric() { return DistanceMetric.HAMMING; }
}