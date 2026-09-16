package com.zifang.z.vector.core.distance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 距离度量单元测试 — 覆盖 L2 / IP / Cosine / Hamming 四种度量的正确性。
 */
class DistanceTest {

    @Test
    void l2IdenticalVectorsZero() {
        float[] a = {1, 2, 3};
        Distance d = new L2Distance();
        assertEquals(0f, d.compute(a, a), 1e-6);
    }

    @Test
    void l2Orthogonal() {
        float[] a = {3, 0};
        float[] b = {0, 4};
        Distance d = new L2Distance();
        // 3-4-5 直角三角形
        assertEquals(5f, d.compute(a, b), 1e-6);
    }

    @Test
    void l2Asymmetry() {
        // L2 是对称的
        float[] a = {1, 2, 3};
        float[] b = {4, 5, 6};
        Distance d = new L2Distance();
        assertEquals(d.compute(a, b), d.compute(b, a), 1e-6);
    }

    @Test
    void l2DimensionMismatchThrows() {
        Distance d = new L2Distance();
        assertThrows(IllegalArgumentException.class,
                () -> d.compute(new float[]{1, 2}, new float[]{1, 2, 3}));
    }

    @Test
    void innerProductIdentical() {
        // 自己点自己应该是 sum(a^2)，取负后 = -sum(a^2)
        float[] a = {1, 2, 3};
        Distance d = new InnerProductDistance();
        assertEquals(-14f, d.compute(a, a), 1e-6);
    }

    @Test
    void innerProductLargerForSimilarVectors() {
        // 内积越大 → 取负后距离越小（更近）
        float[] query = {1, 0, 0};
        float[] similar = {0.9f, 0.1f, 0};
        float[] orthogonal = {0, 0, 1};
        Distance d = new InnerProductDistance();
        assertTrue(d.compute(query, similar) < d.compute(query, orthogonal));
    }

    @Test
    void cosineIdentical() {
        float[] a = {1, 2, 3};
        Distance d = new CosineDistance();
        // 距离 = 1 - cos_sim = 0
        assertEquals(0f, d.compute(a, a), 1e-6);
    }

    @Test
    void cosineOrthogonal() {
        float[] a = {1, 0};
        float[] b = {0, 1};
        Distance d = new CosineDistance();
        // cos_sim = 0, 距离 = 1
        assertEquals(1f, d.compute(a, b), 1e-6);
    }

    @Test
    void cosineOpposite() {
        float[] a = {1, 0};
        float[] b = {-1, 0};
        Distance d = new CosineDistance();
        // cos_sim = -1, 距离 = 2
        assertEquals(2f, d.compute(a, b), 1e-6);
    }

    @Test
    void cosineScaleInvariant() {
        // 余弦距离应该与向量长度无关
        float[] a = {1, 0};
        float[] b = {1, 0};
        float[] bScaled = {5, 0};
        Distance d = new CosineDistance();
        assertEquals(d.compute(a, b), d.compute(a, bScaled), 1e-6);
    }

    @Test
    void cosineZeroVector() {
        float[] a = {1, 2, 3};
        float[] zero = {0, 0, 0};
        Distance d = new CosineDistance();
        assertEquals(2.0f, d.compute(a, zero), 1e-6);
    }

    @Test
    void hammingIdenticalZero() {
        float[] a = {1, 2, 3};
        Distance d = new HammingDistance();
        assertEquals(0f, d.compute(a, a), 1e-6);
    }

    @Test
    void hammingDifferent() {
        float[] a = {1, 0};
        float[] b = {0, 1};
        Distance d = new HammingDistance();
        // 不同位数 > 0
        assertTrue(d.compute(a, b) > 0);
    }

    @Test
    void factoryCreatesCorrectMetrics() {
        assertEquals(com.zifang.z.vector.api.DistanceMetric.L2,
                DistanceFactory.create(com.zifang.z.vector.api.DistanceMetric.L2).metric());
        assertEquals(com.zifang.z.vector.api.DistanceMetric.IP,
                DistanceFactory.create(com.zifang.z.vector.api.DistanceMetric.IP).metric());
        assertEquals(com.zifang.z.vector.api.DistanceMetric.COSINE,
                DistanceFactory.create(com.zifang.z.vector.api.DistanceMetric.COSINE).metric());
        assertEquals(com.zifang.z.vector.api.DistanceMetric.HAMMING,
                DistanceFactory.create(com.zifang.z.vector.api.DistanceMetric.HAMMING).metric());
    }
}