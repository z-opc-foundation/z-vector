package com.zifang.z.vector.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.api.VectorStoreFactory;
import com.zifang.z.vector.grpc.QdrantRestServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * z-vector 独立服务器
 * <p>
 * 阶段一（feature001）改造后挂的路由：
 * <ul>
 *   <li>{@code GET    /health}                              —— 自检 + 版本号</li>
 *   <li>{@code GET    /__instance}                          —— 实例自省：version/port/uptime/dataDir/collections/points/内存</li>
 *   <li>{@code GET    /collections}                         —— 列表（Qdrant 风格）</li>
 *   <li>{@code PUT    /collections/{name}}                  —— 建集合</li>
 *   <li>{@code GET    /collections/{name}}                  —— 集合元信息</li>
 *   <li>{@code DELETE /collections/{name}}                  —— 删集合</li>
 *   <li>{@code PUT    /collections/{name}/points}           —— upsert</li>
 *   <li>{@code GET    /collections/{name}/points/count}     —— 点数</li>
 *   <li>{@code POST   /collections/{name}/points/search}    —— ANN 检索</li>
 *   <li>{@code GET    /collections/{name}/points/{id}}      —— 单点读</li>
 *   <li>{@code DELETE /collections/{name}/points/{id}}      —— 单点删</li>
 * </ul>
 * 阶段一之前是自创扁平路由（/points、/search），两条 REST 形状并存导致客户端跟任一边写
 * 都会在另一边 404。统一后 OpenApiSpec 与实际路由形状一致。前端 admin 页面经
 * vite proxy 打到本端口。
 * <p>
 * CORS：通过 {@code ZVECTOR_CORS_ORIGINS}（逗号分隔 Origin 列表，或 {@code *}）开启；
 * 未设时不挂任何 CORS 头（生产同源 nginx 反代不需要）。预检（OPTIONS + {@code Access-Control-Request-Method}）
 * 由 {@link CorsHandler} 直接 204，不下钻业务。
 */
public class VectorServerApplication {

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

    /** {@code ZVECTOR_PORT} 缺省值。0 表示让内核自己挑端口（测试就用它）。 */
    static final int DEFAULT_PORT = 6333;
    static final String DEFAULT_DATA_DIR = "/data/zvector";

    /** 内嵌 Qdrant REST：start() 不调它的 start()，而是把它的 handler() 挂到我们自己的 HttpServer。 */
    private static volatile QdrantRestServer qdrant;
    private static volatile Instant startedAt;
    private static volatile HttpServer serverRef;

    public static void main(String[] args) throws Exception {
        boot(System.getenv());
    }

    /** 一次装配的产物：起住的 server、内核真正分到的端口、被装配好的 store、数据目录、启动时刻。 */
    static final class Boot {
        final HttpServer server;
        final VectorStore store;
        final int port;
        final String dataDir;
        final Instant startedAt;

        Boot(HttpServer server, VectorStore store, int port, String dataDir, Instant startedAt) {
            this.server = server;
            this.store = store;
            this.port = port;
            this.dataDir = dataDir;
            this.startedAt = startedAt;
        }
    }

