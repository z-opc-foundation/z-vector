package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.Distance;
import com.zifang.z.vector.core.distance.L2Distance;
import com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HNSW 构建期分配量门禁。
 *
 * <p><b>钉的是"每插入一个点分配多少字节"，不是耗时。</b> 和 {@code HnswQueryAllocationTest}
 * 同一个道理：同一份代码在两台机器上 ns/insert 能差几倍（250 那台 JDK 8 的机器上一次 fsync
 * 就是 8 ms，本机 67 µs），拿耗时当判据只会得到一条随时翻红的线；分配量由代码结构决定，
 * 本机同一配置两次独立 JVM 运行读数逐字节相同（15,704 / 15,704），而 250 那台 JDK 1.8.0_362
 * 上跑<b>同一条门禁</b>打印的是同一个三元组（15,704 / 4172.2 / d353b6b8…，见
 * {@code ~/.cache/zv-build-alloc/logs/jdk8_gate_summary.txt}）—— 跨 JDK 精确到字节。
 * 独立的 BuildAllocOne 探针读到 15,716（JDK 25）/ 15,711（JDK 8），差 12 B，是探针自己的
 * 语料构造与 JVM 差异，不是被测路径的；下面 5% 的余量给的是"还没见过的机器"。
 *
 * <p><b>这条线为什么必须钉住"图没变"。</b> 构建期最省分配的做法是少建边、少搜候选 —— 那全是
 * 召回损失，不是优化。所以这里除了字节上界，还钉两把尺：
 * <ul>
 *   <li>{@code dist/insert} 区间：搜索轨迹。字节这条尺看不见"少搜"（撤掉一半度数的变异体
 *       反而<b>省</b>了 4% 的字节），只有这条看得见；</li>
 *   <li>图指纹（每个节点的层数 + 各层邻居表<b>按表内顺序</b>的 id + 向量位）：直接把"建出来
 *       的还是不是同一张图"钉死。邻居顺序进指纹是有意的 —— beam 走的就是这个顺序，
 *       "同样的边集、换了顺序"同样是行为变化。</li>
 * </ul>
 *
 * <p>阈值取自 {@code ~/.cache/zv-build-alloc/zv_build_teeth.py} 的变异电池（读数落在
 * {@code ~/.cache/zv-build-alloc/teeth/run-*}），并在 250（JDK 1.8.0_362）上复算过同一套
 * 配置，跨 JDK 的基线差写进了下面的余量里。
 */
class HnswBuildAllocationTest {

    private static final int N = 1500;
    private static final int DIM = 128;
    private static final int CLUSTERS = 8;
    private static final double SIGMA = 0.05;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 200;
    private static final int WINDOW_START = 300;
    private static final long LEVEL_SEED = 12345L;
    private static final long CORPUS_SEED = 11L;

    /**
     * B/insert 上界。三处基线（同一配置 M=16、efConstruction=200）：本测试在 JDK 25 = 15,704；
     * BuildAllocOne 在 JDK 25 = 15,716、在 250 的 JDK 1.8.0_362 = 15,711 —— 跨 JDK 只差 5 B
     * （改前的老代码同一对比是 51,713 vs 43,860，差 15%，所以老代码根本没法钉绝对值）。
     * <p>电池里每支"只改分配、不改图"的变异在 JDK 25 上的代价（图指纹逐字节 = 基线）：
     * +55（payload 包装回来，噪声地板以下，<b>钉不住也不该钉</b>）、+1,753（每次搜索换新候选数组）、
     * +4,946（修剪每次取新快照数组）、+6,660（修剪改用 JDK sort）、+7,553（正向物化每个候选）、
     * +24,929（修剪不再复用边对象）。
     * <p>16,500 = 最高基线 +784（5.0% 余量），同时比最便宜的必杀变异还低 969 B ⇒ 这条线的灵敏度
     * 是 1.11 倍，不是"只看得住翻倍级的塌方"。
     * <p>灵敏度不是推算出来的，是拿本门禁在 mvn 里跑出来的（{@code ~/.cache/zv-build-alloc/
     * zv_junit_teeth.py}，读数在 {@code junit-teeth/run-*}）：注入"每次搜索换新候选数组"⇒ 这条尺
     * 红（17,455），注入"半数度数"⇒ 15,075 <b>比基线还便宜</b>、这条尺放过（改由下面的 dist 带与
     * 图指纹红），还原后 4 例全绿。三把尺各管各的，这条线不需要大到能盖住后一种。
     */
    private static final double MAX_BYTES_PER_INSERT = 16_500;

