package com.zifang.z.vector.protocol;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议规范单元测试。
 * <p>
 * 除了版本常量本身，这里还有一族<b>词汇表尺</b>：{@link OpenApiSpec} 是唯一会被
 * OpenAPI Generator 拿去生成客户端的文档，它广告出来的取值集合就是对外承诺。此前它写着
 * {@code enum: [L2, INNER_PRODUCT, COSINE]}，而 {@code INNER_PRODUCT} 在
 * {@link DistanceMetric} 里根本不存在 —— 照这份 spec 生成的客户端提交上去只会拿到
 * "No enum constant"。那种错法没有任何一处尺看得见，因为它跟代码之间没有任何引用关系。
 * <p>
 * 所以两边各钉一把：广告出去的每个 token 必须能在真枚举里查到，真枚举的每个常量必须都被
 * 广告到。加成员忘了写进 spec、或凭想象往 spec 里加值，都是当场判红。
 */
class ProtocolSpecTest {

    /**
     * spec 里的属性名 → 它对应的真枚举类。
     * <p>
     * 这份映射是<b>强制登记</b>的：spec 里出现一个没登记过的 {@code enum:}  vocabulary，
     * {@link #everyAdvertisedEnumVocabularyIsOwnedByAKnownEnum()} 会直接判红，
     * 逼着加广告的人先说清"这一族取值由哪个枚举兑现"。
     */
    private static final Map<String, Class<? extends Enum<?>>> VOCABULARY = vocabulary();

    private static Map<String, Class<? extends Enum<?>>> vocabulary() {
        Map<String, Class<? extends Enum<?>>> m = new LinkedHashMap<String, Class<? extends Enum<?>>>();
        m.put("metric", DistanceMetric.class);
        m.put("index_type", IndexType.class);
        return m;
    }

    @Test
    void versionExists() {
        assertNotNull(ProtocolSpec.VERSION);
        assertEquals("1.0.0", ProtocolSpec.VERSION);
    }

    @Test
    void milvusCompatibilityVersion() {
        assertNotNull(ProtocolSpec.MILVUS_VERSION);
    }

    @Test
    void qdrantCompatibilityVersion() {
        assertNotNull(ProtocolSpec.QDRANT_VERSION);
    }

    /** spec 的 info.version 不许再各写一份字面量 —— 同一个通道错过两次的那一族。 */
    @Test
    void specVersionComesFromProtocolSpec() {
        String spec = OpenApiSpec.generateSpec();
        assertEquals("  version: " + ProtocolSpec.VERSION + "\n", versionLine(spec),
                "generateSpec() 的 info.version 与 ProtocolSpec.VERSION 漂了");
        assertEquals(ProtocolSpec.VERSION, OpenApiSpec.getApiDocs().get("version"),
                "getApiDocs() 的 version 与 ProtocolSpec.VERSION 漂了");
    }

    private static String versionLine(String spec) {
        for (String line : spec.split("\n", -1)) {
            if (line.startsWith("  version: ")) return line + "\n";
        }
        throw new AssertionError("生成的 spec 里没有 info.version 这一行");
    }

    /** 正向 + 反向合成一条判据：spec 里每一族广告，都必须与真枚举<b>完全相等</b>。 */
    @Test
    void everyAdvertisedEnumBlockEqualsTheRealConstantSet() {
        Map<String, List<List<String>>> advertised = advertisedEnums(OpenApiSpec.generateSpec());
        for (Map.Entry<String, List<List<String>>> e : advertised.entrySet()) {
            String property = e.getKey();
            Class<? extends Enum<?>> type = VOCABULARY.get(property);
            assertNotNull(type, "属性 " + property + " 广告了枚举却没登记对应类");
            Set<String> real = new HashSet<String>(Arrays.asList(constantNames(type)));
            int occurrence = 0;
            for (List<String> tokens : e.getValue()) {
                occurrence++;
                Set<String> advertisedTokens = new HashSet<String>(tokens);
                for (String token : tokens) {
                    assertTrue(real.contains(token), type.getSimpleName() + " 里没有 \"" + token
                            + "\"（第 " + occurrence + " 处 " + property + " 广告）—— 照这份 spec 生成的"
                            + "客户端提交它会收到 400。真取值: " + real);
                }
                Set<String> missing = new HashSet<String>(real);
                missing.removeAll(advertisedTokens);
                assertTrue(missing.isEmpty(), type.getSimpleName() + " 的 " + missing + " 没出现在"
                        + " spec 的第 " + occurrence + " 处 " + property + " 广告里（生成的客户端够不到"
                        + "这一族取值）");
                assertEquals(real.size(), advertisedTokens.size(),
                        property + " 第 " + occurrence + " 处广告与真枚举数量不符（多写/重复）");
            }
        }
    }

