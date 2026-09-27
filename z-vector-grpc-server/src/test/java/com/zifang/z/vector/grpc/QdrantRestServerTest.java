package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QdrantRestServer 集成测试 — 通过 HTTP 调用验证 REST 端点。
 */
class QdrantRestServerTest {

    /** Java 8 版 Map.of（同款见 IndexFactoryParamTest.params / FilterTest.mapOf）。 */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }


    private static final int TEST_PORT = 16334; // 避开默认 6334
    private QdrantRestServer server;
    private VectorStore store;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        store = new InMemoryVectorStore();
        server = new QdrantRestServer(store, TEST_PORT);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (store != null) store.close();
    }

    @Test
    void listCollectionsEmpty() throws IOException {
        Map<String, Object> resp = httpGet("/collections");
        assertEquals("ok", resp.get("status"));
        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) resp.get("collections");
        assertNotNull(cols);
        assertEquals(0, cols.size());
    }

    @Test
    void createCollection() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dimension", 768);
        body.put("metric", "COSINE");
        body.put("index_type", "HNSW");

        Map<String, Object> resp = httpPut("/collections/docs", body);
        assertEquals("ok", resp.get("status"));
        assertTrue(store.hasCollection("docs"));
        assertEquals(768, store.getCollection("docs").getDimension());
    }

    @Test
    void getCollection() throws IOException {
        store.createCollection("docs", 128, DistanceMetric.L2, IndexType.FLAT, null);
        Map<String, Object> resp = httpGet("/collections/docs");
        assertEquals(128, resp.get("dimension"));
        assertEquals("L2", resp.get("metric"));
        assertEquals("FLAT", resp.get("index_type"));
    }

    @Test
    void deleteCollection() throws IOException {
        store.createCollection("temp", 64, DistanceMetric.L2);
        Map<String, Object> resp = httpDelete("/collections/temp");
        assertEquals("ok", resp.get("status"));
        assertFalse(store.hasCollection("temp"));
    }

    @Test
    void upsertAndSearch() throws IOException {
        store.createCollection("docs", 3, DistanceMetric.L2);

        // Upsert
        Map<String, Object> upsertBody = new LinkedHashMap<>();
        upsertBody.put("points", java.util.Arrays.asList(
                pointJson("d1", new float[]{1, 0, 0}, null),
                pointJson("d2", new float[]{0, 1, 0}, null),
                pointJson("d3", new float[]{0.9f, 0.1f, 0}, null)
        ));
        Map<String, Object> resp = httpPut("/collections/docs/points", upsertBody);
        assertEquals("ok", resp.get("status"));

        // Search
        Map<String, Object> searchBody = new LinkedHashMap<>();
        searchBody.put("vector", java.util.Arrays.asList(1, 0, 0));
        searchBody.put("limit", 2);
        Map<String, Object> searchResp = httpPost("/collections/docs/points/search", searchBody);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResp.get("result");
        assertEquals(2, results.size());
        assertEquals("d1", results.get(0).get("id"));
    }

    @Test
    void searchWithFilter() throws IOException {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsertBatch("docs", java.util.Arrays.asList(
                new com.zifang.z.vector.api.VectorPoint("d1", new float[]{1, 0, 0},
                        map("lang", "en")),
                new com.zifang.z.vector.api.VectorPoint("d2", new float[]{0.9f, 0.1f, 0},
                        map("lang", "zh"))
        ));

        Map<String, Object> searchBody = new LinkedHashMap<>();
        searchBody.put("vector", java.util.Arrays.asList(1, 0, 0));
        searchBody.put("limit", 10);
        searchBody.put("filter", map("lang", "en"));

        Map<String, Object> resp = httpPost("/collections/docs/points/search", searchBody);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("result");
        assertEquals(1, results.size());
        assertEquals("d1", results.get(0).get("id"));
    }

    @Test
    void pointCount() throws IOException {
        store.createCollection("docs", 3, DistanceMetric.L2);
        store.upsertBatch("docs", java.util.Arrays.asList(
                new com.zifang.z.vector.api.VectorPoint("d1", new float[]{1, 0, 0}),
                new com.zifang.z.vector.api.VectorPoint("d2", new float[]{0, 1, 0})
        ));
        Map<String, Object> resp = httpGet("/collections/docs/points/count");
        assertEquals(2, ((Number) resp.get("count")).intValue());
    }

    // ==================== HTTP 工具方法 ====================

    private Map<String, Object> httpGet(String path) throws IOException {
        return doHttp("GET", path, null);
    }

    private Map<String, Object> httpPost(String path, Object body) throws IOException {
        return doHttp("POST", path, body);
    }

    private Map<String, Object> httpPut(String path, Object body) throws IOException {
        return doHttp("PUT", path, body);
    }

    private Map<String, Object> httpDelete(String path) throws IOException {
        return doHttp("DELETE", path, null);
    }

    private Map<String, Object> doHttp(String method, String path, Object body) throws IOException {
        try {
            return doHttpOnce(method, path, body);
        } catch (IOException transport) {
            // 传输层事故（端口复用后写到上一台 server 的废连接上）重试一次；判据只看真响应。
            // 成因与 {@code OpenApiSpecRoutingTest.send} 里记的同一条。
            return doHttpOnce(method, path, body);
        }
    }

    private Map<String, Object> doHttpOnce(String method, String path, Object body) throws IOException {
        URL url = new URL("http://localhost:" + TEST_PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        conn.setRequestProperty("Connection", "close");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.writeValueAsBytes(body));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return new LinkedHashMap<>();
        // InputStream.readAllBytes() 是 Java 9 API，本模块 target 1.8 —— 与
        // QdrantRestServer.readAll 同款替身（那边的注释说明了为什么不能直接用）。
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            is.close();
        }
        byte[] bytes = bos.toByteArray();
        conn.disconnect();
        if (bytes.length == 0) return new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = json.readValue(bytes, Map.class);
        return resp == null ? new LinkedHashMap<>() : resp;
    }

    /**
     * keep-alive：一条 TCP 连接上连发三个请求，每个都要拿到状态行 + 恰好 Content-Length 那么多字节。
     * <p>
     * 这条尺守的是 {@code sendJson} 里那句 {@code sendResponseHeaders(code, bytes.length)}：把长度写成
     * 0 或 -1，{@code HttpURLConnection} 那类"每次新开连接"的客户端全都察觉不到，而复用连接的客户端
     * 会把第一个响应体当成第二个响应的开头 —— 客户端拿到的是一堆错位数据，不是错误。
     */
    @Test
    void twoRequestsOnOneKeepAliveConnection() throws Exception {
        java.net.Socket so = new java.net.Socket("localhost", TEST_PORT);
        so.setSoTimeout(5000);
        try {
            OutputStream os = so.getOutputStream();
            InputStream is = so.getInputStream();
            String[] paths = {"/collections", "/collections", "/definitely-not-advertised"};
            int[] want = {200, 200, 404};
            for (int i = 0; i < paths.length; i++) {
                os.write(("GET " + paths[i] + " HTTP/1.1\r\nHost: localhost:" + TEST_PORT
                        + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                String status = readStatusLine(is);
                assertTrue(status.startsWith("HTTP/1.1 " + want[i]),
                        "第 " + (i + 1) + " 个请求（" + paths[i] + "）状态行错位: [" + status + "]");
                int len = -1;
                String line;
                while (!(line = readLine(is)).isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        len = Integer.parseInt(line.split(":", 2)[1].trim());
                    }
                }
                assertTrue(len > 0, "第 " + (i + 1) + " 个响应没带 Content-Length(" + len
                        + ")，复用连接的客户端无从判断边界");
                byte[] body = new byte[len];
                int got = 0;
                while (got < len) {
                    int n = is.read(body, got, len - got);
                    assertTrue(n > 0, "第 " + (i + 1) + " 个响应体读到第 " + got + "/" + len + " 字节就断了");
                    got += n;
                }
                // 边界必须正好在 len 处：多读一个字节就会把下一个响应的头吃掉，下一轮必然错位。
                assertEquals(len, got, "第 " + (i + 1) + " 个响应体字节数不符");
            }
        } finally {
            so.close();
        }
    }

    private static String readStatusLine(InputStream is) throws IOException {
        String line = readLine(is);
        assertTrue(line.startsWith("HTTP/1."), "不是 HTTP 响应: [" + line + "]");
        return line;
    }

    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(96);
        int c;
        while ((c = is.read()) >= 0) {
            if (c == '\r') {
                int n = is.read();
                if (n == '\n') break;
                bos.write(c);
                if (n >= 0) bos.write(n);
            } else {
                bos.write(c);
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private Map<String, Object> pointJson(String id, float[] vector, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        List<Float> vecList = new java.util.ArrayList<>(vector.length);
        for (float f : vector) vecList.add(f);
        p.put("vector", vecList);
        if (payload != null) p.put("payload", payload);
        return p;
    }
}