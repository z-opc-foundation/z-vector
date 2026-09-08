package com.zifang.z.vector.core.quantizer;

import com.zifang.z.vector.api.QuantizationType;

/**
 * 量化器工厂 — 根据 QuantizationType 创建对应的量化器实例。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 无训练
 * Quantizer q = QuantizerFactory.create(QuantizationType.FP16, 768);
 *
 * // 需要训练的（INT8 / PQ）
 * Int8Quantizer q = (Int8Quantizer) QuantizerFactory.create(QuantizationType.INT8, 768);
 * q.train(allVectors);
 * }</pre>
 */
public final class QuantizerFactory {

    private QuantizerFactory() {}

    public static Quantizer create(QuantizationType type, int dimension) {
        switch (type) {
            case NONE:
                return null; // NONE 表示不量化
            case FP16:
                return new Fp16Quantizer(dimension);
            case INT8:
                return new Int8Quantizer(dimension);
            case PQ:
                // 默认 8 个子空间，256 个质心
                int m = 8;
                if (dimension % m != 0) {
                    // 找最近的能整除的数
                    for (int i = 4; i <= 16; i *= 2) {
                        if (dimension % i == 0) { m = i; break; }
                    }
                }
                return new ProductQuantizer(dimension, m, 256);
            case BINARY:
                return new BinaryQuantizer(dimension);
            default:
                throw new IllegalArgumentException("Unsupported quantization type: " + type);
        }
    }
}