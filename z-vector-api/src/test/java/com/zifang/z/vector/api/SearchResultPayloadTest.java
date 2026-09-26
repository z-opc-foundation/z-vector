package com.zifang.z.vector.api;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearchResult 的 payload 语义门禁。
 * <p>
 * 起因是查询期分配量：HNSW 一次 ef=64 的查询要为 beam 造上百个 SearchResult，而 beam 里的
 * 候选从来不带 payload —— 旧实现在每个实例上挂一个新的 {@code LinkedHashMap}（外加每次
 * {@code getPayload()} 再包一层 unmodifiable 壳），实测每次查询因此多分配约 28 KB
 * （48,125 → 13,494 B/query，见 core 的 {@code HnswQueryAllocationTest}）。
 * <p>
 * 省分配不能顺手改掉语义，所以这里逐条钉住：空 payload 是共享单例（这是"没有每实例 map"的
 * 结构证据，与负载无关）、非空仍是防御性复制、视图仍不可变、且同一个实例每次返回同一个视图。
 */
class SearchResultPayloadTest {

    @Test
    void absentPayloadIsTheSharedEmptyMapSingleton() {
        SearchResult a = new SearchResult("v1", 0.5f);
        SearchResult b = new SearchResult("v2", 0.5f, null);
        SearchResult c = new SearchResult("v3", 0.5f, new HashMap<String, Object>());

        assertTrue(a.getPayload().isEmpty());
        assertTrue(b.getPayload().isEmpty());
        assertTrue(c.getPayload().isEmpty());
        // 三条都必须是同一个对象：只要有任何一条给每个实例新建 map，这里就红。
        assertSame(Collections.emptyMap(), a.getPayload());
        assertSame(Collections.emptyMap(), b.getPayload());
        assertSame(Collections.emptyMap(), c.getPayload());
    }

    @Test
    void payloadViewIsTheSameInstanceOnEveryCall() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("lang", "zh");
        SearchResult r = new SearchResult("v1", 0.5f, src);

        assertSame(r.getPayload(), r.getPayload(),
                "不可变视图应当构造时包一次，而不是每次 getPayload() 新建一个壳");
        assertEquals(Collections.singletonMap("lang", "zh"), r.getPayload());
    }

    /** 视图不可变，且结果不随调用方之后改自己那张 map 而漂移。 */
    @Test
    void payloadIsACopyAndItsViewIsUnmodifiable() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("lang", "zh");
        final SearchResult r = new SearchResult("v1", 0.5f, src);

        src.put("lang", "en");
        src.put("extra", 1);
        assertEquals("zh", r.getPayload().get("lang"), "结果应当是构造那一刻的快照");
        assertEquals(1, r.getPayload().size(), "复制不该跟着源 map 长大");

        assertThrows(UnsupportedOperationException.class, () -> r.getPayload().put("k", "v"));
    }

    @Test
    void idAndScoreAreStillReturnedAndToStringKeepsTheScoreShape() {
        SearchResult r = new SearchResult("v1", 1.25f);
        assertEquals("v1", r.getVectorId());
        assertEquals(1.25f, r.getScore(), 0f);
        assertEquals("SearchResult{id='v1', score=1.2500}", r.toString());
    }
}
