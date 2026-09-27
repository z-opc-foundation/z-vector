package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import com.zifang.z.vector.protocol.OpenApiSpec;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filter 契约尺（#22）：文档广告过的每一种过滤形状，都要在真起的 server 上筛出**确切**的那批点。
 * <p>
 * 这一族缺陷的共同点是"客户端收不到任何异常信号"：改前 {@code {"and":[…]}}（文档自己广告过的形状）、
 * {@code {"must":[…]}} 会恒返回零命中，写错的操作符会让整条 filter 静默消失、返回未过滤的结果。
 * 所以判据不能是"没报错"，只能是"筛出来的集合恰好等于这一行写着的集合" —— 每种形状的期望结果集
 * 两两不同，摘掉任一层处理（组合词 / 操作符 / 多键 AND）都会让至少一行对不上。
 * <p>
 * 操作符与组合词的**清单**取自生成的 OpenAPI 文档（{@code x-implemented-*} 两行），不是本文件里
 * 另抄一份：文档加一个操作符而没有对应的探针行 ⇒ 红；探针行有而文档没广告 ⇒ 红；400 消息里
 * 回给客户端的"expected one of …"与文档不一致 ⇒ 红。
 */
class QdrantRestFilterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COL = "flt";

    /** {操作符/组合词, 探针 filter, 期望命中的 id}。探针结果集两两不同，见类注释。 */
    private static final String[][] OP_PROBES = {
            {"eq", "{\"lang\":{\"eq\":\"zh\"}}", "p1,p3"},
            {"ne", "{\"lang\":{\"ne\":\"zh\"}}", "p2,p4"},
            {"gt", "{\"score\":{\"gt\":0.5}}", "p1,p4"},
            {"gte", "{\"score\":{\"gte\":0.5}}", "p1,p2,p4"},
            {"lt", "{\"views\":{\"lt\":60}}", "p2"},
            {"lte", "{\"views\":{\"lte\":100}}", "p1,p2"},
            {"in", "{\"lang\":{\"in\":[\"zh\",\"fr\"]}}", "p1,p3,p4"},
            {"nin", "{\"lang\":{\"nin\":[\"zh\"]}}", "p2,p4"},
            {"exists", "{\"views\":{\"exists\":true}}", "p1,p2"},
            {"contains", "{\"tags\":{\"contains\":\"ne\"}}", "p1,p3"},
    };

    private static final String[][] COMBINATOR_PROBES = {
            {"and", "{\"and\":[{\"lang\":\"zh\"},{\"score\":{\"gte\":0.5}}]}", "p1"},
            {"or", "{\"or\":[{\"lang\":\"fr\"},{\"views\":{\"exists\":true}}]}", "p1,p2,p4"},
            {"must", "{\"must\":[{\"lang\":\"zh\"}]}", "p1,p3"},
            {"should", "{\"should\":[{\"lang\":\"fr\"},{\"lang\":\"en\"}]}", "p2,p4"},
            {"must_not", "{\"must_not\":[{\"lang\":\"zh\"},{\"score\":{\"gte\":0.8}}]}", "p2"},
            {"not", "{\"not\":{\"views\":{\"exists\":true}}}", "p3,p4"},
    };

    private VectorStore store;
    private QdrantRestServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryVectorStore();
        server = new QdrantRestServer(store, 0);
        server.start();
        base = "http://localhost:" + server.getPort();
        create(COL);
        upsert("p1", new float[]{1f, 0f}, "{\"lang\":\"zh\",\"score\":0.9,\"tags\":\"news\",\"views\":100}");
        upsert("p2", new float[]{0f, 1f}, "{\"lang\":\"en\",\"score\":0.5,\"tags\":\"blog\",\"views\":50}");
        upsert("p3", new float[]{0.9f, 0.1f}, "{\"lang\":\"zh\",\"score\":0.2,\"tags\":\"news\"}");
        upsert("p4", new float[]{0.1f, 0.9f}, "{\"lang\":\"fr\",\"score\":0.8}");
        // 种子必须真的种上了：否则下面每一条"期望恰好等于"都会因为双方都是空集而假绿。
        assertEquals(new TreeSet<String>(Arrays.asList("p1", "p2", "p3", "p4")),
                search(null), "种子数据没落全，这把尺的每一行都会空跑");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (store != null) store.close();
    }

    // ==================== 文档 ↔ 实现 ====================

    @Test
    void advertisedOperatorsAndProbesCorrespond() throws Exception {
        List<String> advertised = advertisedList("x-implemented-operators");
        assertTrue(advertised.size() >= 8, "只从文档里解析出 " + advertised.size()
                + " 个操作符 —— 解析器或文档结构变了，这条尺现在是瞎的");
        Set<String> probed = new TreeSet<String>();
        for (String[] probe : OP_PROBES) probed.add(probe[0]);
        List<String> noProbe = new ArrayList<String>(advertised);
        noProbe.removeAll(probed);
        List<String> notAdvertised = new ArrayList<String>(probed);
        notAdvertised.removeAll(advertised);
        assertTrue(noProbe.isEmpty(), "文档广告了却没有探针的操作符: " + noProbe);
        assertTrue(notAdvertised.isEmpty(), "有探针却没在文档广告的操作符: " + notAdvertised);
    }

    @Test
    void everyAdvertisedCombinatorHasAProbe() throws Exception {
        List<String> advertised = advertisedList("x-implemented-combinators");
        Set<String> probed = new TreeSet<String>();
        for (String[] probe : COMBINATOR_PROBES) probed.add(probe[0]);
        assertEquals(new TreeSet<String>(advertised), probed,
                "文档广告的组合词与有探针的组合词必须一一对应（左边文档、右边探针）");
    }

    /** 400 里回给客户端的操作符清单必须与文档同一份 —— 否则照文档写的客户端会被误导两次。 */
    @Test
    void unknownOperatorErrorNamesTheDocumentedVocabulary() throws Exception {
        Resp r = searchRaw("{\"lang\":{\"match\":{\"value\":\"zh\"}}}");
        assertEquals(400, r.code, "Qdrant 的 match 形状没被判 400，而是 " + r.code + " " + r.body);
        String expected = "expected one of ";
        int at = r.message.indexOf(expected);
        assertTrue(at >= 0, "400 消息没点名可用操作符: " + r.message);
        String listed = r.message.substring(at + expected.length()).replace(")", "").trim();
        assertEquals(String.join(", ", advertisedList("x-implemented-operators")), listed,
                "400 回的操作符清单与文档广告的不是同一份");
        // 阳性对照：这条尺不能对"消息里根本没有清单"空跑。
        assertTrue(listed.contains("eq"), "解析出的清单是空的: [" + listed + "]");
    }

    // ==================== 形状逐个真在服务 ====================

    @Test
    void eachAdvertisedOperatorSelectsExactlyItsProbeSet() throws Exception {
        for (String[] probe : OP_PROBES) {
            Set<String> got = search(probe[1]);
            assertEquals(toSet(probe[2]), got, "操作符 " + probe[0] + " 筛出的不是期望集合，filter=" + probe[1]);
            assertFalse(got.isEmpty() && toSet(probe[2]).isEmpty(), "空对空不算数: " + probe[0]);
            assertFalse(toSet(probe[2]).equals(search(null)),
                    "操作符 " + probe[0] + " 没起作用（结果与不筛相同），filter=" + probe[1]);
        }
    }

    @Test
    void eachAdvertisedCombinatorSelectsExactlyItsProbeSet() throws Exception {
        for (String[] probe : COMBINATOR_PROBES) {
            Set<String> got = search(probe[1]);
            assertEquals(toSet(probe[2]), got, "组合词 " + probe[0] + " 筛出的不是期望集合，filter=" + probe[1]);
        }
        // 组合词之间结果必须互不相同，否则"and 被摘成 or"这类变异没人管。
        Set<String> seen = new HashSet<String>();
        for (String[] probe : COMBINATOR_PROBES) {
            assertTrue(seen.add(probe[2]), "两条组合词探针共用结果集 " + probe[2] + "，摘掉任一层都不会红");
        }
    }

    /** 一个对象里多个键 = AND；一个字段多个操作符 = AND。改前前者整条丢、后者只取第一项。 */
    @Test
    void multipleKeysAndMultipleOperatorsAreBothAnd() throws Exception {
        assertEquals(toSet("p1"), search("{\"lang\":\"zh\",\"score\":{\"gte\":0.5}}"),
                "多键对象没按 AND 处理（改前是整条 filter 静默丢掉）");
        assertEquals(toSet("p2,p3"), search("{\"score\":{\"gte\":0.2,\"lte\":0.5}}"),
                "同字段多个操作符没按 AND 处理（改前只取迭代到的第一项）");
        assertEquals(toSet("p3"),
                search("{\"and\":[{\"or\":[{\"lang\":\"zh\"},{\"lang\":\"en\"}]},{\"score\":{\"lt\":0.5}}]}"),
                "嵌套组合没算对");
    }

    /** 裸值仍是等值简写；{} / 空数组是"不施加限制"，不是恒假。 */
    @Test
    void shorthandAndEmptyShapesMatchAll() throws Exception {
        assertEquals(toSet("p1,p3"), search("{\"lang\":\"zh\"}"), "单字段裸值不再是等值简写");
        Set<String> all = search(null);
        assertEquals(all, search("{}"), "{} 应该等价于不筛");
        assertEquals(all, search("{\"and\":[]}"), "空 and 应该等价于不筛");
        assertEquals(all, search("{\"should\":[]}"), "空 should 应该等价于不筛（不是恒假）");
        assertEquals(all, search("{\"must_not\":[]}"), "空 must_not 应该等价于不筛");
    }

    /** exists 的 false 值是"没有这个字段"，不是被忽略。 */
    @Test
    void existsFalseIsFieldMissing() throws Exception {
        assertEquals(toSet("p3,p4"), search("{\"views\":{\"exists\":false}}"));
        assertEquals(toSet("p1,p2"), search("{\"views\":{\"exists\":true}}"));
    }

    // ==================== 写错了必须响，不能"筛了个寂寞" ====================

    @Test
    void malformedFiltersAre400NotSilentlyUnfiltered() throws Exception {
        String[] cases = {
                "{\"score\":{\"gt\":\"abc\"}}",          // 数值操作符拿到字符串（改前 ClassCastException→500）
                "{\"lang\":{\"in\":\"zh\"}}",            // in 要数组
                "{\"must\":{\"lang\":\"zh\"}}",          // must 要数组
                "{\"and\":[{\"lang\":\"zh\"},\"nope\"]}", // 数组里混进非对象
                "{\"lang\":{}}",                          // 空操作符对象
                "{\"score\":{\"gte\":null}}",              // 操作符值是 null：不是任何已知形状
        };
        for (String filter : cases) {
            Resp r = searchRaw(filter);
            assertEquals(400, r.code, "这个 filter 该 400 并说清哪里写错: " + filter
                    + " -> " + r.code + " " + r.body);
            assertNotNull(r.message, "400 没带 message: " + filter);
            assertFalse(r.message.trim().isEmpty(), "400 的 message 是空的: " + filter);
        }
        // filter 整体不是对象（字符串/数组）同样不能变成"没筛"。
        assertEquals(400, searchRaw("[]").code, "filter 是数组时没判 400");
    }

    /** 反向对照：这一台上"合法 filter"确实能改结果集，上面的 400 判据不是只会报红。 */
    @Test
    void validFilterStillReturnsTwoHundred() throws Exception {
        Resp ok = searchRaw("{\"lang\":{\"eq\":\"zh\"}}");
        assertEquals(200, ok.code, "合法 filter 被拒了: " + ok.body);
        assertEquals(toSet("p1,p3"), ids(ok));
    }

    // ==================== 工具 ====================

    private Set<String> search(String filterJson) throws Exception {
        Resp r = searchRaw(filterJson);
        assertEquals(200, r.code, "搜索失败 filter=" + filterJson + " -> " + r.code + " " + r.body);
        return ids(r);
    }

    private Resp searchRaw(String filterJson) throws Exception {
        StringBuilder body = new StringBuilder("{\"vector\":[1,0],\"limit\":10");
        if (filterJson != null) body.append(",\"filter\":").append(filterJson);
        body.append("}");
        return send("POST", "/collections/" + COL + "/points/search", body.toString());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> ids(Resp r) {
        Object result = r.json.get("result");
        assertTrue(result instanceof List, "响应里没有 result 列表: " + r.body);
        Set<String> ids = new TreeSet<String>();
        for (Object hit : (List<Object>) result) {
            ids.add(String.valueOf(((Map<String, Object>) hit).get("id")));
        }
        return ids;
    }

    /** 解析文档里的 {@code x-implemented-operators: [a, b, …]} 那一行。 */
    private static List<String> advertisedList(String key) throws IOException {
        String spec = OpenApiSpec.generateSpec();
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(key) + ":\\s*\\[([^\\]]*)]\\s*$",
                Pattern.MULTILINE);
        Matcher m = p.matcher(spec);
        assertTrue(m.find(), "生成的 OpenAPI 文档里没有 " + key + " 这一行，文档↔实现那两把尺现在是瞎的");
        List<String> out = new ArrayList<String>();
        for (String token : m.group(1).split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Set<String> toSet(String csv) {
        return new TreeSet<String>(Arrays.asList(csv.split(",")));
    }

    private void create(String name) throws Exception {
        Resp r = send("PUT", "/collections/" + name, "{\"dimension\":2,\"metric\":\"L2\"}");
        assertEquals(200, r.code, "建集合失败: " + r.body);
    }

    private void upsert(String id, float[] vector, String payloadJson) throws Exception {
        StringBuilder vec = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) vec.append(i == 0 ? "" : ",").append(vector[i]);
        vec.append("]");
        String point = "{\"id\":\"" + id + "\",\"vector\":" + vec + ",\"payload\":" + payloadJson + "}";
        Resp r = send("PUT", "/collections/" + COL + "/points", "{\"points\":[" + point + "]}");
        assertEquals(200, r.code, "upsert " + id + " 失败: " + r.body);
    }

    private Resp send(String method, String path, String body) throws Exception {
        try {
            return sendOnce(method, path, body);
        } catch (IOException transport) {
            // 端口复用把请求写到上一台 server 的废连接上时会这样；判据只看真响应。
            // 详见 OpenApiSpecRoutingTest.send 的注释。
            return sendOnce(method, path, body);
        }
    }

    private Resp sendOnce(String method, String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Connection", "close");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        if (is != null) {
            try {
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            } finally {
                is.close();
            }
        }
        conn.disconnect();
        Resp r = new Resp(code, new String(bos.toByteArray(), StandardCharsets.UTF_8));
        if (!r.body.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(r.body, Map.class);
            if (parsed != null) r.json = parsed;
        }
        Object message = r.json.get("message");
        r.message = message == null ? null : String.valueOf(message);
        return r;
    }

    private static final class Resp {
        final int code;
        final String body;
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        String message;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
