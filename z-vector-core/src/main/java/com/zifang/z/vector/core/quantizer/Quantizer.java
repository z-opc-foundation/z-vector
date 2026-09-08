package com.zifang.z.vector.core.quantizer;

import com.zifang.z.vector.api.QuantizationType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 量化器 — 负责 float[] 向量与压缩字节数组之间的转换。
 * <p>
 * 设计参考 zvec 的量化器栈（FP16 / INT8 / RABITQ）+ Faiss 的 scalar quantizer + PQ.
 *
 * <h2>量化对比</h2>
 * <table>
 *   <tr><th>类型</th><th>压缩比</th><th>实现</th><th>精度损失</th></tr>
 *   <tr><td>NONE (FP32)</td><td>1x</td><td>原始存储</td><td>无</td></tr>
 *   <tr><td>FP16</td><td>2x</td><td>IEEE 754 半精度</td><td>极低（< 0.1%）</td></tr>
 *   <tr><td>INT8</td><td>4x</td><td>线性 min-max 量化</td><td>低（< 1%）</td></tr>
 *   <tr><td>PQ</td><td>16-32x</td><td>产品量化</td><td>中（< 5%）</td></tr>
 *   <tr><td>BINARY</td><td>32x</td><td>1-bit 二进制</td><td>高（用于粗筛）</td></tr>
 * </table>
 *
 * <h2>适用场景</h2>
 * <ul>
 *   <li><b>GPU 推理</b>：FP16 是 GPU 标配</li>
 *   <li><b>内存敏感</b>：INT8 平衡内存与精度</li>
 *   <li><b>十亿级</b>：PQ 必备</li>
 *   <li><b>粗筛</b>：BINARY 用于第一阶段过滤</li>
 * </ul>
 */
public interface Quantizer {

    /**
     * 将 float[] 编码为压缩字节数组
     *
     * @param vector 原始向量（dim 维）
     * @return 压缩后的字节数组
     */
    byte[] encode(float[] vector);

    /**
     * 将压缩字节数组解码为 float[] 向量
     */
    float[] decode(byte[] bytes);

    /**
     * 估算压缩字节数
     */
    int encodedSize(int dimension);

    /** 对应的量化类型 */
    QuantizationType type();

    /** 压缩比（与 FP32 相比） */
    default float compressionRatio() {
        return 4f * dimension() / encodedSize(dimension());
    }

    /** 维度（量化器构造时固定） */
    int dimension();
}