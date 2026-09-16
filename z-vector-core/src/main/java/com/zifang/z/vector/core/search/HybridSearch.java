package com.zifang.z.vector.core.search;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 混合检索（Hybrid Search）— 多路检索结果融合器。
 * <p>
 * 设计参考 zvec 的 MultiQuery + Cross-Encoder Reranker + Chroma 的 QueryPlanner.
 *
 * <h2>核心思想</h2>
 * <p>
 * 把多个独立的检索通道（向量、FTS、标量）的结果用 RRF (Reciprocal Rank Fusion) 融合：
 * <pre>
 * RRF_score(d) = Σ weight_i / (k + rank_i(d))
 * 其中：
 *   weight_i = 通道 i 的权重（用户指定）
 *   rank_i(d) = 文档 d 在通道 i 中的排名（1-based）
 *   k = 常数（典型 60）
 * </pre>
 *
 * <h2>特性</h2>
 * <ul>
 *   <li>无需训练（与 cross-encoder 重排序不同）</li>
 *   <li>天然支持任意通道数量</li>
 *   <li>每通道独立权重控制</li>
 *   <li>最终结果按 RRF 分数排序</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * List<SearchResult> vectorHits = store.search("docs", vector, 20, null);
 * List<SearchResult> ftsHits = ftsEngine.search("docs", "机器学习", 20);
 * List<SearchResult> combined = HybridSearch.rrfFuse(
 *     List.of(
 *         new SearchChannel(vectorHits, 0.7),
 *         new SearchChannel(ftsHits, 0.3)
 *     ),
 *     10
 * );
 * }</pre>
 */
public final class HybridSearch {

    private static final Logger LOG = LoggerFactory.getLogger(HybridSearch.class);

    /** RRF 默认 k 值（常数，控制排名对分数的影响） */
    public static final int DEFAULT_K = 60;

    private HybridSearch() {}

    /**
     * 单通道的搜索结果 + 权重
     */
    public static class SearchChannel {
        public final List<SearchResult> results;
        public final double weight;

        public SearchChannel(List<SearchResult> results, double weight) {
            this.results = results;
            this.weight = weight;
        }
    }

    /**
     * 用 RRF 融合多路结果
     *
     * @param channels  各通道结果（按相关性从高到低排列）
     * @param topK      最终返回数量
     * @param k         RRF 常数（默认 60）
     * @return 融合后的结果，按 RRF 分数降序排列
     */
    public static List<SearchResult> rrfFuse(List<SearchChannel> channels, int topK) {
        return rrfFuse(channels, topK, DEFAULT_K);
    }

    public static List<SearchResult> rrfFuse(List<SearchChannel> channels, int topK, int k) {
        if (channels == null || channels.isEmpty()) {
            return new ArrayList<>();
        }

        // 收集每个 id 的 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        // 同时保存每个通道原始分数（取最小距离作为综合分数）
        Map<String, Float> minScores = new HashMap<>();
        // 保存 payload（来自首次出现）
        Map<String, Map<String, Object>> payloads = new HashMap<>();

        for (SearchChannel channel : channels) {
            if (channel.results == null || channel.weight <= 0) continue;
            int rank = 0;
            for (SearchResult hit : channel.results) {
                rank++;
                String id = hit.getVectorId();
                double rrfScore = channel.weight / (k + rank);
                rrfScores.merge(id, rrfScore, Double::sum);

                float score = hit.getScore();
                if (!minScores.containsKey(id) || score < minScores.get(id)) {
                    minScores.put(id, score);
                }
                if (!payloads.containsKey(id)) {
                    payloads.put(id, hit.getPayload());
                }
            }
        }

        // 转换为结果列表，按 RRF 分数排序
        List<SearchResult> fused = new ArrayList<>();
        for (Map.Entry<String, Double> e : rrfScores.entrySet()) {
            String id = e.getKey();
            float score = minScores.getOrDefault(id, 0f);
            // 用 1 - rrfScore 作为"距离"（越大越远），方便排序
            // 保留原始距离分数在 payload 中以便 debug
            Map<String, Object> payload = payloads.getOrDefault(id, new java.util.HashMap<>());
            // 注入 rrf_score 到 payload
            Map<String, Object> enriched = new java.util.LinkedHashMap<>(payload);
            enriched.put("_rrf_score", e.getValue());
            fused.add(new SearchResult(id, score, enriched));
        }

        fused.sort((a, b) -> {
            double rrfA = (double) a.getPayload().getOrDefault("_rrf_score", 0.0);
            double rrfB = (double) b.getPayload().getOrDefault("_rrf_score", 0.0);
            return Double.compare(rrfB, rrfA); // RRF 高分在前
        });

        if (topK > 0 && fused.size() > topK) {
            fused = fused.subList(0, topK);
        }
        LOG.debug("HybridSearch RRF: {} channels → {} unique docs → top {}",
                channels.size(), rrfScores.size(), fused.size());
        return fused;
    }

    /**
     * 加权融合（Convex Combination）— 把多通道的距离分数线性加权。
     * <p>
     * 与 RRF 不同之处：要求每个通道的距离分数归一化到 [0, 1] 区间（越小越好）。
     *
     * @param channels  各通道结果（按相关性从高到低排列）
     * @param topK      最终返回数量
     * @return 融合后的结果
     */
    public static List<SearchResult> weightedFuse(List<SearchChannel> channels, int topK) {
        if (channels == null || channels.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Double> weightedScores = new HashMap<>();
        Map<String, Float> baseScores = new HashMap<>();
        Map<String, Map<String, Object>> payloads = new HashMap<>();

        for (SearchChannel channel : channels) {
            if (channel.results == null || channel.weight <= 0) continue;
            int rank = 0;
            for (SearchResult hit : channel.results) {
                rank++;
                String id = hit.getVectorId();
                // 使用排名倒数作为权重分（rank_score = 1 - rank/totalRankN）
                double rankScore = 1.0 / rank;
                double weighted = channel.weight * rankScore;
                weightedScores.merge(id, weighted, Double::sum);

                float score = hit.getScore();
                if (!baseScores.containsKey(id) || score < baseScores.get(id)) {
                    baseScores.put(id, score);
                }
                if (!payloads.containsKey(id)) {
                    payloads.put(id, hit.getPayload());
                }
            }
        }

        List<SearchResult> fused = new ArrayList<>();
        for (Map.Entry<String, Double> e : weightedScores.entrySet()) {
            String id = e.getKey();
            float score = baseScores.getOrDefault(id, 0f);
            Map<String, Object> payload = payloads.getOrDefault(id, new java.util.HashMap<>());
            Map<String, Object> enriched = new java.util.LinkedHashMap<>(payload);
            enriched.put("_weighted_score", e.getValue());
            fused.add(new SearchResult(id, score, enriched));
        }

        fused.sort((a, b) -> {
            double wsA = (double) a.getPayload().getOrDefault("_weighted_score", 0.0);
            double wsB = (double) b.getPayload().getOrDefault("_weighted_score", 0.0);
            return Double.compare(wsB, wsA);
        });

        if (topK > 0 && fused.size() > topK) {
            fused = fused.subList(0, topK);
        }
        return fused;
    }
}