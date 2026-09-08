package com.zifang.z.vector.core;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorException;
import com.zifang.z.vector.api.VectorPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryVectorStore 综合测试 — 覆盖 Collection CRUD、向量 CRUD、搜索、过滤、索引切换。
 */
class InMemoryVectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
    }

    // ==================== Collection 管理 ====================

    @Test
    void createAndGetCollection() {
        store.createCollection("docs", 128, DistanceMetric.COSINE);
        VectorCollection c = store.getCollection("docs");
        assertNotNull(c);
        assertEquals("docs", c.getName());
        assertEquals(128, c.getDimension());
        assertEquals(DistanceMetric.COSINE, c.getMetric());
        assertEquals(IndexType.FLAT, c.getIndexType());
    }

    @Test
    void createWithHnswIndex() {
        store.createCollection("docs", 768, DistanceMetric.COSINE,
                IndexType.HNSW, Map.of("M", 32, "efConstruction", 400));
        VectorCollection c = store.getCollection("docs");
        assertEquals(IndexType.HNSW, c.getIndexType());
        assertEquals(32, c.getConfig().get("M"));
    }

    @Test
    void duplicateCollectionThrows() {
        store.createCollection("docs", 128, DistanceMetric.L2);
        assertThrows(VectorException.class,
                () -> store.createCollection("docs", 128, DistanceMetric.L2));
    }

    @Test
    void invalidDimensionThrows() {
        assertThrows(VectorException.class,
                () -> store.createCollection("docs", 0, DistanceMetric.L2));
        assertThrows(VectorException.class,
                () -> store.createCollection("docs", -1, DistanceMetric.L2));
    }

    @Test
    void deleteCollection() {
        store.createCollection("temp", 64, DistanceMetric.L2);
        assertTrue(store.deleteCollection("temp"));
        assertNull(store.getCollection("temp"));
        assertFalse(store.deleteCollection("temp")); // 重复删除
    }

    @Test
    void listAndHasCollection() {
        assertTrue(store.listCollections().isEmpty());
        store.createCollection("a", 64, DistanceMetric.L2);
        store.createCollection("b", 128, DistanceMetric.IP);
        assertEquals(2, store.listCollections().size());
        assertTrue(store.hasCollection("a"));
        assertTrue(store.hasCollection("b"));
        assertFalse(store.hasCollection("c"));
    }

    // ==================== 向量 CRUD ====================

    @Test
    void upsertAndGetPoint() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        VectorPoint p = new VectorPoint("d1", new float[]{1f, 2f, 3f}, Map.of("lang", "zh"));
        store.upsert("docs", p);

        VectorPoint got = store.getPoint("docs", "d1");
        assertNotNull(got);
        assertEquals("d1", got.getId());
        assertArrayEquals(new float[]{1f, 2f, 3f}, got.getVector());
        assertEquals("zh", got.getPayload("lang"));
    }

    @Test
    void upsertOverwrites() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}));
        store.upsert("docs", new VectorPoint("d1", new float[]{0, 1, 0}));
        VectorPoint got = store.getPoint("docs", "d1");
        assertArrayEquals(new float[]{0, 1, 0}, got.getVector());
        assertEquals(1, store.getPointCount("docs"));
    }

    @Test
    void upsertBatch() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        List<VectorPoint> batch = Arrays.asList(
                new VectorPoint("d1", new float[]{1, 0, 0}),
                new VectorPoint("d2", new float[]{0, 1, 0}),
                new VectorPoint("d3", new float[]{0, 0, 1})
        );
        store.upsertBatch("docs", batch);
        assertEquals(3, store.getPointCount("docs"));
    }

    @Test
    void deletePoint() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}));
        assertTrue(store.deletePoint("docs", "d1"));
        assertNull(store.getPoint("docs", "d1"));
        assertFalse(store.deletePoint("docs", "d1"));
    }

    @Test
    void deletePoints() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsertBatch("docs", Arrays.asList(
                new VectorPoint("d1", new float[]{1, 0, 0}),
                new VectorPoint("d2", new float[]{0, 1, 0}),
                new VectorPoint("d3", new float[]{0, 0, 1})
        ));
        store.deletePoints("docs", Arrays.asList("d1", "d3"));
        assertEquals(1, store.getPointCount("docs"));
        assertNull(store.getPoint("docs", "d1"));
        assertNotNull(store.getPoint("docs", "d2"));
    }

    @Test
    void dimensionMismatchOnUpsert() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        assertThrows(VectorException.class,
                () -> store.upsert("docs", new VectorPoint("d1", new float[]{1, 2})));
    }

    // ==================== 搜索 - Flat ====================

    @Test
    void searchFlatL2() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("a", new float[]{1f, 0f, 0f}));
        store.upsert("docs", new VectorPoint("b", new float[]{0f, 1f, 0f}));
        store.upsert("docs", new VectorPoint("c", new float[]{0.9f, 0.1f, 0f}));

        List<SearchResult> results = store.search("docs", new float[]{1f, 0f, 0f}, 2, null);
        assertEquals(2, results.size());
        assertEquals("a", results.get(0).getVectorId()); // 距离 0
        assertEquals("c", results.get(1).getVectorId()); // 距离 sqrt(0.02) ≈ 0.14
    }

    @Test
    void searchFlatCosine() {
        store.createCollection("docs", 3, DistanceMetric.COSINE);
        store.upsert("docs", new VectorPoint("a", new float[]{1f, 1f, 0f}));
        store.upsert("docs", new VectorPoint("b", new float[]{0f, 0f, 1f}));
        store.upsert("docs", new VectorPoint("c", new float[]{0.9f, 1f, 0f}));

        List<SearchResult> results = store.search("docs", new float[]{1f, 1f, 0f}, 2, null);
        assertEquals("a", results.get(0).getVectorId()); // 完全相同，cosine distance = 0
    }

    @Test
    void searchRange() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("a", new float[]{1, 0, 0}));
        store.upsert("docs", new VectorPoint("b", new float[]{0, 1, 0}));
        store.upsert("docs", new VectorPoint("c", new float[]{10, 0, 0}));

        List<SearchResult> results = store.searchRange("docs",
                new float[]{1, 0, 0}, 2.0f, 10, null);
        assertEquals(2, results.size()); // a=0, b=√2≈1.414 在阈值内，c=9 超出
    }

    // ==================== 搜索 - Payload 过滤 ====================

    @Test
    void searchWithSimpleFilter() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}, Map.of("lang", "en")));
        store.upsert("docs", new VectorPoint("d2", new float[]{0.9f, 0.1f, 0}, Map.of("lang", "zh")));
        store.upsert("docs", new VectorPoint("d3", new float[]{0.8f, 0.2f, 0}, Map.of("lang", "en")));

        List<SearchResult> results = store.search("docs", new float[]{1, 0, 0}, 10,
                Filter.eq("lang", "en"));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.getVectorId().equals("d1")
                || r.getVectorId().equals("d3")));
    }

    @Test
    void searchWithComplexFilter() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0}, Map.of("lang", "zh", "score", 0.9)));
        store.upsert("docs", new VectorPoint("d2", new float[]{0.9f, 0.1f, 0}, Map.of("lang", "zh", "score", 0.4)));
        store.upsert("docs", new VectorPoint("d3", new float[]{0.8f, 0.2f, 0}, Map.of("lang", "en", "score", 0.95)));

        // (lang=zh OR lang=en) AND score >= 0.5
        Filter f = Filter.and(
                Filter.inValues("lang", Arrays.asList("zh", "en")),
                Filter.gte("score", 0.5)
        );
        List<SearchResult> results = store.search("docs", new float[]{1, 0, 0}, 10, f);
        // d1 (zh, 0.9) 满足，d2 (zh, 0.4) 不满足，d3 (en, 0.95) 满足
        assertEquals(2, results.size());
    }

    // ==================== 搜索 - HNSW ====================

    @Test
    void searchHnswRecall() {
        int dim = 64;
        int N = 500;
        store.createCollection("docs", dim, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 16, "efConstruction", 200, "efSearch", 50));

        // 随机生成 N 个向量
        List<VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            float[] v = randomVector(dim, i);
            points.add(new VectorPoint("d" + i, v));
        }
        store.upsertBatch("docs", points);
        store.buildIndex("docs");

        // 查询：找一个已知存在的向量
        float[] query = randomVector(dim, 0);
        List<SearchResult> results = store.search("docs", query, 10, null);
        assertEquals(10, results.size());

        // 第一个结果应该是 d0（自己搜索自己，距离=0）
        assertEquals("d0", results.get(0).getVectorId());
        assertEquals(0f, results.get(0).getScore(), 1e-5);
    }

    // ==================== 搜索 - IVF ====================

    @Test
    void searchIvf() {
        int dim = 32;
        int N = 200;
        store.createCollection("docs", dim, DistanceMetric.L2,
                IndexType.IVF, Map.of("nlist", 16, "nprobe", 8));

        List<VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            float[] v = randomVector(dim, i);
            points.add(new VectorPoint("d" + i, v));
        }
        store.upsertBatch("docs", points);
        store.buildIndex("docs");

        float[] query = randomVector(dim, 0);
        List<SearchResult> results = store.search("docs", query, 5, null);
        assertEquals(5, results.size());
        // d0 自己搜索自己应该排第一
        assertEquals("d0", results.get(0).getVectorId());
    }

    // ==================== 索引切换 ====================

    @Test
    void switchIndex() {
        int dim = 16;
        int N = 100;
        store.createCollection("docs", dim, DistanceMetric.L2, IndexType.FLAT, null);

        for (int i = 0; i < N; i++) {
            store.upsert("docs", new VectorPoint("d" + i, randomVector(dim, i)));
        }
        assertEquals(IndexType.FLAT, store.getIndexType("docs"));

        // 切换到 HNSW
        store.deleteCollection("docs");
        store.createCollection("docs", dim, DistanceMetric.L2,
                IndexType.HNSW, Map.of("M", 16));
        for (int i = 0; i < N; i++) {
            store.upsert("docs", new VectorPoint("d" + i, randomVector(dim, i)));
        }
        store.buildIndex("docs");
        assertEquals(IndexType.HNSW, store.getIndexType("docs"));
        assertTrue(store.isIndexed("docs"));
    }

    // ==================== 关闭 ====================

    @Test
    void closeAndReuseFails() {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.close();
        assertTrue(store.isClosed());
        assertThrows(VectorException.class,
                () -> store.createCollection("other", 3, DistanceMetric.L2));
    }

    // ==================== 辅助方法 ====================

    private static float[] randomVector(int dim, long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = r.nextFloat();
        return v;
    }
}