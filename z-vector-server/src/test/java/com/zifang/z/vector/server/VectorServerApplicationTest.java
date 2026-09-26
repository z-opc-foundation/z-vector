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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * z-vector-server 独立服务器的端到端 HTTP 测试。
 * <p>
 * 这个模块此前 <b>一个测试都没有</b>（src/test 目录不存在），所以下面每一条都是在
 * 钉一个真实存在过的缺陷，不是装饰性断言。
 */
class VectorServerApplicationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", 3, DistanceMetric.COSINE, IndexType.FLAT, null);
        server = VectorServerApplication.start(0, store);
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
        return new Resp(status, bos.toByteArray());
    }

    // ==================== /health ====================

    @Test
    void healthReportsCurrentArtifactVersion() throws Exception {
        Resp r = call("GET", "/health", null);
        assertEquals(200, r.status);
        // 这一条以前长这样：assertEquals("1.0.2", r.json().get("version"))，而 main 里就是同一个
        // 字面量 —— 两边一起漂，断言永远绿（同一处错过两次：1.0.1 对 1.0.2、1.0.2 对 1.0.3）。
        // 参照值取聚合 pom 的 <revision>（全仓版本的唯一定义点）。不再读模块 pom 的第一个
        // <version>：模块 pom 如今压根不写自己的版本，那样会一路读到依赖的头上去
        // （实测读到的是字面量 "${project.version}"）。
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
    void listCollectionsReturnsStoreContents() throws Exception {
        Resp r = call("GET", "/collections", null);
        assertEquals(200, r.status);
        assertTrue(r.text().contains("docs"), r.text());
    }

    @Test
    void createCollectionAcceptsIntegerDimensions() throws Exception {
        Resp r = call("PUT", "/collections", "{\"name\":\"imgs\",\"dimensions\":4}");
        assertEquals(200, r.status, r.text());
        assertEquals("imgs", r.json().get("collection"));
        // 真的建出来了，而不只是回了个 ok
        InMemoryVectorStore store = (InMemoryVectorStore) store();
        assertTrue(store.hasCollection("imgs"));
        assertEquals(4, store.getCollection("imgs").getDimension());
    }

    @Test
    void jacksonParsesJsonIntsAsDoubleSoCastMustTolerateIt() throws Exception {
        // 此前代码写的是 (int) request.get("dimensions")。JSON 数字经 Jackson 默认
        // 走 Integer，但一旦客户端提交 4.0 或走 float 通道就是 Double —— CCE 直接 500。
        Resp r = call("PUT", "/collections", "{\"name\":\"f4\",\"dimensions\":4.0}");
        assertEquals(200, r.status, "dimensions 以小数形式提交不应 500: " + r.text());
    }

    @Test
    void missingOrNonPositiveDimensionsIs400NotNpe() throws Exception {
        // 此前 (int) get(...) 遇 null 直接 NPE → 500 "Internal error: null"
        assertEquals(400, call("PUT", "/collections", "{\"name\":\"x\"}").status);
        assertEquals(400, call("PUT", "/collections", "{\"dimensions\":4}").status);
        assertEquals(400, call("PUT", "/collections", "{\"name\":\"x\",\"dimensions\":0}").status);
        assertEquals(400, call("PUT", "/collections", "{\"name\":\"\",\"dimensions\":4}").status);
    }

    @Test
    void unknownMethodOnCollectionsIs405WithBody() throws Exception {
        Resp r = call("DELETE", "/collections", null);
        assertEquals(405, r.status);
        assertNotNull(r.json().get("error"));
    }

    // ==================== /points ====================

    @Test
    void upsertThenSearchRoundTrip() throws Exception {
        Resp up = call("POST", "/points", "{\"collection\":\"docs\",\"points\":["
                + "{\"id\":\"a\",\"vector\":[1,0,0],\"payload\":{\"lang\":\"en\"}},"
                + "{\"id\":\"b\",\"vector\":[0,1,0],\"payload\":{\"lang\":\"zh\"}}]}");
        assertEquals(200, up.status, up.text());
        assertEquals(2, ((Number) up.json().get("upserted")).intValue());

        Resp se = call("POST", "/search", "{\"collection\":\"docs\",\"vector\":[0.9,0.1,0],\"limit\":2}");
        assertEquals(200, se.status, se.text());
        List<?> hits = (List<?>) se.json().get("results");
        assertEquals(2, hits.size());
        Map<?, ?> top = (Map<?, ?>) hits.get(0);
        assertEquals("a", top.get("id"), "最近邻应是 a: " + se.text());
        assertTrue(top.containsKey("score"));
        assertTrue(top.containsKey("payload"));
    }

    @Test
    void searchLimitIsHonoured() throws Exception {
        call("POST", "/points", "{\"collection\":\"docs\",\"points\":["
                + "{\"id\":\"a\",\"vector\":[1,0,0]},{\"id\":\"b\",\"vector\":[0,1,0]},"
                + "{\"id\":\"c\",\"vector\":[0,0,1]}]}");
        Resp r = call("POST", "/search", "{\"collection\":\"docs\",\"vector\":[1,1,1],\"limit\":1}");
        assertEquals(200, r.status);
        assertEquals(1, ((List<?>) r.json().get("results")).size(), "limit 必须生效");
    }

    @Test
    void missingCollectionIs404NotNpe() throws Exception {
        // 此前 hasCollection(null) 会走进实现里抛异常 → 500
        assertEquals(404, call("POST", "/points",
                "{\"collection\":\"nope\",\"points\":[]}").status);
        assertEquals(404, call("POST", "/search",
                "{\"collection\":\"nope\",\"vector\":[1,0,0]}").status);
        assertEquals(404, call("POST", "/points", "{\"points\":[]}").status);
    }

    @Test
    void badVectorShapeIs400Not500() throws Exception {
        Resp r = call("POST", "/points", "{\"collection\":\"docs\",\"points\":[{\"id\":\"a\",\"vector\":\"oops\"}]}");
        assertEquals(400, r.status, "非法 vector 应是 400 + error，实际 " + r.status + " " + r.text());
        assertNotNull(r.json().get("error"));
    }

    // ==================== UTF-8：中文 payload ====================

    @Test
    void chinesePayloadSurvivesRoundTrip() throws Exception {
        Resp up = call("POST", "/points", "{\"collection\":\"docs\",\"points\":[{\"id\":\"中文\",\"vector\":[1,0,0],"
                + "\"payload\":{\"标题\":\"向量数据库\"}}]}");
        assertEquals(200, up.status, up.text());
        assertEquals(1, ((Number) up.json().get("upserted")).intValue(), "1 条中文记录应写成功");

        Resp se = call("POST", "/search", "{\"collection\":\"docs\",\"vector\":[1,0,0],\"limit\":1}");
        assertEquals(200, se.status);
        Map<?, ?> top = (Map<?, ?>) ((List<?>) se.json().get("results")).get(0);
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
    void emptyBodyOnPutIsTreatedAsMissingFieldsNotError() throws Exception {
        assertEquals(400, call("PUT", "/collections", "").status);
    }

    @Test
    void upsertEmptyPointListIsOkAndAddsNothing() throws Exception {
        Resp r = call("POST", "/points", "{\"collection\":\"docs\",\"points\":[]}");
        assertEquals(200, r.status, r.text());
        assertEquals(0, ((Number) r.json().get("upserted")).intValue());
    }

    private Object store() {
        try {
            java.lang.reflect.Field f = VectorServerApplication.class.getDeclaredField("vectorStore");
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