    /**
     * 两把尺都不许是空跑：spec 里出现的每一个 {@code enum:} 都必须有归属，
     * 登记的属性也必须真的还在 spec 里。删掉 {@code index_type} 那一行、或新加一族
     * 没人认领的取值，都会在这一条上被判出来，而不是让上面那条安静地少检查一项。
     */
    @Test
    void everyAdvertisedEnumVocabularyIsOwnedByAKnownEnum() {
        Map<String, List<List<String>>> advertised = advertisedEnums(OpenApiSpec.generateSpec());
        assertTrue(advertised.size() >= 2, "词汇表尺空跑：spec 里只解析出 " + advertised.size()
                + " 族枚举（改前至少 metric + index_type 两族）");
        for (String property : advertised.keySet()) {
            assertTrue(VOCABULARY.containsKey(property),
                    "OpenApiSpec 新广告了属性 " + property + " 的取值集合，但测试里没有登记它由哪个枚举兑现");
        }
        for (String property : VOCABULARY.keySet()) {
            assertTrue(advertised.containsKey(property),
                    "登记在词汇表尺里的属性 " + property + " 不在 spec 里了（广告被删？）");
        }
    }

    // ==================== 取证据 ====================

    private static String[] constantNames(Class<? extends Enum<?>> type) {
        String[] names = new String[type.getEnumConstants().length];
        for (int i = 0; i < names.length; i++) names[i] = type.getEnumConstants()[i].name();
        return names;
    }

    /**
     * 从生成的 YAML 文本里按缩进找出 {@code 属性 → enum 取值} 的对应关系。     * <p>
     * 不复用 {@code OpenApiSpec} 里的常量、也不按行号取 —— 那等于把被测的那份字面量
     * 抄进尺里，改了字面量尺就跟着改，永远红不了。
     */
    private static Map<String, List<List<String>>> advertisedEnums(String spec) {
        Map<String, List<List<String>>> out = new LinkedHashMap<String, List<List<String>>>();
        Map<Integer, String> lastKeyAtIndent = new LinkedHashMap<Integer, String>();
        for (String line : spec.split("\n", -1)) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("enum:")) {
                String owner = ownerOf(indent, lastKeyAtIndent);
                if (owner == null) continue;
                String list = trimmed.substring("enum:".length()).trim();
                List<List<String>> occurrences = out.get(owner);
                if (occurrences == null) {
                    occurrences = new ArrayList<List<String>>();
                    out.put(owner, occurrences);
                }
                occurrences.add(splitList(list));
                continue;
            }
            if (isKeyLine(trimmed)) {
                lastKeyAtIndent.put(indent, trimmed.substring(0, trimmed.length() - 1).trim());
            }
        }
        return out;
    }

    private static String ownerOf(int indent, Map<Integer, String> lastKeyAtIndent) {
        String owner = null;
        int ownerIndent = -1;
        for (Map.Entry<Integer, String> e : lastKeyAtIndent.entrySet()) {
            if (e.getKey() < indent && e.getKey() > ownerIndent) {
                ownerIndent = e.getKey();
                owner = e.getValue();
            }
        }
        return owner;
    }

    /** 形如 {@code metric:}（键后无值）；{@code type: string} 与 {@code url: http://…} 都不算。 */
    private static boolean isKeyLine(String trimmed) {
        if (!trimmed.endsWith(":") || trimmed.length() < 2) return false;
        String key = trimmed.substring(0, trimmed.length() - 1).trim();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return false;
        }
        return true;
    }

    private static List<String> splitList(String bracketed) {
        String body = bracketed.trim();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        List<String> tokens = new ArrayList<String>();
        for (String token : body.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }
}
