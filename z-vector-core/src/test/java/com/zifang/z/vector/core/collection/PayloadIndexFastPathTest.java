package com.zifang.z.vector.core.collection;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.L2Distance;
import com.zifang.z.vector.core.index.FlatIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * payload 倒排快路径（{@code Collection.filteredSearch} 的第一条分支）。
 * <p>
 * 这里要区分两件事，否则测试会退化成"结果对不对"的重复覆盖：
 * <ul>
 *   <li><b>结果正确</b> —— {@code FilteredSearchTest} 已经钉过；</li>
 *   <li><b>走的是哪条路</b> —— 快路径没被走到时结果同样正确（慢路径兜底），所以必须靠
 *       {@link Collection#payloadFastPathHits()} 计数器来判，光看返回值永远发现不了
 *       倒排索引整个失效。</li>
 * </ul>
 * 每条"应当走快路径"的断言都配一条"不该走快路径"的反向断言（大命中集 / OR / NE /
 * 倒排与向量索引不同步），双向钉住分流条件本身。
 */
class PayloadIndexFastPathTest {

    private static final int DIM = 8;
    private static final int N = 1000;
    private static final long SEED = 7L;

    private static List<VectorPoint> rarePoints(int step, String tag) {
        Random r = new Random(SEED);
        List<VectorPoint> out = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            float[] v = new float[DIM];
            for (int j = 0; j < DIM; j++) v[j] = r.nextFloat();
            Map<String, Object> payload = new HashMap<>();
            payload.put("lang", i % 2 == 0 ? "zh" : "en");     // 50% 命中：太大，不该走快路径
            payload.put("seq", i);
            if (i % step == 0) payload.put("tag", tag);         // 小命中集：该走快路径
            out.add(new VectorPoint("d" + i, v, payload));
        }
        return out;
    }

    private static Collection newCollection(IndexType type, List<VectorPoint> pts) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("efSearch", 50);
        Collection coll = new Collection(new VectorCollection("docs", DIM,
                DistanceMetric.L2, type, cfg));
        coll.upsertBatch(pts);
        coll.buildIndex();
        return coll;
    }

    private static List<String> bruteForce(List<VectorPoint> pts, float[] q, int topK,
                                           Predicate<Map<String, Object>> keep) {
        L2Distance dist = new L2Distance();
        List<SearchResult> all = new ArrayList<>();
        for (VectorPoint p : pts) {
            if (!keep.test(p.getPayload())) continue;
            all.add(new SearchResult(p.getId(), dist.compute(q, p.getVector()), p.getPayload()));
        }
        all.sort(Comparator.comparingDouble(SearchResult::getScore));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, all.size()); i++) ids.add(all.get(i).getVectorId());
        return ids;
    }

    private static Set<String> idsOf(List<SearchResult> results) {
        Set<String> ids = new LinkedHashSet<>();
        for (SearchResult r : results) ids.add(r.getVectorId());
        return ids;
    }

    /** 同上，但保留顺序 —— 钉"第 i 条就是第 i 近"时不能用集合比对糊过去。 */
    private static List<String> orderedIds(List<SearchResult> results) {
        List<String> ids = new ArrayList<>(results.size());
        for (SearchResult r : results) ids.add(r.getVectorId());
        return ids;
    }

    private static float[] query(int shift) {
        Random r = new Random(SEED + shift);
        float[] q = new float[DIM];
        for (int j = 0; j < DIM; j++) q[j] = r.nextFloat();
        return q;
    }

    // ==================== 快路径被真正走到 ====================

    @Test
    void rareEqualityIsAnsweredExactlyByTheInvertedIndex() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(1);

        List<SearchResult> hits = coll.search(q, 10, Filter.eq("tag", "rare"));

        assertEquals(1, coll.payloadFastPathHits(),
                "a 1.3%-selectivity EQ must be served by the payload inverted index");
        List<String> exact = bruteForce(pts, q, 10, p -> "rare".equals(p.get("tag")));
        assertEquals(new LinkedHashSet<>(exact), idsOf(hits),
                "fast path must be exact — it does no ANN approximation at all");
        assertEquals(10, hits.size());
        // 距离必须升序（上层按 score 截断 topK 依赖这个顺序）
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).getScore() <= hits.get(i).getScore(),
                    "fast path results not sorted by distance");
        }
    }

    @Test
    void inAndExistsAlsoTakeTheFastPath() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.FLAT, pts);
        float[] q = query(2);

        List<SearchResult> in = coll.search(q, 10,
                Filter.inValues("tag", Arrays.asList("rare", "absent")));
        assertEquals(1, coll.payloadFastPathHits(), "IN should use the inverted index too");
        assertEquals(new LinkedHashSet<>(bruteForce(pts, q, 10,
                        p -> "rare".equals(p.get("tag")))), idsOf(in));

        List<SearchResult> exists = coll.search(q, 100, Filter.exists("tag"));
        assertEquals(2, coll.payloadFastPathHits(), "EXISTS should use the inverted index");
        int matches = 0;
        for (VectorPoint p : pts) if (p.getPayload().containsKey("tag")) matches++;
        assertEquals(matches, exists.size(), "EXISTS returned the wrong population");
    }

    @Test
    void andOfEqualitiesIntersectsTheInvertedLists() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(3);

        List<SearchResult> hits = coll.search(q, 10,
                Filter.and(Filter.eq("tag", "rare"), Filter.eq("lang", "zh")));
        assertEquals(1, coll.payloadFastPathHits(), "AND of two indexed EQs should stay fast");
        assertEquals(new LinkedHashSet<>(bruteForce(pts, q, 10,
                        p -> "rare".equals(p.get("tag")) && "zh".equals(p.get("lang")))),
                idsOf(hits));
        assertTrue(hits.size() > 0, "fixture must contain zh+rare rows");
    }

    // ==================== 分流条件：命中集太大就退回窗口 ====================

    @Test
    void wideMatchSetFallsBackToTheWindowedPath() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(4);

        // lang=zh 命中 500/1000 —— 比在图上跑一遍窗口更贵，必须不走快路径
        List<SearchResult> hits = coll.search(q, 10, Filter.eq("lang", "zh"));
        assertEquals(0, coll.payloadFastPathHits(),
                "a 50%-selectivity EQ must NOT brute-force 500 distances through the fast path");
        assertEquals(10, hits.size());
        for (SearchResult r : hits) assertEquals("zh", r.getPayload().get("lang"));
    }

    @Test
    void unsolvableFiltersFallBackAndStayCorrect() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(5);

        List<SearchResult> or = coll.search(q, 10,
                Filter.or(Filter.eq("tag", "rare"), Filter.eq("tag", "absent")));
        List<SearchResult> ne = coll.search(q, 10, Filter.ne("lang", "zh"));
        List<SearchResult> range = coll.search(q, 10, Filter.lt("seq", 5));
        assertEquals(0, coll.payloadFastPathHits(),
                "OR / NE / range are not answerable by an ExactIndex — got "
                        + coll.payloadFastPathHits() + " fast-path hits");
        assertEquals(10, or.size(), "fallback path short-returned (OR)");
        assertEquals(10, ne.size(), "fallback path short-returned (NE)");
        // seq<5 只有 5 个点：慢路径必须把它们全找出来，同时不许凑数
        assertEquals(5, range.size(), "fallback path must return exactly the 5 matches, got "
                + idsOf(range));
        for (SearchResult r : ne) assertTrue(!"zh".equals(r.getPayload().get("lang")));
        for (SearchResult r : range) {
            Object seq = r.getPayload().get("seq");
            assertTrue(seq instanceof Integer && (Integer) seq < 5, "range leaked " + r);
        }
    }

    // ==================== 一致性：倒排必须跟着写路径走 ====================

    @Test
    void deleteRemovesTheInvertedEntry() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(6);

        List<SearchResult> before = coll.search(q, 50, Filter.eq("tag", "rare"));
        assertTrue(before.size() > 1, "fixture must have several rare rows before deletion");

        // 逐个删掉快路径返回的行：每删一个，结果集必须少一个，且不能出现"倒排还在但点没了"
        int expected = before.size();
        for (SearchResult r : before) {
            coll.delete(r.getVectorId());
            List<SearchResult> after = coll.search(q, 50, Filter.eq("tag", "rare"));
            expected--;
            assertEquals(expected, after.size(),
                    "deleted id " + r.getVectorId() + " still answered by the filter");
            assertTrue(!idsOf(after).contains(r.getVectorId()));
        }
        assertTrue(coll.search(q, 10, Filter.eq("tag", "rare")).isEmpty(),
                "all rare rows deleted, filter must return nothing");
    }

    @Test
    void countIsNotDoubleDecrementedByTombstones() {
        // HnswIndex / IvfIndex 的 size() 一度写成 primaryMap.size() - tombstones.size()，
        // 而 remove() 已经物理删点：删一个少两个。count() 虚降还会连带让 payload 倒排的
        // 同步校验误判，把过滤搜索整体踹回慢路径 —— 所以这里两件事一起钉。
        for (IndexType type : Arrays.asList(IndexType.HNSW, IndexType.IVF)) {
            List<VectorPoint> pts = rarePoints(83, "rare");
            Collection coll = newCollection(type, pts);
            assertEquals(N, coll.count(), type + ": count before deletes");

            coll.delete("d0");
            coll.delete("d83");
            coll.delete("d166");
            assertEquals(N - 3, coll.count(), type + ": each deletion must cost exactly 1");

            List<SearchResult> hits = coll.search(query(14), 50, Filter.eq("tag", "rare"));
            assertEquals(1, coll.payloadFastPathHits(),
                    type + ": count() drift must not disable the inverted fast path");
            assertEquals(10, hits.size(), type + ": 13 rare rows minus 3 deleted");
        }
    }

    @Test
    void upsertReplacesTheOldInvertedEntry() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(7);

        VectorPoint original = null;
        for (VectorPoint p : pts) {
            if ("rare".equals(p.getPayload().get("tag"))) { original = p; break; }
        }
        assertTrue(original != null);

        Map<String, Object> moved = new HashMap<>();
        moved.put("lang", "zh");
        moved.put("seq", 12345);
        moved.put("tag", "relocated");
        coll.upsert(new VectorPoint(original.getId(), original.getVector(), moved));

        // 旧值不能再命中：倒排若只做"加"不"删"，这里会捞出一条 payload 已经是新值的行
        List<SearchResult> stale = coll.search(q, 50, Filter.eq("tag", "rare"));
        assertTrue(!idsOf(stale).contains(original.getId()),
                "inverted index kept the pre-update value for " + original.getId());
        List<SearchResult> fresh = coll.search(q, 10, Filter.eq("tag", "relocated"));
        assertEquals(1, fresh.size());
        assertEquals(original.getId(), fresh.get(0).getVectorId());
        assertEquals(12345, fresh.get(0).getPayload().get("seq"));
    }

    @Test
    void numericKeysOfDifferentJavaTypesShareOneBucket() {
        List<VectorPoint> pts = new ArrayList<>(4);
        Map<String, Object> pInt = new HashMap<>();
        pInt.put("views", 7);                                  // Jackson 会给出 Integer
        pts.add(new VectorPoint("a", query(8), pInt));
        Map<String, Object> pBig = new HashMap<>();
        pBig.put("views", 500000);
        pts.add(new VectorPoint("b", query(9), pBig));

        Collection coll = newCollection(IndexType.HNSW, pts);

        // Long 字面量查 Integer 存的数据：倒排按 equals 分桶，不做规范化就是静默 0 行
        List<SearchResult> hits = coll.search(new float[DIM], 10, Filter.eq("views", 7L));
        assertEquals(1, coll.payloadFastPathHits(), "numeric payload must be canonicalised");
        assertEquals(1, hits.size(), "Integer 7 must be findable by a Long 7L filter");
        assertEquals("a", hits.get(0).getVectorId());

        assertEquals(1, coll.search(new float[DIM], 10, Filter.eq("views", 7.0)).size());
        assertTrue(coll.search(new float[DIM], 10, Filter.eq("views", 8L)).isEmpty(),
                "canonicalisation must not turn into fuzzy matching");
    }

    @Test
    void fastPathIsDisabledWhenTheInvertedIndexIsOutOfSync() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(10);

        // 绕过 Collection 直接写底层索引：payloadTracked 与 index.size() 分叉，
        // 快路径必须自我禁用（否则它会拿着旧倒排答"没有这个点"）
        Map<String, Object> sneaky = new HashMap<>();
        sneaky.put("tag", "rare");
        sneaky.put("seq", 9999);
        coll.getIndex().add(new VectorPoint("sneak", query(11), sneaky));

        List<SearchResult> hits = coll.search(q, 10, Filter.eq("tag", "rare"));
        assertEquals(0, coll.payloadFastPathHits(),
                "out-of-sync inverted index must not be trusted");
        assertTrue(idsOf(hits).contains("sneak"),
                "fallback path must still see the point the inverted index missed");
    }

    @Test
    void replaceIndexWithPayloadlessIndexKeepsResultsHonest() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(12);

        // 模拟 recover() 后接管的索引：只带 id + vector，不带 payload（HnswPersistence 的现状）
        FlatIndex bare = new FlatIndex(new L2Distance(), DIM);
        List<VectorPoint> noPayload = new ArrayList<>();
        for (VectorPoint p : pts) noPayload.add(new VectorPoint(p.getId(), p.getVector()));
        bare.build(noPayload);
        coll.replaceIndex(bare);

        List<SearchResult> hits = coll.search(q, 10, Filter.eq("tag", "rare"));
        // 倒排被重建为空 ⇒ resolve 给不出答案 ⇒ 只能走慢路径；慢路径同样答不出（payload 已丢），
        // 但绝不能凭空造出结果。这里记录的是"payload 在快照里丢失"这一已知缺口
        // （见任务 #7），而不是让它伪装成一次成功的过滤。
        assertEquals(0, coll.payloadFastPathHits());
        assertTrue(hits.isEmpty(),
                "a payload-less index must not claim filtered matches it cannot verify");
    }

    // ==================== 截断：命中集远大于 topK ====================

    /**
     * 快路径只对命中集全量算距离，但<b>只交出 topK 条</b> —— 现在的实现连"没进前 K"的点
     * 都不再造 {@link SearchResult}。这条测试钉的是省法的边界：截断发生在一半的命中上时，
     * 交出来的仍然要和暴力法<b>逐条同序同名</b>（不只是同一个集合 —— 顺序是上层再截 topK 的依据）。
     */
    @Test
    void truncatingALargeHitSetStaysExactAndOrdered() {
        List<VectorPoint> pts = rarePoints(16, "rare");        // 1000/16 ⇒ 63 个命中
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(20);

        List<SearchResult> hits = coll.search(q, 10, Filter.eq("tag", "rare"));
        assertEquals(1, coll.payloadFastPathHits(), "63 hits must still be served by the fast path");
        List<String> exact = bruteForce(pts, q, 10, p -> "rare".equals(p.get("tag")));
        assertEquals(10, exact.size(), "fixture must have far more hits than topK, got " + exact);
        // 有序逐条比对，不是集合比对：随机向量下距离两两不同，所以"同名同序"是能钉死的强判据
        assertEquals(exact, orderedIds(hits),
                "fast path truncated to a different top-10 than brute force");
    }

    /**
     * 前 K 的边界上挤着一组等距离点时的判据。<b>哪一个胜出不是契约的一部分</b> —— 命中 id
     * 集合来自 {@code PayloadIndex} 的 {@code HashSet} 副本，它的遍历顺序既不是写入顺序也
     * 不是字典序，旧写法（全量排序 + 稳定截断）同样是"按那个顺序先到先得"。所以这里钉三件
     * 真实可判的：条数、每条的距离（逐位等于暴力法第 i 近）、以及"严格更近的一个不许漏"。
     */
    @Test
    void tiesAtTheCutOffKeepEveryCloserPointAndAnExactScoreProfile() {
        int dim = 4;
        List<VectorPoint> pts = new ArrayList<>();
        Map<String, Object> tag = new HashMap<>();
        tag.put("tag", "t");
        // a 唯一最近；b/c/d 是同一个向量的三份拷贝 ⇒ 与查询等距，正好压在 topK=3 的边界外沿
        pts.add(new VectorPoint("a", new float[]{9f, 0f, 0f, 0f}, tag));
        pts.add(new VectorPoint("b", new float[]{0f, 5f, 0f, 0f}, tag));
        pts.add(new VectorPoint("c", new float[]{0f, 5f, 0f, 0f}, tag));
        pts.add(new VectorPoint("d", new float[]{0f, 5f, 0f, 0f}, tag));
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("efSearch", 50);
        Collection coll = new Collection(new VectorCollection("ties", dim,
                DistanceMetric.L2, IndexType.HNSW, cfg));
        coll.upsertBatch(pts);
        coll.buildIndex();
        float[] q = new float[]{9f, 5f, 0f, 0f};

        List<SearchResult> first = coll.search(q, 3, Filter.eq("tag", "t"));
        List<SearchResult> second = coll.search(q, 3, Filter.eq("tag", "t"));
        assertEquals(2, coll.payloadFastPathHits(), "4 hits with topK=3 must use the fast path");

        L2Distance dist = new L2Distance();
        float tieScore = dist.compute(q, new float[]{0f, 5f, 0f, 0f});
        float closerScore = dist.compute(q, new float[]{9f, 0f, 0f, 0f});
        assertTrue(closerScore < tieScore, "fixture drift: a 必须严格比 b/c/d 近");
        assertEquals(3, first.size(), "3 of the 4 rows belong in top-3");
        assertEquals("a", first.get(0).getVectorId(), "the strictly-closer row must never be dropped");
        assertEquals(closerScore, first.get(0).getScore(), 0f);
        assertEquals(tieScore, first.get(1).getScore(), 0f);
        assertEquals(tieScore, first.get(2).getScore(), 0f);
        for (SearchResult r : first.subList(1, 3)) {
            assertTrue("b".equals(r.getVectorId()) || "c".equals(r.getVectorId())
                            || "d".equals(r.getVectorId()), "leaked a non-tie row: " + r);
        }
        assertEquals(orderedIds(first), orderedIds(second),
                "the same query twice must not return a different tie winner set");
    }

    @Test
    void searchRangeHonoursMaxDistanceOnTheFastPath() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        Collection coll = newCollection(IndexType.HNSW, pts);
        float[] q = query(13);

        List<SearchResult> wide = coll.search(q, 5, Filter.eq("tag", "rare"));
        assertEquals(1, coll.payloadFastPathHits());
        float threshold = wide.get(wide.size() - 1).getScore();

        List<SearchResult> narrow = coll.searchRange(q, threshold, 10, Filter.eq("tag", "rare"));
        assertEquals(2, coll.payloadFastPathHits(), "searchRange should use the fast path as well");
        for (SearchResult r : narrow) {
            assertTrue(r.getScore() <= threshold + 1e-6f, "leaked beyond maxDistance");
            assertEquals("rare", r.getPayload().get("tag"));
        }
        assertTrue(narrow.size() <= 5, "threshold cut nothing — fixture is degenerate");
        // 阈值就是第 5 近的距离 ⇒ 恰好 5 行落在阈值内（多一行说明 maxDistance 被忽略）
        assertEquals(5, narrow.size(), "exactly the 5 rows inside maxDistance, got " + narrow.size());
    }

    // ==================== 边界：借出去的视图不许漏到公开 API ====================

    /**
     * 快路径回表换成了 {@code index.getRef(id)}（直接把节点本体借给调用方），所以"公开取点
     * 仍然交复制品"这条线必须钉死 —— 它是整套借用设计唯一的外泄口子。
     * <p>
     * <b>猎物在 FLAT / IVF 那一侧</b>：{@code HnswIndex.get()} 本来就现场重建（向量 clone、
     * map 复制），而 {@code FlatIndex.get()} / {@code IvfIndex.get()} 交出的是<em>存着的那个
     * VectorPoint 本身</em> —— 一旦 {@code getPoint} 直接转发它，调用方 {@code setPayload}
     * 就改到了索引本体，而 payload 倒排仍按旧值记着（下次过滤既查不到新值、也摘不掉旧值）。
     * 所以这里比的是引用同一块内存这件事本身，而不是"改完看不出效果"（改克隆永远看不出效果，
     * 那种断言是空跑）。
     */
    @Test
    void publicPointStillIsACopy() {
        List<VectorPoint> pts = rarePoints(83, "rare");
        for (IndexType type : Arrays.asList(IndexType.HNSW, IndexType.FLAT, IndexType.IVF)) {
            Collection coll = newCollection(type, pts);
            VectorPoint got = coll.getPoint("d0");
            VectorPoint stored = coll.getIndex().getRef("d0");
            assertTrue(got != null && stored != null, type + ": fixture point missing");

            assertNotSame(got.vectorRef(), stored.vectorRef(),
                    type + ": getPoint() handed out the index's own float[] — mutating it writes"
                            + " the store through the public API");
            assertNotSame(got.payloadRef(), stored.payloadRef(),
                    type + ": getPoint() handed out the index's own payload map");

            got.setPayload("seq", -7);        // 改这份复制品
            got.setPayload("poison", "yes");
            assertEquals(pts.get(0).getPayload().get("seq"), coll.getPoint("d0").getPayload().get("seq"),
                    type + ": mutating a returned point leaked into the index");
            assertTrue(coll.search(query(22), 10, Filter.eq("seq", -7)).isEmpty(),
                    type + ": the inverted index picked up a mutation made on a returned copy");
            assertTrue(coll.search(query(22), 10, Filter.eq("poison", "yes")).isEmpty(),
                    type + ": a field added to a returned copy became filterable");
            assertTrue(coll.getPoint("d0").getPayload().get("lang") != null,
                    type + ": fixture payload lost");
        }
    }
}
