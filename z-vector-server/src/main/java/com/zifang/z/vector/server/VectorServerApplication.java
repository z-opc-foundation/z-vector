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

    /**
     * 构建期由资源过滤写进 {@code /build-info.properties}，取的就是构件自己的版本。
     * <p>
     * 此前这里是字面量，而且是**第二个**字面量（测试里再抄一份同值的），于是"VERSION 必须与 pom
     * 一致"这条断言结构上不可能发现漂移：改 pom 不改编码，两边一起漂。历史实证：先硬编码 1.0.1
     * 而构件已是 1.0.2，上一轮改成 1.0.2 而 pom 已经是 1.0.3 —— 同一处错了两次，第二次是"修法"错。
     */
    static final String VERSION = readBuildVersion();

    private static String readBuildVersion() {
        Properties p = new Properties();
        try (InputStream in = VectorServerApplication.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            return "unknown";
        }
        String v = p.getProperty("version", "").trim();
        return v.isEmpty() ? "unknown" : v;
    }

    private static VectorStore vectorStore;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** {@code ZVECTOR_PORT} 缺省值。0 表示让内核自己挑端口（测试就用它）。 */
    static final int DEFAULT_PORT = 6333;
    static final String DEFAULT_DATA_DIR = "/data/zvector";

    public static void main(String[] args) throws Exception {
        boot(System.getenv());
    }

    /** 一次装配的产物：起住的 server、内核真正分到的端口、以及被装配好的 store。 */
    static final class Boot {
        final HttpServer server;
        final VectorStore store;
        final int port;

        Boot(HttpServer server, VectorStore store, int port) {
            this.server = server;
            this.store = store;
            this.port = port;
        }
    }

    /**
     * 装配：读环境 → 建 store → 落默认索引 → 起 HTTP → 挂 shutdown hook。{@code main} 走这一条，
     * 测试走的也是这一条（{@code ZVECTOR_PORT=0} 让内核分端口，{@code ZVECTOR_DATA_DIR} 指到临时目录）。
     * <p>
     * 之所以把它从 {@code main} 里抽出来：这几步长在 main 里时，任何测试调 main 都会
     * 占死 6333、往 {@code /data/zvector} 写、再注册一个 shutdown hook —— 于是"env 里配的默认索引
     * 到底有没有落到 store 上"这一问结构上无人能答，而 #18 刚为同样的形状记过一笔账。
     * <p>
     * 配置读错一律<b>启动即失败</b>，不做静默兜底：一台把 {@code ZVECTOR_DEFAULT_INDEX} 拼错的容器，
     * 悄悄跑成 FLAT 比它不起来更糟（实测：改前压根没有这个旋钮，无论怎么配都是 FLAT）。
     */
    static Boot boot(Map<String, String> env) throws IOException {
        String portRaw = valueOr(env.get("ZVECTOR_PORT"), String.valueOf(DEFAULT_PORT));
        int port;
        try {
            port = Integer.parseInt(portRaw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ZVECTOR_PORT is not an integer: \"" + portRaw + "\"");
        }
        if (port < 0 || port > 65535) {
            // 0 是合法值（内核自己挑一个空闲端口），负数与越界不是：绑到 -1 上
            // 拿到的是 IllegalArgumentException 从 bind 里冒出来，报的是"port out of range:-1"，
            // 不说是哪个配置坏了 —— 而 ops 手里只有这个 env 变量。
            throw new IllegalArgumentException("ZVECTOR_PORT is out of range (0-65535): " + port);
        }
        String dataDir = valueOr(env.get("ZVECTOR_DATA_DIR"), DEFAULT_DATA_DIR);

        String indexRaw = valueOr(env.get("ZVECTOR_DEFAULT_INDEX"), "").trim();
        IndexType defaultIndex = indexRaw.isEmpty() ? null : parseIndexType(indexRaw, "ZVECTOR_DEFAULT_INDEX");
        Map<String, Object> defaultParams = parseIndexParams(
                valueOr(env.get("ZVECTOR_INDEX_PARAMS"), "").trim());

        System.out.println("Starting z-vector server...");
        System.out.println("Port: " + port);
        System.out.println("Data directory: " + dataDir);
        System.out.println("Default index for 3-arg createCollection: "
                + (defaultIndex == null ? "unset (FLAT)" : defaultIndex + " " + defaultParams));

        VectorStore store = VectorStoreFactory.persistent(dataDir);
        store.setDefaultIndex(defaultIndex, defaultParams);

        HttpServer server = start(port, store);
        int bound = server.getAddress().getPort();

        // 没有 shutdown hook 时，SIGTERM 直接绕过 close()：WAL 尾部的记录不落 snapshot，
        // 容器停止即丢已确认写入。
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdown(server, store), "z-vector-shutdown"));

        System.out.println("z-vector server started on port " + bound);
        return new Boot(server, store, bound);
    }

    /**
     * SIGTERM 那条路 —— shutdown hook 的函数体。
     * <p>
     * 抽成方法只为了一件事：长在 lambda 里时"hook 到底关没关 store"没有测试问得到，
     * 而它当时（实测）一次都没关过：守卫写的是 {@code store instanceof AutoCloseable}，
     * 而 {@code VectorStore} 改前不继承 {@code AutoCloseable} ⇒ 对这个进程唯一可能出现的
     * 两个 store 恒为 false，close() 从来没被调用过。
     */
    static void shutdown(HttpServer server, VectorStore store) {
        System.out.println("Shutting down z-vector server...");
        server.stop(2);
        shutdownExecutor();
        closeQuietly(store);
    }

    private static String valueOr(String v, String fallback) {
        return v == null ? fallback : v;
    }

    static IndexType parseIndexType(String raw, String where) {
        try {
            return IndexType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 支持哪些取值不抄字面量：抄来的那一半会在 IndexType 加成员时变成第二条谎。
            throw new IllegalArgumentException(where + " is not a known index type: \"" + raw
                    + "\" (expected one of " + indexTypeNames() + ")");
        }
    }

    private static String indexTypeNames() {
        IndexType[] all = IndexType.values();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(all[i].name());
        }
        return sb.toString();
    }

    /** {@code ZVECTOR_INDEX_PARAMS}：一个 JSON 对象，形状与 REST body 里的 index_params 相同。 */
    static Map<String, Object> parseIndexParams(String raw) {
        if (raw.isEmpty()) return null;
        Object parsed;
        try {
            parsed = objectMapper.readValue(raw, Object.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("ZVECTOR_INDEX_PARAMS is not valid JSON: \""
                    + raw + "\" (" + e.getMessage() + ")");
        }
        return asParamsMap(parsed, "ZVECTOR_INDEX_PARAMS");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asParamsMap(Object v, String where) {
        if (v == null) return null;
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException(where + " must be a JSON object, got: "
                    + (v instanceof List ? "array" : v.getClass().getSimpleName()));
        }
        return new LinkedHashMap<>((Map<String, Object>) v);
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
        if (store == null) return;
        try {
            // 不再判 instanceof AutoCloseable：close() 本来就是 VectorStore 接口上的方法，
            // 那道 instanceof 是多余的门，而且当时恒为 false（见 shutdown() 的说明）。
            store.close();
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
                    metric = DistanceMetric.valueOf(((String) request.get("metric")).toUpperCase(Locale.ROOT));
                }
                // index_type 此前是**收下来就丢**：OpenApiSpec 的 CreateCollectionRequest 广告了
                // index_type（enum FLAT/HNSW/IVF），gRPC 侧那台 QdrantRestServer 也一直在读它，
                // 只有这台独立 server 走 3 参那一支 ⇒ 客户端换到 Docker 部署就静默变成 FLAT，
                // 而且回的是 200。缺省那一支保留 3 参：这样 ZVECTOR_DEFAULT_INDEX 才有生效的地方。
                Object rawIndex = request.get("index_type");
                Map<String, Object> indexParams = asParamsMap(request.get("index_params"),
                        "index_params");
                if (rawIndex == null) {
                    vectorStore.createCollection(name, dimensions, metric);
                } else {
                    if (!(rawIndex instanceof String)) {
                        throw new IllegalArgumentException("index_type must be a string, got: "
                                + rawIndex.getClass().getSimpleName());
                    }
                    vectorStore.createCollection(name, dimensions, metric,
                            parseIndexType((String) rawIndex, "index_type"), indexParams);
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("collection", name);
                result.put("dimensions", dimensions);
                result.put("metric", metric.name());
                // 回读 store 里真正建出来的那个，而不是回吐客户端传进来的 —— 报"建好了 HNSW"
                // 而建出来是 FLAT，正是这一系列缺陷的形状。
                VectorCollection created = vectorStore.getCollection(name);
                result.put("index_type", created.getIndexType().name());
                result.put("index_params", created.getConfig());
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
