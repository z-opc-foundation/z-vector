package com.zifang.z.vector.core;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.collection.Collection;
import com.zifang.z.vector.core.distance.CosineDistance;
import com.zifang.z.vector.core.distance.L2Distance;
import com.zifang.z.vector.core.index.FlatIndex;
import com.zifang.z.vector.core.index.HnswIndex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * ANN 基准 —— 输出机器可读的一行一条指标，用于优化前后对比。
 * <p>
 * 为什么另起一个而不是用 {@code IndexBenchmarkTest}：那个用例里
 * <pre>{@code float[] query = new float[dim]; new Random(99).nextFloat(); }</pre>
 * 把 nextFloat() 的结果丢掉了，查询向量<b>全零</b>。COSINE 下全零向量走的是
 * "零向量返回 2.0" 分支，所有候选距离相同，测到的不是真实检索路径。
 * <p>
 * 本类每个指标跑 {@value #ROUNDS} 轮取<b>中位数</b>，单次绿不算数。
 */
public final class AnnBench {

    private static final int ROUNDS = 5;

    /**
     * 分配量尺。
     * <p>
     * 本机经常有别的会话在抢 CPU（实测同一段代码两次跑出 19ms 和 180ms），耗时的跨进程
     * 可比性很差。线程分配字节数只由代码决定，负载再高也不会漂，所以优化前后用它对账；
     * 耗时则另加 min-of-N（外来干扰只会让单次变慢，不会让 min 变小）。
     */
    private static final com.sun.management.ThreadMXBean ALLOC_MX = allocMx();

    private static com.sun.management.ThreadMXBean allocMx() {
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean)) return null;
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) mx;
        // 用 JDK 8 就有的名字（getCurrentThreadAllocatedBytes 是 9+，本项目 target 1.8）
        return t.isThreadAllocatedMemorySupported() ? t : null;
    }

    private static long allocBytes() {
        return ALLOC_MX == null ? -1L
                : ALLOC_MX.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private AnnBench() {}

    public static void main(String[] args) {
        int n = intArg(args, "-n", 20000);
        int dim = intArg(args, "-d", 64);
        int queries = intArg(args, "-q", 200);
        int topK = intArg(args, "-k", 10);
        long seed = Long.parseLong(arg(args, "-seed", "42"));

        System.out.println("# AnnBench n=" + n + " dim=" + dim + " queries=" + queries
                + " topK=" + topK + " seed=" + seed + " rounds=" + ROUNDS);

        List<VectorPoint> data = randomPoints(n, dim, seed);
        float[][] qs = randomQueries(queries, dim, seed + 1);

        // ---------- ground truth (Flat, 精确) ----------
        FlatIndex truthIdx = new FlatIndex(new L2Distance(), dim);
        truthIdx.build(data);
        Set<String>[] truth = groundTruth(truthIdx, qs, topK);

        // ---------- Flat 吞吐 ----------
        double[] flatMs = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < queries; i++) truthIdx.search(qs[i], topK, null, Float.MAX_VALUE);
            flatMs[r] = (System.nanoTime() - t0) / 1e6;
        }
        long alloc0 = allocBytes();
        for (int i = 0; i < queries; i++) truthIdx.search(qs[i], topK, null, Float.MAX_VALUE);
        long alloc1 = allocBytes();
        print("flat_l2_query_ms_median", median(flatMs), queries);
        print("flat_l2_query_ms_min", min(flatMs), queries);
        print("flat_l2_bytes_per_query", alloc0 < 0 || alloc1 < 0 ? -1 : (alloc1 - alloc0) / (double) queries, 0);
        print("flat_l2_recall_at_topk", 1.0, 0);

        // ---------- HNSW / L2 ----------
        for (int m : new int[]{16}) {
            for (int ef : new int[]{64, 128}) {
                HnswIndex hnsw = new HnswIndex(new L2Distance(), dim, m, 200, ef);
                long b0 = System.nanoTime();
                hnsw.build(data);
                double buildMs = (System.nanoTime() - b0) / 1e6;
                print("hnsw_l2_build_ms", buildMs, 0);

                double[] ms = new double[ROUNDS];
                double recall = -1;
                for (int r = 0; r < ROUNDS; r++) {
                    long t0 = System.nanoTime();
                    for (int i = 0; i < queries; i++) hnsw.search(qs[i], topK, null, Float.MAX_VALUE);
                    ms[r] = (System.nanoTime() - t0) / 1e6;
                    if (r == ROUNDS - 1) recall = recallOf(hnsw, qs, truth, topK);
                }
                print("hnsw_l2_m" + m + "_ef" + ef + "_query_ms_median", median(ms), queries);
                print("hnsw_l2_m" + m + "_ef" + ef + "_query_ms_min", min(ms), queries);
                long ha0 = allocBytes();
                for (int i = 0; i < queries; i++) hnsw.search(qs[i], topK, null, Float.MAX_VALUE);
                long ha1 = allocBytes();
                print("hnsw_l2_m" + m + "_ef" + ef + "_bytes_per_query",
                        ha0 < 0 || ha1 < 0 ? -1 : (ha1 - ha0) / (double) queries, 0);
                print("hnsw_l2_m" + m + "_ef" + ef + "_recall_at_topk", recall, 0);
            }
        }

        // ---------- HNSW / COSINE（RAG 默认度量，此前最慢） ----------
        FlatIndex cosTruth = new FlatIndex(new CosineDistance(), dim);
        cosTruth.build(data);
        Set<String>[] cosGt = groundTruth(cosTruth, qs, topK);
        HnswIndex cos = new HnswIndex(new CosineDistance(), dim, 16, 200, 100);
        long c0 = System.nanoTime();
        cos.build(data);
        print("hnsw_cos_build_ms", (System.nanoTime() - c0) / 1e6, 0);
        double[] cms = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < queries; i++) cos.search(qs[i], topK, null, Float.MAX_VALUE);
            cms[r] = (System.nanoTime() - t0) / 1e6;
        }
        print("hnsw_cos_query_ms_median", median(cms), queries);
        print("hnsw_cos_recall_at_topk", recallOf(cos, qs, cosGt, topK), 0);

        // ---------- 带过滤的召回（HNSW 先取 ef 再过滤，容易塌） ----------
        filteredRecallBench(data, qs, topK);
    }

    private static void filteredRecallBench(List<VectorPoint> data, float[][] qs, int topK) {
        // 一半点 lang=zh，一半 en；查询要求 lang=zh。
        // 若图搜只返回 ef 个候选再过滤，命中的 zh 数会显著低于 topK。
        List<VectorPoint> tagged = new ArrayList<>(data.size());
        int i = 0;
        for (VectorPoint p : data) {
            java.util.Map<String, Object> pl = new java.util.LinkedHashMap<>();
            pl.put("lang", (i++ & 1) == 0 ? "zh" : "en");
            tagged.add(new VectorPoint(p.getId(), p.getVector(), pl));
        }
        HnswIndex hnsw = new HnswIndex(new L2Distance(), qs[0].length, 16, 200, 64);
        hnsw.build(tagged);
        java.util.Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("lang", "zh");

        FlatIndex ft = new FlatIndex(new L2Distance(), qs[0].length);
        ft.build(tagged);

        int shortQueries = 0;
        double totalReturned = 0;
        int idSetMatches = 0;
        for (float[] q : qs) {
            List<SearchResult> got = hnsw.search(q, topK, f, Float.MAX_VALUE);
            List<SearchResult> want = ft.search(q, topK, f, Float.MAX_VALUE);
            totalReturned += got.size();
            if (got.size() < want.size()) shortQueries++;
            Set<String> gotIds = new HashSet<>();
            for (SearchResult r : got) gotIds.add(r.getVectorId());
            Set<String> wantIds = new HashSet<>();
            for (SearchResult r : want) wantIds.add(r.getVectorId());
            if (gotIds.equals(wantIds)) idSetMatches++;
        }
        print("hnsw_filtered_avg_returned", totalReturned / qs.length, topK);
        print("hnsw_filtered_short_query_ratio", (double) shortQueries / qs.length, 0);
        // "返回 10 行"只证明没塌，不证明是**哪** 10 行 —— 集合一致才算真过滤最近邻
        print("hnsw_filtered_idset_match_ratio", (double) idSetMatches / qs.length, 0);

        collectionFilteredPathBench(qs[0].length, qs, topK);
    }

    /**
     * Collection 层过滤搜索 A/B：payload 倒排快路径 vs 过取 + post-filter 慢路径。
     * <p>
     * 两侧是同一份数据、同一个 filter，唯一区别是把 slow 侧的倒排故意弄得与向量索引
     * 不同步（{@code Collection} 的分流守卫会因此禁用快路径）。若快慢两侧其实走了同一条
     * 路，两个耗时必然相等，下面的 {@code *_fastpath_hits} 就是用来戳穿这种假对比的。
     */
    private static void collectionFilteredPathBench(int dim, float[][] qs, int topK) {
        int n = 5000;
        List<VectorPoint> pts = new ArrayList<>(n);
        Random r = new Random(4321L);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) v[j] = r.nextFloat();
            Map<String, Object> pl = new LinkedHashMap<>();
            pl.put("lang", (i & 1) == 0 ? "zh" : "en");
            if (i % 100 == 0) pl.put("tag", "rare");        // 1% 选择率
            pts.add(new VectorPoint("d" + i, v, pl));
        }

        Collection fast = newFilteredCollection(dim, pts);
        Collection slow = newFilteredCollection(dim, pts);
        Map<String, Object> desync = new LinkedHashMap<>();
        desync.put("tag", "rare");
        slow.getIndex().add(new VectorPoint("desync", pts.get(0).getVector(), desync));

        Filter only = Filter.eq("tag", "rare");
        // 分流自检：快侧每次都进快路径，慢侧一次都不进
        for (float[] q : qs) {
            fast.search(q, topK, only);
            slow.search(q, topK, only);
        }
        print("collection_filtered_fast_fastpath_hits", fast.payloadFastPathHits(), qs.length);
        print("collection_filtered_slow_fastpath_hits", slow.payloadFastPathHits(), 0);

        double[] fastMs = new double[ROUNDS];
        double[] slowMs = new double[ROUNDS];
        for (int rd = 0; rd < ROUNDS; rd++) {
            long t0 = System.nanoTime();
            for (float[] q : qs) fast.search(q, topK, only);
            fastMs[rd] = (System.nanoTime() - t0) / 1e6;
            long t1 = System.nanoTime();
            for (float[] q : qs) slow.search(q, topK, only);
            slowMs[rd] = (System.nanoTime() - t1) / 1e6;
        }
        print("collection_filtered_fast_ms_median", median(fastMs), qs.length);
        print("collection_filtered_slow_ms_median", median(slowMs), qs.length);
        print("collection_filtered_speedup", median(slowMs) / median(fastMs), 0);

        // 快路径不做任何近似：结果集合必须与精确扫（Flat + 过滤）逐条一致
        FlatIndex exact = new FlatIndex(new L2Distance(), dim);
        exact.build(pts);
        // FlatIndex.search 的 4 参重载收的是扁平 payload Map，不是 Filter
        Map<String, Object> flatFilter = new LinkedHashMap<>();
        flatFilter.put("tag", "rare");
        int fastExact = 0, slowExact = 0;
        for (float[] q : qs) {
            Set<String> want = new HashSet<>();
            for (SearchResult x : exact.search(q, topK, flatFilter, Float.MAX_VALUE)) want.add(x.getVectorId());
            Set<String> gotFast = new HashSet<>();
            for (SearchResult x : fast.search(q, topK, only)) gotFast.add(x.getVectorId());
            Set<String> gotSlow = new HashSet<>();
            for (SearchResult x : slow.search(q, topK, only)) gotSlow.add(x.getVectorId());
            if (want.equals(gotFast)) fastExact++;
            if (want.equals(gotSlow)) slowExact++;
        }
        print("collection_filtered_fast_exact_ratio", (double) fastExact / qs.length, 0);
        print("collection_filtered_slow_exact_ratio", (double) slowExact / qs.length, 0);
    }

    private static Collection newFilteredCollection(int dim, List<VectorPoint> pts) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("efSearch", 64);
        Collection coll = new Collection(new VectorCollection("docs", dim,
                DistanceMetric.L2, IndexType.HNSW, cfg));
        coll.upsertBatch(pts);
        coll.buildIndex();
        return coll;
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private static Set<String>[] groundTruth(FlatIndex idx, float[][] qs, int topK) {
        Set<String>[] out = new HashSet[qs.length];
        for (int i = 0; i < qs.length; i++) {
            out[i] = new HashSet<>();
            for (SearchResult r : idx.search(qs[i], topK, null, Float.MAX_VALUE)) out[i].add(r.getVectorId());
        }
        return out;
    }

    private static double recallOf(HnswIndex idx, float[][] qs, Set<String>[] truth, int topK) {
        double acc = 0;
        for (int i = 0; i < qs.length; i++) {
            List<SearchResult> got = idx.search(qs[i], topK, null, Float.MAX_VALUE);
            int hits = 0;
            for (SearchResult r : got) if (truth[i].contains(r.getVectorId())) hits++;
            acc += (double) hits / topK;
        }
        return acc / qs.length;
    }

    private static List<VectorPoint> randomPoints(int n, int dim, long seed) {
        Random r = new Random(seed);
        List<VectorPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) v[j] = r.nextFloat();
            out.add(new VectorPoint("d" + i, v));
        }
        return out;
    }

    private static float[][] randomQueries(int q, int dim, long seed) {
        Random r = new Random(seed);
        float[][] out = new float[q][];
        for (int i = 0; i < q; i++) {
            out[i] = new float[dim];
            for (int j = 0; j < dim; j++) out[i][j] = r.nextFloat();
        }
        return out;
    }

    private static void print(String key, double value, int extra) {
        System.out.println("METRIC\t" + key + "\t" + String.format("%.6f", value));
    }

    private static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2.0;
    }

    /** 外来负载只会让单次计时变长，不会让最小值变小，所以 min 是抖动机器上最稳的一端。 */
    private static double min(double[] a) {
        double m = Double.MAX_VALUE;
        for (double v : a) if (v < m) m = v;
        return m;
    }

    private static String arg(String[] a, String key, String def) {
        for (int i = 0; i < a.length - 1; i++) if (key.equals(a[i])) return a[i + 1];
        return def;
    }

    private static int intArg(String[] a, String key, int def) {
        return Integer.parseInt(arg(a, key, String.valueOf(def)));
    }

    @SuppressWarnings("unused")
    private static final Comparator<SearchResult> BY_SCORE =
            Comparator.comparingDouble(SearchResult::getScore);
}
