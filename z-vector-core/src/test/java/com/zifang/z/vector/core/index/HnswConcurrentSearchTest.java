package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.CosineDistance;
import com.zifang.z.vector.core.distance.Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HNSW 查询路径的并发门禁。
 *
 * <p>为什么值得钉：查询期现在用<b>线程私有</b>的工作区（位图 + 两个 primitive 堆 + 距离数组，
 * 见 {@code HnswIndex.SearchScratch}）来换掉"每次查询新建 HashSet/PriorityQueue/候选对象"。
 * 这类改造的失败方式是固定的那两种，而单线程测试一种都看不见：
 * <ul>
 *   <li>工作区不小心跨线程共享 ⇒ 结果随调度随机漂移，全绿跑不出红；</li>
 *   <li>槽位回收（删除→复用）与在飞的搜索互斥没做对 ⇒ 越界/NPE，或者更糟：把上一次搜索的
 *       "已访问"状态漏给下一次，之后这个线程的召回永久变差却无人知晓。</li>
 * </ul>
 * 所以这里两条判据分开钉：A 只跑读者，比对"与单线程逐条同解"（漏了隔离就红）；B 让写者一起
 * 搅动图，比对"不抛异常 + 结果里没有幽灵 id + 搅完之后召回仍站得住"。
 *
 * <p>线程数、轮数都是常量而不是 {@code availableProcessors()}：并发门禁的判定必须与跑它的机器
 * 无关，否则换一台核少一半的 CI 就等于换一套时序，红/绿不再可比。
 */
class HnswConcurrentSearchTest {

    private static final int N = 800;
    private static final int DIM = 32;
    private static final int CLUSTERS = 8;
    private static final double SIGMA = 0.12;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 100;
    private static final int EF_SEARCH = 64;
    private static final int TOP_K = 10;
    private static final int QUERIES = 16;
    private static final int READERS = 8;
    private static final int WRITERS = 2;
    private static final int ROUNDS = 150;

    private static final Distance DIST = new CosineDistance();

    private ExecutorService pool;

