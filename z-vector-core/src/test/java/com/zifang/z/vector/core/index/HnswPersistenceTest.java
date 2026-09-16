package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.L2Distance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HNSW 索引磁盘持久化单元测试。
 * <p>
 * 验证：
 * <ol>
 *   <li>构建索引后保存到磁盘</li>
 *   <li>从磁盘加载索引</li>
 *   <li>加载后的索引与原始索引搜索结果一致（验证精确恢复）</li>
 * </ol>
 */
class HnswPersistenceTest {

    @TempDir
    Path tmpDir;

    private List<VectorPoint> randomVectors(int n, int dim, long seed) {
        Random r = new Random(seed);
        List<VectorPoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) v[j] = r.nextFloat();
            points.add(new VectorPoint("d" + i, v));
        }
        return points;
    }

    @Test
    void saveAndLoadPreservesSearchResults() throws IOException {
        int dim = 16;
        int N = 200;
        List<VectorPoint> points = randomVectors(N, dim, 42);

        // 1. 原始索引
        HnswIndex original = new HnswIndex(new L2Distance(), dim, 16, 100, 50);
        original.build(points);

        // 2. 测试查询
        float[] query = new float[dim];
        new Random(99).nextBytes(new byte[8]);
        for (int i = 0; i < dim; i++) query[i] = new Random(99).nextFloat();

        List<SearchResult> originalResults = original.search(query, 10, null, Float.MAX_VALUE);

        // 3. 保存到磁盘
        String file = tmpDir.resolve("hnsw.bin").toString();
        HnswPersistence.save(original, file);

        // 4. 加载
        HnswIndex loaded = HnswPersistence.load(file, new L2Distance());

        // 5. 验证基础状态
        assertEquals(original.size(), loaded.size(), "Size mismatch");
        assertEquals(original.getMaxLevel(), loaded.getMaxLevel(), "MaxLevel mismatch");

        // 6. 验证搜索结果一致性（top-10 IDs 必须相同）
        List<SearchResult> loadedResults = loaded.search(query, 10, null, Float.MAX_VALUE);
        assertEquals(originalResults.size(), loadedResults.size(), "Result count mismatch");

        java.util.Set<String> originalIds = new java.util.HashSet<>();
        for (SearchResult r : originalResults) originalIds.add(r.getVectorId());
        java.util.Set<String> loadedIds = new java.util.HashSet<>();
        for (SearchResult r : loadedResults) loadedIds.add(r.getVectorId());

        assertEquals(originalIds, loadedIds, "Top-10 IDs mismatch after reload");

        // 7. 验证距离分数（允许小误差，因为不同实例的距离计算可能略有不同）
        for (int i = 0; i < originalResults.size(); i++) {
            SearchResult orig = originalResults.get(i);
            SearchResult ld = loadedResults.get(i);
            if (orig.getVectorId().equals(ld.getVectorId())) {
                assertEquals(orig.getScore(), ld.getScore(), 1e-4f,
                        "Score mismatch for " + orig.getVectorId());
            }
        }
    }

    @Test
    void saveAndLoadEmptyIndex() throws IOException {
        HnswIndex original = new HnswIndex(new L2Distance(), 32, 8, 100, 50);
        // 不调用 build，所以 entryPoint 为 null

        String file = tmpDir.resolve("empty.bin").toString();
        HnswPersistence.save(original, file);

        HnswIndex loaded = HnswPersistence.load(file, new L2Distance());
        assertEquals(0, loaded.size());
        assertFalse(loaded.isBuilt());
    }

    @Test
    void saveAndLoadWithPayload() throws IOException {
        int dim = 8;
        int N = 50;
        List<VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            float[] v = new float[dim];
            new Random(i).nextBytes(new byte[8]);
            for (int j = 0; j < dim; j++) v[j] = new Random(i).nextFloat();
            points.add(new VectorPoint("d" + i, v,
                    java.util.Map.of("idx", i, "lang", i % 2 == 0 ? "zh" : "en")));
        }

        HnswIndex original = new HnswIndex(new L2Distance(), dim, 8, 100, 50);
        original.build(points);

        String file = tmpDir.resolve("payload.bin").toString();
        HnswPersistence.save(original, file);

        HnswIndex loaded = HnswPersistence.load(file, new L2Distance());
        assertEquals(N, loaded.size());
    }

    @Test
    void loadNonexistentFileThrows() {
        String missing = tmpDir.resolve("missing.bin").toString();
        assertThrows(IOException.class,
                () -> HnswPersistence.load(missing, new L2Distance()));
    }

    @Test
    void saveMultipleTimesOverwritesFile() throws IOException {
        HnswIndex idx = new HnswIndex(new L2Distance(), 8, 8, 50, 30);
        idx.build(randomVectors(50, 8, 1));

        String file = tmpDir.resolve("multi.bin").toString();
        HnswPersistence.save(idx, file);
        HnswPersistence.save(idx, file);  // 覆盖写
        HnswPersistence.save(idx, file);  // 再覆盖

        HnswIndex loaded = HnswPersistence.load(file, new L2Distance());
        assertEquals(50, loaded.size());
    }
}