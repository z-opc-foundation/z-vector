package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code QdrantRestServer} 这一台 REST 面上"索引选择必须被兑现"的尺。
 * <p>
 * 用例清单与 {@code ServerIndexContractTest}（独立 server 那一台）<b>刻意保持同形</b>：
 * 两台 server 是两套代码、两个入口（embedded 走这台，docker/standalone 走那台），
 * 任何一台单独漂掉，只有各自那一把尺看得见。共享一个解析工具类反而会把"两台的默认值语义
 * 本来就不同"（这台 {@code dimension} 可缺省 128，那台 {@code dimensions} 必填）这件事糊掉。
 * <p>
 * 这一族缺陷的原型在最开始那一条：{@code handleCreateCollection} 原先写的是
 * {@code body.getOrDefault("index_type", "FLAT")} 然后<b>永远</b>走 5 参 —— 于是
 * starter 配的 {@code zvector.default-index=HNSW}（{@code store.setDefaultIndex}）在这条路上
 * 被一个显式的 FLAT 顶掉；而响应只有 {@code status}/{@code name}，客户端无从得知被降级了。
 * 那台独立 server 同一条广告（OpenApiSpec 的 {@code index_type}）走的是另一支，所以两台
 * 只有一台兑现 —— 这正是"改了独立 server 就以为收口了"的形状。
 */
class QdrantRestIndexContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private VectorStore store;
    private QdrantRestServer server;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        store = new InMemoryVectorStore();
        server = new QdrantRestServer(store, 0);
        server.start();
        base = "http://localhost:" + server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (store != null) store.close();
    }

    /** body 里写了 index_type / index_params，就必须真的建出那个索引，并且回读得到。 */
    @Test
    void bodyIndexTypeReachesStoreAndResponse() throws Exception {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("M", 7);
        params.put("ef_construction", 33);
        Resp r = put("/collections/docs", map("dimension", 8,
                "metric", "cosine",              // 小写也要认（改前是 toUpperCase()，这一支本来就通）
                "index_type", "hnsw",
                "index_params", params));

        assertEquals(200, r.code, "创建失败: " + r.body);
        assertEquals("HNSW", r.json.get("index_type"),
                "响应没回读真实索引类型（改前整个响应只有 status/name）");
        assertEquals("COSINE", r.json.get("metric"));
        assertEquals(7, num(paramsOf(r.json).get("M"), "M"), "index_params 没进响应");
        VectorCollection vc = store.getCollection("docs");
        assertNotNull(vc, "集合没建出来");
        assertEquals(IndexType.HNSW, vc.getIndexType());
        assertEquals(7, num(vc.getConfig().get("M"), "M"), "index_params 没进 store");
    }

    /**
     * 这是这一族缺陷的正主：客户端没写 index_type 时，进程配好的默认索引必须生效。
     * 改前这里传的是<b>显式</b> FLAT，把 {@code setDefaultIndex} 顶掉了。
     */
    @Test
    void absentIndexTypeUsesTheStoresDefaultIndex() throws Exception {
        Map<String, Object> dflt = new HashMap<String, Object>();
        dflt.put("nlist", 5);
        store.setDefaultIndex(IndexType.IVF, dflt);

        Resp r = put("/collections/vectors", map("dimension", 8));
        assertEquals(200, r.code, "创建失败: " + r.body);
        assertEquals("IVF", r.json.get("index_type"),
                "客户端没写 index_type 时被显式 FLAT 顶掉了（默认索引形同虚设）");
        assertEquals(5, num(paramsOf(r.json).get("nlist"), "nlist"));
        assertEquals(IndexType.IVF, store.getCollection("vectors").getIndexType());
    }

    /**
     * 上一条的反向对照：没有默认索引时缺省才是 FLAT。
     * 少了这一条，"把 getOrDefault 摘掉但默认索引也没生效"这种半修状态会伪装成通过。
     */
    @Test
    void absentIndexTypeWithoutDefaultIsFlat() throws Exception {
        Resp r = put("/collections/plain", map("dimension", 8));
        assertEquals(200, r.code, "创建失败: " + r.body);
        assertEquals("FLAT", r.json.get("index_type"));
        assertEquals(IndexType.FLAT, store.getCollection("plain").getIndexType());
    }

    /** 未知索引类型：400 + 说清支持哪些 + 一个集合都不许多建出来（改前是 500 "No enum constant"）。 */
    @Test
    void unknownIndexTypeIs400AndCreatesNothing() throws Exception {
        Resp r = put("/collections/bad", map("dimension", 8, "index_type", "SPARSE"));
        assertEquals(400, r.code, "未知 index_type 竟然不是 400: " + r.code + " " + r.body);
        String err = errorOf(r);
        assertTrue(err.contains("index_type"), "错误信息没点名是哪个字段写错: " + err);
        // "支持哪些"必须是照着枚举生成的：抄一份字面量进消息，加一个索引类型就少告知一个。
        for (IndexType t : IndexType.values()) {
            assertTrue(err.contains(t.name()),
                    "错误信息里没列出 " + t.name() + "（支持的取值是抄来的字面量）: " + err);
        }
        assertFalse(store.hasCollection("bad"), "报错却已经把集合建出来了");
    }

    /**
     * {@code INNER_PRODUCT} 是 OpenApiSpec 曾经广告过的值（枚举里根本没有）。
     * 这一条钉的是"照旧 spec 生成的客户端会拿到什么"：必须是 400 并说明 metric 的合法取值，
     * 而不是 500 加一句 JVM 内部的 "No enum constant"。
     */
    @Test
    void unknownMetricIs400AndNamesTheField() throws Exception {
        Resp r = put("/collections/m", map("dimension", 8, "metric", "INNER_PRODUCT"));
        assertEquals(400, r.code, "未知 metric 竟然不是 400: " + r.code + " " + r.body);
        String err = errorOf(r);
        assertTrue(err.contains("metric"), "错误信息没点名 metric: " + err);
        for (DistanceMetric m : DistanceMetric.values()) {
            assertTrue(err.contains(m.name()),
                    "错误信息里没列出 " + m.name() + "（支持的 metric 是抄来的字面量）: " + err);
        }
        assertFalse(store.hasCollection("m"));
    }

    /** 类型写错（不是字符串 / 不是对象）也必须是 400，不能是 ClassCastException 的 500。 */
    @Test
    void mistypedFieldsAre400NotCrash() throws Exception {
        Resp numericType = put("/collections/c1", map("dimension", 8, "index_type", 3));
        assertEquals(400, numericType.code, "index_type 给数字不是 400: " + numericType.body);
        assertFalse(store.hasCollection("c1"));

        Resp arrayParams = put("/collections/c2",
                map("dimension", 8, "index_type", "HNSW", "index_params",
                        java.util.Arrays.asList(1, 2)));
        assertEquals(400, arrayParams.code, "index_params 给数组不是 400: " + arrayParams.body);
        assertTrue(errorOf(arrayParams).contains("index_params"),
                "错误信息没点名 index_params: " + arrayParams.json);
        assertFalse(store.hasCollection("c2"), "报错却已经把集合建出来了");

        Resp metricNumber = put("/collections/c3", map("dimension", 8, "metric", 1));
        assertEquals(400, metricNumber.code, "metric 给数字不是 400: " + metricNumber.body);
        assertFalse(store.hasCollection("c3"));
    }

    /** dimension 是这一台唯一可缺省的入参（缺省 128），但 0 / 负数不能一路走到 store。 */
    @Test
    void dimensionMustBePositiveAndDefaultsWhenAbsent() throws Exception {
        Resp zero = put("/collections/d0", map("dimension", 0));
        assertEquals(400, zero.code, "dimension=0 不是 400: " + zero.body);
        assertTrue(errorOf(zero).contains("dimension"),
                "拒绝了却没说是哪个字段: " + zero.body);
        assertFalse(store.hasCollection("d0"));

        Resp negative = put("/collections/dn", map("dimension", -8));
        assertEquals(400, negative.code, "dimension<0 不是 400: " + negative.body);
        assertFalse(store.hasCollection("dn"));

        Resp absent = put("/collections/da", map("metric", "L2"));
        assertEquals(200, absent.code, "缺省 dimension 仍该可用: " + absent.body);
        assertEquals(128, num(absent.json.get("dimension"), "dimension"),
                "缺省 dimension 回读不对");
    }

    /**
     * 记录了当前的语义边界：{@code index_params} 而不写 {@code index_type} 时，参数走不到
     * 默认索引上（3 参那一支只认 store 的默认参数）。这不是"静默丢弃"了 —— 回读会照着
     * store 里真正建出来的那个报 —— 但"该 400 还是该让 body 的参数覆盖默认参数"要人拍板，
     * 两台 server 现在行为一致，改动要一起改。
     */
    @Test
    void paramsWithoutTypeAreNotHonouredButAreObservable() throws Exception {
        Map<String, Object> dflt = new HashMap<String, Object>();
        dflt.put("nlist", 5);
        store.setDefaultIndex(IndexType.IVF, dflt);

        Resp r = put("/collections/p", map("dimension", 8,
                "index_params", map("M", 99)));
        assertEquals(200, r.code, "创建失败: " + r.body);
        assertEquals("IVF", r.json.get("index_type"));
        Map<String, Object> echoed = paramsOf(r.json);
        assertFalse(echoed.containsKey("M"),
                "响应回读了 store 的真实状态，就不该同时出现没生效的 M: " + echoed);
        assertEquals(5, num(echoed.get("nlist"), "nlist"));
    }

    /**
     * 建成什么样，{@code GET /collections/{name}} 就得说什么样。
     * <p>
     * 创建响应这一路此前只回 status/name，而 GET 这一路回 index_type 却<b>不回 index_params</b>
     * —— 于是"M=16 到底进没进去"仍然只能靠重建一次集合去猜。OpenApiSpec 的 CollectionInfo
     * 现在把这两个字段都广告出去了，这一条就是那两条广告的兑现尺。
     */
    @Test
    void createdIndexStateIsReadableViaGet() throws Exception {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("M", 16);
        params.put("ef_construction", 200);
        Resp created = put("/collections/readback", map("dimension", 8,
                "index_type", "HNSW", "index_params", params));
        assertEquals(200, created.code, "创建失败: " + created.body);

        Resp got = get("/collections/readback");
        assertEquals(200, got.code, "GET 读不回来: " + got.body);
        assertEquals("HNSW", got.json.get("index_type"));
        Map<String, Object> echoed = paramsOf(got.json);
        assertEquals(16, num(echoed.get("M"), "M"), "GET 的响应里没有把 index_params 读回来");
        assertEquals(paramsOf(created.json), echoed, "PUT 与 GET 回读的 index_params 不一致");
        assertEquals(8, num(got.json.get("dimension"), "dimension"));
    }

    // ==================== 取证据 ====================

    private static final class Resp {
        final int code;
        final String body;
        Map<String, Object> json = new LinkedHashMap<String, Object>();

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Resp put(String path, Map<String, Object> body) throws IOException {
        return send("PUT", path, body);
    }

    private Resp get(String path) throws IOException {
        return send("GET", path, null);
    }

    /**
     * 传输层失败重试一次，并且每次都要一条新连接 —— 理由与 {@code OpenApiSpecRoutingTest.send}
     * 里记的同一条（这台是 {@code port=0}，每个方法换一台 server，端口复用会让 JDK 的
     * keep-alive 连接缓存把请求写到上一台的废连接上）。
     */
    private Resp send(String method, String path, Map<String, Object> body) throws IOException {
        try {
            return sendOnce(method, path, body);
        } catch (IOException transport) {
            return sendOnce(method, path, body);
        }
    }

    private Resp sendOnce(String method, String path, Map<String, Object> body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Connection", "close");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(JSON.writeValueAsBytes(body));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        if (is != null) {
            try {
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            } finally {
                is.close();
            }
        }
        conn.disconnect();
        Resp r = new Resp(code, new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        if (r.body.length() > 0) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(r.body, Map.class);
            if (parsed != null) r.json = parsed;
        }
        return r;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /**
     * 这一台的错误信封是 {@code {status, code, message}}，而独立 server 那一台是
     * {@code {error}} —— 两台各一套，客户端要写两套解析。这一条先照实钉住（信封读错时
     * 下面的断言会拿到 null 而不是安静通过），统一与否归待办。
     */
    private static String errorOf(Resp r) {
        assertEquals("error", r.json.get("status"), "错误信封不是这台的形状: " + r.json);
        Object m = r.json.get("message");
        assertNotNull(m, "4xx 响应里没有 message: " + r.json);
        return String.valueOf(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(Map<String, Object> resp) {
        Object v = resp.get("index_params");
        assertNotNull(v, "响应里没有 index_params（回读面缺一项）: " + resp);
        assertTrue(v instanceof Map, "index_params 不是对象: " + v);
        return (Map<String, Object>) v;
    }

    private static int num(Object v, String where) {
        assertNotNull(v, where + " 不在集合的 indexParams 里");
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }
}
