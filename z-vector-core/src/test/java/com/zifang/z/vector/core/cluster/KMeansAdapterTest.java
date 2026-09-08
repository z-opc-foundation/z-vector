package com.zifang.z.vector.core.cluster;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KMeansAdapter 单元测试 — 验证 z-util-ml KMeans 集成正确。
 * <p>
 * 测试场景：
 * <ol>
 *   <li>已知分布（两团高斯）聚类后应基本还原团心位置；</li>
 *   <li>边界：k &gt; n 时自动截断为 n；</li>
 *   <li>边界：n == 0 返回空；</li>
 *   <li>assignNearest 在简单几何下应能找到正确最近质心。</li>
 * </ol>
 */
class KMeansAdapterTest {

    @Test
    void clusterTwoGaussianBlobsRecoversCentroids() {
        // 构造 2 团数据：团 A 中心 (0, 0, 0)，团 B 中心 (10, 10, 10)
        int nPerCluster = 60;
        int dim = 3;
        Random r = new Random(42);
        float[][] data = new float[nPerCluster * 2][dim];
        for (int i = 0; i < nPerCluster; i++) {
            for (int d = 0; d < dim; d++) {
                data[i][d] = (float) (r.nextGaussian() * 0.5);
                data[i + nPerCluster][d] = 10f + (float) (r.nextGaussian() * 0.5);
            }
        }

        float[][] centroids = KMeansAdapter.cluster(data, 2, 50);

        assertEquals(2, centroids.length);
        assertEquals(dim, centroids[0].length);

        // 每个质心应靠近 (0,0,0) 或 (10,10,10) 之一
        float[] expectedA = {0f, 0f, 0f};
        float[] expectedB = {10f, 10f, 10f};
        boolean foundA = anyCentroidClose(centroids, expectedA, 1.5f);
        boolean foundB = anyCentroidClose(centroids, expectedB, 1.5f);
        assertTrue(foundA, "Should recover centroid near (0,0,0)");
        assertTrue(foundB, "Should recover centroid near (10,10,10)");
    }

    @Test
    void clusterKGreaterThanNTruncates() {
        // n=3, k=10：应当截断为 n=3（每个点一个簇）
        float[][] data = {
                {0f, 0f},
                {1f, 1f},
                {2f, 2f}
        };
        float[][] centroids = KMeansAdapter.cluster(data, 10, 5);
        assertEquals(3, centroids.length, "k should be truncated to n");
    }

    @Test
    void clusterEmptyReturnsEmpty() {
        assertEquals(0, KMeansAdapter.cluster(new float[0][], 2, 5).length);
        assertEquals(0, KMeansAdapter.cluster(null, 2, 5).length);
    }

    @Test
    void clusterInvalidKThrows() {
        float[][] data = {{1f, 2f}};
        assertThrows(IllegalArgumentException.class,
                () -> KMeansAdapter.cluster(data, 0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> KMeansAdapter.cluster(data, -1, 5));
    }

    @Test
    void assignNearestFindsCorrectCentroid() {
        float[][] centroids = {
                {0f, 0f},
                {10f, 10f},
                {20f, 20f}
        };
        assertEquals(0, KMeansAdapter.assignNearest(centroids, new float[]{0.1f, 0.1f}));
        assertEquals(1, KMeansAdapter.assignNearest(centroids, new float[]{9.9f, 10.1f}));
        assertEquals(2, KMeansAdapter.assignNearest(centroids, new float[]{19.5f, 19.5f}));
    }

    @Test
    void toMatrixConvertsList() {
        java.util.List<float[]> list = java.util.Arrays.asList(
                new float[]{1f, 2f}, new float[]{3f, 4f});
        float[][] m = KMeansAdapter.toMatrix(list);
        assertEquals(2, m.length);
        assertArrayEquals(new float[]{1f, 2f}, m[0], 1e-6f);
        assertArrayEquals(new float[]{3f, 4f}, m[1], 1e-6f);
    }

    // ==================== 工具 ====================

    private static boolean anyCentroidClose(float[][] centroids, float[] target, float tolerance) {
        for (float[] c : centroids) {
            double sum = 0;
            for (int i = 0; i < c.length; i++) {
                double diff = c[i] - target[i];
                sum += diff * diff;
            }
            if (Math.sqrt(sum) < tolerance) return true;
        }
        return false;
    }
}
