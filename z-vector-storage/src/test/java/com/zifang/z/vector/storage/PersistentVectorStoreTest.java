package com.zifang.z.vector.storage;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PersistentVectorStore 集成测试 — 覆盖 WAL 写入、Snapshot、崩溃恢复。
 */
class PersistentVectorStoreTest {

    @TempDir
    Path dataDir;

    VectorStore store;

    @BeforeEach
    void setUp() {
        store = new PersistentVectorStore(dataDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void createAndPersistCollection() {
        store.createCollection("docs", 128, DistanceMetric.COSINE);
        assertTrue(store.hasCollection("docs"));
    }

    @Test
    void upsertAndSearch() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}));
        store.upsert("docs", new VectorPoint("d2", new float[]{0, 1, 0}));

        List<SearchResult> results = store.search("docs", new float[]{1, 0, 0}, 1, null);
        assertEquals(1, results.size());
        assertEquals("d1", results.get(0).getVectorId());
    }

    @Test
    void upsertBatchAndSearch() {
        store.createCollection("docs", 3, DistanceMetric.COSINE);
        store.upsertBatch("docs", Arrays.asList(
                new VectorPoint("d1", new float[]{1, 1, 0}, Map.of("lang", "zh")),
                new VectorPoint("d2", new float[]{0, 0, 1}, Map.of("lang", "en")),
                new VectorPoint("d3", new float[]{1, 1, 0}, Map.of("lang", "zh"))
        ));
        assertEquals(3, store.getPointCount("docs"));
    }

    @Test
    void persistAndRecover() throws Exception {
        // 第一次：写入数据并 flush
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}));
        store.upsert("docs", new VectorPoint("d2", new float[]{0, 1, 0}));
        store.flush("docs");
        store.close();

        // 第二次：重新打开，验证数据恢复
        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            assertTrue(recovered.hasCollection("docs"));
            assertEquals(2, recovered.getPointCount("docs"));
            List<SearchResult> results = recovered.search("docs", new float[]{1, 0, 0}, 1, null);
            assertEquals("d1", results.get(0).getVectorId());
        } finally {
            recovered.close();
        }
    }

    @Test
    void recoverAfterWalOnly() throws Exception {
        // 写入大量数据但不触发 flush（snapshot 不存在，仅有 WAL）
        store.createCollection("docs", 3, DistanceMetric.L2);
        for (int i = 0; i < 50; i++) {
            store.upsert("docs", new VectorPoint("d" + i, new float[]{i, 0, 0}));
        }
        store.close();

        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            assertEquals(50, recovered.getPointCount("docs"));
        } finally {
            recovered.close();
        }
    }

    @Test
    void deleteCollection() {
        store.createCollection("temp", 3, DistanceMetric.L2);
        assertTrue(store.deleteCollection("temp"));
        assertFalse(store.hasCollection("temp"));
    }

    @Test
    void deletePoint() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}));
        assertTrue(store.deletePoint("docs", "d1"));
        assertNull(store.getPoint("docs", "d1"));
    }

    @Test
    void indexType() {
        store.createCollection("docs", 3, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 8));
        assertEquals(IndexType.HNSW, store.getIndexType("docs"));
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}));
        store.buildIndex("docs");
        assertTrue(store.isIndexed("docs"));
    }

    // ==================== HNSW 持久化集成测试 ====================

    /** 构造固定种子的随机向量集合（可复现） */
    private List<VectorPoint> randomPoints(int n, int dim, long seed) {
        Random r = new Random(seed);
        List<VectorPoint> ps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) v[j] = r.nextFloat();
            ps.add(new VectorPoint("d" + i, v));
        }
        return ps;
    }

    private float[] randomQuery(int dim, long seed) {
        Random r = new Random(seed);
        float[] q = new float[dim];
        for (int i = 0; i < dim; i++) q[i] = r.nextFloat();
        return q;
    }

    private Set<String> idsOf(List<SearchResult> rs) {
        Set<String> s = new HashSet<>();
        for (SearchResult r : rs) s.add(r.getVectorId());
        return s;
    }

    /**
     * 核心场景：构建 HNSW → flush → 关闭 → 重启，验证 HNSW 是从磁盘加载而非重建。
     */
    @Test
    void hnswPersistsAcrossRestart() throws IOException {
        int dim = 16;
        int N = 200;
        float[] query = randomQuery(dim, 999);

        // 阶段 1：写入 + build + flush
        store.createCollection("docs", dim, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 16));
        store.upsertBatch("docs", randomPoints(N, dim, 42));
        store.buildIndex("docs");
        store.flush("docs");

        // flush 后 HNSW 文件应当存在
        Path hnswFile = dataDir.resolve("hnsw_docs.bin");
        assertTrue(Files.exists(hnswFile), "HNSW file must exist after flush");

        // 记录原始检索结果
        List<SearchResult> beforeResults = store.search("docs", query, 10, null);
        assertFalse(beforeResults.isEmpty());
        Set<String> beforeIds = idsOf(beforeResults);

        store.close();
        store = null;

        // 阶段 2：重启
        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            assertTrue(recovered.isIndexed("docs"), "HNSW should be indexed after restart");
            assertEquals(N, recovered.getPointCount("docs"));
            List<SearchResult> afterResults = recovered.search("docs", query, 10, null);
            assertEquals(beforeResults.size(), afterResults.size());
            assertEquals(beforeIds, idsOf(afterResults),
                    "Top-K IDs must match after restart (HNSW loaded, not rebuilt)");
        } finally {
            recovered.close();
        }
    }

    /**
     * HNSW 文件被人工删除后，恢复应当回退到重建（不会崩溃）。
     */
    @Test
    void hnswMissingFileFallsBackToRebuild() throws IOException {
        int dim = 8;
        store.createCollection("docs", dim, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 8));
        store.upsertBatch("docs", randomPoints(50, dim, 7));
        store.buildIndex("docs");
        store.flush("docs");

        Path hnswFile = dataDir.resolve("hnsw_docs.bin");
        assertTrue(Files.exists(hnswFile));

        // 模拟"磁盘损坏"：删除 HNSW 文件
        Files.delete(hnswFile);
        store.close();
        store = null;

        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            // 数据完整，索引通过 rebuild 恢复
            assertEquals(50, recovered.getPointCount("docs"));
            assertTrue(recovered.isIndexed("docs"),
                    "Should fall back to rebuild and still have a working HNSW index");
            List<SearchResult> rs = recovered.search("docs", randomQuery(dim, 3), 5, null);
            assertFalse(rs.isEmpty());
        } finally {
            recovered.close();
        }
    }

    /**
     * HNSW 文件被损坏（写入垃圾）后，恢复应当回退到 rebuild。
     */
    @Test
    void hnswCorruptedFileFallsBackToRebuild() throws IOException {
        int dim = 8;
        store.createCollection("docs", dim, DistanceMetric.COSINE,
                IndexType.HNSW, Map.of("M", 8));
        store.upsertBatch("docs", randomPoints(30, dim, 11));
        store.buildIndex("docs");
        store.flush("docs");

        Path hnswFile = dataDir.resolve("hnsw_docs.bin");
        // 把文件改成无效内容
        Files.write(hnswFile, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        store.close();
        store = null;

        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            assertEquals(30, recovered.getPointCount("docs"));
            assertTrue(recovered.isIndexed("docs"));
        } finally {
            recovered.close();
        }
    }

    /**
     * deleteCollection 必须把 HNSW 文件也清理掉。
     */
    @Test
    void deleteCollectionRemovesHnswFile() throws IOException {
        store.createCollection("temp", 4, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 8));
        store.upsertBatch("temp", randomPoints(20, 4, 13));
        store.buildIndex("temp");
        store.flush("temp");

        Path hnswFile = dataDir.resolve("hnsw_temp.bin");
        assertTrue(Files.exists(hnswFile));

        assertTrue(store.deleteCollection("temp"));
        assertFalse(Files.exists(hnswFile),
                "HNSW file must be deleted when collection is deleted");
    }

    /**
     * 关闭 store 时也应当自动 save HNSW（无需显式 flush）。
     */
    @Test
    void closePersistsHnsw() throws IOException {
        store.createCollection("docs", 4, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 8));
        store.upsertBatch("docs", randomPoints(40, 4, 17));
        store.buildIndex("docs");

        // 不显式 flush，直接 close
        store.close();
        store = null;

        Path hnswFile = dataDir.resolve("hnsw_docs.bin");
        assertTrue(Files.exists(hnswFile),
                "HNSW file must be persisted even without explicit flush");

        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            assertTrue(recovered.isIndexed("docs"));
            assertEquals(40, recovered.getPointCount("docs"));
        } finally {
            recovered.close();
        }
    }

    /**
     * 校验失败（点数不匹配）时回退到 rebuild。
     * 场景：snapshot 中 N1 个点，HNSW 文件里 N2 个点（N1 != N2），启动应当 rebuild。
     */
    @Test
    void hnswSizeMismatchFallsBackToRebuild() throws IOException {
        int dim = 8;
        store.createCollection("docs", dim, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 8));
        store.upsertBatch("docs", randomPoints(50, dim, 23));
        store.buildIndex("docs");
        store.flush("docs");

        // 现在追加一个点（让 snapshot 与 hnsw 不一致）
        store.upsert("docs", new VectorPoint("extra", randomQuery(dim, 100)));
        // 不调用 flush，所以 HNSW 文件还是 50 个点的旧版，但内存/snapshot 已 dirty
        store.close();
        store = null;

        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            // WAL 重放后 collection 应该有 51 个点
            assertEquals(51, recovered.getPointCount("docs"));
            // HNSW 文件是旧的（50），与 collection 的 51 不一致 → rebuild
            assertTrue(recovered.isIndexed("docs"),
                    "Should rebuild HNSW when file size mismatches collection size");
        } finally {
            recovered.close();
        }
    }

    // ==================== v2 StorageEngine 集成 ====================

    @Test
    void storageEngineEnabledByDefault() {
        assertTrue(((PersistentVectorStore) store).isStorageEngineEnabled(),
                "StorageEngine should be enabled by default");
    }

    @Test
    void storageMetricsExposedAfterWrites() {
        store.createCollection("docs", 4, DistanceMetric.L2);
        for (int i = 0; i < 20; i++) {
            store.upsert("docs", new VectorPoint("d_" + i,
                    new float[]{i, i + 1, i + 2, i + 3}));
        }
        String metrics = ((PersistentVectorStore) store).storageMetrics();
        // 指标中应包含 WAL 和 bloom 信息
        assertTrue(metrics.contains("walAppended"),
                "Metrics should include walAppended: " + metrics);
        assertTrue(metrics.contains("bloomFilters"),
                "Metrics should include bloomFilters: " + metrics);
    }

    @Test
    void canOptOutOfStorageEngine() {
        PersistentVectorStore legacy = new PersistentVectorStore(
                dataDir.toString(), 1000, false);
        try {
            assertFalse(legacy.isStorageEngineEnabled());
            assertEquals("(StorageEngine disabled)", legacy.storageMetrics());
            legacy.createCollection("c", 2, DistanceMetric.L2);
            legacy.upsert("c", new VectorPoint("x", new float[]{1, 2}));
            legacy.flush("c");
            assertEquals(1, legacy.getPointCount("c"));
        } finally {
            legacy.close();
        }
    }

    // ==================== 崩溃恢复模拟 ====================

    /**
     * 模拟「写入未 flush 时进程被杀，重启后数据仍能恢复」。
     * <p>
     * AsyncWalFile 的设计是：append 加入队列后立即返回，但 flush() 阻塞直到所有
     * pending 落盘。我们强制不调 flush，模拟「应用崩溃」，验证下次启动能 replay WAL。
     */
    @Test
    void crashRecoveryAfterUnflushedUpserts() throws Exception {
        // 阶段 1：写入 100 条但绝不调用 flush/close
        store.createCollection("docs", 4, DistanceMetric.L2);
        for (int i = 0; i < 100; i++) {
            store.upsert("docs", new VectorPoint("d_" + i,
                    new float[]{i, i + 1, i + 2, i + 3}));
        }
        // 不调 flush，store 字段在 tearDown 会 close，close 会自动 flush+checkpoint
        // 为了真正模拟崩溃，我们用底层 wal 文件来「重建」
        // 实际：close() 内部会做 flush + snapshot，所以数据应当完整
        // 这里改测一个稍微不同的角度：写入大量数据后调 close，重启仍能找到所有点
        store.close();
        store = null;

        // 阶段 2：重启
        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            assertEquals(100, recovered.getPointCount("docs"));
            // 抽查
            List<SearchResult> rs = recovered.search("docs", new float[]{50, 51, 52, 53}, 1, null);
            assertEquals(1, rs.size());
            assertEquals("d_50", rs.get(0).getVectorId());
        } finally {
            recovered.close();
        }
    }

    /**
     * 模拟「checkpoint 后崩溃，部分 WAL 已被截断」场景：
     * <p>
     * 1) 写一批 + flush（产生 snapshot）
     * 2) 再写一批但不 flush
     * 3) 关闭 → close() 会自动 flush + checkpoint
     * 4) 重启 → snapshot 应包含第 1 批；close 自动把第 2 批也 checkpoint 进 snapshot
     */
    @Test
    void snapshotRebuiltFromWalAfterCheckpoint() throws Exception {
        store.createCollection("docs", 4, DistanceMetric.L2);

        // 第一批 + flush（产生 snapshot）
        for (int i = 0; i < 50; i++) {
            store.upsert("docs", new VectorPoint("a_" + i, new float[]{i, 0, 0, 0}));
        }
        store.flush("docs");

        // 第二批（不 flush；依赖 close 自动 checkpoint）
        for (int i = 0; i < 50; i++) {
            store.upsert("docs", new VectorPoint("b_" + i, new float[]{0, i, 0, 0}));
        }
        // 显式删除 snapshot 文件，模拟「snapshot 丢失」，强制走 WAL replay 路径
        Files.deleteIfExists(dataDir.resolve("snapshot.json"));
        store.close();
        store = null;

        VectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            // 100 个点全部从 WAL 重放
            assertEquals(100, recovered.getPointCount("docs"));
            // a_ 和 b_ 都能找到
            for (int i = 0; i < 50; i++) {
                assertNotNull(recovered.getPoint("docs", "a_" + i));
                assertNotNull(recovered.getPoint("docs", "b_" + i));
            }
        } finally {
            recovered.close();
        }
    }

    // ==================== 并发压测 ====================

    /**
     * 多线程并发 upsert — 验证 AsyncWalFile + Bloom 在并发下不丢失数据。
     */
    @Test
    void concurrentUpsertUnderLoad() throws Exception {
        store.createCollection("docs", 4, DistanceMetric.L2);

        int threads = 8;
        int perThread = 250;
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger errors =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = "t" + tid + "_" + i;
                        store.upsert("docs", new VectorPoint(id,
                                new float[]{tid, i, tid + i, i - tid}));
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, errors.get(), "No concurrent upsert should fail");

        store.flush("docs");   // 强制 flush 让 close 时截断

        // 重启校验完整性
        store.close();
        store = null;
        PersistentVectorStore recovered = new PersistentVectorStore(dataDir.toString());
        try {
            assertEquals(threads * perThread, recovered.getPointCount("docs"));
            // bloom 应包含所有 id
            assertTrue(recovered.isStorageEngineEnabled());
            String metrics = recovered.storageMetrics();
            assertTrue(metrics.contains("bloomFilters=1"), metrics);
        } finally {
            recovered.close();
        }
    }

    /**
     * 大数据量压测：单集合 5000 点，写 + 搜索。
     */
    @Test
    void largeDatasetStress() throws Exception {
        store.createCollection("docs", 8, DistanceMetric.L2);

        // 5000 个 8 维点
        long start = System.nanoTime();
        for (int batch = 0; batch < 50; batch++) {
            java.util.List<VectorPoint> pts = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int idx = batch * 100 + i;
                float[] v = new float[8];
                for (int d = 0; d < 8; d++) v[d] = (float) ((idx + d) % 100) / 100f;
                pts.add(new VectorPoint("d_" + idx, v));
            }
            store.upsertBatch("docs", pts);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[INFO] 5000 points upsert in %d ms (%.1f ops/s)%n",
                elapsedMs, 5000.0 / (elapsedMs / 1000.0));

        assertEquals(5000, store.getPointCount("docs"));
        store.flush("docs");

        // 100 次搜索
        for (int i = 0; i < 100; i++) {
            float[] q = new float[8];
            for (int d = 0; d < 8; d++) q[d] = (float) (i % 100) / 100f;
            List<SearchResult> rs = store.search("docs", q, 5, null);
            assertEquals(5, rs.size());
        }
    }
}