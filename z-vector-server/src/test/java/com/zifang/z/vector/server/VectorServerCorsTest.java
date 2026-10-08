package com.zifang.z.vector.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.core.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * z-vector-server CORS 行为契约。
 * <p>
 * 分开发部署下前端用 vite dev proxy / nginx proxy_pass 反代，不需要 CORS；
 * 但阶段一给前端「浏览器直连 :6333」的能力留一个口子（ZVECTOR_CORS_ORIGINS 启用）。
 * 一旦启用，浏览器会发 OPTIONS 预检；任何对预检的失误都会让「前端能起但请求全 0」——
 * 所以把行为钉住：
 * <ul>
 *   <li>预检命中返回 204 + 完整 CORS 头，<b>不下钻业务</b>（不能让 OPTIONS 打到业务上）</li>
 *   <li>Origin 不在白名单时预检 403</li>
 *   <li>白名单 = {@code *} 时 Allow-Origin: * 且不附加 Vary: Origin</li>
 *   <li>白名单 = 精确匹配时 Allow-Origin 回声 + Vary: Origin</li>
 *   <li>未启用 CORS 时不挂任何 CORS 头</li>
 * </ul>
 * <p>
 * 实际发请求一律用裸 socket 写 HTTP/1.1 —— JDK HttpURLConnection 对 OPTIONS 请求发送
 * 自定义 header（特别是 {@code Access-Control-Request-Method}）有平台差异：部分版本
 * 不发 doOutput=false 的 OPTIONS 的非标准 header，导致预检识别不到 ACRM 而误判为非预检。
 * 裸 socket 没有这层包装，永远忠实发出。
 */
class VectorServerCorsTest {

    private HttpServer server;
    private String base;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ==================== 关闭 CORS：默认形态 ====================

    @Test
    void noCorsHeaderWhenEnvUnset() throws Exception {
        boot(Collections.emptyList());
        Map<String, String> resp = rawRequest("GET", "/__instance",
                headers("Origin", "http://localhost:3000"));
        assertEquals("200", resp.get("status"));
        // rawRequest 把 header key 全转小写：HTTP 头大小写不敏感，统一存小写避免歧义。
        assertNull(resp.get("access-control-allow-origin"),
                "未启用 CORS 时不挂 Allow-Origin 头（生产同源 nginx 反代不需要）");
    }

    // ==================== 通配白名单 ====================

    @Test
    void wildcardOriginMatchesAny() throws Exception {
        boot(Arrays.asList("*"));
        Map<String, String> resp = rawRequest("GET", "/__instance",
                headers("Origin", "http://somewhere.example.com"));
        assertEquals("200", resp.get("status"));
        assertEquals("*", resp.get("access-control-allow-origin"));
    }

    @Test
    void wildcardPreflightReturns204NoVary() throws Exception {
        boot(Arrays.asList("*"));
        Map<String, String> resp = rawRequest("OPTIONS", "/collections",
                headers("Origin", "http://localhost:3000",
                        "Access-Control-Request-Method", "GET"));
        assertEquals("204", resp.get("status"),
                "OPTIONS 预检命中必须 204，不能 200 / 405 / 404");
        assertEquals("*", resp.get("access-control-allow-origin"));
        assertNull(resp.get("vary"),
                "通配白名单不挂 Vary: Origin（Vary 在精确匹配时才挂）");
        String methods = resp.get("access-control-allow-methods");
        assertNotNull(methods, "Allow-Methods 必须有");
        assertTrue(methods.contains("GET") && methods.contains("POST")
                        && methods.contains("PUT") && methods.contains("DELETE"),
                "Allow-Methods 必须覆盖所有业务方法: " + methods);
        assertNotNull(resp.get("access-control-allow-headers"));
        assertEquals("3600", resp.get("access-control-max-age"));
    }

    // ==================== 精确白名单 ====================

    @Test
    void exactOriginEchoesAndAddsVary() throws Exception {
        boot(Arrays.asList("http://localhost:3000", "https://app.example.com"));
        Map<String, String> resp = rawRequest("GET", "/__instance",
                headers("Origin", "http://localhost:3000"));
        assertEquals("200", resp.get("status"));
        assertEquals("http://localhost:3000", resp.get("access-control-allow-origin"),
                "精确白名单命中：Allow-Origin 必须回声请求的 Origin");
        assertEquals("Origin", resp.get("vary"),
                "精确匹配必须挂 Vary: Origin 防缓存串");
    }

