package com.zifang.z.vector.core.quantizer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 量化器单元测试 — 覆盖 FP16 / INT8 / BINARY / PQ 4 种量化器。
 */
class QuantizerTest {

    @Test
    void fp16EncodeDecodeRoundtrip() {
        Fp16Quantizer q = new Fp16Quantizer(4);
        float[] vec = {1.0f, -2.5f, 0.001f, 100.5f};
        byte[] encoded = q.encode(vec);
        float[] decoded = q.decode(encoded);

        assertEquals(4 * 2, encoded.length);
        for (int i = 0; i < vec.length; i++) {
            // FP16 精度有限，大值误差容忍放宽
            assertEquals(vec[i], decoded[i], Math.max(0.01f, Math.abs(vec[i]) * 0.01f));
        }
    }

    @Test
    void fp16CompressionRatio() {
        Fp16Quantizer q = new Fp16Quantizer(768);
        assertEquals(2f, q.compressionRatio(), 0.01f);
    }

    @Test
    void int8EncodeDecode() {
        Int8Quantizer q = new Int8Quantizer(4);
        List<float[]> training = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < 100; i++) {
            float[] v = new float[4];
            for (int j = 0; j < 4; j++) v[j] = r.nextFloat() * 10;
            training.add(v);
        }
        q.train(training);

        float[] vec = {3.5f, 7.2f, 1.0f, 8.8f};
        byte[] encoded = q.encode(vec);
        float[] decoded = q.decode(encoded);

        assertEquals(4, encoded.length); // 4 dim → 4 bytes
        for (int i = 0; i < vec.length; i++) {
            // INT8 精度损失 < 5%
            assertEquals(vec[i], decoded[i], Math.max(0.5f, Math.abs(vec[i]) * 0.05f));
        }
    }

    @Test
    void int8QuantizedL2() {
        Int8Quantizer q = new Int8Quantizer(4);
        List<float[]> training = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < 100; i++) {
            float[] v = new float[4];
            for (int j = 0; j < 4; j++) v[j] = r.nextFloat();
            training.add(v);
        }
        q.train(training);

        float[] a = {0.5f, 0.3f, 0.7f, 0.1f};
        float[] b = {0.5f, 0.4f, 0.7f, 0.2f};
        byte[] qa = q.encode(a);
        byte[] qb = q.encode(b);
        float dist = q.quantizedL2(qa, qb);
        assertTrue(dist > 0);
        assertTrue(dist < 1.0f); // 接近但有偏差
    }

    @Test
    void int8UntrainedThrows() {
        Int8Quantizer q = new Int8Quantizer(4);
        assertThrows(IllegalStateException.class,
                () -> q.encode(new float[]{1, 2, 3, 4}));
    }

    @Test
    void binaryEncodeDecode() {
        BinaryQuantizer q = new BinaryQuantizer(16);
        float[] vec = new float[16];
        for (int i = 0; i < 16; i++) vec[i] = (i % 2 == 0) ? 1.0f : -1.0f;

        byte[] encoded = q.encode(vec);
        assertEquals(2, encoded.length); // 16 bit = 2 bytes

        float[] decoded = q.decode(encoded);
        for (int i = 0; i < 16; i++) {
            // 正数 → 1，负数 → -1
            assertEquals(vec[i] > 0 ? 1.0f : -1.0f, decoded[i], 0.01f);
        }
    }

    @Test
    void binaryCompressionRatio() {
        BinaryQuantizer q = new BinaryQuantizer(768);
        assertEquals(32f, q.compressionRatio(), 0.01f);
    }

    @Test
    void binaryHammingDistance() {
        BinaryQuantizer q = new BinaryQuantizer(8);
        byte[] a = {(byte) 0xFF}; // 11111111
        byte[] b = {(byte) 0x00}; // 00000000
        assertEquals(8, q.hammingDistance(a, b));

        byte[] c = {(byte) 0xFF}; // 11111111
        byte[] d = {(byte) 0x0F}; // 00001111
        assertEquals(4, q.hammingDistance(c, d)); // 前4位不同
    }

    @Test
    void pqEncodeDecode() {
        // 8 维，分 2 个子空间，每空间 4 维，k=4
        ProductQuantizer q = new ProductQuantizer(8, 2, 4);

        List<float[]> training = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < 100; i++) {
            float[] v = new float[8];
            for (int j = 0; j < 8; j++) v[j] = r.nextFloat() * 10;
            training.add(v);
        }
        q.train(training, 10);

        float[] vec = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] encoded = q.encode(vec);
        assertEquals(2, encoded.length); // 2 个子空间 → 2 bytes

        float[] decoded = q.decode(encoded);
        assertEquals(8, decoded.length);
        // PQ 解码后用码字表示，可能与原向量有偏差但应有形状相似性
    }

    @Test
    void pqApproximateDistance() {
        ProductQuantizer q = new ProductQuantizer(8, 2, 4);
        List<float[]> training = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < 100; i++) {
            float[] v = new float[8];
            for (int j = 0; j < 8; j++) v[j] = r.nextFloat();
            training.add(v);
        }
        q.train(training, 10);

        float[] query = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
        float[] vec = {0.6f, 0.4f, 0.6f, 0.4f, 0.6f, 0.4f, 0.6f, 0.4f};
        byte[] encoded = q.encode(vec);
        float dist = q.approximateDistance(query, encoded);
        assertTrue(dist > 0);
    }

    @Test
    void factoryCreatesCorrectQuantizers() {
        assertNull(QuantizerFactory.create(com.zifang.z.vector.api.QuantizationType.NONE, 768));
        assertEquals(com.zifang.z.vector.api.QuantizationType.FP16,
                QuantizerFactory.create(com.zifang.z.vector.api.QuantizationType.FP16, 768).type());
        assertEquals(com.zifang.z.vector.api.QuantizationType.INT8,
                QuantizerFactory.create(com.zifang.z.vector.api.QuantizationType.INT8, 768).type());
        assertEquals(com.zifang.z.vector.api.QuantizationType.PQ,
                QuantizerFactory.create(com.zifang.z.vector.api.QuantizationType.PQ, 768).type());
        assertEquals(com.zifang.z.vector.api.QuantizationType.BINARY,
                QuantizerFactory.create(com.zifang.z.vector.api.QuantizationType.BINARY, 768).type());
    }

    @Test
    void fp16SpecialValues() {
        // 测试 0、负 0、INF、NaN
        float[] vec = {0f, -0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.NaN, 1e-30f, 1e30f};
        Fp16Quantizer q = new Fp16Quantizer(vec.length);
        byte[] encoded = q.encode(vec);
        float[] decoded = q.decode(encoded);
        // 0 应该完美还原
        assertEquals(0f, decoded[0], 1e-10f);
        assertEquals(0f, decoded[1], 1e-10f);
        // INF 应该保留为 INF
        assertTrue(Float.isInfinite(decoded[2]));
        assertTrue(Float.isInfinite(decoded[3]));
    }
}