    /**
     * dist/insert 区间：实测 4,172.2，本机与 250、两份量具（BuildAllocOne 与本测试）四个读数
     * 逐位相同 —— 它是确定性的，所以这里给 ±0.25 就等于"必须精确相等"（1 次距离计算摊到
     * 1,200 次插入是 0.0008，摊出 1.0 的位移要少 1,200 次计算）。
     * 下界挡"少搜省分配"，上界挡"多搜"。
     */
    private static final double MIN_DIST_CALLS_PER_INSERT = 4172.0;
    private static final double MAX_DIST_CALLS_PER_INSERT = 4172.5;

    /**
     * 图指纹（M=16、efConstruction=200）。同一个值三处一致：本测试（JDK 25）、
     * BuildAllocOne/GraphIdentityProbe（JDK 25）、GraphIdentityProbe（250 的 JDK 1.8.0_362）。
     * 注意 GraphIdentityProbe 是<b>另一套实现</b>的摘要（邻居表顺序 + 向量位，取法不同），
     * 两边给出同一个 md5 才算这值不是自证。
     */
    private static final String GRAPH_FINGERPRINT = "d353b6b875ee516c57ccd31ad0813c36";

    /** 计数装饰器：dist/insert 是这条门禁的分母，也是"搜索没变少"的量具。 */
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

    private static List<VectorPoint> corpus;