    @Test
    void exactOriginRejectsForeignPreflight() throws Exception {
        boot(Arrays.asList("http://localhost:3000"));
        Map<String, String> resp = rawRequest("OPTIONS", "/collections",
                headers("Origin", "http://evil.example",
                        "Access-Control-Request-Method", "GET"));
        assertEquals("403", resp.get("status"),
                "Origin 不在白名单：预检必须 403，不能 204 后让浏览器以为 OK —— 否则 Access-Control-Allow-Origin 不挂，浏览器其实会拦截");
    }

    @Test
    void exactOriginLeavesForeignGetWithoutCorsHeader() throws Exception {
        boot(Arrays.asList("http://localhost:3000"));
        // foreign GET：仍然 200（业务正常执行），但 Allow-Origin 不挂 —— 浏览器拿到后自然会拦。
        Map<String, String> resp = rawRequest("GET", "/__instance",
                headers("Origin", "http://evil.example"));
        assertEquals("200", resp.get("status"),
                "业务 GET 即使 Origin 不在白名单也照样执行；拦截交给浏览器");
        assertNull(resp.get("access-control-allow-origin"),
                "Origin 不命中时不应挂 Allow-Origin，让浏览器自行拒绝");
    }

    // ==================== 短路证据：OPTIONS 命中不打到业务 ====================

    @Test
    void preflightShortCircuitsBeforeBusinessHandler() throws Exception {
        // OPTIONS 打到 /collections/{n} 这条路由如果下钻到 QdrantRestServer.route()，
        // 走 P_COLLECTION 分支；当前 route() 对非 GET/PUT/DELETE 不在该分支处理 ⇒ 405。
        // CorsHandler 必须拦在 route() 之前，否则 OPTIONS 会变成 405 而不是 204。
        boot(Arrays.asList("*"));
        Map<String, String> resp = rawRequest("OPTIONS", "/collections/anything",
                headers("Origin", "http://localhost:3000",
                        "Access-Control-Request-Method", "PUT"));
        assertEquals("204", resp.get("status"),
                "OPTIONS 预检必须在业务路由之前短路：如果是 405 说明 CorsHandler 没拦在 QdrantRestServer.route() 之前");
    }

    // ==================== 工具方法：裸 socket HTTP/1.1 客户端 ====================

    private void boot(List<String> corsOrigins) throws Exception {
        server = VectorServerApplication.start(0, new InMemoryVectorStore(),
                "/tmp/zvector-cors-test", Instant.now(), corsOrigins);
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
    }

    /**
     * 用裸 socket 写一份 HTTP/1.1 请求；返回 status + 响应头集合。响应体丢弃。
     * <p>
     * 为什么不用 HttpURLConnection：JDK 对 OPTIONS 请求的自定义 header 处理有平台差异，
     * 部分版本不发 {@code Access-Control-Request-Method}，导致预检识别不到 ACRM。
     * 这个测试专门验的是 CORS 预检逻辑，HttpURLConnection 自身的可变性是干扰项 —— 用 socket
     * 直接写出请求，保证每次都精确发出我们要的 header。
     */
    private Map<String, String> rawRequest(String method, String path,
                                            Map<String, String> hdrs) throws IOException {
        Socket so = new Socket("127.0.0.1", port);
        so.setSoTimeout(5000);
        try {
            OutputStream os = so.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: 127.0.0.1:").append(port).append("\r\n");
            req.append("Connection: close\r\n");
            for (Map.Entry<String, String> e : hdrs.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            req.append("\r\n");
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();

            BufferedReader br = new BufferedReader(new InputStreamReader(so.getInputStream(), StandardCharsets.UTF_8));
            Map<String, String> result = new HashMap<>();
            String statusLine = br.readLine();
            if (statusLine == null) throw new IOException("empty response");
            // HTTP/1.1 200 OK
            int sp1 = statusLine.indexOf(' ');
            int sp2 = statusLine.indexOf(' ', sp1 + 1);
            result.put("status", statusLine.substring(sp1 + 1, sp2));
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    // HTTP header 名大小写不敏感，存小写为统一 key
                    result.put(name.toLowerCase(Locale.ROOT), value);
                }
            }
            // 响应体丢弃到 EOF（Connection: close 已经会触发）
            return result;
        } finally {
            so.close();
        }
    }

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // 留作未来扩展：响应 body 解析。
    @SuppressWarnings("unused")
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unused")
    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
