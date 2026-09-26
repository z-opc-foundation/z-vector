package com.zifang.z.vector.core.collection;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.L2Distance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * payload 倒排快路径的<b>每查询分配量</b>门禁。
 *
 * <p>和 {@code HnswBuildAllocationTest} 同一个道理：钉耗时得到的是一条随时翻红的线（同一份
 * 代码两台机器的 µs/query 能差几倍），而"每次查询分配多少字节"由代码结构决定 —— 本机同一
 * 配置连跑数遍读数逐字节相同。
 *
 * <p><b>为什么光有"每查询字节上界"不够。</b> 快路径的开销形状是<em>命中集大小</em>乘以
 * "每命中一点"的成本，而 {@code topK} 只是其中交出结果的那一小截。把上界单独当尺子，看不见
 * "命中集变大时代价涨多快"；所以这里在<b>同一套语料、同一个向量索引</b>上跑两个命中规模
 * （72 / 36），取斜率：
 * <pre>
 *   B/hit = (B/query @72 命中 - B/query @36 命中) / 36
 * </pre>
 * 这条尺子直接钉住本模块的设计决定 —— <b>没进前 K 的命中点不许物化成 {@link SearchResult}</b>
 * （建一个就要复制一份 payload，而 72 个命中里只交 10 条）。两种"退回去"的写法各自会把斜率
 * 抬回 793.6 B/hit（每命中回表重建一个 VectorPoint）或 357.1 B/hit（每命中都建 SearchResult），
 * 而实测斜率是 57.6 B/hit。
 *
 * <p><b>第三条尺子是"便宜了但答错了"。</b> 两个规模的过滤结果都逐条（含顺序）与暴力法对齐。
 * 它的猎物是第三类错误，那一类错误在字节两条尺子上完全隐形：把插入排序的后移循环摘掉
 * （变异体 {@code M3_no_shift_insert}）字节读数与对照一致（7,744 / 57.1），只有这条红 ——
 * 它同时红掉 {@code PayloadIndexFastPathTest} 里四道排序相关的老断言，说明这条判据在套件里
 * 不是孤本。
 *
 * <p><b>记账：一支故意留下的等价变异。</b> {@code M4_ties_yield_to_later_equal}（丢弃判据从
 * {@code >=} 改成 {@code >}）<b>四条尺子全绿，而且是设计如此</b>：等距离的一组点里哪一个胜出
 * 不属于契约 —— 命中 id 集合来自 {@code PayloadIndex} 里的 {@code HashSet} 副本，它的遍历顺序
 * 既不是写入序也不是字典序，改前的"全量排序 + 稳定截断"同样只是"按那个顺序先到先得"。
 * 把它判红等于把一次散列遍历的偶然顺序固化成契约。
 *
 * <p>阈值取自 {@code ~/.cache/zv-build-alloc/run_fastpath_probe.sh} 的读数与
 * {@code ~/.cache/zv-build-alloc/fastpath-teeth/run-20260926150759/summary.txt}
 * （{@code zv_fastpath_teeth.py}：注入哪一处、涨回多少、哪条尺子红、波及面是否只红在预期的测试上）。
 */
class CollectionFastPathAllocationTest {

    private static final int N = 1152;
    private static final int DIM = 128;
    private static final int TOP_K = 10;
    private static final int QUERIES = 25;
    private static final long SEED = 4242L;

    /** 72 个命中用 tag，36 个命中用 tag2（后者是前者的子集 ⇒ 两组共享同一批距离）。 */
    private static final String WIDE = "wide";
    private static final String NARROW = "narrow";
    private static final int WIDE_STEP = 16;    // 1152/16 = 72
    private static final int NARROW_STEP = 32;  // 1152/32 = 36
    private static final int WIDE_HITS = N / WIDE_STEP;
    private static final int NARROW_HITS = N / NARROW_STEP;

    /**
     * 实测 7,760 B/query @72 命中（本机三次 JVM 启动：7,760 / 7,760 / 7,770 —— 抖动 0.13%，
     * 与跨 JDK 的余量相比可忽略；电池对照同值）。上界 9,600 = 实测 +24%，而最便宜的那个
     * 必检回退（每命中都建 SearchResult）落在 27,848 —— 线在两者之间，离哪一头都不近。
     */
    private static final double MAX_BYTES_PER_QUERY = 9_600;
    /**
     * 实测斜率 57.6 B/hit（5,688 → 7,760 B/query 跨 36 个额外命中）。上界 96 = 实测 +67%：
     * 余量要给"对象头 12B→16B（关压缩指针）、HashMap.Node 32B→40B"这类结构性放大留位置 ——
     * 这条斜率的构成就是 24 B 的借用 VectorPoint + 倒排每查询复制 id 集的散列节点。
     * 即便如此仍比最便宜的可检回退（357.1 B/hit）低 3.7 倍。
     */
    private static final double MAX_BYTES_PER_SCORED_HIT = 96;

