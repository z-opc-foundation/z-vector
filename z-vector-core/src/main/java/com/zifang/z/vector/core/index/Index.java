package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;

import java.util.List;
import java.util.Map;

/**
 * ANN 索引抽象 — 所有索引算法的统一接口。
 * <p>
 * 设计参考 zvec 的 {@code IndexBase} + Faiss 的 {@code Index} + LanceDB 的 {@code Index}.
 *
 * <h2>生命周期</h2>
 * <pre>{@code
 * Index idx = IndexFactory.create(IndexType.HNSW, distance, 768);
 * idx.build(points);                  // 一次性 build（FLAT 无需 build）
 * List<SearchResult> hits = idx.search(query, 10, filter, maxDistance);
 * idx.add(point);                     // 动态插入（HNSW 支持，IVF 需重建）
 * idx.remove(pointId);                // 软删除
 * }</pre>
 */
public interface Index {

    /** 索引类型 */
    String type();

    /** 底层距离维度（构造时确定） */
    int dimension();

    /** 当前索引中的向量数量 */
    int size();

    /**
     * 一次性构建索引 — 接收所有向量点，构造内部数据结构
     *
     * @param points 向量点集合（不可变快照）
     */
    void build(List<VectorPoint> points);

    /**
     * 动态插入单个点（部分索引如 HNSW 支持，IVF 不支持）
     */
    void add(VectorPoint point);

    /** 按 id 删除（逻辑删除，索引中保留 tombstone） */
    boolean remove(String id);

    /**
     * 最近邻搜索
     *
     * @param query         查询向量
     * @param topK          返回数量
     * @param filterPayload payload 过滤（null 不过滤）
     * @param maxDistance   最大距离阈值（Float.MAX_VALUE 表示无限制）
     * @return 按距离排序的 topK 结果
     */
    List<SearchResult> search(float[] query, int topK,
                              Map<String, Object> filterPayload,
                              float maxDistance);

    /**
     * 范围搜索 — 返回距离 ≤ maxDistance 的所有点
     */
    List<SearchResult> searchRange(float[] query, float maxDistance, int topK,
                                   Map<String, Object> filterPayload);

    /** 索引是否已构建 */
    boolean isBuilt();

    /** 按 id 获取向量点（null 表示不存在） */
    VectorPoint get(String id);

    /**
     * {@link #get(String)} 的借用版 —— 给"按 id 回表只为读一眼向量/payload"的内部热路径用
     * （过滤搜索逐条命中、upsert/delete 取旧 payload 摘倒排）。
     * <p>
     * 约定同 {@code VectorPoint.ref}：返回的点里数组与 map 是存储本体，调用方不得修改、不得
     * 长期持有、不得把它交给外部。默认实现退回 {@link #get(String)}（Flat/IVF 的 {@code get}
     * 本来就返回存好的点，没有可省的拷贝）；只有真正把点重建出来的索引覆盖它才有意义。
     */
    default VectorPoint getRef(String id) {
        return get(id);
    }

    /**
     * 获取所有当前向量点（用于索引重建）
     */
    java.util.List<VectorPoint> entries();

    /** 清空索引 */
    void clear();
}