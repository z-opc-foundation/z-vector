package com.zifang.z.vector.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向量搜索结果 — 包含最近邻 ID + 距离分数 + 可选 payload。
 * <p>
 * 距离分数语义:
 * <ul>
 *   <li>L2: 越小越近</li>
 *   <li>IP (Inner Product): 越大越近（已取负号，越小越近）</li>
 *   <li>COSINE: 越大越相似（已转距离 = 1 - cos_sim，越小越近）</li>
 *   <li>HAMMING: 越小越近（用于二进制向量）</li>
 * </ul>
 */
public class SearchResult {

    private final String vectorId;
    private final float score;
    /**
     * 已经是不可变视图，所以构造时包一次就够。
     * <p>
     * 空 payload 一律指向 {@link Collections#EMPTY_MAP} 这个单例：HNSW 查询期每个进 beam 的
     * 候选都要造 SearchResult，而候选的 payload 恒为空 —— 每个实例挂一个新
     * {@code LinkedHashMap}，一次 ef=64 的查询就要多分配约 9 KB 空壳。
     */
    private final Map<String, Object> payload;

    public SearchResult(String vectorId, float score) {
        this(vectorId, score, null);
    }

    public SearchResult(String vectorId, float score, Map<String, Object> payload) {
        this.vectorId = vectorId;
        this.score = score;
        // 非空才复制：保住"结果不随调用方之后改自己那张 map 而漂移"的语义，同时让空 payload 零分配。
        this.payload = (payload == null || payload.isEmpty())
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(payload));
    }

    public String getVectorId() { return vectorId; }
    public float getScore() { return score; }

    /**
     * 不可变的 payload 视图（无 payload 时为空 map，不为 null）。
     * <p>
     * 视图在构造时包好，这里直接返回字段：以前每次调用都要 new 一个
     * {@code Collections.unmodifiableMap} 壳，而过滤路径会把同一个结果的 payload 读好几遍。
     */
    public Map<String, Object> getPayload() { return payload; }

    @Override
    public String toString() {
        return String.format("SearchResult{id='%s', score=%.4f}", vectorId, score);
    }
}
