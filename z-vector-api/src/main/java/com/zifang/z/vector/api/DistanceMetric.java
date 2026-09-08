package com.zifang.z.vector.api;

/**
 * 距离度量类型 — 决定向量之间的相似度计算方式。
 * <p>
 * 参考 Milvus / Qdrant / zvec 的设计，提供 4 种工业级距离度量。
 *
 * <h2>距离语义统一</h2>
 * 所有度量在内部被统一为 <b>"越小越近"</b> 的距离表示:
 * <ul>
 *   <li>L2: 直接返回欧氏距离</li>
 *   <li>IP: 返回 -inner_product（取负使越大越近转为越小越近）</li>
 *   <li>COSINE: 返回 1 - cosine_similarity（转距离）</li>
 *   <li>HAMMING: 返回汉明距离（不同位数）</li>
 * </ul>
 */
public enum DistanceMetric {
    /** 欧氏距离（越小越近） */
    L2,
    /** 内积（越大越近） */
    IP,
    /** 余弦相似度（越大越近） */
    COSINE,
    /** 汉明距离（越小越近，用于二进制向量） */
    HAMMING
}