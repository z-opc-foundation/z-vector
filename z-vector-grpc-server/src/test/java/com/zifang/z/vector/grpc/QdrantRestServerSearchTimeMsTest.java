package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link QdrantRestServer#route} 里 search 接口的 {@code time_ms} 真实测量。
 * <p>
 * 之前这一字段是硬编码 0；前端检索页「耗时」列因此永远是 0。把这条抬成真实测量后，
 * 退化到「写死 0」会**静默**回到老样子 —— 没有人会因为「time_ms 看起来对」去打开它。
 * 所以这里必须有断言：time_ms 必须存在、必须是非负数、必须与墙上时钟量级相当。
 */
class QdrantRestServerSearchTimeMsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private QdrantRestServer qdrant;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.createCollection("docs", 3, DistanceMetric.COSINE, IndexType.FLAT, null);
        store.upsertBatch("docs", java.util.Arrays.asList(
                new com.zifang.z.vector.api.VectorPoint("a", new float[]{1, 0, 0}, null),
                new com.zifang.z.vector.api.VectorPoint("b", new float[]{0, 1, 0}, null)));
        qdrant = new QdrantRestServer(store, 0);
        qdrant.start();
        server = null;
        base = "http://127.0.0.1:" + qdrant.getPort();
    }

    @AfterEach
    void tearDown() {
        if (qdrant != null) qdrant.stop();
        if (server != null) server.stop(0);
    }

    @Test
    void searchReturnsRealElapsedMsNotZero() throws Exception {
        long wallStart = System.currentTimeMillis();
        String body = "{\"vector\":[0.9,0.1,0],\"limit\":2}";
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/collections/docs/points/search").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        assertEquals(200, code);
        byte[] respBody = readAll(c.getInputStream());
        Map<?, ?> json = JSON.readValue(respBody, Map.class);
        c.disconnect();
        long wallEnd = System.currentTimeMillis();

        Object t = json.get("time_ms");
        assertNotNull(t, "response 缺 time_ms 字段 —— 不能退化到占位 0: " + new String(respBody, StandardCharsets.UTF_8));
        assertTrue(t instanceof Number, "time_ms 必须为数字，实际: " + t.getClass());
        long ms = ((Number) t).longValue();
        assertTrue(ms >= 0, "time_ms 不能为负: " + ms);
        // 量级：wall 总耗时（含建连 + 网络）通常在 5~500ms 之间；time_ms 是 server 内 nanoTime 差，
        // 比 wall 小但不会大几个数量级。给一个宽容上界 5000ms 防计时器抖动假阳性。
        assertTrue(ms <= wallEnd - wallStart + 5000,
                "time_ms=" + ms + " 远大于墙钟耗时 wall=" + (wallEnd - wallStart));
    }

    @Test
    void searchStillReturnsHitsAndStatus() throws Exception {
        String body = "{\"vector\":[0.9,0.1,0],\"limit\":2}";
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/collections/docs/points/search").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        byte[] respBody = readAll(c.getInputStream());
        Map<?, ?> json = JSON.readValue(respBody, Map.class);
        c.disconnect();
        assertEquals("ok", json.get("status"));
        List<?> result = (List<?>) json.get("result");
        assertEquals(2, result.size());
        // 顺带验证 top-1 是 a（cosine 最近邻）
        assertEquals("a", ((Map<?, ?>) result.get(0)).get("id"));
    }

    private static byte[] readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
