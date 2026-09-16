package com.zifang.z.vector.core.search;

import com.zifang.z.vector.api.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合检索 (RRF 融合) 单元测试。
 */
class HybridSearchTest {

    @Test
    void rrfFuseSingleChannel() {
        // 只有向量通道时，RRF 结果应与原通道相同
        List<SearchResult> vectorHits = Arrays.asList(
                new SearchResult("d1", 0.1f),
                new SearchResult("d2", 0.2f),
                new SearchResult("d3", 0.3f)
        );
        List<SearchResult> fused = HybridSearch.rrfFuse(
                Arrays.asList(new HybridSearch.SearchChannel(vectorHits, 1.0)), 10);
        assertEquals(3, fused.size());
        assertEquals("d1", fused.get(0).getVectorId());
    }

    @Test
    void rrfFuseTwoChannelsBoostsOverlapping() {
        // d1 同时出现在两个通道的 top-1，应该被强烈提升
        List<SearchResult> channel1 = Arrays.asList(
                new SearchResult("d1", 0.1f),
                new SearchResult("d2", 0.2f),
                new SearchResult("d3", 0.3f)
        );
        List<SearchResult> channel2 = Arrays.asList(
                new SearchResult("d1", 0.15f),  // 同一文档
                new SearchResult("d4", 0.25f),
                new SearchResult("d5", 0.35f)
        );
        List<SearchResult> fused = HybridSearch.rrfFuse(Arrays.asList(
                new HybridSearch.SearchChannel(channel1, 0.7),
                new HybridSearch.SearchChannel(channel2, 0.3)
        ), 10);

        // d1 应该是第一名（两个通道都是 top-1）
        assertEquals("d1", fused.get(0).getVectorId());
        // 总共 5 个不同 id
        assertEquals(5, fused.size());
    }

    @Test
    void rrfFuseEmptyChannels() {
        assertTrue(HybridSearch.rrfFuse(Arrays.asList(), 10).isEmpty());
        assertTrue(HybridSearch.rrfFuse(null, 10).isEmpty());
    }

    @Test
    void rrfFuseTopKLimit() {
        List<SearchResult> channel1 = Arrays.asList(
                new SearchResult("d1", 0.1f),
                new SearchResult("d2", 0.2f),
                new SearchResult("d3", 0.3f),
                new SearchResult("d4", 0.4f),
                new SearchResult("d5", 0.5f)
        );
        List<SearchResult> fused = HybridSearch.rrfFuse(Arrays.asList(
                new HybridSearch.SearchChannel(channel1, 1.0)), 3);
        assertEquals(3, fused.size());
    }

    @Test
    void weightedFuse() {
        List<SearchResult> channel1 = Arrays.asList(
                new SearchResult("d1", 0.1f),
                new SearchResult("d2", 0.2f)
        );
        List<SearchResult> channel2 = Arrays.asList(
                new SearchResult("d3", 0.1f),
                new SearchResult("d4", 0.2f)
        );
        List<SearchResult> fused = HybridSearch.weightedFuse(Arrays.asList(
                new HybridSearch.SearchChannel(channel1, 0.6),
                new HybridSearch.SearchChannel(channel2, 0.4)
        ), 10);
        assertEquals(4, fused.size());
    }

    @Test
    void rrfFuseCustomK() {
        List<SearchResult> hits = Arrays.asList(
                new SearchResult("d1", 0.1f),
                new SearchResult("d2", 0.2f)
        );
        // k=0 时 RRF 退化为排名倒数加权
        List<SearchResult> fused1 = HybridSearch.rrfFuse(
                Arrays.asList(new HybridSearch.SearchChannel(hits, 1.0)), 10, 0);
        List<SearchResult> fused60 = HybridSearch.rrfFuse(
                Arrays.asList(new HybridSearch.SearchChannel(hits, 1.0)), 10, 60);
        // 不同的 k 值不影响文档顺序，只影响分数
        assertEquals(fused1.get(0).getVectorId(), fused60.get(0).getVectorId());
    }
}