    @AfterEach
    void shutDownPool() throws InterruptedException {
        if (pool == null) return;
        pool.shutdownNow();
        // 收尾必须有上限：挂死的测试会拖着整条 reactor 一起挂（fork 停在 ?E，只留一个 kill 机会）。
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "worker threads did not exit");
    }

    private static List<VectorPoint> corpus() {
        java.util.Random r = new java.util.Random(11L);
        float[][] centers = new float[CLUSTERS][DIM];
        for (int c = 0; c < CLUSTERS; c++) {
            for (int i = 0; i < DIM; i++) centers[c][i] = r.nextFloat() * 2f - 1f;
        }
        List<VectorPoint> pts = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            float[] center = centers[r.nextInt(CLUSTERS)];
            float[] v = new float[DIM];
            for (int d = 0; d < DIM; d++) v[d] = (float) (center[d] + r.nextGaussian() * SIGMA);
            pts.add(new VectorPoint("p" + i, v));
        }
        return pts;
    }

    private static HnswIndex builtIndex() {
        List<VectorPoint> pts = corpus();
        HnswIndex idx = new HnswIndex(DIST, DIM, M, EF_CONSTRUCTION, EF_SEARCH, 20260926L);
        idx.build(pts);
        return idx;
    }

    private static float[][] queries() {
        List<VectorPoint> pts = corpus();
        java.util.Random r = new java.util.Random(77L);
        float[][] qs = new float[QUERIES][];
        for (int i = 0; i < QUERIES; i++) qs[i] = pts.get(r.nextInt(N)).getVector();
        return qs;
    }

    /** 结果的精确签名：id 顺序 + score 的原始位。差一个 id、差一比特都不算过。 */
    private static List<String> signature(List<SearchResult> got) {
        List<String> sig = new ArrayList<>(got.size());
        for (SearchResult r : got) {
            sig.add(r.getVectorId() + "@" + Float.floatToRawIntBits(r.getScore()));
        }
        return sig;
    }

    private static List<List<String>> baseline(HnswIndex idx, float[][] qs) {
        List<List<String>> want = new ArrayList<>(qs.length);
        for (float[] q : qs) want.add(signature(idx.search(q, TOP_K, null, Float.MAX_VALUE)));
        return want;
    }

    @Test
    void readersAloneGetExactlyTheSingleThreadedAnswers() throws InterruptedException {
        HnswIndex idx = builtIndex();
        float[][] qs = queries();
        List<List<String>> want = baseline(idx, qs);
        // 阳性对照：如果量具本身没在跑查询，后面那条"零失败"就毫无价值。
        assertEquals(QUERIES, want.size());
        for (List<String> w : want) assertEquals(TOP_K, w.size(), "baseline itself must return topK");

        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger done = new AtomicInteger();
        pool = Executors.newFixedThreadPool(READERS);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < READERS; t++) {
            pool.submit(() -> {
                try {
                    go.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        for (int i = 0; i < qs.length; i++) {
                            List<String> got = signature(idx.search(qs[i], TOP_K, null, Float.MAX_VALUE));
                            if (!got.equals(want.get(i))) {
                                failures.add(Thread.currentThread().getName() + " q" + i
                                        + " round" + round + " got" + got + " want" + want.get(i));
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add("interrupted: " + e);
                } catch (RuntimeException | Error e) {
                    failures.add("threw " + e + " @" + Thread.currentThread().getName());
                } finally {
                    done.incrementAndGet();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "readers did not finish in time");

        assertEquals(READERS, done.get(),
                "all reader tasks must reach their finally-block; a vanished task means the "
                + "thread never ran the loop at all and this test measured nothing");
        List<String> first = new ArrayList<>(failures);
        assertTrue(first.isEmpty(),
                READERS + " threads x " + ROUNDS + " rounds x " + QUERIES + " queries must each"
                + " get the same answers a single thread gets; thread-confined search state that"
                + " leaks across threads shows up exactly here. first failures: "
                + first.subList(0, Math.min(3, first.size())));

        // 搅完之后同一个索引还得答对：任何一个线程把"已访问"状态漏进共享结构，都会在这里显形。
        assertEquals(want, baseline(idx, qs), "answers drifted after the concurrent phase");
    }

    @Test
    void searchStaysSaneWhileTheGraphIsBeingRewritten() throws InterruptedException {
        final HnswIndex idx = builtIndex();
        final float[][] qs = queries();

        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger readerDone = new AtomicInteger();
        AtomicInteger writerDone = new AtomicInteger();
        pool = Executors.newFixedThreadPool(READERS + WRITERS);
        final CountDownLatch go = new CountDownLatch(1);

        for (int t = 0; t < READERS; t++) {
            pool.submit(() -> {
                try {
                    go.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        for (float[] q : qs) {
                            List<SearchResult> got = idx.search(q, TOP_K, null, Float.MAX_VALUE);
                            Set<String> seen = new HashSet<>();
                            for (SearchResult r : got) {
                                if (!seen.add(r.getVectorId())) {
                                    failures.add("duplicate id " + r.getVectorId() + " in one result");
                                }
                                // 幽灵 id：已经被删掉的点还在结果里。删点走 tombstone，
                                // search() 末尾按 tombstones 过滤，槽位一旦错乱这里就会漏。
                                if (idx.get(r.getVectorId()) == null) {
                                    failures.add("ghost id in results: " + r.getVectorId());
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | Error e) {
                    failures.add("reader threw " + e);
                } finally {
                    readerDone.incrementAndGet();
                }
            });
        }
        for (int w = 0; w < WRITERS; w++) {
            final int wid = w;
            pool.submit(() -> {
                try {
                    go.await();
                    java.util.Random r = new java.util.Random(900L + wid);
                    for (int i = 0; i < 120; i++) {
                        // 反复写同一批 id：add 走槽位复用，remove 把槽位退回 freeSlots，
                        // 这正是"回收槽位 vs 在飞搜索"的角力点。
                        String id = "w" + wid + "_" + (i % 7);
                        float[] v = new float[DIM];
                        for (int d = 0; d < DIM; d++) v[d] = r.nextFloat() * 2f - 1f;
                        idx.add(new VectorPoint(id, v));
                        idx.remove(id);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | Error e) {
                    failures.add("writer threw " + e);
                } finally {
                    writerDone.incrementAndGet();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "churn did not finish in time");

        assertEquals(READERS, readerDone.get(), "every reader task must reach its finally-block");
        assertEquals(WRITERS, writerDone.get(), "every writer task must reach its finally-block");
        List<String> first = new ArrayList<>(failures);
        assertTrue(first.isEmpty(),
                "concurrent rewrite must not break search: no exceptions, no duplicate ids, no"
                + " deleted ids resurfacing. first failures: "
                + first.subList(0, Math.min(3, first.size())));

        // 搅完之后图还能用：写者会往基点的邻居表里插反向边、被 prune 掉的边不会自己长回来，
        // 所以这里不比对"逐条同解"（那是上一条测试的活儿），只钉"召回没塌"。
        int hits = 0;
        List<VectorPoint> pts = corpus();
        for (float[] q : qs) {
            List<String> exact = bruteForceTopK(pts, q, TOP_K);
            for (SearchResult r : idx.search(q, TOP_K, null, Float.MAX_VALUE)) {
                if (exact.contains(r.getVectorId())) hits++;
            }
        }
        double recall = hits / (double) (QUERIES * TOP_K);
        assertTrue(recall >= 0.9,
                "recall@10 after the churn phase should stay >= 0.9, got " + recall);
    }

    private static List<String> bruteForceTopK(List<VectorPoint> pts, float[] q, int k) {
        List<VectorPoint> sorted = new ArrayList<>(pts);
        final float[] qq = q;
        Collections.sort(sorted, (a, b) -> Float.compare(
                DIST.compute(qq, a.vectorRef()), DIST.compute(qq, b.vectorRef())));
        List<String> ids = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, sorted.size()); i++) ids.add(sorted.get(i).getId());
        return ids;
    }
}
