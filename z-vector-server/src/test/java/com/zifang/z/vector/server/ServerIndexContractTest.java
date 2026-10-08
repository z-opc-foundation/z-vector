package com.zifang.z.vector.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立 server 的<b>索引选择</b>契约：客户端说 HNSW，建出来的就得是 HNSW；env 说默认 HNSW，
 * 没写 {@code index_type} 的那一支也得吃到。
 * <p>
 * feature001 之后：server 改挂 Qdrant 风格 REST（与内嵌 starter 同形状）。
 * 旧契约「body 里的 {@code index_type} 收下来就丢 / env 没有默认索引旋钮」在这套新形状里都仍有效，
 * 路径换成形如 {@code PUT /collections/{name}} —— 集合名从 URL 路径取，body 只放配置。
 */
class ServerIndexContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String base;
    private VectorStore store;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (store != null) {
            try {
                store.close();
            } catch (Exception ignore) {
                // 临时目录随 @TempDir 回收，关不掉不影响判定
            }
        }
    }

    // ==================== 客户端小工具 ====================

    private static final class Resp {
        final int status;
        final Map<?, ?> body;

        Resp(int status, byte[] raw) throws IOException {
            this.status = status;
            this.body = raw.length == 0
                    ? new LinkedHashMap<String, Object>()
                    : JSON.readValue(raw, Map.class);
        }

        Object field(String k) { return body.get(k); }

        @SuppressWarnings("unchecked")
        Map<String, Object> map(String k) { return (Map<String, Object>) body.get(k); }

        String text() { return body.toString(); }
    }

    private Resp call(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
        }
        c.disconnect();
        return new Resp(status, bos.toByteArray());
    }

    private void openWith(VectorStore s) throws IOException {
        store = s;
        server = VectorServerApplication.start(0, s, "/tmp/zvector-server-index-test",
                java.time.Instant.now(), java.util.Collections.<String>emptyList());
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 走 main() 那条装配路；返回的 store/server 交给 tearDown 收尾。 */
    private VectorServerApplication.Boot bootWith(Map<String, String> env) throws IOException {
        VectorServerApplication.Boot b = VectorServerApplication.boot(env);
        server = b.server;
        store = b.store;
        base = "http://127.0.0.1:" + b.port;
        return b;
    }

    private static Map<String, String> env(String dataDir, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ZVECTOR_PORT", "0");
        m.put("ZVECTOR_DATA_DIR", dataDir);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ==================== body 里的 index_type ====================

    @Test
    void bodyIndexTypeReachesStoreAndResponse() throws Exception {
        openWith(new InMemoryVectorStore());
        Resp r = call("PUT", "/collections/imgs",
                "{\"dimension\":4,\"metric\":\"L2\",\"index_type\":\"hnsw\","
                        + "\"index_params\":{\"M\":7,\"ef_construction\":33}}");
        assertEquals(200, r.status, r.text());
        // 小写也认（与 QdrantRestServer.parseIndexType 走 toUpperCase 归一）
        assertEquals("HNSW", r.field("index_type"), "响应没报出真正用的索引");
        assertEquals(7, num(r.map("index_params").get("M")), "响应里的 index_params 不对");

        VectorCollection vc = store.getCollection("imgs");
        assertNotNull(vc);
        assertEquals(IndexType.HNSW, vc.getIndexType(),
                "响应说 HNSW 而建出来是 " + vc.getIndexType());
        assertEquals(7, num(vc.getConfig().get("M")));
        assertEquals(33, num(vc.getConfig().get("ef_construction")));
    }

    @Test
    void absentIndexTypeUsesTheConfiguredDefault() throws Exception {
        InMemoryVectorStore s = new InMemoryVectorStore();
        Map<String, Object> dflt = new LinkedHashMap<>();
        dflt.put("nlist", 5);
        s.setDefaultIndex(IndexType.IVF, dflt);
        openWith(s);
        Resp r = call("PUT", "/collections/vecs", "{\"dimension\":4}");
        assertEquals(200, r.status, r.text());
        assertEquals("IVF", r.field("index_type"));
        assertEquals(5, num(r.map("index_params").get("nlist")),
                "没写 index_type 的那一支不许把默认索引的参数丢掉: " + r.text());
    }

    @Test
    void unknownIndexTypeIs400AndCreatesNothing() throws Exception {
        openWith(new InMemoryVectorStore());
        Resp r = call("PUT", "/collections/x",
                "{\"dimension\":4,\"index_type\":\"HNSWW\"}");
        assertEquals(400, r.status, "拼错的索引名不该静默降级: " + r.text());
        Map<?, ?> err = r.body;
        // Qdrant 形状错误体是 {status, code, message}
        assertEquals("error", err.get("status"));
        String msg = String.valueOf(err.get("message"));
        assertTrue(msg.contains("index_type"), "错误消息要指名是哪个字段: " + msg);
        // 逐个真枚举常量核，而不是手写三个名字：抄来的清单只会核出"抄的这三个在"，
        // 枚举加了第四个常量、消息却漏告知，那种红只有遍历真枚举才看得到。
        for (IndexType t : IndexType.values()) {
            assertTrue(msg.contains(t.name()), "错误消息里没列出 " + t.name() + ": " + msg);
        }
        assertEquals(0, store.listCollections().size(), "被拒的请求不许留下集合: "
                + store.listCollections());
    }

    @Test
    void indexParamsMustBeAnObject() throws Exception {
        openWith(new InMemoryVectorStore());
        assertEquals(400, call("PUT", "/collections/a",
                "{\"dimension\":4,\"index_type\":\"HNSW\",\"index_params\":[1,2]}").status);
        assertEquals(400, call("PUT", "/collections/b",
                "{\"dimension\":4,\"index_type\":\"HNSW\",\"index_params\":\"M=7\"}").status);
        assertEquals(400, call("PUT", "/collections/c",
                "{\"dimension\":4,\"index_type\":42}").status);
        assertEquals(0, store.listCollections().size(), "三个坏请求都不该留下集合: "
                + store.listCollections());
    }

    // ==================== env 侧的默认索引（走 main 的装配路） ====================

    @Test
    void bootAppliesEnvDefaultIndex(@TempDir java.nio.file.Path dir) throws Exception {
        bootWith(env(dir.toString(), "ZVECTOR_DEFAULT_INDEX", "hnsw",
                "ZVECTOR_INDEX_PARAMS", "{\"M\":9}"));
        Resp r = call("PUT", "/collections/docs", "{\"dimension\":4}");
        assertEquals(200, r.status, r.text());
        assertEquals("HNSW", r.field("index_type"),
                "env 里配的默认索引没落到没传 index_type 的那一支上（改前恒 FLAT）");
        assertEquals(9, num(r.map("index_params").get("M")), "env 的 index_params 没进集合");
    }

    @Test
    void emptyEnvMeansNoOverride(@TempDir java.nio.file.Path dir) throws Exception {
        // 空串必须与"不设"同义：容器里 ENV FOO="" 是常态，把它当非法值会让镜像起不来
        bootWith(env(dir.toString(), "ZVECTOR_DEFAULT_INDEX", "", "ZVECTOR_INDEX_PARAMS", ""));
        Resp r = call("PUT", "/collections/plain", "{\"dimension\":4}");
        assertEquals("FLAT", r.field("index_type"), r.text());
    }

    @Test
    void envMistakesFailFastAtBoot(@TempDir java.nio.file.Path dir) {
        // 一台把索引名拼错的容器，悄悄跑成 FLAT 比它不起来更糟。
        IllegalArgumentException a = assertThrows(IllegalArgumentException.class, () ->
                VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_DEFAULT_INDEX", "SPARSE")));
        assertTrue(a.getMessage().contains("ZVECTOR_DEFAULT_INDEX"), a.getMessage());

        IllegalArgumentException b = assertThrows(IllegalArgumentException.class, () ->
                VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_PORT", "http")));
        assertTrue(b.getMessage().contains("ZVECTOR_PORT"), b.getMessage());

        IllegalArgumentException c = assertThrows(IllegalArgumentException.class, () ->
                VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_PORT", "-1")));
        assertTrue(c.getMessage().contains("ZVECTOR_PORT"), c.getMessage());

        IllegalArgumentException d = assertThrows(IllegalArgumentException.class, () ->
                VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_INDEX_PARAMS", "M=7")));
        assertTrue(d.getMessage().contains("ZVECTOR_INDEX_PARAMS"), d.getMessage());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_INDEX_PARAMS", "[1,2]")));
        assertTrue(e.getMessage().contains("ZVECTOR_INDEX_PARAMS"), e.getMessage());
    }

    @Test
    void bootReportsThePortItBound(@TempDir java.nio.file.Path dir) throws Exception {
        VectorServerApplication.Boot b = bootWith(env(dir.toString()));
        assertTrue(b.port > 0, "ZVECTOR_PORT=0 时内核会分一个真端口，报 0 就是幻影端口: " + b.port);
        assertEquals(b.port, b.server.getAddress().getPort(),
                "Boot.port 必须是内核实际绑上的那个");
        assertEquals("ok", call("GET", "/health", null).field("status"));
    }

    /**
     * 整条链的收口尺：HTTP 建一个带参数的 HNSW 集合 → 关店 → 同一份数据目录再起一次
     * （第二起走的是完整的 WAL 重放）→ 类型与参数都必须在。
     * 缺任何一环都会红：body 不读 index_type / WAL 不写 index_params / 重放不还原。
     */
    @Test
    void createdIndexSurvivesARealRestart(@TempDir java.nio.file.Path dir) throws Exception {
        bootWith(env(dir.toString(), "ZVECTOR_DEFAULT_INDEX", "HNSW"));
        Resp r = call("PUT", "/collections/docs",
                "{\"dimension\":3,\"index_type\":\"HNSW\",\"index_params\":{\"M\":9,\"ef_construction\":40}}");
        assertEquals(200, r.status, r.text());
        // upsert 走 Qdrant 形状：path 取集合名，body 只放 points
        Resp up = call("PUT", "/collections/docs/points",
                "{\"points\":["
                        + "{\"id\":\"a\",\"vector\":[1,0,0],\"payload\":{\"lang\":\"en\"}},"
                        + "{\"id\":\"b\",\"vector\":[0,1,0]},"
                        + "{\"id\":\"c\",\"vector\":[0,0,1]}]}");
        assertEquals(200, up.status, up.text());
        closeFirstInstance();

        bootWith(env(dir.toString(), "ZVECTOR_DEFAULT_INDEX", "HNSW"));
        VectorCollection vc = store.getCollection("docs");
        assertNotNull(vc, "重启后集合没了");
        assertEquals(IndexType.HNSW, vc.getIndexType());
        assertEquals(9, num(vc.getConfig().get("M")),
                "重启后 index_params 丢了（getConfig=" + vc.getConfig() + "）");
        assertEquals(40, num(vc.getConfig().get("ef_construction")));
        assertEquals(3, readHits().size(), "重启后检索应当还能拿到那 3 个点");
    }

    private java.util.List<?> readHits() throws IOException {
        // Qdrant 形状 search 响应 key 是 "result" 不是 "results"
        return (List<?>) call("POST", "/collections/docs/points/search",
                "{\"vector\":[1,0,0],\"limit\":3}").body.get("result");
    }

    /**
     * SIGTERM 那条路（{@link VectorServerApplication#shutdown}）得真的把 store 关到"已关"为止。
     * <p>
     * 改前的守卫是 {@code store instanceof AutoCloseable}，而 {@code VectorStore} 不继承
     * {@code AutoCloseable} ⇒ 对这台进程唯一可能出现的两个 store 恒为 false，{@code close()}
     * 一次都没被调用过 —— 紧挨着它的那句注释"不 close 就丢 WAL 尾部、容器停止即丢已确认写入"
     * 保护的是一件从来没有发生过的事。docker stop 发的就是 SIGTERM。
     */
    @Test
    void shutdownPathClosesTheStore(@TempDir java.nio.file.Path dir) throws Exception {
        VectorServerApplication.Boot b = bootWith(env(dir.toString()));
        assertTrue(b.store instanceof AutoCloseable,
                "VectorStore 必须是 AutoCloseable —— PersistentVectorStore 的 javadoc 用法示例写的就是 try-with-resources");
        assertEquals(200, call("PUT", "/collections/docs",
                "{\"dimension\":3}").status);
        assertFalse(b.store.isClosed(), "还没关，不该已经是 closed");

        VectorServerApplication.shutdown(b.server, b.store);
        assertTrue(b.store.isClosed(), "shutdown() 没关掉 store ⇒ WAL/mmap/线程池一个都没释放");
        server = null;
        store = null;   // 已经关过了，别再让 tearDown 动它

        bootWith(env(dir.toString()));
        assertNotNull(store.getCollection("docs"),
                "关店之后同一份目录再起，集合应当在（快照/WAL 落了盘）");
    }

    /** 停掉 server 并关掉 store（走 SIGTERM 那条路自己那份收尾），但不清 {@link #base}。 */
    private void closeFirstInstance() {
        VectorServerApplication.shutdown(server, store);
        server = null;
        store = null;
    }

    private static int num(Object v) {
        if (v == null) return -1;
        return v instanceof Number ? ((Number) v).intValue()
                : Integer.parseInt(String.valueOf(v).trim());
    }
}
