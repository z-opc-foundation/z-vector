package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import com.zifang.z.vector.protocol.OpenApiSpec;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenApiSpec 广告的每一条 path/method，都必须被这台 server 真的路由到。
 * <p>
 * 这一族缺陷的形状：文档写着 {@code GET /healthz}，而两台 server 都没有这个端点
 * （这台会掉进路由兜底，返回 404 "Not found: GET /healthz"）；文档又把搜索挂在
 * {@code POST /collections/{name}/points} 上，而这台对 {@code /points} 只认 PUT，
 * POST 直接 405。照着这份文档生成客户端的人拿到的是"看上去支持、一调就不通"的方法，
 * 而且方法名（healthz / searchPoints）还会让人以为是自己用法错了。
 * <p>
 * 所以不比对文本、不比对常量，比对<b>行为</b>：真起一台，把文档里的每条路都发一遍，
 * 只要求一件事 —— 不许是路由兜底的那句 "Not found:"。业务性 404（集合不存在）与它不同
 * 消息，因此这条尺既不为实现打开绿灯，也不把"还没实现"当成合法状态留在文档里。
 */
class OpenApiSpecRoutingTest {

    /** 兜底那一句的开头 —— 只有 {@link QdrantRestServer} 的路由没命中时才会出现。 */
    private static final String ROUTER_FALLTHROUGH = "Not found:";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] METHODS = {"get", "put", "post", "delete"};

    /**
     * 这台的完整对外面：{方法, 真发的 path, 文档里应有的模板}。
     * 逐项列出来而不是从源码里反射路由表 —— 反射出来的表会跟着代码一起漏掉同一个端点，
     * 而这份清单漏一项就会让上面那条反向尺少核一个端点，两种漏法谁的账更清楚一眼看得出。
     */
    private static final String[][] ROUTED_SURFACE = {
            {"GET", "/collections", "/collections"},
            {"PUT", "/collections/probe", "/collections/{collection_name}"},
            {"GET", "/collections/probe", "/collections/{collection_name}"},
            {"DELETE", "/collections/probe", "/collections/{collection_name}"},
            {"PUT", "/collections/probe/points", "/collections/{collection_name}/points"},
            {"GET", "/collections/probe/points/p1", "/collections/{collection_name}/points/{id}"},
            {"DELETE", "/collections/probe/points/p1", "/collections/{collection_name}/points/{id}"},
            {"POST", "/collections/probe/points/search", "/collections/{collection_name}/points/search"},
            {"GET", "/collections/probe/points/count", "/collections/{collection_name}/points/count"},
    };

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

    @Test
    void everyAdvertisedPathIsRouted() throws Exception {
        List<String[]> advertised = advertisedOperations(OpenApiSpec.generateSpec());
        // 尺不许空跑：解析器一旦认不出结构，下面的循环会一条不发地全绿通过。
        assertTrue(advertised.size() >= 6, "只从 spec 里解析出 " + advertised.size()
                + " 条 path/method（改前至少 7 条）—— 解析器或 spec 结构变了，这把尺现在是瞎的");

        List<String> unrouted = new ArrayList<String>();
        for (String[] op : advertised) {
            String method = op[0].toUpperCase(java.util.Locale.ROOT);
            String path = op[1].replace("{collection_name}", "probe");
            Resp r = send(method, path);
            if (unrouted(r)) {
                unrouted.add(method + " " + op[1] + " → " + r.code + " " + r.message);
            }
        }
        assertTrue(unrouted.isEmpty(),
                "OpenApiSpec 广告了这台 server 不路由的端点（生成的客户端会拿到 404/405）:\n  "
                        + String.join("\n  ", unrouted));
    }

    /**
     * 反向的一半：这台真的在路由的 path，文档里也必须有。
     * <p>
     * 判"路由到了"用的是行为而非源码常量 —— 只问"这个 path 返回的不是兜底 404"。
     * 少了这一条，把文档越写越少就能单向让上一条变绿，而 README 还在说"兼容 Qdrant 全部
     * 核心端点"。两向都钉住，文档这一层才跟实现同宽。
     */
    @Test
    void endpointsThisServerRoutesAreAdvertised() throws Exception {
        Set<String> advertised = new HashSet<String>();
        for (String[] op : advertisedOperations(OpenApiSpec.generateSpec())) {
            advertised.add(op[0].toUpperCase(java.util.Locale.ROOT) + " " + op[1]);
        }
        List<String> silent = new ArrayList<String>();
        for (String[] probe : ROUTED_SURFACE) {
            Resp r = send(probe[0], probe[1]);
            boolean routed = !unrouted(r);
            String key = probe[0] + " " + probe[2];
            if (routed && !advertised.contains(key)) {
                silent.add(key + "（实测 " + probe[0] + " " + probe[1] + " → " + r.code + " "
                        + r.message + "）");
            }
        }
        assertTrue(silent.isEmpty(), "这台 server 在路由、OpenApiSpec 却一个字没提的端点:\n  "
                + String.join("\n  ", silent));
    }

    // ==================== 取证据 ====================

    /**
     * 什么算"这条 path/method 没被路由"。
     * <p>
     * 404 只认兜底那一句（业务性的 "Collection not found: probe" 是路由命中之后的事），
     * 而 405 一律算没路由：这台只在"这条 path 不认这个方法"时打 405 —— 文档把搜索挂在
     * {@code /points} 上（真机对它有 PUT 无 POST）正是这一形状，只判 404 会把它放过去。
     */
    private static boolean unrouted(Resp r) {
        return r.code == 405 || (r.code == 404 && r.message != null
                && r.message.startsWith(ROUTER_FALLTHROUGH));
    }

    private static final class Resp {
        final int code;
        final String message;

        Resp(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /**
     * 这把尺量的是"服务器对这条 (method, path) 答了什么"，所以连接本身必须是干净的：
     * 每个测试方法都新建/关闭一台 server（{@code port=0} ⇒ 内核挑端口），而 {@code HttpURLConnection}
     * 默认按 host:port 缓存 keep-alive 连接 —— 端口被下一台复用后，请求可能写到上一条 server 留下的
     * 废连接上，实测就是 1/20 的 {@code SocketException: Unexpected end of file from server}
     * （基线因此判红过一次）。{@code Connection: close} 让它永不复用连接；keep-alive 本身是对的，
     * 由 {@code QdrantRestServerTest#两个请求走同一条连接都拿到完整响应} 那条尺守着。
     * <p>
     * 传输层失败（连不上/半路断）重试一次：判据只看真响应，一次 socket 事故不该让整把尺报红。
     * 服务器给出的 4xx/5xx 不是传输层失败，不会重试。
     */
    private Resp send(String method, String path) throws IOException {
        try {
            return sendOnce(method, path);
        } catch (IOException transport) {
            return sendOnce(method, path);
        }
    }

    private Resp sendOnce(String method, String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Connection", "close");
        if (!"GET".equals(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        String text;
        try {
            text = readAll(is);
        } finally {
            if (is != null) is.close();
        }
        conn.disconnect();
        String message = null;
        if (!text.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JSON.readValue(text, Map.class);
                Object m = parsed == null ? null : parsed.get("message");
                // 这台的信封是 {status, code, message}；兜底那条一定带 message。
                message = m == null ? null : String.valueOf(m);
                if (message == null && parsed != null && parsed.get("detail") != null) {
                    message = String.valueOf(parsed.get("detail"));
                }
            } catch (IOException notJson) {
                message = text;
            }
        }
        return new Resp(code, message);
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 从生成的 YAML 文本里取出 {@code paths} 段的 (method, pathTemplate) 清单。
     * <p>
     * 只按缩进认结构（indent 2 的 {@code /xxx:} 是 path，indent 4 的 {@code get:/put:/…} 是
     * 操作），不去复用 OpenApiSpec 里的任何字面量 —— 抄它的字面量就等于让它自己给自己打分。
     */
    private static List<String[]> advertisedOperations(String spec) {
        List<String[]> out = new ArrayList<String[]>();
        String path = null;
        boolean inPaths = false;
        for (String line : spec.split("\n", -1)) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (indent == 0) {
                inPaths = "paths:".equals(trimmed);
                path = null;
                continue;
            }
            if (!inPaths) continue;
            if (indent == 2 && trimmed.startsWith("/") && trimmed.endsWith(":")) {
                path = trimmed.substring(0, trimmed.length() - 1);
                continue;
            }
            if (indent == 4 && path != null && trimmed.endsWith(":")) {
                String key = trimmed.substring(0, trimmed.length() - 1);
                if (Arrays.asList(METHODS).contains(key)) {
                    assertNotNull(key, "空操作名");
                    out.add(new String[]{key, path});
                }
            }
        }
        return out;
    }

    /** 兜底消息必须真的可能出现：这条对照保证上面的判据不是恒真（写错的常量会让整把尺空跑）。 */
    @Test
    void routerFallthroughProbeItselfWorks() throws Exception {
        Resp r = send("GET", "/definitely-not-advertised");
        assertNotNull(r.message, "兜底响应没有可读的 message 字段，这把尺就没有判据了");
        assertTrue(r.message.startsWith(ROUTER_FALLTHROUGH),
                "未路由的 path 没返回兜底 404，而是: " + r.code + " " + r.message);
        assertTrue(unrouted(r), "兜底 404 没被判成未路由");
        // 405 那一支也得有猎物，否则 unrouted() 里的 405 判据是空写的：这台对 /points 只认 PUT。
        Resp mismatch = send("POST", "/collections/probe/points");
        assertEquals(405, mismatch.code, "path 命中、方法不命中的形状变了: " + mismatch.message);
        assertTrue(unrouted(mismatch), "405 没被算进未路由 —— 挂在错误 method 上的广告会放过");
        // 反向对照：路由命中且方法也命中的那条，不许被误判成未路由。
        assertFalse(unrouted(send("PUT", "/collections/probe/points")),
                "真在服务的端点被判成了未路由，上一条尺就成了只会报红的空尺");
        assertFalse(advertisedOperations(OpenApiSpec.generateSpec()).isEmpty(), "解析器没解析出任何操作");
    }
}
