package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorException;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Qdrant REST 兼容 API 服务器 — 轻量级 HTTP 层。
 * <p>
 * 对标 Qdrant REST API 核心端点:
 * <ul>
 *   <li>{@code GET    /collections}                  → 列出集合</li>
 *   <li>{@code PUT    /collections/{name}}           → 创建集合</li>
 *   <li>{@code GET    /collections/{name}}           → 获取集合信息</li>
 *   <li>{@code DELETE /collections/{name}}           → 删除集合</li>
 *   <li>{@code PUT    /collections/{name}/points}    → Upsert 向量</li>
 *   <li>{@code POST   /collections/{name}/points/search}  → ANN 搜索</li>
 *   <li>{@code GET    /collections/{name}/points/count}   → 向量数量</li>
 * </ul>
 *
 * <h2>设计</h2>
 * <p>
 * 使用 JDK 自带 {@code com.sun.net.httpserver.HttpServer} 实现，避免引入 Spring MVC 等重量级框架。
 * JSON 解析使用 Jackson（项目已有依赖）。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * VectorStore store = new InMemoryVectorStore();
 * QdrantRestServer server = new QdrantRestServer(store, 6334);
 * server.start();
 * // ... HTTP 请求 ...
 * server.stop();
 * }</pre>
 */
public class QdrantRestServer {

    private static final Logger log = LoggerFactory.getLogger(QdrantRestServer.class);

    private final VectorStore store;
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private int port;

    public QdrantRestServer(int port) {
        this(new com.zifang.z.vector.core.InMemoryVectorStore(), port);
    }

    public QdrantRestServer(VectorStore store, int port) {
        this.store = store;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", this::route);
        server.start();
        log.info("Qdrant REST API started on port {}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("Qdrant REST API stopped");
        }
    }

    public VectorStore getStore() { return store; }

    public int getPort() { return port; }

