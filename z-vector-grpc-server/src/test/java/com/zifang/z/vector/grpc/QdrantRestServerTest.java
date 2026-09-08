package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QdrantRestServer 集成测试 — 通过 HTTP 调用验证 REST 端点。
 */
class QdrantRestServerTest {

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
                        Map.of("lang", "en")),
                new com.zifang.z.vector.api.VectorPoint("d2", new float[]{0.9f, 0.1f, 0},
                        Map.of("lang", "zh"))
        ));

        Map<String, Object> searchBody = new LinkedHashMap<>();
        searchBody.put("vector", java.util.Arrays.asList(1, 0, 0));
        searchBody.put("limit", 10);
        searchBody.put("filter", Map.of("lang", "en"));

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
        URL url = new URL("http://localhost:" + TEST_PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
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
        byte[] bytes = is.readAllBytes();
        conn.disconnect();
        if (bytes.length == 0) return new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = json.readValue(bytes, Map.class);
        return resp == null ? new LinkedHashMap<>() : resp;
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