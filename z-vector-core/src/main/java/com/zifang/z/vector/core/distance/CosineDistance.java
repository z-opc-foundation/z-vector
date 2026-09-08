package com.zifang.z.vector.core.distance;

import com.zifang.z.vector.api.DistanceMetric;

/**
 * 余弦距离（Cosine Distance）— 衡量两个向量在方向上的相似度，忽略大小。
 *
 * <h2>数学定义</h2>
 * <pre>{@code
 * cos_sim(a, b) = Σ(a_i * b_i) / (||a|| * ||b||)
 * cos_dist(a, b) = 1 - cos_sim(a, b)
 *
 * 范围: [0, 2]
 *   0: 完全相同（方向一致）
 *   1: 正交
 *   2: 完全相反
 * }</pre>
 *
 * <h2>适用场景</h2>
 * <ul>
 *   <li>文本 Embedding 检索（OpenAI/Coze/BGE 等）</li>
 *   <li>需要忽略向量模长差异的场景</li>
 *   <li>RAG 系统中最常用的距离度量</li>
 * </ul>
 *
 * <h2>性能优化</h2>
 * 实现采用单次遍历同时计算 dot / normA / normB，避免三次遍历。
 */
public class CosineDistance implements Distance {

    @Override
    public float compute(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + a.length + " vs " + b.length);
        }
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0f || normB <= 0f) {
            // 零向量无法计算余弦相似度，返回最大距离
            return 2.0f;
        }
        float similarity = dot / ((float) Math.sqrt(normA) * (float) Math.sqrt(normB));
        // 钳制到 [-1, 1] 以避免浮点误差
        if (similarity > 1f) similarity = 1f;
        if (similarity < -1f) similarity = -1f;
        return 1f - similarity;
    }

    @Override
    public DistanceMetric metric() { return DistanceMetric.COSINE; }
}