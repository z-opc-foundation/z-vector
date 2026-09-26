package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.CosineDistance;
import com.zifang.z.vector.core.distance.Distance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图的可达性门禁。
 *
 * <p>为什么这些断言值得钉住：HNSW 的召回上限不是 ef，而是"从入口点出发，layer 0 上能够走到
 * 多少节点"。邻居选择如果只按"离自己最近"取 M 条，跨簇的长程边会在反向修剪时被同簇的近邻
 * 全部挤掉，图碎成一堆孤岛 —— 这时候把 efSearch 从 64 抬到 1024 一个结果都换不来，因为那些
 * 节点压根不在可达集里，不是 beam 太窄。实测（n=3000、dim=128、50 簇、M=16、
 * efConstruction=200、efSearch=64）入口点 layer 0 可达集只有 2.1%，200 个自召回探测有 55 个
 * 找不到自己。真实 embedding 就是这种聚簇形状。
 *
 * <p>只用公共 API（{@link HnswIndex#exportNodes()} / {@link HnswIndex#getEntryPoint()}），
 * 不往生产类里塞测试专用的诊断方法。
 */
class HnswGraphConnectivityTest {

    private static final int N = 3000;
    private static final int DIM = 128;
    private static final int CLUSTERS = 50;
    private static final double SIGMA = 0.12;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 200;
    private static final int EF_SEARCH = 64;
    /** 层级种子写死：这三条断言必须是可复现的，否则"红"本身没有证据价值。 */
    private static final long LEVEL_SEED = 20260926L;

    private static final Distance DIST = new CosineDistance();
    private static List<VectorPoint> corpus;
    private static HnswIndex index;

    @BeforeAll
    static void buildOnce() {
        corpus = clustered(N, DIM, CLUSTERS, SIGMA, 11L);
        index = new HnswIndex(DIST, DIM, M, EF_CONSTRUCTION, EF_SEARCH, LEVEL_SEED);
        index.build(corpus);
    }

    /** 聚簇语料：真实 embedding 的形状（不是 i.i.d. 均匀随机，那种数据所有点对距离都差不多）。 */
    private static List<VectorPoint> clustered(int n, int dim, int clusters, double sigma, long seed) {
        Random r = new Random(seed);
        float[][] centers = new float[clusters][dim];
        for (int c = 0; c < clusters; c++) {
            for (int i = 0; i < dim; i++) centers[c][i] = r.nextFloat() * 2f - 1f;
        }
        List<VectorPoint> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] center = centers[r.nextInt(clusters)];
            float[] v = new float[dim];
            for (int d = 0; d < dim; d++) v[d] = (float) (center[d] + r.nextGaussian() * sigma);
            pts.add(new VectorPoint("p" + i, v));
        }
        return pts;
    }

    /** 入口点出发、沿 layer 0 出边能走到的节点集合。 */
    private static Set<String> reachableFromEntry() {
        Map<String, List<String>> adj = new HashMap<>();
        Set<String> all = new HashSet<>();
        for (com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData nd : index.exportNodes()) {
            all.add(nd.id);
            List<String> l0 = nd.neighbors.get(0);
            adj.put(nd.id, l0 == null ? new ArrayList<String>() : l0);
        }
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        String entry = index.getEntryPoint();
        if (entry != null && all.contains(entry)) {
            seen.add(entry);
            stack.push(entry);
        }
        while (!stack.isEmpty()) {
            for (String nb : adj.getOrDefault(stack.pop(), new ArrayList<String>())) {
                if (all.contains(nb) && seen.add(nb)) stack.push(nb);
            }
        }
        return seen;
    }

    @Test
    void everyNodeIsReachableFromEntryPointAtLayer0() {
        Set<String> reachable = reachableFromEntry();
        List<String> missing = new ArrayList<>();
        for (VectorPoint p : corpus) {
            if (!reachable.contains(p.getId()) && missing.size() < 8) missing.add(p.getId());
        }
        assertEquals(N, reachable.size(),
                "layer 0 must be fully reachable from the entry point; unreachable nodes can "
                + "never be returned no matter how large efSearch is. first missing: " + missing);
    }

    @Test
    void everyIndexedPointIsItsOwnNearestNeighbor() {
        List<String> misses = new ArrayList<>();
        int checked = 0;
        for (VectorPoint p : corpus) {
            List<SearchResult> got = index.search(p.getVector(), 1, null, Float.MAX_VALUE);
            checked++;
            if (got.isEmpty() || !p.getId().equals(got.get(0).getVectorId())) {
                if (misses.size() < 8) {
                    misses.add(p.getId() + "->" + (got.isEmpty() ? "<empty>" : got.get(0).getVectorId()));
                }
            }
        }
        assertEquals(N, checked);
        assertTrue(misses.isEmpty(),
                "querying with an indexed point's own vector must return that point (distance 0); "
                + "a miss means the beam can never reach the node. first misses: " + misses);
    }

    @Test
    void recallAtEf64IsCloseToBruteForce() {
        Random r = new Random(77L);
        int queries = 30;
        int topK = 10;
        int hits = 0;
        for (int i = 0; i < queries; i++) {
            float[] q = corpus.get(r.nextInt(N)).getVector();
            List<String> exact = bruteForceTopK(q, topK);
            List<SearchResult> got = index.search(q, topK, null, Float.MAX_VALUE);
            for (SearchResult sr : got) if (exact.contains(sr.getVectorId())) hits++;
        }
        double recall = hits / (double) (queries * topK);
        assertTrue(recall >= 0.9,
                "recall@10 at efSearch=64 should be >= 0.9 on clustered data, got " + recall);
    }

    private static List<String> bruteForceTopK(float[] q, int k) {
        List<Object[]> scored = new ArrayList<>(corpus.size());
        for (VectorPoint p : corpus) scored.add(new Object[]{p.getId(), DIST.compute(q, p.vectorRef())});
        scored.sort(Comparator.comparingDouble(a -> (Float) a[1]));
        List<String> ids = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, scored.size()); i++) ids.add((String) scored.get(i)[0]);
        return ids;
    }
}
