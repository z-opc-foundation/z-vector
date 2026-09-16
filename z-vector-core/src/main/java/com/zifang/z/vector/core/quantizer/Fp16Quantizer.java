package com.zifang.z.vector.core.quantizer;

import com.zifang.z.vector.api.QuantizationType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FP16 量化器 — IEEE 754 半精度浮点。
 * <p>
 * 设计参考 zvec 的 half_float_converter + TensorRT 的 FP16 优化.
 *
 * <h2>原理</h2>
 * <ul>
 *   <li>每个 float 转换为 2 字节（sign 1B + exponent 5B + mantissa 10B）</li>
 *   <li>压缩比 2x，几乎无精度损失（精度降低 < 0.1%）</li>
 *   <li>GPU 推理标准格式，可与 TensorRT / ONNX Runtime 无缝对接</li>
 * </ul>
 *
 * <h2>实现要点</h2>
 * <p>
 * 我们用纯 Java 实现半精度转换（基于 IEEE 754 规则），避免引入额外依赖。
 */
public class Fp16Quantizer implements Quantizer {

    private final int dimension;

    public Fp16Quantizer(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public byte[] encode(float[] vector) {
        ByteBuffer bb = ByteBuffer.allocate(dimension * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dimension; i++) {
            bb.putShort(floatToHalf(vector[i]));
        }
        return bb.array();
    }

    @Override
    public float[] decode(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            result[i] = halfToFloat(bb.getShort());
        }
        return result;
    }

    @Override
    public int encodedSize(int dim) { return dim * 2; }

    @Override
    public QuantizationType type() { return QuantizationType.FP16; }

    @Override
    public int dimension() { return dimension; }

    // ==================== IEEE 754 Half-Precision 转换 ====================

    /**
     * float 转 half — 标准 IEEE 754 算法
     */
    static short floatToHalf(float f) {
        int fbits = Float.floatToRawIntBits(f);
        int sign = (fbits >>> 16) & 0x8000;
        int val = (fbits & 0x7FFFFFFF) + 0x1000; // 上舍入偏置

        if (val >= 0x47800000) { // 可能是 INF / NaN
            if ((fbits & 0x7FFFFFFF) >= 0x47800000) {
                if (val < 0x7F800000) return (short) (sign | 0x7C00); // INF
                return (short) (sign | 0x7C00 | (((fbits & 0x007FFFFF) >>> 13)));
            }
            return (short) (sign | 0x7BFF); // 最大有界值
        }
        if (val >= 0x38800000) { // 归一化值
            return (short) (sign | ((val - 0x38000000) >>> 13));
        }
        if (val < 0x33000000) { // 太小 → 0
            return (short) sign;
        }
        // 非归一化值
        val = (fbits & 0x7FFFFFFF) >>> 23;
        return (short) (sign | ((((fbits & 0x7FFFFF) | 0x800000)
                + (0x800000 >>> (val - 102))) >>> (126 - val)));
    }

    /**
     * half 转 float
     */
    static float halfToFloat(short h) {
        int hbits = h & 0xFFFF;
        int sign = (hbits & 0x8000) << 16;
        int val = hbits & 0x7FFF;

        if (val > 0x7BFF) { // NaN 或 INF（指数 = 全1）
            if (val == 0x7C00) {
                return Float.intBitsToFloat(sign | 0x7F800000); // ±INF
            }
            return Float.intBitsToFloat(sign | 0x7F800000 | ((val & 0x03FF) << 13)); // NaN
        }
        if (val == 0) {
            return Float.intBitsToFloat(sign); // ±0
        }
        if (val < 0x0400) { // 非归一化 (subnormal)
            // 转换为归一化
            while ((val & 0x0400) == 0) {
                val <<= 1;
                val += 1;
            }
            val &= 0x03FF;
            return Float.intBitsToFloat(sign | (0x38800000 | (val << 13)));
        }
        // 归一化值 (normal)
        return Float.intBitsToFloat(sign | ((val + 0x1C000) << 13));
    }
}