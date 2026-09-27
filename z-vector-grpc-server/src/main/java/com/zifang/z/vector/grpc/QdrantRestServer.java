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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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

    /**
     * 路由 pattern 预编译。{@code String.matches} 每次调用都会 {@code Pattern.compile}，
     * 一个请求要走 5 次，等于每请求白建 5 个自动机。
     */
    private static final Pattern P_COLLECTION = Pattern.compile("/collections/[A-Za-z0-9_\\-]+");
    private static final Pattern P_SEARCH = Pattern.compile("/collections/.+/points/search");
    private static final Pattern P_COUNT = Pattern.compile("/collections/.+/points/count");
    private static final Pattern P_POINTS_BATCH = Pattern.compile("/collections/.+/points");
    private static final Pattern P_POINT_BY_ID = Pattern.compile("/collections/.+/points/.+");

    private static final String PREFIX_COLLECTIONS = "/collections/";
    private static final String MARKER_POINTS = "/points/";

    private final VectorStore store;
    private final ObjectMapper json = new ObjectMapper();
    private volatile HttpServer server;
    /** 请求的端口：0 交给内核挑。start() 之后 {@link #getPort()} 报的是真实端口，不是这个。 */
    private final int port;
    /** 内核实际绑上的端口；-1 = 还没起来。 */
    private volatile int boundPort = -1;
    /** start() 交出去的工作线程池：stop() 必须关掉它，否则每起停一次就漏 8 条线程。 */
    private volatile ExecutorService executor;

    public QdrantRestServer(int port) {
        this(new com.zifang.z.vector.core.InMemoryVectorStore(), port);
    }

    public QdrantRestServer(VectorStore store, int port) {
        this.store = store;
        this.port = port;
    }

    public void start() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        // 线程要给名字：stop() 之后"这批线程确实没了"是唯一能观测的关闭证据，没有名字就只能
        // 在全局线程表里猜哪些是本服务的（HttpServer 的工作线程是按需建的，不起请求根本不存在）。
        ThreadFactory named = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "z-vector-rest-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ExecutorService ex = Executors.newFixedThreadPool(8, named);
        s.setExecutor(ex);
        s.createContext("/", this::route);
        s.start();
        this.executor = ex;
        this.server = s;
        this.boundPort = s.getAddress().getPort();
        log.info("Qdrant REST API started on port {}", getPort());
    }

    public void stop() {
        HttpServer s = server;
        server = null;
        boundPort = -1;
        ExecutorService ex = executor;
        executor = null;
        if (s != null) {
            s.stop(1);
            log.info("Qdrant REST API stopped");
        }
        if (ex != null) {
            // HttpServer.stop() 不会替关调用方 setExecutor() 交出去的池 —— 不关就是永久泄漏，
            // 而且池里是非阻塞等待的 worker，JVM 会因此等不到它们（daemon 只是让退出不至于卡死）。
            ex.shutdownNow();
        }
    }

    public VectorStore getStore() { return store; }

    /** 真实监听端口（{@code port=0} 时是内核挑的那个）；未启动时退回请求值。 */
    public int getPort() {
        int bound = boundPort;
        return bound >= 0 ? bound : port;
    }

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
            if (P_COLLECTION.matcher(path).matches()) {
                String name = path.substring(PREFIX_COLLECTIONS.length());
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
            if (P_SEARCH.matcher(path).matches()) {
                String name = extractCollectionName(path, "/points/search");
                if ("POST".equals(method)) {
                    handleSearch(exchange, name);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}/points/count
            if (P_COUNT.matcher(path).matches()) {
                String name = extractCollectionName(path, "/points/count");
                if ("GET".equals(method)) {
                    handlePointCount(exchange, name);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}/points (PUT = upsert batch)
            if (P_POINTS_BATCH.matcher(path).matches()) {
                String name = path.substring(PREFIX_COLLECTIONS.length(),
                        path.length() - "/points".length());
                if ("PUT".equals(method)) {
                    handleUpsertPoints(exchange, name);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            // /collections/{name}/points/{id}
            if (P_POINT_BY_ID.matcher(path).matches()) {
                int marker = path.indexOf(MARKER_POINTS);
                String name = path.substring(PREFIX_COLLECTIONS.length(), marker);
                String id = path.substring(marker + MARKER_POINTS.length());
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
        } catch (IllegalArgumentException e) {
            // 请求体写错（metric/index_type/dimension 的取值或类型）是客户端的锅，不是服务端的 500。
            // 独立 server 那一侧的 dispatch 早就是这个映射了；两边不一致时，照 OpenApiSpec
            // 广告的两个 400 分支生成的客户端会在这一台上收到 500。
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            log.error("Request failed: {} {}", method, path, e);
            sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ==================== Collection 端点 ====================

    private void handleCreateCollection(HttpExchange exchange, String name) throws IOException {
        Map<String, Object> body = parseJson(exchange);
        int dimension = asInt(body.get("dimension"), 128, "dimension");
        DistanceMetric metric = parseMetric(body.get("metric"));
        Map<String, Object> indexParams = asParamsMap(body.get("index_params"), "index_params");
        // 缺省时不能填个 "FLAT" 再走 5 参 —— 那等于当着客户端的面把进程配好的默认索引顶掉。
        // 而 starter 那条路恰好就是这么死的：zvector.default-index → store.setDefaultIndex →
        // 同一个 store 交给这台 server，但客户端不写 index_type 时这里传的是显式 FLAT ⇒
        // 配了 HNSW 也永远建出 FLAT，且响应只有 status/name，谁都看不见这次降级。
        Object rawIndexType = body.get("index_type");
        if (rawIndexType == null) {
            store.createCollection(name, dimension, metric);
        } else {
            store.createCollection(name, dimension, metric,
                    parseIndexType(rawIndexType, "index_type"), indexParams);
        }
        // 回读 store 里真正建出来的那个，不回吐客户端传进来的（理由同独立 server 那一侧）。
        VectorCollection created = store.getCollection(name);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("name", name);
        resp.put("dimension", created.getDimension());
        resp.put("metric", created.getMetric().name());
        resp.put("index_type", created.getIndexType().name());
        resp.put("index_params", created.getConfig());
        sendJson(exchange, 200, resp);
    }

    /**
     * 请求体里的 {@code metric}：允许缺省（COSINE），大小写不敏感，写错必须是 400 而不是
     * "No enum constant" 的 500 —— OpenApiSpec 给 createCollection 广告的就是 '400' Bad request。
     */
    private static DistanceMetric parseMetric(Object v) {
        if (v == null) return DistanceMetric.COSINE;
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("metric must be a string, got: "
                    + v.getClass().getSimpleName());
        }
        String raw = (String) v;
        try {
            return DistanceMetric.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("metric is not a known distance metric: \"" + raw
                    + "\" (expected one of " + names(DistanceMetric.values()) + ")");
        }
    }

    /** 写错的索引类型必须带得上"那什么算对" —— 支持的取值直接从枚举取，不再抄一份字面量。 */
    private static IndexType parseIndexType(Object v, String where) {
        if (!(v instanceof String)) {
            throw new IllegalArgumentException(where + " must be a string, got: "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        String raw = (String) v;
        try {
            return IndexType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + " is not a known index type: \"" + raw
                    + "\" (expected one of " + names(IndexType.values()) + ")");
        }
    }

    private static String names(Enum<?>[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i].name());
        }
        return sb.toString();
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

    /** Jackson 会把 JSON 数字解成 Integer 或 Double，而 `(Number) "768"` 是 ClassCastException ⇒ 500。 */
    private static int asInt(Object v, int defaultValue, String where) {
        if (v == null) return defaultValue;
        int parsed;
        if (v instanceof Number) {
            parsed = ((Number) v).intValue();
        } else {
            try {
                parsed = Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(where + " must be a positive integer, got: " + v);
            }
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(where + " must be a positive integer, got: " + v);
        }
        return parsed;
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
        // 索引参数也要能读回来：创建响应现在回读了，GET 这一路却只回类型，
        // 于是"M=16 到底进没进去"仍然只能靠再建一次集合去猜。
        resp.put("index_params", c.getConfig());
        resp.put("points_count", store.getPointCount(name));
        resp.put("indexed", store.isIndexed(name));
        sendJson(exchange, 200, resp);
    }

    private void handleDeleteCollection(HttpExchange exchange, String name) throws IOException {
        boolean removed = store.deleteCollection(name);
        if (removed) {
            sendJson(exchange, 200, mapOf("status", "ok"));
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
        sendJson(exchange, 200, mapOf("status", "ok", "count", points.size()));
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
            sendJson(exchange, 200, mapOf("status", "ok"));
        } else {
            sendError(exchange, 404, "Point not found: " + id);
        }
    }

    private void handlePointCount(HttpExchange exchange, String name) throws IOException {
        sendJson(exchange, 200, mapOf("count", store.getPointCount(name)));
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
        // 过滤：形状与错误语义见 parseFilter —— 解析不出来必须 400，不能把"没筛过"当结果发出去。
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
     * Filter 解析 —— 认得的形状全部落到 {@link Filter} 的真实语义，认不出的报 400 并点名是哪一层。
     * <p>
     * 改前最坏的地方不是"少支持了 Qdrant 的 {@code must/should/must_not}"，而是<b>它不报错</b>：
     * <ul>
     *   <li>{@code {"must":[…]}} 只有一个 key，会先落进"单字段等值"那一支，变成
     *       {@code Filter.eq("must", 那个数组)}。payload 里没有叫 must 的字段 ⇒ "筛一下"的回复是
     *       一个合法的空结果集，客户端无从知道自己写错了。</li>
     *   <li>文档自己广告过的 {@code {"and":[…]}} / {@code {"or":[…]}} 同理（{@code size()==1} 那一支
     *       在复合处理前面，复合那两段根本到不了）⇒ 恒零命中。</li>
     *   <li>操作符写错走 {@code default: return null} ⇒ 整条 filter 被丢掉，返回的是<b>没筛过</b>的结果，
     *       比零命中更糟。</li>
     *   <li>多个 key 的对象（{@code {"lang":"zh","score":{"gte":0.5}}}）落到末尾的 {@code return null}，
     *       同样静默不筛。</li>
     *   <li>{@code {"score":{"gte":0.2,"lte":0.6}}} 只取第一项（迭代序），lte 被悄悄扔掉。</li>
     *   <li>{@code {"score":{"gt":"abc"}}} 是 {@code (Number) val} 的 ClassCastException ⇒ 500。</li>
     * </ul>
     * 现在这六种写法要么真在服务，要么 400 —— "筛了但没生效"这条路上不再有任何一种写法。
     */
    private Filter parseFilter(Object filterObj) {
        return parseFilter(filterObj, "filter");
    }

    private Filter parseFilter(Object filterObj, String where) {
        if (filterObj == null) return null;
        if (!(filterObj instanceof Map)) {
            throw new IllegalArgumentException(where + " must be a JSON object, got: " + kindOf(filterObj));
        }
        Map<?, ?> m = (Map<?, ?>) filterObj;
        List<Filter> parts = new ArrayList<Filter>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            String here = where + "." + key;
            if (COMBINATORS.contains(key)) {
                Filter combinator = parseCombinator(key, e.getValue(), here);
                if (combinator != null) parts.add(combinator);
            } else {
                parts.add(parseCondition(key, e.getValue(), here));
            }
        }
        if (parts.isEmpty()) return null;          // {} —— 一个条件也没有，等价于不筛
        if (parts.size() == 1) return parts.get(0);
        return Filter.and(parts.toArray(new Filter[parts.size()]));
    }

    /**
     * {@code and/must} → AND，{@code or/should} → OR，{@code must_not} → 每项取反后 AND，
     * {@code not} → 收一个条件对象取反。空数组/空对象返回 {@code null}（不施加限制），
     * 因为 {@code Filter.or()} 的空children编码是"恒假"，直接交给它会变成零命中。
     */
    private Filter parseCombinator(String key, Object raw, String where) {
        if ("not".equals(key)) {
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException(where + " must be a JSON object, got: " + kindOf(raw));
            }
            Filter one = parseFilter(raw, where);
            return one == null ? null : one.not();
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException(where + " must be a JSON array, got: " + kindOf(raw));
        }
        List<?> items = (List<?>) raw;
        List<Filter> children = new ArrayList<Filter>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Filter child = parseFilter(items.get(i), where + "[" + i + "]");
            if (child != null) children.add(child);
        }
        if (children.isEmpty()) return null;
        Filter[] arr = children.toArray(new Filter[children.size()]);
        if ("or".equals(key) || "should".equals(key)) return Filter.or(arr);
        if ("must_not".equals(key)) {
            Filter[] negated = new Filter[arr.length];
            for (int i = 0; i < arr.length; i++) negated[i] = arr[i].not();
            return Filter.and(negated);
        }
        return Filter.and(arr);                     // and / must
    }

    /** {@code {"lang":"zh"}} 是等值；{@code {"lang":{…}}} 里的每个操作符都要真生效。 */
    private Filter parseCondition(String field, Object value, String where) {
        if (!(value instanceof Map)) return Filter.eq(field, value);
        Map<?, ?> spec = (Map<?, ?>) value;
        if (spec.isEmpty()) {
            throw new IllegalArgumentException(where + " 的操作符对象是空的，形如 {\"" + field
                    + "\":{\"eq\":\"zh\"}}");
        }
        List<Filter> parts = new ArrayList<Filter>(spec.size());
        for (Map.Entry<?, ?> opEntry : spec.entrySet()) {
            String op = String.valueOf(opEntry.getKey());
            parts.add(parseOperator(field, op, opEntry.getValue(), where + "." + op));
        }
        if (parts.size() == 1) return parts.get(0);
        return Filter.and(parts.toArray(new Filter[parts.size()]));  // {"score":{"gte":0.2,"lte":0.6}}
    }

    @SuppressWarnings("unchecked")
    private Filter parseOperator(String field, String op, Object val, String where) {
        if ("eq".equals(op)) return Filter.eq(field, val);
        if ("ne".equals(op)) return Filter.ne(field, val);
        if ("exists".equals(op)) {
            // {"exists": false} 是"这个字段不存在"，不能当 true 处理。
            if (val instanceof Boolean && !((Boolean) val)) return Filter.exists(field).not();
            return Filter.exists(field);
        }
        if ("contains".equals(op)) {
            require(val instanceof String, where + " expects a string, got: " + kindOf(val));
            return Filter.contains(field, (String) val);
        }
        if ("in".equals(op) || "nin".equals(op)) {
            require(val instanceof List, where + " expects a JSON array, got: " + kindOf(val));
            List<Object> values = (List<Object>) val;
            return "in".equals(op) ? Filter.inValues(field, values) : Filter.notIn(field, values);
        }
        if ("gt".equals(op) || "gte".equals(op) || "lt".equals(op) || "lte".equals(op)) {
            require(val instanceof Number, where + " expects a number, got: " + kindOf(val));
            Number n = (Number) val;
            if ("gt".equals(op)) return Filter.gt(field, n);
            if ("gte".equals(op)) return Filter.gte(field, n);
            if ("lt".equals(op)) return Filter.lt(field, n);
            return Filter.lte(field, n);
        }
        throw new IllegalArgumentException("filter operator \"" + op + "\" is not supported for field \""
                + field + "\" (expected one of " + OPERATORS + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    /** 报错里要说清"这东西是什么"，而 Java 的类名对写 JSON 的人没有意义。 */
    private static String kindOf(Object v) {
        if (v == null) return "null";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        if (v instanceof String) return "string";
        if (v instanceof Number) return "number";
        if (v instanceof Boolean) return "boolean";
        return v.getClass().getSimpleName();
    }

    private static final java.util.Set<String> COMBINATORS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "and", "or", "must", "should", "must_not", "not"));

    private static final String OPERATORS =
            "eq, ne, gt, gte, lt, lte, in, nin, exists, contains";

    // ==================== 工具方法 ====================

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private String extractCollectionName(String path, String suffix) {
        String base = path.substring(PREFIX_COLLECTIONS.length());
        if (!base.endsWith(suffix)) {
            throw new VectorException("Malformed collection path: " + path);
        }
        return base.substring(0, base.length() - suffix.length());
    }

    /**
     * Java 8 兼容的 {@code Map.of} 替身 — 本模块 target 为 1.8，而 {@code Map.of}
     * 是 Java 9 才有的 API（编译期不报错，运行期在 8-jre 上 NoSuchMethodError）。
     */
    private static Map<String, Object> mapOf(Object... kv) {
        if ((kv.length & 1) != 0) {
            throw new IllegalArgumentException("mapOf requires an even number of arguments");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** Java 8 兼容的 {@code InputStream.readAllBytes} 替身。 */
    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = readAll(is);
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