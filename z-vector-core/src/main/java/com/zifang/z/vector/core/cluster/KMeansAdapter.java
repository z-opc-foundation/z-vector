package com.zifang.z.vector.core.cluster;

import com.zifang.util.ml.clustering.KMeans;
import com.zifang.util.numpy.DType;
import com.zifang.util.numpy.NdArray;

import java.util.List;

/**
 * K-means 适配器 — 把 z-util-ml 的 {@link KMeans}（输入 NdArray / double）适配为 z-vector
 * 内部使用的 float[][] 接口。
 * <p>
 * 之所以需要这个适配层：
 * <ul>
 *   <li>z-util-ml 的 KMeans 仅暴露 {@code fit(NdArray)} / {@code predict(NdArray)} /
 *       {@code fitPredict(NdArray)}，不接受 float[][]；</li>
 *   <li>且 centroids 字段是 {@code private}，外部拿不到；</li>
 *   <li>z-vector 的 IVF / PQ 都用 float 精度，热路径希望保持 float 而非 double 转换开销。</li>
 * </ul>
 * 因此本适配器：float[][] → NdArray → fitPredict(labels) → 根据 labels 在 float 精度下重算 centroids。
 * <p>
 * 用例：
 * <ul>
 *   <li>{@link com.zifang.z.vector.core.index.IvfIndex} 的聚类（按 dim）</li>
 *   <li>{@link com.zifang.z.vector.core.quantizer.ProductQuantizer} 的每个子空间聚类（按 subspaceDim）</li>
 * </ul>
 */
public final class KMeansAdapter {

    private KMeansAdapter() {}

    /**
     * 对 float 向量集合做 K-means 聚类，返回 float[][] centroids（k × d）。
     *
     * @param vectors    n × d 的 float 向量矩阵
     * @param k          簇数量（k ≤ n；超过 n 会被截断为 n）
     * @param maxIter    最大迭代次数
     * @return k × d 的质心矩阵；若 n==0 返回空数组
     */
    public static float[][] cluster(float[][] vectors, int k, int maxIter) {
        if (vectors == null || vectors.length == 0) return new float[0][];
        int n = vectors.length;
        int d = vectors[0].length;
        if (k <= 0) throw new IllegalArgumentException("k must be > 0");
        if (k > n) k = n;

        // float → NdArray(FLOAT64)：z-util 内部用 double 跑 K-means
        NdArray x = toNdArray(vectors, n, d);

        // 用 z-util-ml 的 KMeans.fitPredict() 拿 labels
        KMeans kmeans = new KMeans(k, maxIter, 1e-4);
        int[] labels = kmeans.fitPredict(x);

        // 从 labels 在 float 精度下重算 centroids（避免依赖 z-util 私有字段）
        return computeCentroids(vectors, labels, k, d, n);
    }

    /** 与上面相同，但允许显式指定 tolerance */
    public static float[][] cluster(float[][] vectors, int k, int maxIter, double tolerance) {
        if (vectors == null || vectors.length == 0) return new float[0][];
        int n = vectors.length;
        int d = vectors[0].length;
        if (k <= 0) throw new IllegalArgumentException("k must be > 0");
        if (k > n) k = n;

        NdArray x = toNdArray(vectors, n, d);
        KMeans kmeans = new KMeans(k, maxIter, tolerance);
        int[] labels = kmeans.fitPredict(x);
        return computeCentroids(vectors, labels, k, d, n);
    }

    /**
     * 对查询向量做最近质心分配（用于增量分配）。
     *
     * @param centroids k × d 的质心矩阵
     * @param vector    d 维查询向量
     * @return 最近质心的索引
     */
    public static int assignNearest(float[][] centroids, float[] vector) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < centroids.length; i++) {
            double d = l2Squared(vector, centroids[i]);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    // ==================== 内部工具 ====================

    private static NdArray toNdArray(float[][] vectors, int n, int d) {
        double[] flat = new double[n * d];
        for (int i = 0; i < n; i++) {
            float[] row = vectors[i];
            for (int j = 0; j < d; j++) {
                flat[i * d + j] = row[j];
            }
        }
        return NdArray.array(flat, DType.FLOAT64).reshape(n, d);
    }

    /**
     * 根据 labels 在 float 精度下重算 centroids。
     * <p>
     * 空簇保留索引 0 的质心（兜底）。
     */
    private static float[][] computeCentroids(float[][] vectors, int[] labels,
                                              int k, int d, int n) {
        float[][] centroids = new float[k][d];
        int[] counts = new int[k];
        // 累加
        for (int i = 0; i < n; i++) {
            int c = labels[i];
            if (c < 0 || c >= k) continue; // 防御
            counts[c]++;
            float[] row = vectors[i];
            float[] centroid = centroids[c];
            for (int j = 0; j < d; j++) {
                centroid[j] += row[j];
            }
        }
        // 求平均
        for (int c = 0; c < k; c++) {
            if (counts[c] > 0) {
                float inv = 1.0f / counts[c];
                for (int j = 0; j < d; j++) {
                    centroids[c][j] *= inv;
                }
            } else {
                // 空簇：用第一个向量的拷贝兜底（避免返回全 0）
                if (n > 0) {
                    System.arraycopy(vectors[0], 0, centroids[c], 0, d);
                }
            }
        }
        return centroids;
    }

    private static double l2Squared(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }

    /**
     * 便捷重载：从 List&lt;float[]&gt; 转 float[][]（避免调用方手动展开）。
     */
    public static float[][] toMatrix(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) return new float[0][];
        float[][] m = new float[vectors.size()][];
        for (int i = 0; i < vectors.size(); i++) m[i] = vectors.get(i);
        return m;
    }
}
