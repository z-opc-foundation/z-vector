package com.zifang.z.vector.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * z-vector-server /__instance 自省接口契约。
 * <p>
 * 钉住的字段是前端「实例状态」页消费的形状 —— 任何字段被悄悄去掉都会让页面变成「加载中」。
 * 钉住的数值关系（collections 数 = listCollections().size()，points_total = Σ getPointCount）
 * 是 bug 重现的护栏：实例自省与真实 store 一旦脱节（比如换了 store ref、忘了 sum points），
 * 这一条立刻红。
 */
class VectorServerInstanceEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private InMemoryVectorStore store;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryVectorStore();
        server = VectorServerApplication.start(0, store, "/data/zvector-fixture",
                Instant.now().minusSeconds(1), Collections.emptyList());
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void instanceReportsAllFields() throws Exception {
        Map<?, ?> json = getJson("/__instance");
        assertEquals("ok", json.get("status"));
        assertNotNull(json.get("version"), "version 不能缺");
        assertNotNull(json.get("port"), "port 不能缺");
        assertNotNull(json.get("uptime_ms"), "uptime_ms 不能缺");
        assertEquals("/data/zvector-fixture", json.get("data_dir"));
        assertNotNull(json.get("jvm"), "jvm (pid@host) 不能缺");
        assertNotNull(json.get("java"), "java.version 不能缺");
        assertNotNull(json.get("collections"), "collections 数不能缺");
        assertNotNull(json.get("points_total"), "points_total 不能缺");
        Map<?, ?> mem = (Map<?, ?>) json.get("memory");
        assertNotNull(mem, "memory.heap_used / heap_max / free 三件套都要在");
        assertNotNull(mem.get("heap_used"));
        assertNotNull(mem.get("heap_max"));
        assertNotNull(mem.get("free"));
        // uptime 至少 0，不能是负的；since = now - 1s，所以 ≥ 1000
        long uptime = ((Number) json.get("uptime_ms")).longValue();
        assertTrue(uptime >= 0, "uptime 不能为负: " + uptime);
        assertTrue(uptime >= 500, "Instant.now() - startedAt 在 setUp 里给了 1 秒偏移，" +
                "uptime 不应小于 500ms: " + uptime);
    }

    @Test
    void instanceCollectionsCountMatchesStore() throws Exception {
        store.createCollection("a", 4, DistanceMetric.COSINE, IndexType.FLAT, null);
        store.createCollection("b", 4, DistanceMetric.L2, IndexType.HNSW, null);
        Map<?, ?> json = getJson("/__instance");
        assertEquals(2, ((Number) json.get("collections")).intValue(),
                "collections 字段必须等于 store.listCollections().size()");
    }

    @Test
    void instancePointsTotalSumsAcrossCollections() throws Exception {
        store.createCollection("a", 2, DistanceMetric.COSINE, IndexType.FLAT, null);
        store.createCollection("b", 2, DistanceMetric.COSINE, IndexType.FLAT, null);
        store.upsertBatch("a", Arrays.asList(
                new VectorPoint("a1", new float[]{1, 0}, null),
                new VectorPoint("a2", new float[]{0, 1}, null)));
        store.upsertBatch("b", Arrays.asList(
                new VectorPoint("b1", new float[]{1, 0}, null)));
        Map<?, ?> json = getJson("/__instance");
        assertEquals(3, ((Number) json.get("points_total")).intValue(),
                "points_total 必须等于 Σ store.getPointCount(c)，不能漏算也不能多算");
    }

    @Test
    void instancePortMatchesBoundPort() throws Exception {
        Map<?, ?> json = getJson("/__instance");
        int reportedPort = ((Number) json.get("port")).intValue();
        assertEquals(server.getAddress().getPort(), reportedPort,
                "instance.port 必须等于 HttpServer 真实绑上的端口，0 时由内核挑的那个也要报对");
    }

    @Test
    void instanceRejectsNonGet() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/__instance").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        c.disconnect();
        assertEquals(405, code, "instance 必须只接受 GET；其它方法 405，不能让前端意外写了 POST 也走通");
    }

    private Map<?, ?> getJson(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
        }
        c.disconnect();
        assertEquals(200, code);
        return JSON.readValue(bos.toByteArray(), Map.class);
    }
}
