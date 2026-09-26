package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.CosineDistance;
import com.zifang.z.vector.core.distance.Distance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HNSW 查询期分配量门禁。
 *
 * <p><b>钉的是"每次距离计算分配多少字节"，不是耗时。</b> 同一份代码在本机两次运行的
 * ns/query 能差 1.5 倍（GC、JIT、邻居进程都在动），拿耗时当判据会得到一条随时翻红的门禁；
 * 而分配量只由代码结构决定 —— 实测同一套参数下三轮的 alloc/query 完全相同（本轮重构后
 * 433、433、433；四个批次一路是 48,125 → 13,494 → 5,058 → 433）。
 *
 * <p><b>为什么还要钉 distCalls/query。</b> 只钉分配量会留一条作弊路：少搜一点，分配自然降。
 * 这条带子把"搜索走的路径没变"钉住 —— 上界挡"贪心下降白做了"（layer 0 从顶层入口点起跑，
 * 同一个图、distCalls 从 176.6 涨到 249.9），下界挡"beam 提前收工"（156.3）。
 * 到了每查询只剩 433 字节的今天，<b>字节这条尺已经看不见"多搜"了</b>：候选不再物化成对象，
 * 多出来的四十次距离计算一个字节都不多花（实测 B/distCall 反而从 2.5 掉到 1.7），
 * 所以轨迹必须由 distCalls 区间独立钉住，不能指望分配量顺带管到。
 *
 * <p>语料是聚簇的（真实 embedding 的形状）；i.i.d. 均匀随机向量所有点对距离都差不多，
 * 在那种数据上"搜得少"和"搜得对"分不出来。层级随机数播种 ⇒ 图可复现 ⇒ 门禁里的数字才钉得住。
 */
class HnswQueryAllocationTest {

    private static final int N = 1500;
    private static final int DIM = 64;
    private static final int CLUSTERS = 25;
    private static final double SIGMA = 0.12;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 100;
    private static final int EF_SEARCH = 64;
    private static final int TOP_K = 10;
    private static final int QUERIES = 200;
    private static final long LEVEL_SEED = 20260926L;

    /**
     * 本轮实测（同一台机器、同一份语料、三轮取值完全一致）：
     * alloc=433 B/query、distCalls=176.6/query ⇒ 2.5 B/distCall。
     * <p>
     * 阈值由变异电池（{@code ~/.cache/zv-alloc-teeth}）逐项卡出来，不是拍脑袋。这条线上一批
     * 取的是 28.0，而实测撤掉优化的三个变异体分别落在 7.1 / 7.9 / 11.3 —— 也就是说<b>旧线对
     * 本批优化的三倍回退完全视而不见</b>（三个 SURVIVED 全是这么来的）。取 4.0：距实测
     * 2.5 有 60% 余量（换 JDK 关掉压缩指针、对象头 12B→16B 也顶不红），距最近的可检回退 7.1
     * 还有 1.8 倍。
     * <p>
     * 反方向也要记账：轨迹类变异体（撤掉贪心下降 2.0、Phase 2 从顶层入口点起跑 1.7、beam
     * 提前收工 2.8）都<b>不该</b>红这条线 —— 它们涨的是距离计算次数而不是每字节的开销，
     * 挡它们的是下面那个 distCalls 区间。两条尺各管一头，这条只回答"每次距离计算费多少内存"。
     * <p>
     * 433 B/query 是稳态值：工作区的位图和各数组按线程存活、只增不还，所以"容量预留"省下的是
     * <b>每个线程第一几次查询</b>的翻倍链，不是每次查询。这一点电池里有账
     * （{@code scratch_buffers_start_tiny} 读数与对照逐字节相同），别把它当本条门禁的功劳。
     */
    private static final double MAX_BYTES_PER_DIST_CALL = 4.0;
    /**
     * distCalls/query 的容许区间：下界挡"少搜一点省分配"，上界挡"贪心下降白做了"。
     * 实测 176.6（三轮、六个 ef 值上逐条一致，本批重构前后一位不变）。电池读数：beam 提前
     * 收工（ef/2 准入）156.3，撤掉贪心下降 214.7，Phase 2 从顶层入口点起跑 249.9，
     * 结果堆不做堆排序 343.0（它连构建期的升序前提一起破坏，图就建坏了）——
     * 两端都留了同等宽度的余量。
     */
    private static final double MIN_DIST_CALLS_PER_QUERY = 168.0;
    private static final double MAX_DIST_CALLS_PER_QUERY = 185.0;

    /** 计数装饰器：distCalls/query 是这条门禁的分母，也是"搜索路径没变"的量具。 */
    private static final class Counting implements Distance {
        private final Distance delegate;
        private long calls;

        Counting(Distance delegate) { this.delegate = delegate; }

        @Override public float compute(float[] a, float[] b) {
            calls++;
            return delegate.compute(a, b);
        }

        @Override public DistanceMetric metric() { return delegate.metric(); }
    }

    private static Counting DIST;
    private static List<VectorPoint> corpus;
    private static float[][] queries;
    private static HnswIndex index;

