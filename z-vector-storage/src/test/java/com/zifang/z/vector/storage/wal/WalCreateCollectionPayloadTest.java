package com.zifang.z.vector.storage.wal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.storage.PersistentVectorStore;
import com.zifang.z.vector.storage.snapshot.HybridSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CREATE_COLLECTION / UPSERT_POINT 的 WAL payload 编码，以及<b>重启之后</b>还剩多少。
 * <p>
 * 这一族缺陷的共同形状：写侧是 {@code String.format} 直接拼字符串，读侧是 Jackson。
 * 拼出来的东西"大多数时候"正好是合法 JSON，于是全库跑绿；而记录头的 CRC 算的是
 * <b>那串坏字节本身</b>，所以坏 payload 不会被当成损坏记录跳过 —— 它在 {@code recover()}
 * 里抛 IOException，整个 store 打不开。要钉住的就两件事：
 * <ol>
 *   <li>凡是能进 payload 的字符串（集合名、点 id、payload 里的值）都必须被转义；</li>
 *   <li>建集合时给的 {@code index_params} 必须真的进 payload ——
 *       {@code applyCreateCollection} 一直在读 {@code index_params} 这个键，
 *       而写侧从来没写过它（{@code PersistentVectorStoreDefaultIndexTest} 只验了重启<b>前</b>
 *       的 {@code getConfig()}，所以"半生效"从这里漏过去）。</li>
 * </ol>
 */
class WalCreateCollectionPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> params() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("M", 7);
        m.put("ef_construction", 33);
        m.put("extra", "带\"引号\"和\\反斜杠\\的中文值");
        return m;
    }

    /** payload 必须是合法 JSON，且 index_params 与建集合时给的一字不差。 */
    @Test
    void createCollectionPayloadCarriesIndexParams() throws IOException {
        WalRecord rec = WalRecord.createCollection("docs", 8, DistanceMetric.COSINE,
                IndexType.HNSW, params());
        Map<String, Object> m = JSON.readValue(rec.getPayload(),
                new TypeReference<Map<String, Object>>() {});
        assertEquals("docs", m.get("name"));
        assertEquals(8, ((Number) m.get("dimension")).intValue());
        assertEquals("HNSW", m.get("index_type"));
        Map<?, ?> written = (Map<?, ?>) m.get("index_params");
        assertNotNull(written, "payload 里没有 index_params ⇒ 重放必然丢参数: " + rec.getPayload());
        assertEquals(7, ((Number) written.get("M")).intValue());
        assertEquals(33, ((Number) written.get("ef_construction")).intValue());
        assertEquals("带\"引号\"和\\反斜杠\\的中文值", written.get("extra"),
                "值里的引号/反斜杠没转义，或者被 toString() 吞了");
    }

    /** 重启这一腿：HNSW + 参数都要回来。改前回来的是 HNSW + 空 map。 */
    @Test
    void indexParamsSurviveRestart(@TempDir Path dir) {
        Map<String, Object> given = params();
        PersistentVectorStore store = new PersistentVectorStore(dir.toString());
        try {
            store.createCollection("docs", 8, DistanceMetric.COSINE, IndexType.HNSW, given);
        } finally {
            store.close();
        }
        PersistentVectorStore again = new PersistentVectorStore(dir.toString());
        try {
            VectorCollection vc = again.getCollection("docs");
            assertNotNull(vc, "重启后集合没了");
            assertEquals(IndexType.HNSW, vc.getIndexType());
            assertEquals(7, num(vc.getConfig().get("M")),
                    "index_params 没能重放回来（getConfig()=" + vc.getConfig() + "）");
            assertEquals(33, num(vc.getConfig().get("ef_construction")));
            assertEquals(given.get("extra"), vc.getConfig().get("extra"));
        } finally {
            again.close();
        }
    }

    /**
     * 崩在第一次 checkpoint 之前这一腿 —— 只有 WAL，没有 snapshot。
     * <p>
     * 为什么上一条量不到这一腿：{@code close()} 无条件 {@code createSnapshot()}，而
     * {@code createSnapshot()} 写完 snapshot 就 {@code wal.truncate()}（{@code checkpointInterval}
     * 默认 1000，一条 CREATE_COLLECTION 根本触发不了周期 checkpoint）。于是
     * {@code indexParamsSurviveRestart} 重启读到的参数永远来自 snapshot，把 WAL 里的
     * {@code index_params} 整个删掉它照样绿 —— 变异电池 W1 就是这么露馅的。
     * 真实场景是 SIGKILL / 掉电：那时盘上只有 WAL，{@code applyCreateCollection} 是唯一来源，
     * 而它对已在 collections 里的名字直接 return（snapshot 优先），两条腿互不相干。
     */
    @Test
    void indexParamsSurviveWalOnlyReplay(@TempDir Path dir) throws IOException {
        try (WalFile wal = new WalFile(dir.toString())) {
            wal.append(WalRecord.createCollection("docs", 8, DistanceMetric.COSINE,
                    IndexType.HNSW, params()));
        }
        // 前置检查：这一腿必须真的没有 snapshot，否则测的还是上一条那条腿，绿灯是假的。
        assertTrue(!new HybridSnapshot(dir.toString()).exists(),
                "目录里已经有 snapshot ⇒ 下面读到的参数不来自 WAL，这条测试退化成 snapshot 那一腿");

        PersistentVectorStore replayed = new PersistentVectorStore(dir.toString());
        try {
            VectorCollection vc = replayed.getCollection("docs");
            assertNotNull(vc, "纯 WAL 重放后集合没了 ⇒ 上面那条 append 没落到 recover() 读得到的地方");
            assertEquals(IndexType.HNSW, vc.getIndexType());
            assertEquals(7, num(vc.getConfig().get("M")),
                    "纯 WAL 重放丢了 index_params（getConfig=" + vc.getConfig() + "）");
            assertEquals(33, num(vc.getConfig().get("ef_construction")));
            assertEquals(params().get("extra"), vc.getConfig().get("extra"));
        } finally {
            replayed.close();
        }
    }

    /**
     * 集合名里带引号：改前写出一条结构上非法的 payload，而 CRC 是对的 ⇒ 不是"跳过这条"，
     * 是 {@code recover()} 抛异常、整个库启不来（一台被一个名字搞挂的容器）。
     */
    @Test
    void quotedCollectionNameReplays(@TempDir Path dir) {
        String name = "we\"ird\\name";
        PersistentVectorStore store = new PersistentVectorStore(dir.toString());
        try {
            store.createCollection(name, 4, DistanceMetric.L2, IndexType.FLAT, null);
        } finally {
            store.close();
        }
        PersistentVectorStore again = new PersistentVectorStore(dir.toString());
        try {
            VectorCollection vc = again.getCollection(name);
            assertNotNull(vc, "带引号的集合名没能重放回来（库能开但集合丢了）");
            assertEquals(4, vc.getDimension());
        } catch (RuntimeException e) {
            fail("重放即炸 ⇒ 整个 store 打不开（引号没转义）: " + e.getMessage());
        } finally {
            again.close();
        }
    }

    /** 点 id 与嵌套 payload：同一族坏字节，走的是 UPSERT_POINT 那条腿。 */
    @Test
    void pointIdAndNestedPayloadReplay(@TempDir Path dir) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("gt", 3);
        range.put("lt", 9);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("range", range);
        filter.put("flag", true);
        String id = "a\"b\\c";
        PersistentVectorStore store = new PersistentVectorStore(dir.toString());
        try {
            store.createCollection("docs", 3, DistanceMetric.L2);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("filter", filter);
            store.upsert("docs", new VectorPoint(id, new float[]{1, 2, 3}, payload));
        } finally {
            store.close();
        }
        PersistentVectorStore again = new PersistentVectorStore(dir.toString());
        try {
            List<SearchResult> hits = again.search("docs", new float[]{1, 2, 3}, 1, null);
            assertEquals(1, hits.size(), "点没能重放回来");
            assertEquals(id, hits.get(0).getVectorId(), "重放回来的 id 与原 id 不一致");
            VectorPoint p = again.getPoint("docs", id);
            assertNotNull(p, "带引号的点 id 没能重放回来");
            Map<?, ?> f = (Map<?, ?>) p.getPayload().get("filter");
            assertNotNull(f, "嵌套 payload 重放后没了: " + p.getPayload());
            assertEquals(3, num(((Map<?, ?>) f.get("range")).get("gt")));
            assertEquals(Boolean.TRUE, f.get("flag"));
        } finally {
            again.close();
        }
    }

    /**
     * {@code jsonString} 自己这一层：转义必须真的发生（上面几条是通过"重放能成"间接证的，
     * 这一条直接看字面 —— 摘掉转义时这条最先红，报的也是最明白的话）。
     */
    @Test
    void jsonStringEscapes() {
        assertEquals("\"a\\\"b\"", WalRecord.jsonString("a\"b"));
        assertEquals("\"a\\\\b\"", WalRecord.jsonString("a\\b"));
        assertEquals("\"a\\nb\"", WalRecord.jsonString("a\nb"));
        assertEquals("\"tab\\t\"", WalRecord.jsonString("tab\t"));
        assertEquals("null", WalRecord.jsonString(null));
        // 阳性对照：中文与常规字符不许被动过
        assertEquals("\"向量db\"", WalRecord.jsonString("向量db"));
        assertTrue(WalRecord.jsonString("x").startsWith("\""), "字面量必须带引号");
    }

    /** 空/缺 index_params 写成 {@code {}} 而不是 {@code null} —— 读侧拿到空 map，形状与重启前一致。 */
    @Test
    void absentParamsRoundTripToEmptyMap() throws IOException {
        WalRecord rec = WalRecord.createCollection("docs", 4, DistanceMetric.L2,
                IndexType.FLAT, null);
        Map<String, Object> m = JSON.readValue(rec.getPayload(),
                new TypeReference<Map<String, Object>>() {});
        assertEquals(new LinkedHashMap<String, Object>(), m.get("index_params"));

        // 空 List / 空 Map 也不许写成 toString 的形状
        Map<String, Object> withEmpty = new LinkedHashMap<>();
        withEmpty.put("list", new ArrayList<Object>());
        withEmpty.put("map", new LinkedHashMap<String, Object>());
        WalRecord rec2 = WalRecord.upsertPoint("docs",
                new VectorPoint("d1", new float[]{1}, withEmpty));
        Map<String, Object> payload = JSON.readValue(rec2.getPayload(),
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> pl = (Map<String, Object>) payload.get("payload");
        assertEquals(new ArrayList<Object>(), pl.get("list"));
        assertEquals(new LinkedHashMap<String, Object>(), pl.get("map"));
    }

    private static int num(Object v) {
        assertNotNull(v, "键不在 config/payload 里");
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }
}
