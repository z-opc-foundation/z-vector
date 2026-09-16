package com.zifang.z.vector.core.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PayloadIndex 单元测试。
 */
class PayloadIndexTest {

    private PayloadIndex index;

    @BeforeEach
    void setUp() {
        index = new PayloadIndex();
    }

    @Test
    void exactMatch() {
        index.indexField("lang", String.class);
        index.index("lang", "zh", "d1");
        index.index("lang", "zh", "d3");
        index.index("lang", "en", "d2");

        Set<String> zh = index.eq("lang", "zh");
        assertEquals(2, zh.size());
        assertTrue(zh.contains("d1"));
        assertTrue(zh.contains("d3"));

        Set<String> en = index.eq("lang", "en");
        assertEquals(1, en.size());
        assertTrue(en.contains("d2"));
    }

    @Test
    void remove() {
        index.indexField("lang", String.class);
        index.index("lang", "zh", "d1");
        index.remove("lang", "zh", "d1");

        assertTrue(index.eq("lang", "zh").isEmpty());
    }

    @Test
    void notEqual() {
        index.indexField("lang", String.class);
        index.index("lang", "zh", "d1");
        index.index("lang", "en", "d2");
        index.index("lang", "fr", "d3");

        Set<String> universe = new java.util.HashSet<>();
        universe.add("d1");
        universe.add("d2");
        universe.add("d3");

        Set<String> ne = index.ne("lang", "zh", universe);
        assertEquals(2, ne.size());
        assertTrue(ne.contains("d2"));
        assertTrue(ne.contains("d3"));
        assertFalse(ne.contains("d1"));
    }

    @Test
    void inValues() {
        index.indexField("category", String.class);
        index.index("category", "news", "d1");
        index.index("category", "blog", "d2");
        index.index("category", "video", "d3");

        Set<String> result = index.inValues("category",
                java.util.Arrays.asList("news", "blog"));
        assertEquals(2, result.size());
        assertTrue(result.contains("d1"));
        assertTrue(result.contains("d2"));
    }

    @Test
    void numericRange() {
        index.indexField("score", Double.class);
        index.index("score", 0.3, "d1");
        index.index("score", 0.5, "d2");
        index.index("score", 0.7, "d3");
        index.index("score", 0.9, "d4");

        // 0.4 <= score <= 0.8 → d2, d3
        Set<String> range = index.range("score", 0.4, true, 0.8, true);
        assertEquals(2, range.size());
        assertTrue(range.contains("d2"));
        assertTrue(range.contains("d3"));

        // score > 0.5
        Set<String> gt = index.range("score", 0.5, false, 1.0, true);
        assertEquals(2, gt.size());
        assertTrue(gt.contains("d3"));
        assertTrue(gt.contains("d4"));
    }

    @Test
    void exists() {
        index.indexField("lang", String.class);
        index.index("lang", "zh", "d1");
        index.index("lang", "en", "d2");

        Set<String> exists = index.exists("lang");
        assertEquals(2, exists.size());
    }

    @Test
    void indexedFields() {
        index.indexField("lang", String.class);
        index.index("lang", "zh", "d1");
        index.indexField("score", Double.class);
        index.index("score", 0.5, "d1");

        assertEquals(2, index.indexedFields().size());
        assertTrue(index.isIndexed("lang"));
        assertTrue(index.isIndexed("score"));
        assertFalse(index.isIndexed("missing"));
    }

    @Test
    void unindexedFieldReturnsEmpty() {
        Set<String> result = index.eq("missing", "value");
        assertTrue(result.isEmpty());
    }
}