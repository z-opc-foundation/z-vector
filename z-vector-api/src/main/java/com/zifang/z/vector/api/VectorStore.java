package com.zifang.z.vector.api;

import java.util.List;
import java.util.Map;

/**
 * 向量存储接口 — 向量数据库的核心抽象。
 * <p>
 * 本接口对标 Milvus SDK、Qdrant REST API、zvec Python SDK 的核心功能:
 * <ul>
 *   <li>Collection 生命周期管理（create/get/delete/list/has）</li>
 *   <li>向量点 CRUD（upsert/get/delete/count）</li>
 *   <li>ANN 搜索（topK + 范围搜索 + payload 过滤）</li>
 *   <li>索引管理（构建/切换/查询）</li>
 *   <li>持久化（flush/snapshot）</li>
 * </ul>
 * <p>
 * 默认实现见 {@code z-vector-core} 模块；具体存储后端见 {@code z-vector-storage}。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * VectorStore store = VectorStoreFactory.inMemory();
 * store.createCollection("docs", 768, DistanceMetric.COSINE);
 * store.upsert("docs", new VectorPoint("doc-1", new float[768], Map.of("lang", "zh")));
 * List<SearchResult> hits = store.search("docs", query, 10, Filter.eq("lang", "zh"));
 * }</pre>
 */
public interface VectorStore {

    // ==================== Collection 管理 ====================

    /**
     * 创建集合（使用默认索引类型 Flat，适合小数据集精确查询）
     */
    void createCollection(String name, int dimension, DistanceMetric metric);

    /**
     * 创建集合（指定索引类型与参数）
     *
     * @param name       集合名（同一 store 内唯一）
     * @param dimension  向量维度（> 0）
     * @param metric     距离度量
     * @param indexType  索引类型（HNSW/IVF/FLAT）
     * @param indexParams 索引参数（null 则使用默认值）
     */
    void createCollection(String name, int dimension, DistanceMetric metric,
                          IndexType indexType, Map<String, Object> indexParams);

    /** 获取集合信息（不存在返回 null） */
    VectorCollection getCollection(String name);

    /** 删除集合（包括所有向量、索引、payload） */
    boolean deleteCollection(String name);

    /** 列出所有集合名 */
    List<String> listCollections();

    /** 集合是否存在 */
    boolean hasCollection(String name);

    // ==================== 向量 CRUD ====================

    /**
     * 插入/更新单个向量点（id 已存在则覆盖）
     */
    void upsert(String collectionName, VectorPoint point);

    /** 批量插入/更新（事务性：要么全成功要么全失败） */
    void upsertBatch(String collectionName, List<VectorPoint> points);

    /** 按 ID 获取向量点（含 payload），不存在返回 null */
    VectorPoint getPoint(String collectionName, String id);

    /** 按 ID 删除 */
    boolean deletePoint(String collectionName, String id);

    /** 批量删除 */
    void deletePoints(String collectionName, List<String> ids);

    /** 获取集合内的点数量 */
    long getPointCount(String collectionName);

    // ==================== ANN 搜索 ====================

    /**
     * 最近邻搜索 — 返回最相似的 topK 个向量
     *
     * @param collectionName 集合名
     * @param queryVector    查询向量（维度须与集合一致）
     * @param topK           返回数量
     * @param filter         可选 payload 过滤条件（null 表示不过滤）
     * @return 按相似度排序的结果列表
     */
    List<SearchResult> search(String collectionName, float[] queryVector, int topK, Filter filter);

    /**
     * 范围搜索 — 返回距离阈值内的所有向量
     *
     * @param distanceThreshold 最大距离阈值（L2 / COSINE）
     * @param topK              最大返回数量
     */
    List<SearchResult> searchRange(String collectionName, float[] queryVector,
                                   float distanceThreshold, int topK, Filter filter);

    /**
     * 批量搜索 — 一次查询多个向量
     *
     * @param queryVectors 查询向量数组（每个维度须一致）
     * @param topK         每个返回数量
     */
    List<List<SearchResult>> searchBatch(String collectionName, List<float[]> queryVectors,
                                         int topK, Filter filter);

    // ==================== 索引 ====================

    /** 构建索引（HNSW / IVF 由实现决定何时构建；FLAT 无需构建） */
    void buildIndex(String collectionName);

    /** 索引是否已构建 */
    boolean isIndexed(String collectionName);

    /** 获取当前索引类型 */
    IndexType getIndexType(String collectionName);

    // ==================== 持久化 ====================

    /** 刷盘（将 WAL 中未持久化的数据写入磁盘） */
    void flush(String collectionName);

    /** 关闭 store（释放所有资源，包括 WAL、mmap、线程池） */
    void close();

    /** store 是否已关闭 */
    boolean isClosed();
}