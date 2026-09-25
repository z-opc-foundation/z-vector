package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.L2Distance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HNSW 图形状不变量的回归测试。
 * <p>
 * 起因：对已存在的 id 再 upsert 一次，实测 300 组 × 每组最多 30 次重插，崩溃率 84.7%
 * （每次重插 6.2%），抛点固定是 {@code searchLayer} 里的 {@code currNode.neighbors[level]}
 * —— ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1。
 * 单元测试里偶尔见红、复跑又绿，是因为它要撞上图形状才会出现，而不是真偶发。
 * <p>
 * 这里用 {@link HnswIndex#importNodes} 直接构造出这些形状（层数是入参，不靠随机），
 * 所以三条测试都是确定的：不需要播种 Random，也不受 JIT 摘栈影响。
 */
class HnswGraphShapeTest {

    private static final int DIM = 4;

    private static HnswIndex index() {
        // M=16、efConstruction=100、efSearch=50
        return new HnswIndex(new L2Distance(), DIM, 16, 100, 50);
    }

    private static float[] v(float x) {
        return new float[]{x, 0f, 0f, 0f};
    }

    /** layers[0] 是 layer 0 的邻居 id，layers[1] 是 layer 1 的…… */
    @SafeVarargs
    private static Map<Integer, List<String>> layers(List<String>... layers) {
        Map<Integer, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < layers.length; i++) m.put(i, new ArrayList<>(layers[i]));
        return m;
    }

    private static List<String> ids(String... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    private static HnswPersistence.HnswNodeData node(String id, float x, int level,
                                                     Map<Integer, List<String>> neighbors) {
        return new HnswPersistence.HnswNodeData(id, v(x), level, neighbors);
    }

    private static int levelOf(HnswIndex idx, String id) {
        for (HnswPersistence.HnswNodeData d : idx.exportNodes()) {
            if (d.id.equals(id)) return d.level;
        }
        throw new AssertionError("node missing: " + id);
    }

    /** 暴力精确 topK，用来判定"跳过越层引用"之后搜索结果仍然正确 */
    private static List<String> exactTopK(List<VectorPoint> pts, float[] query, int topK) {
        L2Distance d = new L2Distance();
        List<VectorPoint> sorted = new ArrayList<>(pts);
        sorted.sort(Comparator.comparingDouble(p -> d.compute(query, p.getVector())));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, sorted.size()); i++) out.add(sorted.get(i).getId());
        return out;
    }

    /**
     * 入口点自己的层数比 maxLevel 低 —— 恢复磁盘索引时没有任何校验能挡住这种形状
     * （{@code importNodes} 取所有节点层数的最大值当 maxLevel，却照收传入的 entryPoint）。
     * 旧代码在第一层贪心下降时就越界。
     */
    @Test
    void entryPointBelowMaxLevelStillSearches() {
        Map<String, HnswPersistence.HnswNodeData> graph = new LinkedHashMap<>();
        graph.put("hub", node("hub", 0.10f, 0, layers(ids("sat"))));
        graph.put("sat", node("sat", 0.90f, 3, layers(ids("hub"), ids(), ids(), ids("hub"))));

        HnswIndex idx = index();
        idx.importNodes(graph, "hub");                       // 入口点只有 layer 0
        assertEquals(3, idx.getMaxLevel(), "fixture：maxLevel 应由 sat(layer3) 抬到 3");

        List<SearchResult> hits = idx.search(v(0.12f), 2, null, Float.MAX_VALUE);

        assertEquals(Arrays.asList("hub", "sat"), idsOf(hits),
                "跳过高一层的入口点后，layer 0 的精确结果不许跟着丢");
    }

    /**
     * 高一层邻居表里引用了一个低层节点 —— 这就是 upsert 重画层数后图里残留的形状。
     * 修掉崩溃还不够：被引用节点本身仍是这一层的合法候选，不能整条丢掉。
     */
    @Test
    void highLayerReferenceToLowLayerNodeKeepsExactTopK() {
        List<VectorPoint> pts = new ArrayList<>();
        Map<String, HnswPersistence.HnswNodeData> graph = new LinkedHashMap<>();

        List<String> layer0OfGate = ids("a", "b", "c");
        for (int i = 0; i < 6; i++) layer0OfGate.add("p" + i);

        pts.add(new VectorPoint("gate", v(0.50f)));
        graph.put("gate", node("gate", 0.50f, 2,
                layers(layer0OfGate, ids("a", "b"), ids("a", "b"))));
        // a/b 只有 layer 0，却被 gate 的 layer 1/2 邻居表引用 —— 就是这个 bug 留下的形状。
        // layer 0 必须是连通的（gate 的 layer 0 表覆盖全部点），否则"暴力精确"根本不可达，
        // 拿它当参照只会测出 HNSW 的图边距，而不是守卫的对错。
        pts.add(new VectorPoint("a", v(0.05f)));
        graph.put("a", node("a", 0.05f, 0, layers(ids("gate"))));
        pts.add(new VectorPoint("b", v(0.95f)));
        graph.put("b", node("b", 0.95f, 0, layers(ids("gate"))));
        for (int i = 0; i < 6; i++) {
            float x = 0.20f + i * 0.09f;
            pts.add(new VectorPoint("p" + i, v(x)));
            graph.put("p" + i, node("p" + i, x, 0, layers(ids("gate"))));
        }
        pts.add(new VectorPoint("c", v(0.44f)));
        graph.put("c", node("c", 0.44f, 1, layers(ids("gate"), ids("gate"))));

        HnswIndex idx = index();
        idx.importNodes(graph, "gate");                     // 入口点层数够，但它的高层邻居表指向 layer 0 节点
        assertEquals(2, idx.getMaxLevel());
        assertEquals(10, idx.size(), "fixture 得连通，否则参照集没意义");

        float[] q = v(0.46f);
        assertNoTieAtCut(pts, q, 3);                        // 参照集里并列会把断言变成掷硬币
        List<String> hits = idsOf(idx.search(q, 3, null, Float.MAX_VALUE));

        assertEquals(exactTopK(pts, q, 3), hits,
                "layer 0 已连通且 efSearch=50 > 全部 10 个点 ⇒ 应当拿到暴力精确 top-3");
    }

    /** 钉住 fixture：top-K 的截断边界上不许出现等距点，否则顺序不再由算法决定 */
    private static void assertNoTieAtCut(List<VectorPoint> pts, float[] query, int topK) {
        L2Distance d = new L2Distance();
        List<Float> dists = new ArrayList<>();
        for (VectorPoint p : pts) dists.add(d.compute(query, p.getVector()));
        dists.sort(Comparator.naturalOrder());
        for (int i = 1; i <= topK; i++) {
            assertTrue(dists.get(i) > dists.get(i - 1),
                    "fixture 走形：第 " + (i - 1) + "/ " + i + " 名等距（" + dists.subList(0, topK + 1) + "）");
        }
    }

    /**
     * 根因：{@code Collection.upsertLocked} 对已有 id 只调 {@code index.add()}、不先 remove，
     * 而 add 每次重新 randomLevel() ⇒ 同一个 id 的层数会"变小"，别的节点里指向它的高层引用当场失效。
     * 重插必须沿用原有层数。循环 30 次是为了让"重画后恰好还是同一层"这种侥幸没有容身之地。
     */
    @Test
    void reinsertingAnExistingIdKeepsItsLayer() {
        Map<String, HnswPersistence.HnswNodeData> graph = new LinkedHashMap<>();
        graph.put("hub", node("hub", 0.30f, 2,
                layers(ids("leaf"), ids("leaf"), ids("leaf"))));
        graph.put("leaf", node("leaf", 0.70f, 0, layers(ids("hub"))));

        HnswIndex idx = index();
        idx.importNodes(graph, "hub");
        assertEquals(2, idx.getMaxLevel());

        for (int i = 0; i < 30; i++) {
            idx.add(new VectorPoint("hub", v(0.31f + i * 0.001f)));   // 重插最高层的节点
            assertEquals(2, levelOf(idx, "hub"),
                    "第 " + i + " 次重插改写了 hub 的层数 ⇒ 指向它的 layer 1/2 邻居表会落到一个没有该层的节点上");
            // 低层节点也要重插一遍：把"沿用旧层数"写成"抬到 maxLevel"同样是坏图（层级分布塌平），
            // 只测最高层的节点抓不到它。
            idx.add(new VectorPoint("leaf", v(0.71f + i * 0.001f)));
            assertEquals(0, levelOf(idx, "leaf"),
                    "第 " + i + " 次重插抬高了 leaf 的层数 ⇒ layer 1/2 会凭空多出枢纽，HNSW 退化成 flat");
            assertEquals(2, idx.getMaxLevel());
            // 崩溃面：add 的贪心下降 + search 的贪心下降都走一遍
            assertTrue(idx.search(v(0.31f), 2, null, Float.MAX_VALUE).size() >= 1,
                    "第 " + i + " 次重插后搜索结果不应为空");
        }
    }

    /** 反向哨兵：以上三条都依赖"层数不够就跳过、但候选保留"。若哪天改成整轮跳过，这里必须变红。 */
    @Test
    void guardDoesNotSilentlyDropTheEntryPointCandidate() {
        Map<String, HnswPersistence.HnswNodeData> graph = new LinkedHashMap<>();
        // 入口点 near 只有 layer 0，却是离查询最近的点；layer 3 的 far 很远且 layer 0 不可达
        graph.put("far", node("far", 0.99f, 3, layers(ids("far"), ids(), ids(), ids())));
        graph.put("near", node("near", 0.01f, 0, layers(ids())));

        HnswIndex idx = index();
        idx.importNodes(graph, "near");
        assertEquals(3, idx.getMaxLevel());

        List<SearchResult> hits = idx.search(v(0.02f), 1, null, Float.MAX_VALUE);
        assertEquals(Arrays.asList("near"), idsOf(hits),
                "入口点所在层比 maxLevel 低时，layer 0 仍要交出它 —— 守卫只能跳过扩展，不能放弃整次搜索");
    }

    private static List<String> idsOf(List<SearchResult> hits) {
        List<String> out = new ArrayList<>(hits.size());
        for (SearchResult r : hits) out.add(r.getVectorId());
        return out;
    }
}
