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
    private final Map<String, Object> payload;

    public SearchResult(String vectorId, float score) {
        this(vectorId, score, new LinkedHashMap<>());
    }

    public SearchResult(String vectorId, float score, Map<String, Object> payload) {
        this.vectorId = vectorId;
        this.score = score;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public String getVectorId() { return vectorId; }
    public float getScore() { return score; }
    public Map<String, Object> getPayload() { return Collections.unmodifiableMap(payload); }

    @Override
    public String toString() {
        return String.format("SearchResult{id='%s', score=%.4f}", vectorId, score);
    }
}