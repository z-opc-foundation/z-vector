package com.zifang.z.vector.server;

import com.zifang.z.vector.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * z-vector 独立服务器
 * 提供 REST API 用于向量数据库操作
 */
public class VectorServerApplication {

    /** 与 pom 的 version 对齐；此前硬编码 1.0.1 而构件已是 1.0.2。 */
    static final String VERSION = "1.0.2";

    private static VectorStore vectorStore;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("ZVECTOR_PORT", "6333"));
        String dataDir = System.getenv().getOrDefault("ZVECTOR_DATA_DIR", "/data/zvector");

        System.out.println("Starting z-vector server...");
        System.out.println("Port: " + port);
        System.out.println("Data directory: " + dataDir);

        // 初始化向量存储
        HttpServer server = start(port, VectorStoreFactory.persistent(dataDir));

        // 没有 shutdown hook 时，SIGTERM 直接绕过 close()：WAL 尾部的记录不落 snapshot，
        // 容器停止即丢已确认写入。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down z-vector server...");
            server.stop(2);
            shutdownExecutor();
            closeQuietly(vectorStore);
        }, "z-vector-shutdown"));

        System.out.println("z-vector server started on port " + port);
    }

    /** 绑定 store 并在 port 上起服务。测试用 port=0 拿随机端口。 */
    static HttpServer start(int port, VectorStore store) throws IOException {
        vectorStore = store;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/collections", new CollectionsHandler());
        server.createContext("/points", new PointsHandler());
        server.createContext("/search", new SearchHandler());
        executor = Executors.newFixedThreadPool(10);
        server.setExecutor(executor);
        server.start();
        return server;
    }

    private static volatile ExecutorService executor;

    private static void shutdownExecutor() {
        ExecutorService e = executor;
        if (e != null) e.shutdownNow();
    }

    private static void closeQuietly(VectorStore store) {
        if (!(store instanceof AutoCloseable)) return;
        try {
            ((AutoCloseable) store).close();
        } catch (Exception e) {
            System.err.println("Failed to close vector store: " + e.getMessage());
        }
    }

    // ==================== 响应 / 请求工具 ====================

    /**
     * 统一出口：JSON 一律显式 UTF-8 编码，且只编码一次。
     * 此前 {@code sendResponseHeaders(200, s.getBytes().length)} 与随后
     * {@code os.write(s.getBytes())} 各走一次平台默认字符集，中文 payload 在
     * 非 UTF-8 默认字符集下会写出乱码，且长度与实际字节数不一致会截断响应。
     */
    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        sendJson(exchange, status, body);
    }

    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        // 本模块 target 1.8：InputStream.readAllBytes() 是 Java 9 API，
        // 编译能过但在 Dockerfile 的 8-jre 上运行即 NoSuchMethodError。
        try (InputStream is = exchange.getRequestBody()) {
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        byte[] bytes = bos.toByteArray();
        if (bytes.length == 0) return new LinkedHashMap<>();
        return objectMapper.readValue(bytes, Map.class);
    }

    /** 从 Jackson 解出的 JSON 数字安全取 int（此前 `(int) request.get(...)` 遇到 Double 必 ClassCastException）。 */
    private static int asInt(Object v, int defaultValue) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return defaultValue;
        return Integer.parseInt(v.toString().trim());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object v) {
        return v instanceof List ? (List<Map<String, Object>>) v : Collections.emptyList();
    }

    private static float[] toFloatArray(Object v) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("Expected an array of numbers, got: "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        List<?> nums = (List<?>) v;
        float[] out = new float[nums.size()];
        for (int i = 0; i < out.length; i++) {
            Object o = nums.get(i);
            if (!(o instanceof Number)) {
                throw new IllegalArgumentException("vector[" + i + "] is not a number: " + o);
            }
            out[i] = ((Number) o).floatValue();
        }
        return out;
    }

    /** 一次请求的处理体。允许抛出校验异常，由 {@link #dispatch} 统一映射成 4xx。 */
    private interface RequestBody {
        void run(HttpExchange exchange) throws Exception;
    }

    /**
     * 统一异常出口。
     * <p>
     * handler 里抛出的 {@code IllegalArgumentException} / {@code VectorException}
     * 之前会一路冲出 {@code HttpHandler.handle}，被 HttpServer 记一条 stacktrace 后
     * 直接断开连接 —— 客户端拿到的是空响应或 500，而不是说明哪儿写错了的 400。
     */
    private static void dispatch(HttpExchange exchange, RequestBody body) throws IOException {
        try {
            body.run(exchange);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (VectorException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            sendError(exchange, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    // ==================== Handlers ====================

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            dispatch(exchange, ex -> {
                Map<String, Object> health = new LinkedHashMap<>();
                health.put("status", "ok");
                health.put("version", VERSION);
                health.put("collections", vectorStore.listCollections().size());
                sendJson(ex, 200, health);
            });
        }
    }

    static class CollectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            dispatch(exchange, ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method) && "/collections".equals(path)) {
                sendJson(ex, 200, vectorStore.listCollections());
            } else if ("PUT".equals(method) || "POST".equals(method)) {
                Map<String, Object> request = readJson(ex);
                String name = (String) request.get("name");
                if (name == null || name.isEmpty()) {
                    sendError(ex, 400, "Missing required field: name");
                    return;
                }
                if (!request.containsKey("dimensions")) {
                    sendError(ex, 400, "Missing required field: dimensions");
                    return;
                }
                int dimensions = asInt(request.get("dimensions"), -1);
                if (dimensions <= 0) {
                    sendError(ex, 400, "dimensions must be a positive integer");
                    return;
                }
                DistanceMetric metric = DistanceMetric.COSINE;
                if (request.get("metric") instanceof String) {
                    metric = DistanceMetric.valueOf(((String) request.get("metric")).toUpperCase());
                }
                vectorStore.createCollection(name, dimensions, metric);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("collection", name);
                result.put("dimensions", dimensions);
                result.put("metric", metric.name());
                sendJson(ex, 200, result);
            } else {
                sendError(ex, 405, "Method not allowed: " + method);
            }
            });
        }
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            dispatch(exchange, ex -> {
            String method = ex.getRequestMethod();
            if (!"POST".equals(method) && !"PUT".equals(method)) {
                sendError(ex, 405, "Method not allowed");
                return;
            }
            Map<String, Object> request = readJson(ex);
            String collectionName = (String) request.get("collection");
            if (collectionName == null || !vectorStore.hasCollection(collectionName)) {
                sendError(ex, 404, "Collection not found");
                return;
            }

            List<VectorPoint> points = new ArrayList<>();
            for (Map<String, Object> pointData : asList(request.get("points"))) {
                String id = (String) pointData.get("id");
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>)
                        pointData.getOrDefault("payload", new LinkedHashMap<String, Object>());
                points.add(new VectorPoint(id, toFloatArray(pointData.get("vector")), payload));
            }
            vectorStore.upsertBatch(collectionName, points);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("upserted", points.size());
            sendJson(ex, 200, result);
            });
        }
    }

    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            dispatch(exchange, ex -> {
            if (!"POST".equals(ex.getRequestMethod())) {
                sendError(ex, 405, "Method not allowed");
                return;
            }
            Map<String, Object> request = readJson(ex);
            String collectionName = (String) request.get("collection");
            if (collectionName == null || !vectorStore.hasCollection(collectionName)) {
                sendError(ex, 404, "Collection not found");
                return;
            }
            int limit = asInt(request.get("limit"), 10);

            float[] queryArray = toFloatArray(request.get("vector"));
            List<SearchResult> results = vectorStore.search(collectionName, queryArray, limit, null);

            List<Map<String, Object>> hits = new ArrayList<>(results.size());
            for (SearchResult r : results) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("id", r.getVectorId());
                hit.put("score", r.getScore());
                hit.put("payload", r.getPayload());
                hits.add(hit);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("results", hits);
            sendJson(ex, 200, result);
            });
        }
    }
}
