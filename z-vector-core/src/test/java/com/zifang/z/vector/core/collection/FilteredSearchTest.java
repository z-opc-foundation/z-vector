package com.zifang.z.vector.core.collection;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import com.zifang.z.vector.core.distance.L2Distance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 过滤搜索（payload filter + ANN）的端到端语义。
 * <p>
 * 钉住三件曾经实测不成立的事：
 * <ol>
 *   <li><b>过滤后的 topK 必须是"过滤之后"的最近邻</b>。旧实现先问索引要 topK 条再
 *       post-filter，匹配率稍低就断崖式少返回（AnnBench 实测 HNSW 带 filter 平均返回
 *       0.000 行、100% 查询短于 topK）。</li>
 *   <li><b>返回的 {@link SearchResult} 必须带 payload</b>（HnswIndex 的 searchLayer 内部
 *       只把 SearchResult 当 (id, score) 候选载体，payload 恒空 → 上层 evaluate 恒 false）。</li>
 *   <li><b>等值比较要宽容 Integer / Long / Double，范围比较不许命中缺字段的文档</b>
 *       （旧 {@code compareNumbers} 用 {@code Integer.MIN_VALUE} 兜底，等价于"缺字段=最小值"）。</li>
 * </ol>
 * 每条断言都配了"猎物"（prey）：同一个测试里既断言"该返回的返回了"，也断言
 * "不该返回的没返回"，避免像旧 WAL 时间戳断言那样空跑通过。
 */
class FilteredSearchTest {

    private static final int DIM = 8;
    private static final int N = 1000;
    private static final long SEED = 42L;

