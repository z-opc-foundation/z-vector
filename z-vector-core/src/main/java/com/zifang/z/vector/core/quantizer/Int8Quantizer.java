package com.zifang.z.vector.core.quantizer;

import com.zifang.z.vector.api.QuantizationType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * INT8 标量量化器 — 线性 min-max 量化。
 * <p>
 * 设计参考 zvec 的 uniform_int8_converter + Faiss 的 scalar quantizer.
 *
 * <h2>原理</h2>
 * <pre>
 * 训练阶段: 遍历所有向量，记录每个维度的 [min_i, max_i]
 * 编码:    q_i = round((x_i - min_i) / (max_i - min_i) * 255)  (clamp 到 [0, 255])
 * 解码:    x_i ≈ q_i / 255 * (max_i - min_i) + min_i
 * </pre>
 *
 * <h2>特点</h2>
 * <ul>
 *   <li>压缩比 4x（FP32 → INT8）</li>
 *   <li>精度损失 &lt; 1%（适合通用 ANN）</li>
 *   <li>需要训练阶段统计 min/max（首次训练时调用 {@link #train}）</li>
 *   <li>训练后可对所有向量统一量化</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * Int8Quantizer q = new Int8Quantizer(768);
 * q.train(allVectors);     // 训练：统计 min/max
 * byte[] compressed = q.encode(vec);  // 编码
 * float[] decoded = q.decode(compressed);  // 解码（损失 < 1%）
 * }</pre>
 */
public class Int8Quantizer implements Quantizer {

    private final int dimension;
    private float[] minValues;
    private float[] maxValues;
    private volatile boolean trained = false;

    public Int8Quantizer(int dimension) {
        this.dimension = dimension;
    }

    /**
     * 训练阶段：遍历训练数据统计 min/max。
     * <p>
     * 注意：<b>调用方</b>负责保证线程安全。
     */
    public void train(java.util.List<float[]> trainingVectors) {
        if (trainingVectors == null || trainingVectors.isEmpty()) {
            throw new IllegalArgumentException("Training set must not be empty");
        }
        minValues = new float[dimension];
        maxValues = new float[dimension];
        java.util.Arrays.fill(minValues, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(maxValues, Float.NEGATIVE_INFINITY);

        for (float[] vec : trainingVectors) {
            for (int i = 0; i < dimension; i++) {
                float v = vec[i];
                if (v < minValues[i]) minValues[i] = v;
                if (v > maxValues[i]) maxValues[i] = v;
            }
        }
        // 避免除零：max == min 时范围设为 1
        for (int i = 0; i < dimension; i++) {
            if (maxValues[i] - minValues[i] < 1e-9f) {
                maxValues[i] = minValues[i] + 1f;
            }
        }
        trained = true;
    }

    @Override
    public byte[] encode(float[] vector) {
        if (!trained) {
            throw new IllegalStateException("Quantizer must be trained before encoding");
        }
        ByteBuffer bb = ByteBuffer.allocate(dimension).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dimension; i++) {
            float range = maxValues[i] - minValues[i];
            float normalized = (vector[i] - minValues[i]) / range;
            int q = Math.round(normalized * 255f);
            if (q < 0) q = 0;
            if (q > 255) q = 255;
            bb.put((byte) q);
        }
        return bb.array();
    }

    @Override
    public float[] decode(byte[] bytes) {
        if (!trained) {
            throw new IllegalStateException("Quantizer must be trained before decoding");
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            int q = bb.get() & 0xFF;
            float range = maxValues[i] - minValues[i];
            result[i] = q / 255f * range + minValues[i];
        }
        return result;
    }

    /**
     * 量化向量之间的距离（量化空间内的 L2 距离近似）
     * <p>
     * 这是 INT8 检索的核心优化：直接在 uint8 空间内计算距离，避免反量化。
     * 对于 L2：d(q_a, q_b) ≈ (range/255)^2 * L2(q_a, q_b) + 偏移量
     */
    public float quantizedL2(byte[] bytesA, byte[] bytesB) {
        if (!trained) throw new IllegalStateException("Quantizer must be trained");
        long sumSq = 0;
        for (int i = 0; i < dimension; i++) {
            int qa = bytesA[i] & 0xFF;
            int qb = bytesB[i] & 0xFF;
            int d = qa - qb;
            sumSq += d * d;
        }
        // 缩放到原始向量空间
        float avgRange = 0;
        for (int i = 0; i < dimension; i++) {
            avgRange += (maxValues[i] - minValues[i]);
        }
        avgRange /= dimension;
        float scale = avgRange / 255f;
        return (float) Math.sqrt(sumSq) * scale;
    }

    @Override
    public int encodedSize(int dim) { return dim; }

    @Override
    public QuantizationType type() { return QuantizationType.INT8; }

    @Override
    public int dimension() { return dimension; }

    public boolean isTrained() { return trained; }

    /** 获取训练后的 min/max（用于持久化） */
    public float[] getMinValues() { return minValues; }
    public float[] getMaxValues() { return maxValues; }
}