    /** 线程分配量尺（JDK 8 就有的名字，项目 target 1.8）。 */
    private static com.sun.management.ThreadMXBean allocMx() {
        java.lang.management.ThreadMXBean mx =
                java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean)) return null;
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) mx;
        return t.isThreadAllocatedMemorySupported() ? t : null;
    }

    @BeforeAll
    static void buildOnce() {
        DIST = new Counting(new CosineDistance());
        corpus = clustered(N, DIM, CLUSTERS, SIGMA, 11L);
        index = new HnswIndex(DIST, DIM, M, EF_CONSTRUCTION, EF_SEARCH, LEVEL_SEED);
        index.build(corpus);
        Random r = new Random(77L);
        queries = new float[QUERIES][];
        for (int i = 0; i < QUERIES; i++) queries[i] = corpus.get(r.nextInt(N)).getVector();
    }

    /** 聚簇语料，与 {@code HnswGraphConnectivityTest} 同一套生成方式。 */
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

    private static final class Sample {
        final double bytesPerQuery;
        final double callsPerQuery;
        final double bytesPerCall;

        Sample(double bytesPerQuery, double callsPerQuery) {
            this.bytesPerQuery = bytesPerQuery;
            this.callsPerQuery = callsPerQuery;
            this.bytesPerCall = callsPerQuery > 0 ? bytesPerQuery / callsPerQuery : -1;
        }
    }

    /** 预热后测一轮：分配量、距离计算次数都按"每次查询"折算。 */
    private static Sample measure() {
        com.sun.management.ThreadMXBean mx = allocMx();
        assertTrue(mx != null,
                "thread allocation counter unavailable — 这条门禁会空转，必须判红而不是跳过");
        long tid = Thread.currentThread().getId();

        for (int round = 0; round < 3; round++) {
            for (float[] q : queries) index.search(q, TOP_K, null, Float.MAX_VALUE);
        }
        long bytes0 = mx.getThreadAllocatedBytes(tid);
        long calls0 = DIST.calls;
        for (float[] q : queries) {
            List<SearchResult> got = index.search(q, TOP_K, null, Float.MAX_VALUE);
            assertEquals(TOP_K, got.size(), "测量期间每次查询都要交满 topK 条，否则分母不作数");
        }
        long bytes = mx.getThreadAllocatedBytes(tid) - bytes0;
        long calls = DIST.calls - calls0;
        assertTrue(calls > 0, "距离计算计数为 0 ⇒ 量具没打到被测路径");
        return new Sample((double) bytes / QUERIES, (double) calls / QUERIES);
    }

    @Test
    void perDistanceCallAllocationStaysInTheMeasuredBand() {
        Sample s = measure();
        System.out.printf("[HnswQueryAllocationTest] alloc=%.0f B/query, %.1f B/distCall, "
                + "%.1f distCall/query%n", s.bytesPerQuery, s.bytesPerCall, s.callsPerQuery);

        assertTrue(s.bytesPerCall <= MAX_BYTES_PER_DIST_CALL,
                "HNSW query path allocates " + String.format(java.util.Locale.ROOT, "%.1f", s.bytesPerCall)
                        + " B per distance call, gate is " + MAX_BYTES_PER_DIST_CALL
                        + " (measured 2.5 after the candidates stopped being objects: the beam holds"
                        + " ordinals into a thread-local SearchScratch, and an absent payload is"
                        + " Collections.EMPTY_MAP rather than a fresh LinkedHashMap). alloc/query="
                        + s.bytesPerQuery + " B, distCalls/query=" + s.callsPerQuery
                        + " — 读数在带内却顶红这条线，说明查询路径重新开始造对象，不是搜索变多；"
                        + " 要改这条线只能重跑 ~/.cache/zv-alloc-teeth 重新取值。");
    }

    @Test
    void searchStillTraversesTheSameNumberOfNodes() {
        Sample s = measure();
        assertTrue(s.callsPerQuery >= MIN_DIST_CALLS_PER_QUERY,
                "distCalls/query dropped to " + s.callsPerQuery + " (gate floor "
                        + MIN_DIST_CALLS_PER_QUERY + "): the search stopped traversing. Saving"
                        + " allocation by searching less is a recall regression, not an optimization.");
        assertTrue(s.callsPerQuery <= MAX_DIST_CALLS_PER_QUERY,
                "distCalls/query rose to " + s.callsPerQuery + " (gate ceiling "
                        + MAX_DIST_CALLS_PER_QUERY + "): layer 0 no longer starts from where the"
                        + " greedy descent landed — starting from the top-level entry point costs"
                        + " 41% more distance calls for the same graph (measured 249.9 vs 176.6).");
    }

    /** 分配量降下来不许是拿召回换的。 */
    @Test
    void recallAtEf64IsUnchangedByTheAllocationWork() {
        int hits = 0;
        for (float[] q : queries) {
            List<String> exact = bruteForceTopK(q, TOP_K);
            for (SearchResult sr : index.search(q, TOP_K, null, Float.MAX_VALUE)) {
                if (exact.contains(sr.getVectorId())) hits++;
            }
        }
        double recall = hits / (double) (QUERIES * TOP_K);
        assertTrue(recall >= 0.9,
                "recall@10 at efSearch=" + EF_SEARCH + " should stay >= 0.9, got " + recall);
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
