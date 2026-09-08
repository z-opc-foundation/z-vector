package com.zifang.z.vector.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Filter 表达式单元测试 — 覆盖所有运算符 + AND/OR/NOT 组合。
 */
class FilterTest {

    @Test
    void eq() {
        Filter f = Filter.eq("lang", "zh");
        assertTrue(f.evaluate(mapOf("lang", "zh")));
        assertFalse(f.evaluate(mapOf("lang", "en")));
        assertFalse(f.evaluate(new HashMap<>()));
    }

    @Test
    void ne() {
        Filter f = Filter.ne("lang", "zh");
        assertFalse(f.evaluate(mapOf("lang", "zh")));
        assertTrue(f.evaluate(mapOf("lang", "en")));
    }

    @Test
    void numericComparison() {
        Filter gt = Filter.gt("score", 0.5);
        assertTrue(gt.evaluate(mapOf("score", 0.8)));
        assertFalse(gt.evaluate(mapOf("score", 0.5)));
        assertFalse(gt.evaluate(mapOf("score", 0.3)));

        Filter gte = Filter.gte("score", 0.5);
        assertTrue(gte.evaluate(mapOf("score", 0.5)));
        assertTrue(gte.evaluate(mapOf("score", 0.8)));

        Filter lt = Filter.lt("score", 0.5);
        assertTrue(lt.evaluate(mapOf("score", 0.3)));
        assertFalse(lt.evaluate(mapOf("score", 0.5)));

        Filter lte = Filter.lte("score", 0.5);
        assertTrue(lte.evaluate(mapOf("score", 0.5)));
        assertTrue(lte.evaluate(mapOf("score", 0.3)));
    }

    @Test
    void inValues() {
        Filter f = Filter.inValues("category", Arrays.asList("news", "blog"));
        assertTrue(f.evaluate(mapOf("category", "news")));
        assertTrue(f.evaluate(mapOf("category", "blog")));
        assertFalse(f.evaluate(mapOf("category", "other")));
    }

    @Test
    void notIn() {
        Filter f = Filter.notIn("category", Arrays.asList("spam"));
        assertTrue(f.evaluate(mapOf("category", "news")));
        assertFalse(f.evaluate(mapOf("category", "spam")));
    }

    @Test
    void exists() {
        Filter f = Filter.exists("lang");
        assertTrue(f.evaluate(mapOf("lang", "zh")));
        assertFalse(f.evaluate(new HashMap<>()));
    }

    @Test
    void contains() {
        Filter f = Filter.contains("title", "机器学习");
        assertTrue(f.evaluate(mapOf("title", "深入理解机器学习")));
        assertFalse(f.evaluate(mapOf("title", "深入理解深度学习")));
    }

    @Test
    void andAllTrue() {
        Filter f = Filter.and(
                Filter.eq("lang", "zh"),
                Filter.gte("score", 0.5)
        );
        assertTrue(f.evaluate(mapOf("lang", "zh", "score", 0.8)));
        assertFalse(f.evaluate(mapOf("lang", "zh", "score", 0.3)));  // score < 0.5
        assertFalse(f.evaluate(mapOf("lang", "en", "score", 0.8)));  // lang != zh
    }

    @Test
    void orOneTrue() {
        Filter f = Filter.or(
                Filter.eq("lang", "zh"),
                Filter.eq("lang", "en")
        );
        assertTrue(f.evaluate(mapOf("lang", "zh")));
        assertTrue(f.evaluate(mapOf("lang", "en")));
        assertFalse(f.evaluate(mapOf("lang", "fr")));
    }

    @Test
    void complexNested() {
        // (lang = "zh" OR lang = "en") AND category IN ("news", "blog")
        Filter f = Filter.and(
                Filter.or(
                        Filter.eq("lang", "zh"),
                        Filter.eq("lang", "en")
                ),
                Filter.inValues("category", Arrays.asList("news", "blog"))
        );
        assertTrue(f.evaluate(mapOf("lang", "zh", "category", "news")));
        assertTrue(f.evaluate(mapOf("lang", "en", "category", "blog")));
        assertFalse(f.evaluate(mapOf("lang", "zh", "category", "video")));  // category 不在
        assertFalse(f.evaluate(mapOf("lang", "fr", "category", "news")));   // lang 不匹配
    }

    @Test
    void not() {
        // NOT(lang = "zh")
        Filter f = Filter.eq("lang", "zh").not();
        assertFalse(f.evaluate(mapOf("lang", "zh")));
        assertTrue(f.evaluate(mapOf("lang", "en")));
    }

    @Test
    void emptyPayload() {
        Filter f = Filter.eq("lang", "zh");
        assertFalse(f.evaluate(null));
        assertFalse(f.evaluate(new HashMap<>()));
    }

    @Test
    void toStringFormat() {
        Filter f = Filter.eq("lang", "zh");
        assertEquals("lang EQ zh", f.toString());

        Filter and = Filter.and(Filter.eq("a", 1), Filter.eq("b", 2));
        assertTrue(and.toString().contains("AND"));
        assertTrue(and.toString().contains("a EQ 1"));
        assertTrue(and.toString().contains("b EQ 2"));
    }

    private static Map<String, Object> mapOf(Object... kvs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            m.put((String) kvs[i], kvs[i + 1]);
        }
        return m;
    }
}