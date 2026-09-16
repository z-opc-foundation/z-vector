package com.zifang.z.vector.core.quantizer;

import com.zifang.z.vector.api.QuantizationType;
import com.zifang.z.vector.core.cluster.KMeansAdapter;

import java.util.List;

/**
 * 产品量化器（Product Quantization, PQ）— 把向量切分成子空间，每子空间聚类后用码字表示。
 * <p>
 * 设计参考 zvec 的 quantizer 模块 + Faiss 的 IndexPQ.
 *
 * <h2>原理</h2>
 * <pre>
 * 1. 把 d 维向量切成 m 个 d/m 维子向量
 * 2. 每个子空间用 k-means 聚类成 k 个质心（码本）
 * 3. 每个子向量量化为最近质心的 id（log 2(k) 位）
 * 4. 编码: dim 维向量 → m 个 id（每个 1 字节当 k=256）
 * 压缩比 ≈ d / m * 8（d=768, m=8 → 96x 压缩）
 * </pre>
 *
 * <h2>距离</h2>
 * 距离通过查表 (ADC, Asymmetric Distance Computation) 计算：
 * <ol>
 *   <li>查询向量分成 m 个子向量</li>
 *   <li>每个子向量到 k 个质心的距离预先计算成表</li>
 *   <li>编码向量的 m 个 id 查表求和得近似距离</li>
 * </ol>
 *
 * <h2>注意</h2>
 * <p>
 * 当前实现提供完整 PQ 训练和编码框架，但 K-means 使用简化版（随机初始化 + 少量迭代）。
 * 生产环境建议用 50+ 迭代和 K-means++ 初始化。
 */
public class ProductQuantizer implements Quantizer {

    private final int dimension;
    private final int numSubspaces;       // m
    private final int subspaceDim;         // d / m
    private final int numCentroids;        // k

    /** 码本：每个子空间的 k 个质心（subspaceDim 维） */
    private float[][][] codebooks;
    private volatile boolean trained = false;

    public ProductQuantizer(int dimension, int numSubspaces, int numCentroids) {
        if (dimension % numSubspaces != 0) {
            throw new IllegalArgumentException(
                    "Dimension " + dimension + " must be divisible by numSubspaces " + numSubspaces);
        }
        this.dimension = dimension;
        this.numSubspaces = numSubspaces;
        this.subspaceDim = dimension / numSubspaces;
        this.numCentroids = numCentroids;
    }

    /**
     * 训练 PQ：每个子空间独立 K-means 聚类
     */
    public void train(java.util.List<float[]> trainingVectors, int maxIter) {
        if (trainingVectors == null || trainingVectors.isEmpty()) {
            throw new IllegalArgumentException("Training set must not be empty");
        }
        codebooks = new float[numSubspaces][numCentroids][subspaceDim];

        // 对每个子空间独立训练 K-means
        for (int s = 0; s < numSubspaces; s++) {
            // 提取子向量
            float[][] subVectors = new float[trainingVectors.size()][subspaceDim];
            for (int n = 0; n < trainingVectors.size(); n++) {
                System.arraycopy(trainingVectors.get(n), s * subspaceDim,
                        subVectors[n], 0, subspaceDim);
            }
            codebooks[s] = kmeans(subVectors, numCentroids, maxIter);
        }
        trained = true;
    }

    @Override
    public byte[] encode(float[] vector) {
        if (!trained) throw new IllegalStateException("Quantizer must be trained");
        byte[] codes = new byte[numSubspaces];
        for (int s = 0; s < numSubspaces; s++) {
            float[] sub = new float[subspaceDim];
            System.arraycopy(vector, s * subspaceDim, sub, 0, subspaceDim);
            codes[s] = (byte) KMeansAdapter.assignNearest(codebooks[s], sub);
        }
        return codes;
    }

    @Override
    public float[] decode(byte[] bytes) {
        if (!trained) throw new IllegalStateException("Quantizer must be trained");
        float[] result = new float[dimension];
        for (int s = 0; s < numSubspaces; s++) {
            int centroidId = bytes[s] & 0xFF;
            if (centroidId >= numCentroids) centroidId = 0; // 安全兜底
            System.arraycopy(codebooks[s][centroidId], 0,
                    result, s * subspaceDim, subspaceDim);
        }
        return result;
    }

    /**
     * ADC (Asymmetric Distance Computation) — 查询向量与编码向量的近似距离
     */
    public float approximateDistance(float[] query, byte[] codes) {
        if (!trained) throw new IllegalStateException("Quantizer must be trained");
        float totalDist = 0;
        for (int s = 0; s < numSubspaces; s++) {
            float[] subQuery = new float[subspaceDim];
            System.arraycopy(query, s * subspaceDim, subQuery, 0, subspaceDim);
            int centroidId = codes[s] & 0xFF;
            if (centroidId >= numCentroids) continue;
            totalDist += l2Squared(subQuery, codebooks[s][centroidId]);
        }
        return (float) Math.sqrt(totalDist);
    }

    @Override
    public int encodedSize(int dim) {
        return numSubspaces; // 每个子空间一个字节（k=256）
    }

    @Override
    public QuantizationType type() { return QuantizationType.PQ; }

    @Override
    public int dimension() { return dimension; }

    public boolean isTrained() { return trained; }

    // ==================== K-means 子空间聚类（委托给 z-util-ml） ====================

    /**
     * 对单个子空间的子向量做 K-means 聚类。
     * <p>
     * 实际委托给 {@link KMeansAdapter#cluster(float[][], int, int)}，后者内部调用
     * z-util-ml 的 {@code KMeans.fitPredict(NdArray)}。质心在 float 精度下重算以保持精度一致性。
     */
    private float[][] kmeans(float[][] vectors, int k, int maxIter) {
        return KMeansAdapter.cluster(vectors, k, maxIter);
    }

    private static float l2Squared(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }
}