    private static Collection coll;
    private static List<VectorPoint> corpus;
    private static float[][] queries;

    private static com.sun.management.ThreadMXBean allocMx() {
        java.lang.management.ThreadMXBean mx =
                java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean)) return null;
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) mx;
        return t.isThreadAllocatedMemorySupported() ? t : null;
    }

    /**
     * 前置体检：这条 classpath 上的 {@code z-vector-api} 必须是本树源码，而不是已发布的 1.0.3。
     * 已发布版的 {@code SearchResult} 构造器对空 payload 也照样 new 一个 {@code LinkedHashMap}，
     * 快路径每个落选点都要多付一份（同一现象在 {@code HnswBuildAllocationTest} 上是
     * 15,704 → 19,920）。用 {@code -pl z-vector-api,z-vector-core -am} 跑就好；拿
     * {@code -pl z-vector-core} 单跑必须判红，不能把别人的 jar 当自己的成绩。
     */
    private static void assertApiIsSourceTree() {
        SearchResult a = new SearchResult("a", 1f);
        SearchResult b = new SearchResult("b", 2f);
        assertTrue(a.getPayload() == b.getPayload(),
                "z-vector-api on this classpath is the published 1.0.3, not the source tree: its"
                        + " SearchResult copies a LinkedHashMap even for an empty payload, so every"
                        + " reading below is inflated. Re-run with"
                        + " `mvn -pl z-vector-api,z-vector-core -am test`.");
    }

    @BeforeAll
    static void buildOnce() {
        assertApiIsSourceTree();
        Random r = new Random(SEED);
        corpus = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            float[] v = new float[DIM];
            for (int j = 0; j < DIM; j++) v[j] = r.nextFloat();
            Map<String, Object> payload = new HashMap<>();
            payload.put("seq", i);
            if (i % WIDE_STEP == 0) payload.put("tag", WIDE);
            if (i % NARROW_STEP == 0) payload.put("tag2", NARROW);
            corpus.add(new VectorPoint("d" + i, v, payload));
        }
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("efSearch", 50);
        coll = new Collection(new VectorCollection("docs", DIM, DistanceMetric.L2,
                IndexType.HNSW, cfg));
        coll.upsertBatch(corpus);
        coll.buildIndex();
        queries = new float[QUERIES][];
        Random qr = new Random(SEED + 1);
        for (int i = 0; i < QUERIES; i++) {
            float[] q = new float[DIM];
            for (int j = 0; j < DIM; j++) q[j] = qr.nextFloat();
            queries[i] = q;
        }
    }

    /** 命中集规模必须真的大于 topK —— 否则"落选点不物化"这件事根本没发生，斜率尺子空转。 */
    private static void assertHitSetsOutnumberTopK() {
        assertTrue(WIDE_HITS > TOP_K && NARROW_HITS > TOP_K,
                "fixture drift: hits " + WIDE_HITS + "/" + NARROW_HITS + " vs topK " + TOP_K);
    }

    /** 预热三轮后测一轮，返回每查询字节。计数器不参与测量，但它不前进就说明量错了分支。 */
    private static double measureBytesPerQuery(Filter filter) {
        com.sun.management.ThreadMXBean mx = allocMx();
        assertTrue(mx != null, "线程分配量尺不可用 ⇒ 这条门禁会空转，判红而不是跳过");
        long tid = Thread.currentThread().getId();

        for (int rep = 0; rep < 4; rep++) {
            long fast0 = coll.payloadFastPathHits();
            for (int i = 0; i < QUERIES; i++) {
                List<SearchResult> got = coll.search(queries[i], TOP_K, filter);
                assertEquals(TOP_K, got.size(),
                        "测量期间每次查询都要交满 topK 条，否则分母不作数");
            }
            assertEquals(QUERIES, coll.payloadFastPathHits() - fast0,
                    "快路径计数没跟着查询走 ⇒ 量的是别的分支（分流条件变了就去改语料，别改这条线）");
        }
        long fastBefore = coll.payloadFastPathHits();
        long b0 = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < QUERIES; i++) coll.search(queries[i], TOP_K, filter);
        long bytes = mx.getThreadAllocatedBytes(tid) - b0;
        assertEquals(QUERIES, coll.payloadFastPathHits() - fastBefore,
                "测量那一轮本身也必须全走快路径");
        return bytes / (double) QUERIES;
    }

    @Test
    void perQueryAllocationStaysInTheMeasuredBand() {
        assertHitSetsOutnumberTopK();
        double perQuery = measureBytesPerQuery(Filter.eq("tag", WIDE));
        System.out.printf("[CollectionFastPathAllocationTest] %.0f B/query @%d hits, topK=%d%n",
                perQuery, WIDE_HITS, TOP_K);
        assertTrue(perQuery <= MAX_BYTES_PER_QUERY,
                "fast path allocates " + String.format(java.util.Locale.ROOT, "%.0f", perQuery)
                        + " B/query, gate is " + MAX_BYTES_PER_QUERY + "。读数见上面打印；要动这条线"
                        + " 只能重跑 ~/.cache/zv-build-alloc/run_fastpath_probe.sh 取值。");
    }

    @Test
    void scoringOneMoreHitCostsAScanNotAnObjectGraph() {
        assertHitSetsOutnumberTopK();
        double wide = measureBytesPerQuery(Filter.eq("tag", WIDE));
        double narrow = measureBytesPerQuery(Filter.eq("tag2", NARROW));
        double slope = (wide - narrow) / (WIDE_HITS - NARROW_HITS);
        System.out.printf("[CollectionFastPathAllocationTest] %.0f B/query @%d hits,"
                        + " %.0f B/query @%d hits => %.1f B per extra scored hit%n",
                wide, WIDE_HITS, narrow, NARROW_HITS, slope);
        assertTrue(slope > 0, "斜率非正 ⇒ 两次测量没有差别，量具或语料漂了 ("
                + wide + " vs " + narrow + ")");
        assertTrue(slope <= MAX_BYTES_PER_SCORED_HIT,
                "每个落选命中花 " + String.format(java.util.Locale.ROOT, "%.1f", slope)
                        + " B，上界 " + MAX_BYTES_PER_SCORED_HIT + "。多出来的只可能是"
                        + "『给没进前 K 的点建 SearchResult（含一份 payload 复制）』或"
                        + "『回表重建 VectorPoint（clone 向量 + 复制 map）』—— 两者都是本模块"
                        + "特意撤掉的东西，电池读数 793.6 / 357.1 B/hit。");
    }

    /** 字节降下来不许是拿结果换的：两个规模都要与暴力法逐条同序同名。 */
    @Test
    void cheaperReadingIsStillTheExactFilteredTopK() {
        String[][] cases = {{"tag", WIDE}, {"tag2", NARROW}};
        for (String[] c : cases) {
            Filter filter = Filter.eq(c[0], c[1]);
            for (int i = 0; i < 5; i++) {
                float[] q = queries[i];
                List<String> exact = bruteForceTopK(q, c[0], c[1], TOP_K);
                long before = coll.payloadFastPathHits();
                List<SearchResult> got = coll.search(q, TOP_K, filter);
                assertEquals(before + 1, coll.payloadFastPathHits(),
                        "这次查询没走快路径 ⇒ 比对的就不是快路径的答案");
                assertEquals(exact.size(), got.size(), c[0] + "=" + c[1] + ": 条数不对");
                for (int j = 0; j < exact.size(); j++) {
                    assertEquals(exact.get(j), got.get(j).getVectorId(),
                            c[0] + "=" + c[1] + " 第 " + j + " 条不是第 " + j + " 近");
                }
            }
        }
    }

    /** 只在与快路径同一套语义下比对：命中集内全排序取前 K（快路径本身不做任何近似）。 */
    private static List<String> bruteForceTopK(float[] q, String field, String want, int topK) {
        L2Distance dist = new L2Distance();
        List<Object[]> scored = new ArrayList<>();
        for (VectorPoint p : corpus) {
            if (!want.equals(p.getPayload().get(field))) continue;
            scored.add(new Object[]{p.getId(), dist.compute(q, p.getVector())});
        }
        scored.sort(Comparator.comparingDouble(a -> (Float) a[1]));
        assertTrue(scored.size() > topK,
                "fixture drift: 命中集 " + scored.size() + " 不大于 topK，比对不到截断那一半");
        List<String> ids = new ArrayList<>(topK);
        for (int i = 0; i < topK; i++) ids.add((String) scored.get(i)[0]);
        return ids;
    }
}