    private static com.sun.management.ThreadMXBean allocMx() {
        java.lang.management.ThreadMXBean mx =
                java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean)) return null;
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) mx;
        return t.isThreadAllocatedMemorySupported() ? t : null;
    }

    /** 聚簇语料，与 {@code BuildAllocProbe} / {@code GraphIdentityProbe} 同一套生成方式。 */
    private static List<VectorPoint> clustered(int n, long seed) {
        Random r = new Random(seed);
        float[][] centers = new float[CLUSTERS][DIM];
        for (int c = 0; c < CLUSTERS; c++) {
            for (int d = 0; d < DIM; d++) centers[c][d] = r.nextFloat() * 2f - 1f;
        }
        List<VectorPoint> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] base = centers[i % CLUSTERS];
            float[] v = new float[DIM];
            for (int d = 0; d < DIM; d++) v[d] = base[d] + (float) r.nextGaussian() * (float) SIGMA;
            pts.add(new VectorPoint("p" + i, v));
        }
        return pts;
    }

    @BeforeAll
    static void buildCorpus() {
        corpus = clustered(N, CORPUS_SEED);
    }

    private static final class Sample {
        final double bytesPerInsert;
        final double callsPerInsert;
        final String fingerprint;
        final int edges;
        final int size;

        Sample(double bytesPerInsert, double callsPerInsert, String fingerprint, int edges, int size) {
            this.bytesPerInsert = bytesPerInsert;
            this.callsPerInsert = callsPerInsert;
            this.fingerprint = fingerprint;
            this.edges = edges;
            this.size = size;
        }
    }

    /** 建两遍：第一遍让工作区数组长到高水位，第二遍才是稳态读数。 */
    private static Sample measure() {
        com.sun.management.ThreadMXBean mx = allocMx();
        assertTrue(mx != null,
                "thread allocation counter unavailable — 这条门禁会空转，必须判红而不是跳过");
        long tid = Thread.currentThread().getId();
        Counting dist = null;
        HnswIndex idx = null;
        long bytes = -1;
        for (int rep = 0; rep < 2; rep++) {
            dist = new Counting(new L2Distance());
            idx = new HnswIndex(dist, DIM, M, EF_CONSTRUCTION, 64, LEVEL_SEED);
            for (int i = 0; i < WINDOW_START; i++) idx.add(corpus.get(i));
            long b0 = mx.getThreadAllocatedBytes(tid);
            long c0 = dist.calls;
            for (int i = WINDOW_START; i < N; i++) idx.add(corpus.get(i));
            long b1 = mx.getThreadAllocatedBytes(tid);
            long c1 = dist.calls;
            int n = N - WINDOW_START;
            assertEquals(N, idx.size(), "测量窗口内一个点都不能丢，否则分母不作数");
            assertTrue(c1 > c0, "距离计算计数没动 ⇒ 量具没打到被测路径");
            if (rep == 1) {
                bytes = b1 - b0;
                dist.calls = c1 - c0;
            }
        }
        String fp = fingerprint(idx);
        int edges = 0;
        for (HnswNodeData d : idx.exportNodes()) {
            for (int l = 0; l < d.neighbors.size(); l++) edges += d.neighbors.get(l).size();
        }
        int measured = N - WINDOW_START;
        return new Sample((double) bytes / measured, (double) dist.calls / measured, fp, edges, idx.size());
    }

    /** 层数 + 各层邻居表（表内顺序计入）+ 向量位。 */
    private static String fingerprint(HnswIndex idx) {
        List<HnswNodeData> nodes = new ArrayList<HnswNodeData>(idx.exportNodes());
        Collections.sort(nodes, new Comparator<HnswNodeData>() {
            @Override public int compare(HnswNodeData a, HnswNodeData b) {
                return a.id.compareTo(b.id);
            }
        });
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder line = new StringBuilder(256);
            for (HnswNodeData d : nodes) {
                line.setLength(0);
                line.append(d.id).append('|').append(d.level);
                for (int l = 0; l < d.neighbors.size(); l++) {
                    List<String> ids = d.neighbors.get(l);
                    line.append("|L").append(l).append(':').append(ids.size());
                    for (String id : ids) line.append(',').append(id);
                }
                md.update(line.toString().getBytes("UTF-8"));
                ByteArrayOutputStream bos = new ByteArrayOutputStream(d.vector.length * 4);
                DataOutputStream out = new DataOutputStream(bos);
                for (float f : d.vector) out.writeFloat(f);
                out.flush();
                md.update(bos.toByteArray());
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            assertEquals(N, nodes.size(), "指纹覆盖的节点数不对 ⇒ 这个值钉不住任何东西");
            return hex.toString();
        } catch (Exception e) {
            throw new AssertionError("fingerprint gauge failed: " + e, e);
        }
    }

    /**
     * 这条尺只认"本树的 z-vector-api"。
     *
     * <p>{@code mvn -pl z-vector-core test}（不带 {@code -am}）会让 Maven 从 Central 取已发布的
     * 1.0.3 jar 顶上，而那版 {@code SearchResult(String, float)} 每条结果都新建一个
     * {@code LinkedHashMap} 存 payload；本树把空 payload 换成共享的 {@code Collections.emptyMap()}。
     * 实测同一个构建在两种 classpath 下的差别：19,920 B/insert（Central 的 jar）vs 15,704（本树），
     * 差 4,216 —— 而 dist/insert 与图指纹两边逐位相同（4172.2 / d353b6b8…），说明只有字节这把尺
     * 会被 classpath 拖动。不先卡这一条，过期 m2 就会读成一次"分配暴涨"的假红。
     */
    private static void assertApiIsSourceTree() {
        SearchResult a = new SearchResult("a", 1f);
        SearchResult b = new SearchResult("b", 2f);
        assertTrue(a.getPayload() == b.getPayload(),
                "z-vector-api on this classpath is the published 1.0.3, not the source tree: two"
                        + " payload-free SearchResults must share one empty map but they don't."
                        + " 已发布那版每条结果多分配一个 LinkedHashMap ⇒ 这条门禁的基线会整体上移约"
                        + " 4.2 kB/insert（实测 19,920 vs 15,704），红色不代表构建路径退化。"
                        + " 用 `mvn -pl z-vector-api,z-vector-core -am test` 重跑，或先把本树的"
                        + " z-vector-api install 进本地仓库。");
    }

    @Test
    void perInsertAllocationStaysInTheMeasuredBand() {
        assertApiIsSourceTree();
        Sample s = measure();
        System.out.printf("[HnswBuildAllocationTest] alloc=%.0f B/insert, %.1f distCall/insert,"
                + " edges=%d, fingerprint=%s%n", s.bytesPerInsert, s.callsPerInsert, s.edges, s.fingerprint);
        assertTrue(s.bytesPerInsert <= MAX_BYTES_PER_INSERT,
                "HNSW build path allocates " + String.format(java.util.Locale.ROOT, "%.0f", s.bytesPerInsert)
                        + " B per insert, gate is " + MAX_BYTES_PER_INSERT
                        + " — 指纹和 distCalls 都在带内却顶红这条线，说明构建期重新开始造一次性对象"
                        + "（每次插入物化 efConstruction 个候选、或每次修剪新建快照数组/TimSort 临时态）；"
                        + " 要改这条线只能重跑 ~/.cache/zv-build-alloc/zv_build_teeth.py 重新取值。");
    }

    @Test
    void buildStillTraversesTheSameNumberOfNodes() {
        Sample s = measure();
        assertTrue(s.callsPerInsert >= MIN_DIST_CALLS_PER_INSERT,
                "distCalls/insert dropped to " + s.callsPerInsert + " (gate floor "
                        + MIN_DIST_CALLS_PER_INSERT + "): the build stopped searching. Cutting"
                        + " allocation by building fewer edges is a recall regression, not an"
                        + " optimization — and the byte ruler cannot see it (the half-degree"
                        + " mutant in the battery is CHEAPER than the real code).");
        assertTrue(s.callsPerInsert <= MAX_DIST_CALLS_PER_INSERT,
                "distCalls/insert rose to " + s.callsPerInsert + " (gate ceiling "
                        + MAX_DIST_CALLS_PER_INSERT + "): the build is walking more nodes than it"
                        + " used to for the same corpus and parameters.");
    }

    /** 分配量降下来不许是换了一张图。 */
    @Test
    void builtGraphIsStillTheSameGraph() {
        Sample s = measure();
        assertEquals(GRAPH_FINGERPRINT, s.fingerprint,
                "fingerprint changed at M=" + M + ", efConstruction=" + EF_CONSTRUCTION
                        + ", n=" + N + ", levelSeed=" + LEVEL_SEED + " (edges=" + s.edges
                        + ") — 这条门禁的全部前提是'字节那条尺看不见图变了'，所以图要单独钉。"
                        + " 只有在确实要改启发式时才动这个常量，并且必须同时更新"
                        + " HnswGraphConnectivityTest 的可达集/召回读数。");
    }

    /** 顶格的那个配置不能是空跑：拿一次真实的构建产物证明这条路径确实在跑。 */
    @Test
    void theCorpusIsHardEnoughForTheRulersToDiffer() {
        Sample s = measure();
        assertTrue(s.edges >= 20 * N, "edges=" + s.edges + " ⇒ 图几乎没建边，上面三条都是空跑");
        assertTrue(s.callsPerInsert > 10 * EF_CONSTRUCTION / 2,
                "distCalls/insert=" + s.callsPerInsert + " 与 efConstruction=" + EF_CONSTRUCTION
                        + " 差得太远 ⇒ 搜索根本没走到候选收集，这条门禁量不到被测路径");
    }
}
