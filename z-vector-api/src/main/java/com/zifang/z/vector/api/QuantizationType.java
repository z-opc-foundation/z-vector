package com.zifang.z.vector.api;

/**
 * 量化类型 — 决定向量在内存/磁盘上的压缩存储方式。
 * <p>
 * 对标 zvec 的 4 类量化（FP32/FP16/INT8/RaBitQ）和 Faiss 的 PQ/SQ/BQ。
 *
 * <h2>量化对比</h2>
 * <table>
 *   <tr><th>类型</th><th>压缩比</th><th>精度损失</th><th>适用场景</th></tr>
 *   <tr><td>NONE (FP32)</td><td>1x</td><td>无</td><td>精确检索</td></tr>
 *   <tr><td>FP16</td><td>2x</td><td>极低</td><td>通用 GPU 加速</td></tr>
 *   <tr><td>INT8</td><td>4x</td><td>低</td><td>内存敏感场景</td></tr>
 *   <tr><td>PQ (Product Quantization)</td><td>16-32x</td><td>中</td><td>十亿级向量</td></tr>
 *   <tr><td>BINARY (1-bit)</td><td>32x</td><td>高</td><td>粗筛场景</td></tr>
 * </table>
 */
public enum QuantizationType {
    /** 不量化（FP32 全精度） */
    NONE,
    /** 16 位浮点（半精度） */
    FP16,
    /** 8 位整数（标量量化） */
    INT8,
    /** 产品量化（向量子空间量化） */
    PQ,
    /** 1-bit 二进制量化 */
    BINARY
}