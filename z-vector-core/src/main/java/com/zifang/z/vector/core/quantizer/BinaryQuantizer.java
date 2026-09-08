package com.zifang.z.vector.core.quantizer;

import com.zifang.z.vector.api.QuantizationType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 二进制量化器 — 1-bit per dimension。
 * <p>
 * 设计参考 zvec 的 binary_converter + Faiss 的 IndexBinaryFlat.
 *
 * <h2>原理</h2>
 * <pre>
 * 每个维度 x_i → b_i: x_i >= 0 则 1，否则 0
 * 32 个维度 → 1 个 int (4B)
 * dim 维向量 → dim/8 字节
 * </pre>
 *
 * <h2>距离</h2>
 * 使用汉明距离（不同位数）。z-vector 的 HammingDistance 与此配套。
 *
 * <h2>特点</h2>
 * <ul>
 *   <li>压缩比 32x（极致压缩）</li>
 *   <li>精度损失大（仅用于粗筛 + 二阶段精排）</li>
 *   <li>适合内存极度敏感、规模极大的场景</li>
 * </ul>
 */
public class BinaryQuantizer implements Quantizer {

    private final int dimension;

    public BinaryQuantizer(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public byte[] encode(float[] vector) {
        // 每 8 个维度 → 1 字节
        int byteCount = (dimension + 7) / 8;
        byte[] result = new byte[byteCount];
        for (int i = 0; i < dimension; i++) {
            if (vector[i] >= 0) {
                result[i >>> 3] |= (1 << (i & 7));
            }
        }
        return result;
    }

    @Override
    public float[] decode(byte[] bytes) {
        float[] result = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            int bit = (bytes[i >>> 3] >> (i & 7)) & 1;
            result[i] = bit == 1 ? 1.0f : -1.0f;
        }
        return result;
    }

    @Override
    public int encodedSize(int dim) {
        return (dim + 7) / 8;
    }

    @Override
    public QuantizationType type() { return QuantizationType.BINARY; }

    @Override
    public int dimension() { return dimension; }

    /** 计算两个二进制向量的汉明距离 */
    public int hammingDistance(byte[] a, byte[] b) {
        int dist = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int xor = (a[i] ^ b[i]) & 0xFF;
            dist += Integer.bitCount(xor);
        }
        return dist;
    }
}