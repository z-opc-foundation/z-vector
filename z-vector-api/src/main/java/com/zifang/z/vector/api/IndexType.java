package com.zifang.z.vector.api;

/**
 * ANN 索引类型 — 决定集合的搜索算法。
 * <p>
 * 对标 zvec 的 7 类索引（Flat/HNSW/IVF/DiskANN/Vamana/HNSW_RABITQ/HNSW_Sparse），
 * 本系统提供 3 种核心索引，复杂度从低到高:
 * <ul>
 *   <li>{@link #FLAT}: 暴力精确搜索，O(N*d)，适合 < 10k 小数据集</li>
 *   <li>{@link #HNSW}: 分层可导航小世界图，O(log N * d)，推荐用于 < 10M 数据集</li>
 *   <li>{@link #IVF}: 倒排文件（K-means 聚类），适合中等规模数据集</li>
 * </ul>
 *
 * <h2>索引选择策略</h2>
 * <table>
 *   <tr><th>数据集规模</th><th>推荐索引</th><th>召回率</th><th>查询时延</th></tr>
 *   <tr><td>&lt; 10K</td><td>FLAT</td><td>100%</td><td>毫秒级</td></tr>
 *   <tr><td>10K ~ 1M</td><td>HNSW</td><td>95%+</td><td>毫秒级</td></tr>
 *   <tr><td>1M ~ 10M</td><td>HNSW / IVF</td><td>90%+</td><td>毫秒级</td></tr>
 *   <tr><td>&gt; 10M</td><td>IVF + PQ 量化</td><td>85%+</td><td>毫秒级</td></tr>
 * </table>
 */
public enum IndexType {
    /** 暴力精确搜索 */
    FLAT,
    /** 分层可导航小世界图（基于磁盘的近似最近邻） */
    HNSW,
    /** 倒排文件 + K-means 聚类 */
    IVF
}