    private static List<VectorPoint> points() {
        Random r = new Random(SEED);
        List<VectorPoint> out = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            float[] v = new float[DIM];
            for (int j = 0; j < DIM; j++) v[j] = r.nextFloat();
            Map<String, Object> payload = new HashMap<>();
            // 每 7 个点一个 lang=zh：约 14% 匹配率，足以暴露"先取 topK 再过滤"的 starvation
            payload.put("lang", i % 7 == 0 ? "zh" : "en");
            payload.put("seq", i);
            out.add(new VectorPoint("d" + i, v, payload));
        }
        return out;
    }

    private static float[] query(int seedShift) {
        Random r = new Random(SEED + seedShift);
        float[] q = new float[DIM];
        for (int j = 0; j < DIM; j++) q[j] = r.nextFloat();
        return q;
    }

    private static VectorStore newStore(IndexType type, Map<String, Object> params) {
        VectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", DIM, DistanceMetric.L2, type, params);
        store.upsertBatch("docs", points());
        store.buildIndex("docs");
        return store;
    }

    /** 暴力扫：在所有"通过过滤"的点上算真实距离，取前 topK —— 这就是被测代码应当逼近的定义。 */
    private static List<String> bruteForce(List<VectorPoint> pts, float[] q, int topK,
                                           Predicate<Map<String, Object>> keep,
                                           float maxDistance) {
        L2Distance dist = new L2Distance();
        List<float[]> scoredDistances = new ArrayList<>();
        List<String> scoredIds = new ArrayList<>();
        for (VectorPoint p : pts) {
            if (!keep.test(p.getPayload())) continue;
            float d = dist.compute(q, p.getVector());
            if (d > maxDistance) continue;
            scoredDistances.add(new float[]{d});
            scoredIds.add(p.getId());
        }
        Integer[] order = new Integer[scoredIds.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final List<float[]> ds = scoredDistances;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(ds.get(a)[0], ds.get(b)[0]));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, order.length); i++) ids.add(scoredIds.get(order[i]));
        return ids;
    }

    private static Set<String> idsOf(List<SearchResult> results) {
        Set<String> ids = new LinkedHashSet<>();
        for (SearchResult r : results) ids.add(r.getVectorId());
        return ids;
    }

    // ==================== 1. 过滤后的 topK 数量与集合 ====================

    @Test
    void flatFilteredSearchReturnsFullTopKAndMatchesBruteForce() {
        VectorStore store = newStore(IndexType.FLAT, null);
        float[] q = query(1);
        int topK = 10;

        List<SearchResult> hits = store.search("docs", q, topK, Filter.eq("lang", "zh"));

        // 猎物：暴力扫确有 >= topK 个匹配点，所以"返回 10 行"是可验证的事实而非空跑
        List<String> exact = bruteForce(points(), q, topK, p -> "zh".equals(p.get("lang")),
                Float.MAX_VALUE);
        assertEquals(topK, exact.size(), "brute force should find at least topK matches");
        assertEquals(topK, hits.size(),
                "filtered search must return topK rows, not whatever survived a post-filter");
        assertEquals(new LinkedHashSet<>(exact), idsOf(hits),
                "filtered results must be the filtered nearest neighbours");

        // 反向猎物：不存在的取值必须 0 行（否则说明过滤根本没生效）
        assertTrue(store.search("docs", q, topK, Filter.eq("lang", "zz")).isEmpty(),
                "a value nobody has must return 0 rows");
    }

    @Test
    void hnswFilteredSearchReturnsFullTopKWithPayloads() {
        VectorStore store = newStore(IndexType.HNSW, params("efSearch", 50));
        float[] q = query(2);
        int topK = 10;

        List<SearchResult> hits = store.search("docs", q, topK, Filter.eq("lang", "zh"));

        assertEquals(topK, hits.size(), "HNSW filtered search returned short: " + hits.size());
        for (SearchResult r : hits) {
            assertNotNull(r.getPayload(), "payload must not be null");
            assertEquals("zh", r.getPayload().get("lang"),
                    "returned row must satisfy the filter — an empty payload map here means "
                            + "HnswIndex dropped the payload on the way out");
            assertTrue(r.getPayload().containsKey("seq"),
                    "payload must be the stored one, not a synthesised subset");
        }

        // 召回率下限：暴力扫的真实 topK 与 HNSW 结果的交集
        List<String> exact = bruteForce(points(), q, topK, p -> "zh".equals(p.get("lang")),
                Float.MAX_VALUE);
        Set<String> overlap = new LinkedHashSet<>(exact);
        overlap.retainAll(idsOf(hits));
        double recall = overlap.size() / (double) topK;
        assertTrue(recall >= 0.7, "HNSW filtered recall too low: " + recall);
    }

    @Test
    void ivfFilteredSearchReturnsFullTopK() {
        VectorStore store = newStore(IndexType.IVF, params("nlist", 16).and("nprobe", 4));
        float[] q = query(3);

        List<SearchResult> hits = store.search("docs", q, 10, Filter.eq("lang", "zh"));

        assertEquals(10, hits.size(), "IVF filtered search returned short: " + hits.size());
        for (SearchResult r : hits) assertEquals("zh", r.getPayload().get("lang"));
    }

    // ==================== 2. 复合 / 不可下推的过滤 ====================

    @Test
    void andOfEqualitiesIsPushedDownAndStillCorrect() {
        VectorStore store = newStore(IndexType.FLAT, null);
        float[] q = query(4);
        Filter f = Filter.and(Filter.eq("lang", "zh"), Filter.eq("seq", 7));

        assertNotNull(f.toFlatPayload(), "pure-EQ AND must be pushdown-able");
        List<SearchResult> hits = store.search("docs", q, 10, f);
        // 猎物：只有 d7 同时满足两个条件
        assertEquals(1, hits.size(), "expected exactly one row, got " + idsOf(hits));
        assertEquals("d7", hits.get(0).getVectorId());
    }

    @Test
    void orFilterIsNotPushedDownButReturnsFilteredTopK() {
        VectorStore store = newStore(IndexType.FLAT, null);
        float[] q = query(5);
        Filter f = Filter.or(Filter.eq("lang", "zh"), Filter.eq("seq", 1));

        // OR 绝不能被压成等值 Map（旧实现会把 field/value 直接塞进 Map，语义反转）
        assertEquals(null, f.toFlatPayload(), "OR must not be equality-pushed down");

        List<SearchResult> hits = store.search("docs", q, 10, f);
        List<String> exact = bruteForce(points(), q, 10,
                p -> "zh".equals(p.get("lang")) || Integer.valueOf(1).equals(p.get("seq")),
                Float.MAX_VALUE);
        assertEquals(10, hits.size());
        assertEquals(new LinkedHashSet<>(exact), idsOf(hits));
    }

    // ==================== 3. 值比较语义 ====================

    @Test
    void equalityMatchesAcrossIntegerLongAndDouble() {
        VectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", DIM, DistanceMetric.L2);
        List<VectorPoint> pts = new ArrayList<>();
        Map<String, Object> p1 = new HashMap<>();
        p1.put("views", 7);                 // JSON 里小整数解析成 Integer
        pts.add(new VectorPoint("a", new float[DIM], p1));
        Map<String, Object> p2 = new HashMap<>();
        p2.put("views", 99);
        pts.add(new VectorPoint("b", new float[DIM], p2));
        store.upsertBatch("docs", pts);

        // 猎物 1：客户端写 Long 字面量，payload 是 Integer —— 旧 Objects.equals 判不等，静默 0 行
        List<SearchResult> asLong = store.search("docs", new float[DIM], 10, Filter.eq("views", 7L));
        assertEquals(1, asLong.size(), "Long filter value must match Integer payload");
        assertEquals("a", asLong.get(0).getVectorId());

        List<SearchResult> asDouble = store.search("docs", new float[DIM], 10,
                Filter.eq("views", 7.0));
        assertEquals(1, asDouble.size(), "Double filter value must match Integer payload");

        // 猎物 2：真正不相等的值必须不命中，否则宽容比较变成了放水
        assertTrue(store.search("docs", new float[DIM], 10, Filter.eq("views", 8L)).isEmpty(),
                "8 must not match 7");
    }

    @Test
    void rangeFilterExcludesDocumentsLackingTheField() {
        VectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", DIM, DistanceMetric.L2);
        Map<String, Object> has = new HashMap<>();
        has.put("score", 3);
        Map<String, Object> lacks = new HashMap<>();
        lacks.put("lang", "zh");
        List<VectorPoint> pts = new ArrayList<>();
        pts.add(new VectorPoint("has_score", unit(0.05f), has));
        // 猎物布局刻意让"缺字段的文档更靠近查询点"：旧实现下它会作为 lt 命中排在最前
        pts.add(new VectorPoint("no_score", unit(0f), lacks));
        store.upsertBatch("docs", pts);
        float[] q = unit(0f);

        // 猎物：has_score 确实命中（否则空返回也可能是过滤整体失效）
        List<SearchResult> lt = store.search("docs", q, 10, Filter.lt("score", 5));
        assertEquals(1, lt.size(), "lt must match the doc whose score=3");
        assertEquals("has_score", lt.get(0).getVectorId());
        assertTrue(idsOf(store.search("docs", q, 10, Filter.lte("score", 5)))
                .contains("has_score"));

        // 反向：没有 score 字段的文档不能因为"缺字段=最小值"而被判成 lt/lte 命中
        assertFalse(idsOf(lt).contains("no_score"), "doc without the field must not match lt");

        // GT 同形：缺字段也不许命中 gt
        List<SearchResult> gt = store.search("docs", q, 10, Filter.gt("score", 100));
        assertTrue(gt.isEmpty(), "doc without the field must not match gt either: " + idsOf(gt));

        // EXISTS 才是"缺字段"的正确问法
        assertEquals(1, store.search("docs", q, 10, Filter.exists("score")).size());
    }

    // ==================== 3b. 低命中率：候选放大循环本身 ====================
    // 上面 lang=zh 的命中率是 14%，一轮 80 条候选就够填满 topK —— 所以那批测试钉不住
    // "放大重取"的循环（变异实验：把循环删掉仍然全绿）。以下三条把命中率压到 1.3% 和
    // 0.1%，此时"只问一轮"必然短返回，循环才是唯一能问出 topK 的路径。

    @Test
    void rareEqualityFilterOnHnswGrowsCandidatesToFullTopK() {
        List<VectorPoint> pts = rarePoints(83, "rare");        // 1000 点里约 13 个匹配（1.3%）
        VectorStore store = storeWith(IndexType.HNSW, pts);
        float[] q = query(11);

        int matches = countMatching(pts, p -> "rare".equals(p.getPayload().get("tag")));
        assertTrue(matches >= 10, "fixture needs >= topK matching docs, got " + matches);

        List<SearchResult> hits = store.search("docs", q, 10, Filter.eq("tag", "rare"));
        assertEquals(10, hits.size(),
                "1.3% selectivity must trigger candidate growth, not return 1 row");
        for (SearchResult r : hits) assertEquals("rare", r.getPayload().get("tag"));

        List<String> exact = bruteForce(pts, q, 10,
                p -> "rare".equals(p.get("tag")), Float.MAX_VALUE);
        Set<String> overlap = new LinkedHashSet<>(exact);
        overlap.retainAll(idsOf(hits));
        assertTrue(overlap.size() >= 7,
                "grown candidate window should mostly agree with brute force, overlap=" + overlap);
    }

    @Test
    void singleMatchingDocIsFoundAfterWindowExhausted() {
        List<VectorPoint> pts = rarePoints(1000, "only");      // 只有 d0 命中（0.1%）
        VectorStore store = storeWith(IndexType.HNSW, pts);
        float[] q = query(12);

        List<SearchResult> hits = store.search("docs", q, 5, Filter.eq("tag", "only"));
        assertEquals(1, hits.size(), "the unique match must be reachable, got " + idsOf(hits));
        assertEquals("d0", hits.get(0).getVectorId());
        assertEquals("only", hits.get(0).getPayload().get("tag"));

        // 反向猎物：换个不存在的 tag，同样是全量放大后返回 0（不是超时、不是异常）
        assertTrue(store.search("docs", q, 5, Filter.eq("tag", "none")).isEmpty());
    }

    @Test
    void rareNonPushdownFilterAlsoGrowsCandidates() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        VectorStore store = storeWith(IndexType.HNSW, pts);
        float[] q = query(13);
        // OR 不可等值下推 ⇒ 完全依赖 post-filter + 放大循环
        Filter f = Filter.or(Filter.eq("tag", "rare"), Filter.eq("tag", "nope"));
        assertEquals(null, f.toFlatPayload(), "OR must not be pushed down");

        List<SearchResult> hits = store.search("docs", q, 10, f);
        assertEquals(10, hits.size(), "post-filter path needs the growth loop too");
        for (SearchResult r : hits) assertEquals("rare", r.getPayload().get("tag"));
    }

    // ==================== 4. 边界与终止 ====================

    @Test
    void unsatisfiableFilterTerminatesWithEmptyResult() {
        final VectorStore store = newStore(IndexType.HNSW, params("efSearch", 50));
        float[] q = query(6);

        // 匹配数为 0 ⇒ 超量取候选会一路放大到全量；这一条钉住"不会死循环、也不会误返回"
        List<SearchResult> hits = assertTimeoutPreemptively(Duration.ofSeconds(60), () ->
                store.search("docs", q, 10, Filter.eq("lang", "de")));
        assertTrue(hits.isEmpty(), "no doc is lang=de, got " + idsOf(hits));
    }

    @Test
    void searchRangeWithFilterHonoursMaxDistance() {
        VectorStore store = newStore(IndexType.FLAT, null);
        float[] q = query(7);

        // 先用大半径问出真实距离分布，再取"前 20 个匹配点里最远的距离"作为阈值
        List<SearchResult> wide = store.search("docs", q, 20, Filter.eq("lang", "zh"));
        assertEquals(20, wide.size());
        float threshold = wide.get(wide.size() - 1).getScore();

        List<SearchResult> narrow = store.searchRange("docs", q, threshold, 10,
                Filter.eq("lang", "zh"));
        for (SearchResult r : narrow) {
            assertTrue(r.getScore() <= threshold + 1e-6f,
                    "searchRange leaked a row beyond maxDistance: " + r.getScore());
            assertEquals("zh", r.getPayload().get("lang"));
        }
        assertTrue(narrow.size() <= 10, "searchRange ignored topK: " + narrow.size());
        // 猎物：阈值内的匹配点确实存在，且阈值卡住了一部分（否则这条断言是空跑）
        assertTrue(narrow.size() >= 1, "nothing inside maxDistance — test fixture is broken");
        assertTrue(narrow.size() < wide.size(), "threshold cut nothing — fixture is degenerate");
    }

    @Test
    void filterlessSearchIsUnchangedByThePushdownPath() {
        VectorStore store = newStore(IndexType.HNSW, params("efSearch", 50));
        float[] q = query(8);

        List<SearchResult> hits = store.search("docs", q, 10, null);
        assertEquals(10, hits.size());
        // 无过滤路径同样必须带回 payload（REST/gRPC 的返回体依赖它）
        for (SearchResult r : hits) {
            assertTrue(r.getPayload().containsKey("lang"),
                    "unfiltered HNSW search lost the payload for " + r.getVectorId());
        }
    }

    // ==================== 工具 ====================

    private static float[] unit(float lead) {
        float[] v = new float[DIM];
        v[0] = lead;
        for (int i = 1; i < DIM; i++) v[i] = lead;
        return v;
    }

    /** 低命中率夹具：每 step 个点打一次 tag，其余点没有这个 key */
    private static List<VectorPoint> rarePoints(int step, String tag) {
        Random r = new Random(SEED);
        List<VectorPoint> out = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            float[] v = new float[DIM];
            for (int j = 0; j < DIM; j++) v[j] = r.nextFloat();
            Map<String, Object> payload = new HashMap<>();
            payload.put("lang", "en");
            payload.put("seq", i);
            if (i % step == 0) payload.put("tag", tag);
            out.add(new VectorPoint("d" + i, v, payload));
        }
        return out;
    }

    private static VectorStore storeWith(IndexType type, List<VectorPoint> pts) {
        VectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", DIM, DistanceMetric.L2, type, params("efSearch", 50));
        store.upsertBatch("docs", pts);
        store.buildIndex("docs");
        return store;
    }

    private static int countMatching(List<VectorPoint> pts,
                                     Predicate<VectorPoint> keep) {
        int n = 0;
        for (VectorPoint p : pts) if (keep.test(p)) n++;
        return n;
    }

    /** 可变参数式的小 Map 构造器（Java 8：不能用 Map.of） */
    private static Params params(String k, Object v) {
        return new Params(k, v);
    }

    private static class Params extends HashMap<String, Object> {
        Params(String k, Object v) {
            put(k, v);
        }

        Params and(String k, Object v) {
            put(k, v);
            return this;
        }
    }

    // ==================== 4. flat 有界 top-K 与全量稳定排序截断等价 ====================

    /**
     * {@code FlatIndex.search} 换成有界 top-K 插入是为了砍掉热路径分配（实测 dim=64/n=5000
     * 一次查询克隆 1.9MB），优化本身不许改语义：必须与优化前"收全部候选 → 稳定排序 → 截断
     * topK"逐条一致。差别只可能出现在**平局**上，所以这里造两组严格相等的距离：
     * <ul>
     *   <li>8 个单稀疏 0.5 向量：到原点的 L2 精确等于 0.5；</li>
     *   <li>28 个双稀疏 0.5/0.5 向量：平方和都是 0.5，开方后彼此严格相等。</li>
     * </ul>
     * 参照物与被测物共用同一次 {@code entries()} 的遍历顺序 —— points 是 ConcurrentHashMap，
     * 换一个遍历顺序去比平局没有意义。
     */
    @Test
    void flatBoundedTopKEqualsStableSortOfAllCandidates() {
        final int dim = 8;
        List<VectorPoint> pts = new ArrayList<>();
        pts.add(new VectorPoint("closer", new float[]{0.25f, 0, 0, 0, 0, 0, 0, 0}));
        pts.add(new VectorPoint("farther", new float[]{1f, 0, 0, 0, 0, 0, 0, 0}));
        int no = 0;
        for (int i = 0; i < dim; i++) {
            for (int j = i; j < dim; j++) {
                float[] v = new float[dim];
                v[i] = 0.5f;
                v[j] = 0.5f;
                pts.add(new VectorPoint("t" + (no++), v));
            }
        }
        com.zifang.z.vector.core.index.FlatIndex idx =
                new com.zifang.z.vector.core.index.FlatIndex(new L2Distance(), dim);
        idx.build(pts);

        float[] q = new float[dim];
        List<VectorPoint> scanned = idx.entries();
        L2Distance dist = new L2Distance();

        for (int topK : new int[]{1, 3, 8, 9, 10, 35, 36, 40}) {
            List<String> want = referenceTopK(scanned, q, dist, topK, Float.MAX_VALUE);
            assertEquals(want, orderedIds(idx.search(q, topK, null, Float.MAX_VALUE)),
                    "topK=" + topK + " 与全量稳定排序后截断不一致");
        }

        // maxDistance 正好落在平局距离上：等号边界一个都不许掉
        List<String> wantAtBoundary = referenceTopK(scanned, q, dist, 10, 0.5f);
        assertEquals(wantAtBoundary, orderedIds(idx.search(q, 10, null, 0.5f)),
                "maxDistance 等于候选距离时不许把等号点判掉");
        // 猎物：这一刀确实筛掉了 28 个双稀疏点，不是空跑
        assertEquals(9, wantAtBoundary.size(), "fixture 走形：应为 closer + 8 个单稀疏");
    }

    /** 优化前的语义：全量扫描 → 稳定排序（List.sort 即 TimSort）→ 截断 topK。 */
    private static List<String> referenceTopK(List<VectorPoint> scanned, float[] q,
                                              L2Distance dist, int topK, float maxDistance) {
        List<SearchResult> all = new ArrayList<>();
        for (VectorPoint p : scanned) {
            float d = dist.compute(q, p.vectorRef());
            if (d > maxDistance) continue;
            all.add(new SearchResult(p.getId(), d, p.getPayload()));
        }
        all.sort((a, b) -> Float.compare(a.getScore(), b.getScore()));
        return orderedIds(all.subList(0, Math.min(topK, all.size())));
    }

    private static List<String> orderedIds(List<SearchResult> results) {
        List<String> ids = new ArrayList<>(results.size());
        for (SearchResult r : results) ids.add(r.getVectorId());
        return ids;
    }
}
