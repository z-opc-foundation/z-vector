package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.collection.Collection;
import com.zifang.z.vector.core.distance.L2Distance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** Java 8 版 Map.of：仓库里已有同款（IndexFactoryParamTest.params / FilterTest.mapOf）。
     *  用 LinkedHashMap 保住插入序，比对assertEquals 的 entry-set 语义不受影响。 */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

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
                    map("idx", i, "lang", i % 2 == 0 ? "zh" : "en")));
        }

        HnswIndex original = new HnswIndex(new L2Distance(), dim, 8, 100, 50);
        original.build(points);

        String file = tmpDir.resolve("payload.bin").toString();
        HnswPersistence.save(original, file);

        HnswIndex loaded = HnswPersistence.load(file, new L2Distance());
        assertEquals(N, loaded.size());
        // 光看 size() 看不出 payload 被整体丢掉：每个点的 payload 都得逐个对
        for (int i = 0; i < N; i++) {
            assertEquals(map("idx", i, "lang", i % 2 == 0 ? "zh" : "en"),
                    loaded.get("d" + i).getPayload(),
                    "payload of d" + i + " did not survive the snapshot");
        }
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

    /**
     * payload 必须跟着快照回来。
     * <p>
     * Collection 没有独立的点存储 —— 向量索引就是数据本身。快照里少写 payload，重启后
     * {@code get()}/{@code search()} 的 payload 就是空的，而且没有任何一层会报错。
     */
    @Test
    void payloadSurvivesSaveAndLoad() throws IOException {
        int dim = 8;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("str", "中文 value");
        payload.put("i", 7);
        payload.put("l", 9_000_000_000L);
        payload.put("f", 1.5f);
        payload.put("d", 2.25d);
        payload.put("s", (short) 3);
        payload.put("b", (byte) 4);
        payload.put("bool", Boolean.TRUE);
        payload.put("nothing", null);
        payload.put("list", java.util.Arrays.asList("a", 1, 2.0d, null,
                java.util.Arrays.asList("nested")));
        Map<String, Object> nested = new HashMap<>();
        nested.put("k", "v");
        nested.put("deep", java.util.Collections.singletonMap("x", 1));
        payload.put("map", nested);
        payload.put("bd", new java.math.BigDecimal("1.50"));
        payload.put("bi", new java.math.BigInteger("123456789012345678901234567890"));
        // 不在类型标签表里的对象：按 toString() 落成字符串（至少不像旧版那样整个字段消失）
        payload.put("date", new java.util.Date(1_700_000_000_000L));

        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = i * 0.25f;
        HnswIndex original = new HnswIndex(new L2Distance(), dim, 8, 100, 50);
        original.build(java.util.Collections.singletonList(new VectorPoint("only", v, payload)));

        String file = tmpDir.resolve("payload-types.bin").toString();
        HnswPersistence.save(original, file);
        HnswIndex loaded = HnswPersistence.load(file, new L2Distance());

        Map<String, Object> back = loaded.get("only").getPayload();
        assertEquals(payload.keySet(), back.keySet(), "payload fields lost through the snapshot");
        for (String key : payload.keySet()) {
            if ("date".equals(key)) continue;   // 这一支故意降级成字符串，单独钉
            assertEquals(payload.get(key), back.get(key), "payload field '" + key + "' changed type or value");
        }
        assertEquals(String.valueOf(payload.get("date")), back.get("date"),
                "an unsupported payload object should come back as its toString()");
    }

    /** 破损 / 截断的快照必须在 CRC 这一关被拒，不能进解析器。 */
    @Test
    void loadRejectsCorruptedOrTruncatedSnapshot() throws IOException {
        HnswIndex idx = new HnswIndex(new L2Distance(), 8, 8, 50, 30);
        idx.build(randomVectors(40, 8, 5));
        Path file = tmpDir.resolve("corrupt.bin");
        HnswPersistence.save(idx, file.toString());

        // 中间翻一个字节（落在某个向量的浮尾数上）：旧代码照单全收，图里就带着一个错位向量
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(file, bytes);
        IOException corrupted = assertThrows(IOException.class,
                () -> HnswPersistence.load(file.toString(), new L2Distance()));
        assertTrue(corrupted.getMessage().contains("CRC"),
                "a flipped byte should be reported as a CRC failure, got: " + corrupted.getMessage());

        // 写到一半就崩（尾部少 9 字节）：CRC 覆盖不到、也绝不该被当成有效快照
        HnswPersistence.save(idx, file.toString());
        byte[] full = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(full, full.length - 9));
        assertThrows(IOException.class,
                () -> HnswPersistence.load(file.toString(), new L2Distance()));
    }

    /**
     * 版本闸门：v1 快照（不含 payload）能被"看出来"，而不是被当成 v2 少读一截、
     * 后面全靠错位字节撑住。这里连 CRC 一起重算，保证拦住它的只有版本检查。
     */
    @Test
    void loadRejectsForeignVersionEvenWithValidCrc() throws IOException {
        HnswIndex idx = new HnswIndex(new L2Distance(), 8, 8, 50, 30);
        idx.build(randomVectors(20, 8, 11));
        Path file = tmpDir.resolve("v1.bin");
        HnswPersistence.save(idx, file.toString());

        byte[] bytes = Files.readAllBytes(file);
        assertEquals(HnswPersistence.VERSION, bytes[4], "save() should write the current version");
        bytes[4] = 1;                       // 伪装成旧版格式
        reseal(bytes);
        Files.write(file, bytes);

        IOException e = assertThrows(IOException.class,
                () -> HnswPersistence.load(file.toString(), new L2Distance()));
        assertTrue(e.getMessage().contains("version"),
                "CRC is valid, so the version gate is what must reject this file; got: " + e.getMessage());
    }

    /**
     * 头部把 nodeCount 少报一个：CRC 自洽、节点也解析得动，但会剩下一段没人认领的字节。
     * <p>
     * 这一支钉的是"解析完还有余料也算损坏" —— 少报和多重度不同，它悄悄截短了图，
     * 而 size() 看起来仍然像个正常索引。
     */
    @Test
    void loadRejectsUndeclaredTrailingNodes() throws IOException {
        HnswIndex idx = new HnswIndex(new L2Distance(), 8, 8, 50, 30);
        idx.build(randomVectors(6, 8, 3));
        Path file = tmpDir.resolve("nodecount.bin");
        HnswPersistence.save(idx, file.toString());

        byte[] bytes = Files.readAllBytes(file);
        // 布局：magic(4) + version(1) + 5 个 int(20) + epLen(2) + ep + nodeCount(4)
        int epLen = ((bytes[25] & 0xFF) << 8) | (bytes[26] & 0xFF);
        int at = 27 + epLen;
        int declared = ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
        assertEquals(6, declared, "fixture should declare all 6 nodes");
        int fewer = declared - 1;
        bytes[at] = (byte) (fewer >>> 24);
        bytes[at + 1] = (byte) (fewer >>> 16);
        bytes[at + 2] = (byte) (fewer >>> 8);
        bytes[at + 3] = (byte) fewer;
        reseal(bytes);
        Files.write(file, bytes);

        IOException e = assertThrows(IOException.class,
                () -> HnswPersistence.load(file.toString(), new L2Distance()));
        assertTrue(e.getMessage().contains("unread"),
                "leftover bytes after the declared nodes must be rejected; got: " + e.getMessage());
    }

    /** 改了正文就把尾部 CRC 重算，让"拦住它的到底是哪一道关"这个问题只留下一个答案。 */
    private static void reseal(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        int c = (int) crc.getValue();
        bytes[bytes.length - 4] = (byte) (c >>> 24);
        bytes[bytes.length - 3] = (byte) (c >>> 16);
        bytes[bytes.length - 2] = (byte) (c >>> 8);
        bytes[bytes.length - 1] = (byte) c;
    }

    /** id 用 2 字节长度前缀：超长的必须当场拒，不能悄悄把长度字段写截断、让后面全部错位。 */
    @Test
    void saveRejectsIdTooLongForLengthPrefix() throws IOException {
        int dim = 4;
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 70_000; i++) longId.append('x');
        HnswIndex idx = new HnswIndex(new L2Distance(), dim, 4, 20, 10);
        idx.build(java.util.Collections.singletonList(new VectorPoint(longId.toString(), new float[dim])));

        Path file = tmpDir.resolve("oversized.bin");
        assertThrows(IOException.class, () -> HnswPersistence.save(idx, file.toString()));
        assertFalse(Files.exists(file),
                "a rejected save must not leave a half-written snapshot where recovery will find it");
    }

    /**
     * 端到端：恢复后的 collection 仍然认得 payload。
     * <p>
     * 只看过滤搜索的返回结果是看不出问题的（慢路径也答得对），所以要钉倒排本身：
     * payload 丢了 ⇒ 倒排重建出空表 ⇒ 快路径计数器永远是 0，且没人会报错。
     */
    @Test
    void payloadIndexRebuiltAfterReplaceIndexRecovery() throws IOException {
        int dim = 8;
        List<VectorPoint> pts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            float[] v = new float[dim];
            java.util.Random r = new java.util.Random(i);
            for (int j = 0; j < dim; j++) v[j] = r.nextFloat();
            Map<String, Object> pl = new HashMap<>();
            pl.put("tag", i % 10 == 0 ? "rare" : "common");
            pl.put("seq", i);
            pts.add(new VectorPoint("d" + i, v, pl));
        }

        HnswIndex idx = new HnswIndex(new L2Distance(), dim, 8, 100, 50);
        idx.build(pts);
        String file = tmpDir.resolve("recovery.bin").toString();
        HnswPersistence.save(idx, file);
        HnswIndex loaded = HnswPersistence.load(file, new L2Distance());

        Collection coll = new Collection(new VectorCollection("docs", dim,
                DistanceMetric.L2, IndexType.HNSW, new HashMap<>()));
        coll.upsertBatch(pts);
        coll.buildIndex();
        int fieldsBefore = coll.payloadIndexedFields();
        assertTrue(fieldsBefore > 0, "fixture: the inverted index should be populated to begin with");

        // 这就是 PersistentVectorStore.recover() 干的最后一步
        coll.replaceIndex(loaded);

        assertEquals(fieldsBefore, coll.payloadIndexedFields(),
                "replaceIndex rebuilt the payload inverted index from an index that carries no payload");
        assertEquals(pts.size(), coll.count());
        assertEquals("rare", loaded.get("d0").getPayload().get("tag"),
                "get() on the recovered index lost the payload");

        float[] q = new float[dim];
        List<SearchResult> hits = coll.search(q, 5, Filter.eq("tag", "rare"));
        assertEquals(1, coll.payloadFastPathHits(),
                "filtered search fell back to the slow path — the recovered 倒排 is empty");
        assertEquals(5, hits.size());
        for (SearchResult hit : hits) {
            assertEquals("rare", hit.getPayload().get("tag"),
                    "search result came back with a lost payload: " + hit.getVectorId());
        }
    }
}