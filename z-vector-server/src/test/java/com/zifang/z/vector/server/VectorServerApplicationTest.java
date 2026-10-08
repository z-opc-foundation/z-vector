package com.zifang.z.vector.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * z-vector-server 独立服务器的端到端 HTTP 测试。
 * <p>
 * feature001 之后：本 server 改挂 Qdrant 风格 REST（与内嵌 starter 同形状），
 * 全部 7 条路由（GET/POST/PUT/DELETE）经 {@link com.zifang.z.vector.grpc.QdrantRestServer#handler()}
 * 走单点定义，OpenApiSpec 与真实路由一致。本测试只钉 server 这一层装配（最长前缀挂载 /health、/__instance、根上下文）。
 */
class VectorServerApplicationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private InMemoryVectorStore store;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryVectorStore();
        store.createCollection("docs", 3, DistanceMetric.COSINE, IndexType.FLAT, null);
        server = VectorServerApplication.start(0, store, "/tmp/zvector-test", Instant.now(), Collections.emptyList());
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ==================== 测试用 HTTP 客户端 ====================

    private static final class Resp {
        final int status;
        final byte[] body;
        Resp(int status, byte[] body) { this.status = status; this.body = body; }
        String text() { return new String(body, StandardCharsets.UTF_8); }
        Map<?, ?> json() throws IOException { return JSON.readValue(body, Map.class); }
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
        byte[] respBody = bos.toByteArray();
        if (status >= 400) {
            // 偶发红的自助取证（不改变判定）：这台机器上有别的会话的常驻 HTTP 服务，
            // 曾出现过一次"请求拿到 {\"ok\":false,\"error\":\"not found: /collections\"}"——
            // 那句文案 z-vector 自己产生不了（本机只有 z-bot / z-agent 的 HttpChannel 会这么写），
            // 所以当场把这个端口自称是谁、以及同端口 GET /health 的真实回答一起打出来：
            // /health 正常 ⇒ 端口是我们的，404 是真路由问题；/health 也离谱 ⇒ 跨进程串话。
            System.err.println("[FLAKE-EVIDENCE] " + method + " " + base + path
                    + " -> " + status + " body=" + new String(respBody, StandardCharsets.UTF_8)
                    + " | same-port /health -> " + probeHealthOnSamePort());
        }
        return new Resp(status, respBody);
    }

    private String probeHealthOnSamePort() {
        try {
            HttpURLConnection p = (HttpURLConnection) new URL(base + "/health").openConnection();
            p.setRequestMethod("GET");
            p.setConnectTimeout(2000);
            p.setReadTimeout(2000);
            p.setRequestProperty("Connection", "close");
            int code = p.getResponseCode();
            InputStream in = code >= 400 ? p.getErrorStream() : p.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            p.disconnect();
            return code + " " + new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "probe failed: " + e;
        }
    }

    // ==================== /health ====================

    @Test
    void healthReportsCurrentArtifactVersion() throws Exception {
        Resp r = call("GET", "/health", null);
        assertEquals(200, r.status);
        // 这一条以前长这样：assertEquals("1.0.2", r.json().get("version"))，而 main 里就是同一个
        // 字面量 —— 两边一起漂，断言永远绿（同一处错过两次：1.0.1 对 1.0.2、1.0.2 对 1.0.3）。
        // 参照值取聚合 pom 的 <revision>（全仓版本的唯一定义点）。
        String revision = PomFiles.revisionOf(PomFiles.read(PomFiles.rootPom()));
        assertEquals(revision, r.json().get("version"),
                "/health 报的版本不等于聚合 pom 的 <revision>（revision=" + revision + "）");
        assertNotEquals("unknown", r.json().get("version"),
                "build-info.properties 没读到 ⇒ 资源过滤没生效，上面那条会退化成 unknown==unknown");
        assertEquals(1, ((Number) r.json().get("collections")).intValue());
    }

    /**
     * {@link PomFiles#revisionOf} 的猎物对照。聚合 pom 里同时住着三个"版本"：
     * {@code <parent>} 的 1.0.0-SNAPSHOT、{@code <version>${revision}} 这个占位、
     * 和 {@code <revision>} 的真值 ⇒ 读错任何一个都拿得到字符串，只有两值不同的替身 pom 分得开。
     */
    @Test
    void revisionOfReadsThePropertyNotTheParent() {
        String synthetic = "<project>\n"
                + "  <parent>\n    <groupId>com.zifang</groupId>\n    <artifactId>z-opc</artifactId>\n"
                + "    <version>1.0.0-SNAPSHOT</version>\n  </parent>\n"
                + "  <artifactId>z-vector</artifactId>\n  <version>${revision}</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <properties>\n    <revision>8.8.8</revision>\n  </properties>\n"
                + "</project>\n";
        assertEquals("8.8.8", PomFiles.revisionOf(synthetic), "读的不是 <revision>");
        assertEquals("7.7.7", PomFiles.revisionOf(synthetic.replace("8.8.8", "7.7.7")),
                "解析器没在真读输入（回吐了常量）");
        // "唯一定义点"这半句的猎物：定义两次必须当场炸，否则这句话没人钉。
        // 注意是在同一个 <properties> 里加第二个 <revision> —— 拼两份完整文档不是合法 XML，
        // 只会拿到"解析不了"而不是"定义了两处"，猎物就打偏了（实测就是这么假的）。
        final String twice = synthetic.replace(
                "<revision>8.8.8</revision>",
                "<revision>8.8.8</revision>\n    <revision>6.6.6</revision>");
        org.junit.jupiter.api.Assertions.assertThrows(org.opentest4j.AssertionFailedError.class,
                () -> PomFiles.revisionOf(twice), "<revision> 出现两次却仍然返回了其中一个");
    }

    // ==================== /collections ====================

    @Test
    void listCollectionsReturnsQdrantShape() throws Exception {
        Resp r = call("GET", "/collections", null);
        assertEquals(200, r.status);
        assertEquals("ok", r.json().get("status"));
        List<?> names = (List<?>) r.json().get("collections");
        assertNotNull(names, "Qdrant 形状 GET /collections 必须返 {status,collections:[…]}，" +
                "不能退回裸数组（独立 server 与内嵌 starter 形状必须一致）: " + r.text());
        assertTrue(names.contains("docs"), r.text());
    }

    @Test
    void createCollectionAcceptsIntegerDimensions() throws Exception {
        Resp r = call("PUT", "/collections/imgs", "{\"dimension\":4}");
        assertEquals(200, r.status, r.text());
        assertEquals("imgs", r.json().get("name"));
        assertEquals(4, ((Number) r.json().get("dimension")).intValue());
        // 真的建出来了，而不只是回了个 ok
        assertTrue(store.hasCollection("imgs"));
        assertEquals(4, store.getCollection("imgs").getDimension());
    }

    @Test
    void createCollectionEchoesRealIndexTypeNotClientSupplied() throws Exception {
        // 创建响应回读 store 里真建出来的那个 index_type，避免「回吐 FLAT 而 store 里其实是 HNSW」的假阳性
        Resp r = call("PUT", "/collections/hnsw_demo",
                "{\"dimension\":4,\"metric\":\"COSINE\",\"index_type\":\"HNSW\",\"index_params\":{\"M\":16}}");
        assertEquals(200, r.status, r.text());
        assertEquals("HNSW", r.json().get("index_type"), "响应必须回读真实 index_type 而不是回吐客户端传值");
        Map<?, ?> params = (Map<?, ?>) r.json().get("index_params");
        assertNotNull(params, "index_params 必须回读出来：M=16 到底进没进去，必须能在响应里看到");
        assertEquals(16, ((Number) params.get("M")).intValue());
    }

    @Test
    void jacksonParsesJsonIntsAsDoubleSoCastMustTolerateIt() throws Exception {
        // 此前代码写的是 (int) request.get("dimensions")。JSON 数字经 Jackson 默认
        // 走 Integer，但一旦客户端提交 4.0 或走 float 通道就是 Double —— CCE 直接 500。
        Resp r = call("PUT", "/collections/f4", "{\"dimension\":4.0}");
        assertEquals(200, r.status, "dimension 以小数形式提交不应 500: " + r.text());
    }

    @Test
    void missingOrNonPositiveDimensionIs400NotNpe() throws Exception {
        // 此前 (int) get(...) 遇 null 直接 NPE → 500 "Internal error: null"
        // dimension 缺省值 128 是 OpenAPI 既有契约；只对非正 / 类型错返回 400。
        assertEquals(400, call("PUT", "/collections/x", "{\"dimension\":0}").status);
        assertEquals(400, call("PUT", "/collections/x", "{\"dimension\":-1}").status);
        assertEquals(400, call("PUT", "/collections/x", "{\"dimension\":\"oops\"}").status);
        // 缺省 dimension 走 128：与 grpc-server 那边的契约对齐（OpenAPI 没把 dimension 列为 required）。
        Resp absent = call("PUT", "/collections/absent", "{}");
        assertEquals(200, absent.status, "缺省 dimension 仍该可用: " + absent.text());
        assertEquals(128, ((Number) absent.json().get("dimension")).intValue());
    }

    @Test
    void unknownMethodOnCollectionsRootIs405() throws Exception {
        // QdrantRestServer.route() 的 "/collections" 分支只接受 GET；其它方法 → 405
        Resp r = call("DELETE", "/collections", null);
        assertEquals(405, r.status);
        Map<?, ?> err = r.json();
        assertEquals("error", err.get("status"));
        assertEquals(405, ((Number) err.get("code")).intValue(),
                "错误响应里 code 必须等于 HTTP code，前端 axios 拦截器按 code 判断");
    }

    // ==================== Qdrant 形状独有：单点 GET/DELETE ====================

    @Test
    void singlePointGetWorks() throws Exception {
        // PUT /collections/docs/points 先 upsert 一条
        call("PUT", "/collections/docs/points",
                "{\"points\":[{\"id\":\"p1\",\"vector\":[1,0,0],\"payload\":{\"k\":\"v\"}}]}");
        // 单点 GET —— z-opc api.js 旧版曾因 extractCollectionName(path,"/points/") 把后缀按 8 个字符切
        // 而把 "/collections/docs/points/p1" 切成集合名 "docs/p"。这条测试钉当前实现不再踩同一个坑：
        // route() 的 P_POINT_BY_ID 分支走 indexOf("/points/") 取集合名与 id，应当正常返回。
        Resp r = call("GET", "/collections/docs/points/p1", null);
        assertEquals(200, r.status, "单点 GET 之前因为路径切分 bug 切成集合名 docs/p 报 404，" +
                "现在必须能正确返回单点: " + r.text());
        assertEquals("p1", r.json().get("id"));
        List<?> vec = (List<?>) r.json().get("vector");
        assertEquals(1.0, ((Number) vec.get(0)).doubleValue(), 1e-6);
    }

    @Test
    void singlePointGetOnMissingIdIs404() throws Exception {
        Resp r = call("GET", "/collections/docs/points/nope", null);
        assertEquals(404, r.status, "缺 id 必须 404 而不是把 id 当集合名吞掉: " + r.text());
    }

    @Test
    void pointsCountEndpointWorks() throws Exception {
        call("PUT", "/collections/docs/points",
                "{\"points\":[{\"id\":\"a\",\"vector\":[1,0,0]},{\"id\":\"b\",\"vector\":[0,1,0]}]}");
        Resp r = call("GET", "/collections/docs/points/count", null);
        assertEquals(200, r.status);
        assertEquals(2, ((Number) r.json().get("count")).intValue());
    }

    // ==================== /points + /search ====================

    @Test
    void upsertThenSearchRoundTrip() throws Exception {
        Resp up = call("PUT", "/collections/docs/points", "{\"points\":["
                + "{\"id\":\"a\",\"vector\":[1,0,0],\"payload\":{\"lang\":\"en\"}},"
                + "{\"id\":\"b\",\"vector\":[0,1,0],\"payload\":{\"lang\":\"zh\"}}]}");
        assertEquals(200, up.status, up.text());
        assertEquals(2, ((Number) up.json().get("count")).intValue());

        Resp se = call("POST", "/collections/docs/points/search",
                "{\"vector\":[0.9,0.1,0],\"limit\":2}");
        assertEquals(200, se.status, se.text());
        assertEquals("ok", se.json().get("status"));
        List<?> hits = (List<?>) se.json().get("result");
        assertEquals(2, hits.size());
        Map<?, ?> top = (Map<?, ?>) hits.get(0);
        assertEquals("a", top.get("id"), "最近邻应是 a: " + se.text());
        assertTrue(top.containsKey("score"));
        assertTrue(top.containsKey("payload"));
        // time_ms 真实测量（feature001 把硬编码 0 改成了真实 nanoTime 差）
        assertNotNull(se.json().get("time_ms"),
                "search 响应必须带 time_ms，不能退化到占位 0: " + se.text());
        assertTrue(((Number) se.json().get("time_ms")).longValue() >= 0);
    }

    @Test
    void searchLimitIsHonoured() throws Exception {
        call("PUT", "/collections/docs/points", "{\"points\":["
                + "{\"id\":\"a\",\"vector\":[1,0,0]},{\"id\":\"b\",\"vector\":[0,1,0]},"
                + "{\"id\":\"c\",\"vector\":[0,0,1]}]}");
        Resp r = call("POST", "/collections/docs/points/search",
                "{\"vector\":[1,1,1],\"limit\":1}");
        assertEquals(200, r.status);
        assertEquals(1, ((List<?>) r.json().get("result")).size(), "limit 必须生效");
    }

    @Test
    void missingCollectionIs404NotNpe() throws Exception {
        assertEquals(404, call("PUT", "/collections/nope/points",
                "{\"points\":[]}").status);
        assertEquals(404, call("POST", "/collections/nope/points/search",
                "{\"vector\":[1,0,0]}").status);
        assertEquals(404, call("GET", "/collections/nope", null).status);
        assertEquals(404, call("PUT", "/collections/nope/points",
                "{\"points\":[]}").status);
    }

    @Test
    void badVectorShapeIs400Not500() throws Exception {
        Resp r = call("PUT", "/collections/docs/points",
                "{\"points\":[{\"id\":\"a\",\"vector\":\"oops\"}]}");
        assertEquals(400, r.status, "非法 vector 应是 400 + message，实际 " + r.status + " " + r.text());
        assertEquals("error", r.json().get("status"));
        assertNotNull(r.json().get("message"),
                "Qdrant 形状错误体用 message 而不是 error —— 前端按 .message 取文案");
    }

    // ==================== UTF-8：中文 payload ====================

    @Test
    void chinesePayloadSurvivesRoundTrip() throws Exception {
        Resp up = call("PUT", "/collections/docs/points",
                "{\"points\":[{\"id\":\"中文\",\"vector\":[1,0,0],\"payload\":{\"标题\":\"向量数据库\"}}]}");
        assertEquals(200, up.status, up.text());
        assertEquals(1, ((Number) up.json().get("count")).intValue(), "1 条中文记录应写成功");

        Resp se = call("POST", "/collections/docs/points/search",
                "{\"vector\":[1,0,0],\"limit\":1}");
        assertEquals(200, se.status);
        Map<?, ?> top = (Map<?, ?>) ((List<?>) se.json().get("result")).get(0);
        // 此前 getBytes() 用平台默认字符集、且 Content-Length 用 String.length 口径，
        // 中文会被写成乱码或按字节数截断
        assertEquals("向量数据库", ((Map<?, ?>) top.get("payload")).get("标题"), se.text());
    }

    @Test
    void responseDeclaresUtf8Charset() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/health").openConnection();
        String ct = c.getContentType();
        assertTrue(ct != null && ct.toLowerCase().contains("utf-8"),
                "Content-Type 必须显式声明 utf-8，实际: " + ct);
        c.disconnect();
    }

    // ==================== 空 body / 边界 ====================

    @Test
    void upsertEmptyPointListIsOkAndAddsNothing() throws Exception {
        Resp r = call("PUT", "/collections/docs/points", "{\"points\":[]}");
        assertEquals(200, r.status, r.text());
        assertEquals(0, ((Number) r.json().get("count")).intValue());
    }
}