    /**
     * 装配：读环境 → 建 store → 落默认索引 → 起 HTTP → 挂 shutdown hook。
     * 配置读错一律<b>启动即失败</b>，不做静默兜底。
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
            throw new IllegalArgumentException("ZVECTOR_PORT is out of range (0-65535): " + port);
        }
        String dataDir = valueOr(env.get("ZVECTOR_DATA_DIR"), DEFAULT_DATA_DIR);

        String indexRaw = valueOr(env.get("ZVECTOR_DEFAULT_INDEX"), "").trim();
        IndexType defaultIndex = indexRaw.isEmpty() ? null : parseIndexType(indexRaw, "ZVECTOR_DEFAULT_INDEX");
        Map<String, Object> defaultParams = parseIndexParams(
                valueOr(env.get("ZVECTOR_INDEX_PARAMS"), "").trim());

        List<String> corsOrigins = parseCorsOrigins(env.get("ZVECTOR_CORS_ORIGINS"));

        System.out.println("Starting z-vector server...");
        System.out.println("Port: " + port);
        System.out.println("Data directory: " + dataDir);
        System.out.println("Default index for 3-arg createCollection: "
                + (defaultIndex == null ? "unset (FLAT)" : defaultIndex + " " + defaultParams));
        System.out.println("CORS allowed origins: "
                + (corsOrigins.isEmpty() ? "(disabled)" : String.valueOf(corsOrigins)));

        VectorStore store = VectorStoreFactory.persistent(dataDir);
        store.setDefaultIndex(defaultIndex, defaultParams);

        Instant startedAtInstant = Instant.now();
        HttpServer server = start(port, store, dataDir, startedAtInstant, corsOrigins);
        int bound = server.getAddress().getPort();

        // 没有 shutdown hook 时，SIGTERM 直接绕过 close()：WAL 尾部的记录不落 snapshot，
        // 容器停止即丢已确认写入。
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdown(server, store), "z-vector-shutdown"));

        System.out.println("z-vector server started on port " + bound);
        return new Boot(server, store, bound, dataDir, startedAtInstant);
    }

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

    /**
     * 解析 {@code ZVECTOR_CORS_ORIGINS}：
     * <ul>
     *   <li>未设 / 空串 → 不启用 CORS（生产同源 nginx 反代不需要）</li>
     *   <li>{@code *} → 通配，所有 Origin 都放行</li>
     *   <li>{@code https://a.com,https://b.com} → 指定 Origin 白名单（精确匹配，含协议 + host + port）</li>
     * </ul>
     */
    static List<String> parseCorsOrigins(String raw) {
        if (raw == null) return Collections.emptyList();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String o : trimmed.split(",")) {
            String t = o.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 绑定 store 并在 port 上起服务。测试用 port=0 拿随机端口。 */
    static HttpServer start(int port, VectorStore store, String dataDir, Instant startedAt,
                            List<String> corsOrigins) throws IOException {
        QdrantRestServer q = new QdrantRestServer(store, 0);
        // 不调 q.start()：我们要让它的路由挂到本 HttpServer 上，与 /health、/__instance 共用端口。
        qdrant = q;
        VectorServerApplication.startedAt = startedAt;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        serverRef = server;

        // 三个上下文按最长前缀挂载：HttpServer 选最长前缀匹配，
        // 所以 /health、/__instance 不会被根上下文吃掉；其余路径走 Qdrant REST 路由。
        server.createContext("/health", wrap(new HealthHandler(q), corsOrigins));
        server.createContext("/__instance", wrap(new InstanceHandler(q, dataDir, startedAt), corsOrigins));
        server.createContext("/", wrap(q.handler(), corsOrigins));

        executor = Executors.newFixedThreadPool(10);
        server.setExecutor(executor);
        server.start();
        return server;
    }

    private static HttpHandler wrap(HttpHandler inner, List<String> corsOrigins) {
        return corsOrigins.isEmpty() ? inner : new CorsHandler(inner, corsOrigins);
    }

    private static volatile ExecutorService executor;

    private static void shutdownExecutor() {
        ExecutorService e = executor;
        if (e != null) e.shutdownNow();
    }

    private static void closeQuietly(VectorStore store) {
        if (store == null) return;
        try {
            store.close();
        } catch (Exception e) {
            System.err.println("Failed to close vector store: " + e.getMessage());
        }
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Handlers ====================

    static class HealthHandler implements HttpHandler {
        private final QdrantRestServer qdrantRef;
        HealthHandler(QdrantRestServer qdrantRef) { this.qdrantRef = qdrantRef; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Map<String, Object> health = new LinkedHashMap<>();
                health.put("status", "ok");
                health.put("version", VERSION);
                health.put("collections", qdrantRef.getStore().listCollections().size());
                sendJson(exchange, 200, health);
            } catch (Exception e) {
                sendJson(exchange, 500, errorBody("health failed: " + e.getMessage()));
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * 实例自省：version / port / uptime / dataDir / jvm / java / collections / points_total / 内存。
     * <p>
     * 不查 collection 配置细节（点数已够）。前端「实例状态」页靠这个分清
     * "server 起来了 / 在哪" 与 "JVM 挂了就别问我集合列表"。所有字段就地为响应拼装，
     * 不抛错；唯一一处 IO（hostname 解析）失败时退化为 {@code pid@unknown}，不让自省接口 500。
     */
    static class InstanceHandler implements HttpHandler {
        private final QdrantRestServer qdrantRef;
        private final String dataDir;
        private final Instant startedAtRef;

        InstanceHandler(QdrantRestServer qdrantRef, String dataDir, Instant startedAt) {
            this.qdrantRef = qdrantRef;
            this.dataDir = dataDir;
            this.startedAtRef = startedAt;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, errorBody("Method not allowed: " + exchange.getRequestMethod()));
                    return;
                }
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("status", "ok");
                resp.put("version", VERSION);

                HttpServer s = serverRef;
                int port = (s == null) ? -1 : s.getAddress().getPort();
                resp.put("port", port);
                resp.put("uptime_ms", Duration.between(startedAtRef, Instant.now()).toMillis());
                resp.put("data_dir", dataDir);

                long pid = -1;
                String host = "unknown";
                try {
                    // ManagementFactory.getRuntimeMXBean().getName() 在 Java 8 就可用，
                    // 格式 "pid@hostname"。ProcessHandle 是 Java 9+ API，本仓 target 1.8 不能用。
                    String jvmName = ManagementFactory.getRuntimeMXBean().getName();
                    int at = jvmName.indexOf('@');
                    if (at > 0) {
                        try {
                            pid = Long.parseLong(jvmName.substring(0, at));
                        } catch (NumberFormatException ignored) {
                            // 部分环境 jvmName 形如 "standalone@host" ⇒ pid 退 -1
                        }
                        host = jvmName.substring(at + 1);
                    } else {
                        host = jvmName;
                    }
                } catch (RuntimeException ignored) {
                    // hostname 解析在容器/沙箱里偶尔失败；保留 unknown 比让整页 500 强。
                }
                resp.put("jvm", pid + "@" + host);
                resp.put("java", System.getProperty("java.version", "unknown"));

                VectorStore store = qdrantRef.getStore();
                List<String> cols = store.listCollections();
                resp.put("collections", cols.size());
                long pointsTotal = 0L;
                for (String c : cols) {
                    try {
                        pointsTotal += store.getPointCount(c);
                    } catch (RuntimeException ignored) {
                        // 单集合计数失败不影响总览
                    }
                }
                resp.put("points_total", pointsTotal);

                Runtime rt = Runtime.getRuntime();
                Map<String, Object> mem = new LinkedHashMap<>();
                mem.put("heap_used", rt.totalMemory() - rt.freeMemory());
                mem.put("heap_max", rt.maxMemory());
                mem.put("free", rt.freeMemory());
                resp.put("memory", mem);

                sendJson(exchange, 200, resp);
            } catch (Exception e) {
                sendJson(exchange, 500, errorBody("instance failed: " + e.getMessage()));
            } finally {
                exchange.close();
            }
        }
    }

    // ==================== CORS ====================

    /**
     * CORS 包装器：
     * <ul>
     *   <li>非预检：若 Origin 在白名单则注入 {@code Access-Control-Allow-Origin}（精确匹配时附加 {@code Vary: Origin}），
     *       再委派给内层 handler。内层 handler 在调 {@code sendResponseHeaders} 之前把 headers 写入，
     *       所以这里提前写入的 CORS 头不会被冲掉。</li>
     *   <li>预检（OPTIONS + {@code Access-Control-Request-Method}）：直接 204 短路，不下钻业务。
     *       Origin 不在白名单则 403。短路后不会触发内层 handler，避免业务侧的日志噪音。</li>
     * </ul>
     */
    static class CorsHandler implements HttpHandler {
        private static final List<String> ALLOWED_METHODS =
                Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS");
        private static final List<String> ALLOWED_HEADERS =
                Arrays.asList("Content-Type", "Authorization", "X-Requested-With");

        private final HttpHandler inner;
        private final List<String> allowedOrigins;

        CorsHandler(HttpHandler inner, List<String> allowedOrigins) {
            this.inner = inner;
            this.allowedOrigins = allowedOrigins;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            boolean preflight = "OPTIONS".equalsIgnoreCase(ex.getRequestMethod())
                    && ex.getRequestHeaders().containsKey("Access-Control-Request-Method");

            if (preflight) {
                handlePreflight(ex, origin);
                return;
            }
            String allow = (origin == null) ? null : matchOrigin(origin);
            if (allow != null) {
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", allow);
                if (!"*".equals(allow)) {
                    ex.getResponseHeaders().add("Vary", "Origin");
                }
            }
            inner.handle(ex);
        }

        private void handlePreflight(HttpExchange ex, String origin) throws IOException {
            String allow = (origin == null) ? null : matchOrigin(origin);
            if (allow == null) {
                byte[] body = "{\"status\":\"error\",\"code\":403,\"message\":\"CORS origin not allowed\"}"
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(403, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                return;
            }
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", allow);
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", String.join(", ", ALLOWED_METHODS));
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", String.join(", ", ALLOWED_HEADERS));
            ex.getResponseHeaders().add("Access-Control-Max-Age", "3600");
            if (!"*".equals(allow)) ex.getResponseHeaders().add("Vary", "Origin");
            ex.sendResponseHeaders(204, -1);
            ex.close();
        }

        private String matchOrigin(String origin) {
            if (allowedOrigins.contains("*")) return "*";
            return allowedOrigins.contains(origin) ? origin : null;
        }
    }

    // ==================== 响应 / 请求工具 ====================

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return body;
    }

    /** 仅 {@link InstanceHandler} 用到：从请求体读 JSON。测试覆盖即可，目前 instance 无 body。 */
    @SuppressWarnings("unused")
    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        try (InputStream is = exchange.getRequestBody()) {
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        byte[] bytes = bos.toByteArray();
        if (bytes.length == 0) return new LinkedHashMap<>();
        return objectMapper.readValue(bytes, Map.class);
    }

    /** 测试 hook：让 VectorServerApplicationTest 与 InstanceEndpointTest 都能取到当前 store。 */
    static VectorStore currentStore() {
        QdrantRestServer q = qdrant;
        return q == null ? null : q.getStore();
    }
}