    // ==================== 路由分发 ====================

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            // /collections
            if ("/collections".equals(path)) {
                if ("GET".equals(method)) {
                    handleListCollections(exchange);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}
            if (path.matches("/collections/[A-Za-z0-9_\\-]+")) {
                String name = path.substring("/collections/".length());
                if ("PUT".equals(method)) {
                    handleCreateCollection(exchange, name);
                } else if ("GET".equals(method)) {
                    handleGetCollection(exchange, name);
                } else if ("DELETE".equals(method)) {
                    handleDeleteCollection(exchange, name);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}/points/search
            if (path.matches("/collections/.+/points/search")) {
                String name = extractCollectionName(path, "/points/search");
                if ("POST".equals(method)) {
                    handleSearch(exchange, name);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}/points/count
            if (path.matches("/collections/.+/points/count")) {
                String name = extractCollectionName(path, "/points/count");
                if ("GET".equals(method)) {
                    handlePointCount(exchange, name);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}/points (PUT = upsert batch)
            if (path.matches("/collections/.+/points")) {
                String name = extractCollectionName(path, "/points");
                if ("PUT".equals(method)) {
                    handleUpsertPoints(exchange, name);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}/points/{id}
            if (path.matches("/collections/.+/points/.+")) {
                String name = extractCollectionName(path, "/points/");
                String id = path.substring(path.lastIndexOf('/') + 1);
                if ("GET".equals(method)) {
                    handleGetPoint(exchange, name, id);
                } else if ("DELETE".equals(method)) {
                    handleDeletePoint(exchange, name, id);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            sendError(exchange, 404, "Not found: " + method + " " + path);
        } catch (VectorException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            log.error("Request failed: {} {}", method, path, e);
            sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ==================== Collection 端点 ====================

    private void handleCreateCollection(HttpExchange exchange, String name) throws IOException {
        Map<String, Object> body = parseJson(exchange);
        int dimension = ((Number) body.getOrDefault("dimension", 128)).intValue();
        String metricStr = (String) body.getOrDefault("metric", "COSINE");
        DistanceMetric metric = DistanceMetric.valueOf(metricStr.toUpperCase());
        String indexTypeStr = (String) body.getOrDefault("index_type", "FLAT");
        IndexType indexType = IndexType.valueOf(indexTypeStr.toUpperCase());
        @SuppressWarnings("unchecked")
        Map<String, Object> indexParams = (Map<String, Object>) body.get("index_params");
        store.createCollection(name, dimension, metric, indexType, indexParams);
        sendJson(exchange, 200, Map.of("status", "ok", "name", name));
    }

    private void handleGetCollection(HttpExchange exchange, String name) throws IOException {
        VectorCollection c = store.getCollection(name);
        if (c == null) {
            sendError(exchange, 404, "Collection not found: " + name);
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("name", c.getName());
        resp.put("dimension", c.getDimension());
        resp.put("metric", c.getMetric().name());
        resp.put("index_type", c.getIndexType().name());
        resp.put("points_count", store.getPointCount(name));
        resp.put("indexed", store.isIndexed(name));
        sendJson(exchange, 200, resp);
    }

    private void handleDeleteCollection(HttpExchange exchange, String name) throws IOException {
        boolean removed = store.deleteCollection(name);
        if (removed) {
            sendJson(exchange, 200, Map.of("status", "ok"));
        } else {
            sendError(exchange, 404, "Collection not found: " + name);
        }
    }

    private void handleListCollections(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("collections", store.listCollections());
        sendJson(exchange, 200, resp);
    }

    // ==================== 向量端点 ====================

    private void handleUpsertPoints(HttpExchange exchange, String name) throws IOException {
        Map<String, Object> body = parseJson(exchange);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pointsList = (List<Map<String, Object>>) body.get("points");
        if (pointsList == null) {
            sendError(exchange, 400, "Missing 'points' field");
            return;
        }
        List<VectorPoint> points = new ArrayList<>();
        for (Map<String, Object> p : pointsList) {
            String id = String.valueOf(p.get("id"));
            @SuppressWarnings("unchecked")
            List<Number> vec = (List<Number>) p.get("vector");
            float[] vector = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) vector[i] = vec.get(i).floatValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) p.getOrDefault("payload", new LinkedHashMap<>());
            points.add(new VectorPoint(id, vector, payload));
        }
        store.upsertBatch(name, points);
        sendJson(exchange, 200, Map.of("status", "ok", "count", points.size()));
    }

    private void handleGetPoint(HttpExchange exchange, String name, String id) throws IOException {
        VectorPoint p = store.getPoint(name, id);
        if (p == null) {
            sendError(exchange, 404, "Point not found: " + id);
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", p.getId());
        resp.put("vector", toFloatList(p.getVector()));
        resp.put("payload", p.getPayload());
        sendJson(exchange, 200, resp);
    }

    private void handleDeletePoint(HttpExchange exchange, String name, String id) throws IOException {
        boolean removed = store.deletePoint(name, id);
        if (removed) {
            sendJson(exchange, 200, Map.of("status", "ok"));
        } else {
            sendError(exchange, 404, "Point not found: " + id);
        }
    }

    private void handlePointCount(HttpExchange exchange, String name) throws IOException {
        sendJson(exchange, 200, Map.of("count", store.getPointCount(name)));
    }

    private void handleSearch(HttpExchange exchange, String name) throws IOException {
        Map<String, Object> body = parseJson(exchange);
        @SuppressWarnings("unchecked")
        List<Number> vec = (List<Number>) body.get("vector");
        if (vec == null) {
            sendError(exchange, 400, "Missing 'vector' field");
            return;
        }
        float[] queryVector = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) queryVector[i] = vec.get(i).floatValue();
        int topK = ((Number) body.getOrDefault("limit", 10)).intValue();
        Boolean buildIndex = (Boolean) body.get("build_index");
        if (Boolean.TRUE.equals(buildIndex) && !store.isIndexed(name)) {
            store.buildIndex(name);
        }
        // 过滤：当前 REST API 仅支持简单 eq {"field": "lang", "eq": "zh"}
        Filter filter = parseFilter(body.get("filter"));

        List<SearchResult> results = store.search(name, queryVector, topK, filter);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (SearchResult r : results) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", r.getVectorId());
            hit.put("score", r.getScore());
            if (!r.getPayload().isEmpty()) {
                hit.put("payload", r.getPayload());
            }
            hits.add(hit);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", hits);
        resp.put("status", "ok");
        resp.put("time_ms", 0); // 占位：未来加耗时统计
        sendJson(exchange, 200, resp);
    }

    /**
     * 简单 Filter 解析 — 支持 {"field": value} 单层等值匹配 + {"field": {"op": value}}
     */
    @SuppressWarnings("unchecked")
    private Filter parseFilter(Object filterObj) {
        if (filterObj == null) return null;
        if (!(filterObj instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) filterObj;
        // 单字段等值: {"lang": "zh"}
        if (m.size() == 1) {
            Map.Entry<String, Object> e = m.entrySet().iterator().next();
            Object v = e.getValue();
            if (!(v instanceof Map)) {
                return Filter.eq(e.getKey(), v);
            }
            // {"lang": {"eq": "zh"}}
            Map<String, Object> opSpec = (Map<String, Object>) v;
            Map.Entry<String, Object> opEntry = opSpec.entrySet().iterator().next();
            String op = opEntry.getKey();
            Object val = opEntry.getValue();
            switch (op) {
                case "eq":    return Filter.eq(e.getKey(), val);
                case "ne":    return Filter.ne(e.getKey(), val);
                case "gt":    return Filter.gt(e.getKey(), (Number) val);
                case "gte":   return Filter.gte(e.getKey(), (Number) val);
                case "lt":    return Filter.lt(e.getKey(), (Number) val);
                case "lte":   return Filter.lte(e.getKey(), (Number) val);
                case "in":    return Filter.inValues(e.getKey(), (List<Object>) val);
                case "nin":   return Filter.notIn(e.getKey(), (List<Object>) val);
                case "exists":return Filter.exists(e.getKey());
                default:      return null;
            }
        }
        // AND 复合: {"and": [{"lang":"zh"}, {"score":0.5}]}
        if (m.containsKey("and")) {
            List<Object> children = (List<Object>) m.get("and");
            Filter[] arr = new Filter[children.size()];
            for (int i = 0; i < children.size(); i++) arr[i] = parseFilter(children.get(i));
            return Filter.and(arr);
        }
        if (m.containsKey("or")) {
            List<Object> children = (List<Object>) m.get("or");
            Filter[] arr = new Filter[children.size()];
            for (int i = 0; i < children.size(); i++) arr[i] = parseFilter(children.get(i));
            return Filter.or(arr);
        }
        return null;
    }

    // ==================== 工具方法 ====================

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private String extractCollectionName(String path, String suffix) {
        String base = path.substring("/collections/".length());
        return base.substring(0, base.length() - suffix.length());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length == 0) return new LinkedHashMap<>();
            return json.readValue(bytes, Map.class);
        }
    }

    private void sendJson(HttpExchange exchange, int code, Object obj) throws IOException {
        byte[] bytes = json.writeValueAsBytes(obj);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", "error");
        err.put("code", code);
        err.put("message", message);
        sendJson(exchange, code, err);
    }
}