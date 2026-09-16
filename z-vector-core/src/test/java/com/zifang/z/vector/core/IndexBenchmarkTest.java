package com.zifang.z.vector.core;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.distance.L2Distance;
import com.zifang.z.vector.core.index.FlatIndex;
import com.zifang.z.vector.core.index.HnswIndex;
import com.zifang.z.vector.core.index.IvfIndex;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性能基准测试 — 比较 Flat / HNSW / IVF 三种索引的 QPS 和召回率。
 * <p>
 * 验证 z-vector 在不同规模数据下的性能表现，确保实现的 ANN 算法
 * 在保持召回率的前提下显著降低搜索时延。
 */
class IndexBenchmarkTest {

    private static final Logger LOG = LoggerFactory.getLogger(IndexBenchmarkTest.class);

    /**
     * 生成 N 个 d 维的随机向量（确定性种子，可重复）
     */
    private static List<VectorPoint> randomVectors(int n, int dim, long seed) {
        Random r = new Random(seed);
        List<VectorPoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) {
                v[j] = r.nextFloat();
            }
            points.add(new VectorPoint("d" + i, v));
        }
        return points;
    }

    /**
     * 计算召回率：近似结果与 Flat 结果的交集 / topK
     */
    private static double recall(List<String> approx, List<String> exact, int topK) {
        int hits = 0;
        for (int i = 0; i < Math.min(topK, approx.size()); i++) {
            if (exact.contains(approx.get(i))) hits++;
        }
        return (double) hits / topK;
    }

    @Test
    void hnswVsFlatRecall() {
        int dim = 32;
        int N = 1000;
        List<VectorPoint> points = randomVectors(N, dim, 42);

        FlatIndex flat = new FlatIndex(new L2Distance(), dim);
        flat.build(points);

        HnswIndex hnsw = new HnswIndex(new L2Distance(), dim, 16, 200, 100);
        long buildMs = System.currentTimeMillis();
        hnsw.build(points);
        buildMs = System.currentTimeMillis() - buildMs;
        LOG.info("HNSW build: {}ms for {} points", buildMs, N);

        // 100 次查询，召回率
        int queries = 100;
        int topK = 10;
        double totalRecall = 0;
        double totalFlatQps = 0;
        double totalHnswQps = 0;
        Random r = new Random(99);
        for (int i = 0; i < queries; i++) {
            float[] q = new float[dim];
            for (int j = 0; j < dim; j++) q[j] = r.nextFloat();
            List<SearchResult> approx = hnsw.search(q, topK, null, Float.MAX_VALUE);
            List<SearchResult> exact = flat.search(q, topK, null, Float.MAX_VALUE);

            List<String> approxIds = new ArrayList<>();
            for (SearchResult sr : approx) approxIds.add(sr.getVectorId());
            List<String> exactIds = new ArrayList<>();
            for (SearchResult sr : exact) exactIds.add(sr.getVectorId());
            totalRecall += recall(approxIds, exactIds, topK);

            // 预热 + 测 QPS
            flat.search(q, topK, null, Float.MAX_VALUE);
            hnsw.search(q, topK, null, Float.MAX_VALUE);

            long t0 = System.currentTimeMillis();
            int reps = 20;
            for (int k = 0; k < reps; k++) flat.search(q, topK, null, Float.MAX_VALUE);
            totalFlatQps += reps * 1000.0 / Math.max(1, System.currentTimeMillis() - t0);

            t0 = System.currentTimeMillis();
            for (int k = 0; k < reps; k++) hnsw.search(q, topK, null, Float.MAX_VALUE);
            totalHnswQps += reps * 1000.0 / Math.max(1, System.currentTimeMillis() - t0);
        }
        double avgRecall = totalRecall / queries;
        double avgFlatQps = totalFlatQps / queries;
        double avgHnswQps = totalHnswQps / queries;

        LOG.info("=== Benchmark (N={}, dim={}, topK={}) ===", N, dim, topK);
        LOG.info("  Flat:  QPS = {}", String.format("%.0f", avgFlatQps));
        LOG.info("  HNSW:  QPS = {}, Recall = {}",
                String.format("%.0f", avgHnswQps), String.format("%.2f%%", avgRecall * 100));

        // HNSW 召回率应 >= 85%（小数据集精确率高）
        assertTrue(avgRecall >= 0.85,
                "HNSW recall too low: " + avgRecall);
    }

    @Test
    void ivfVsFlatRecall() {
        int dim = 32;
        int N = 500;
        List<VectorPoint> points = randomVectors(N, dim, 42);

        FlatIndex flat = new FlatIndex(new L2Distance(), dim);
        flat.build(points);

        IvfIndex ivf = new IvfIndex(new L2Distance(), dim, 16, 8, 20);
        long buildMs = System.currentTimeMillis();
        ivf.build(points);
        buildMs = System.currentTimeMillis() - buildMs;
        LOG.info("IVF build: {}ms for {} points", buildMs, N);

        int queries = 50;
        int topK = 10;
        double totalRecall = 0;
        Random r = new Random(99);
        for (int i = 0; i < queries; i++) {
            float[] q = new float[dim];
            for (int j = 0; j < dim; j++) q[j] = r.nextFloat();
            List<SearchResult> approx = ivf.search(q, topK, null, Float.MAX_VALUE);
            List<SearchResult> exact = flat.search(q, topK, null, Float.MAX_VALUE);
            List<String> approxIds = new ArrayList<>();
            for (SearchResult sr : approx) approxIds.add(sr.getVectorId());
            List<String> exactIds = new ArrayList<>();
            for (SearchResult sr : exact) exactIds.add(sr.getVectorId());
            totalRecall += recall(approxIds, exactIds, topK);
        }
        double avgRecall = totalRecall / queries;
        LOG.info("=== IVF Benchmark: avgRecall = {} ===", String.format("%.2f%%", avgRecall * 100));

        // IVF 召回率（nprobe=8）应 >= 50%
        assertTrue(avgRecall >= 0.5,
                "IVF recall too low: " + avgRecall);
    }

    @Test
    void cosineSearchBenchmark() {
        int dim = 128;
        int N = 2000;
        VectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", dim, DistanceMetric.COSINE,
                IndexType.HNSW, java.util.Map.of("M", 16));

        List<VectorPoint> points = randomVectors(N, dim, 42);
        store.upsertBatch("docs", points);
        store.buildIndex("docs");

        float[] query = new float[dim];
        new Random(99).nextFloat();
        // 预热
        for (int i = 0; i < 10; i++) store.search("docs", query, 10, null);

        long t0 = System.currentTimeMillis();
        int queries = 1000;
        for (int i = 0; i < queries; i++) {
            store.search("docs", query, 10, null);
        }
        long elapsed = System.currentTimeMillis() - t0;
        double qps = queries * 1000.0 / Math.max(1, elapsed);
        LOG.info("=== HNSW COSINE Benchmark: {} QPS (N={}, dim={}) ===",
                String.format("%.0f", qps), N, dim);

        // 期望 QPS >= 100（保守断言）
        assertTrue(qps >= 100, "HNSW COSINE QPS too low: " + qps);
    }

    @Test
    void batchSearchVsIndividual() {
        int dim = 64;
        int N = 500;
        VectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", dim, DistanceMetric.L2);
        store.upsertBatch("docs", randomVectors(N, dim, 42));

        float[] q = new float[dim];
        new Random(7).nextFloat();

        long t1 = System.currentTimeMillis();
        int iters = 100;
        for (int i = 0; i < iters; i++) store.search("docs", q, 10, null);
        long individual = System.currentTimeMillis() - t1;

        List<float[]> batch = new ArrayList<>();
        for (int i = 0; i < iters; i++) batch.add(q);

        long t2 = System.currentTimeMillis();
        store.searchBatch("docs", batch, 10, null);
        long batchTime = System.currentTimeMillis() - t2;

        LOG.info("=== Batch vs Individual: {}ms vs {}ms ===", individual, batchTime);
        assertTrue(batchTime < individual * 2,
                "Batch search should not be much slower than individual");
    }
}