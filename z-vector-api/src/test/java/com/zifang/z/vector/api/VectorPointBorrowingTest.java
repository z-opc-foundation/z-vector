package com.zifang.z.vector.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VectorPoint#ref(String, float[], Map)} 的借用契约。
 *
 * <p>公开构造器和借用工厂的<em>唯一</em>差别就是"拷不拷"，所以两个方向都要钉：
 * <ul>
 *   <li>{@code ref} 必须交出<b>同一块内存</b>（它存在的意义就是省掉那次 clone + map 复制：
 *       dim=128 时 784 B/次，见 {@code z-vector-core} 的
 *       {@code CollectionFastPathAllocationTest} 斜率尺子）；</li>
 *   <li>公开构造器必须<b>继续</b>复制 —— 一旦有人为了省内存把 {@code borrowing} 顺手打开，
 *       调用方改自己的数组就能改到索引已经存下的点。</li>
 * </ul>
 * 两个方向各配一条"改原对象看不出差别 / 看得出差别"的行为断言，光比引用会漏掉真正的语义。
 */
class VectorPointBorrowingTest {

    @Test
    void refAliasesTheArraysItIsGiven() {
        float[] v = new float[]{1f, 2f, 3f};
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lang", "zh");

        VectorPoint borrowed = VectorPoint.ref("b", v, m);
        assertSame(v, borrowed.vectorRef(), "ref() must not clone the vector");
        assertSame(m, borrowed.payloadRef(), "ref() must not copy the payload map");
        assertSame(borrowed.vectorRef(), borrowed.vectorRef(), "vectorRef() is a view, not a copy");
        assertSame(borrowed.payloadRef(), borrowed.payloadRef());
        assertEquals("b", borrowed.getId());
        assertEquals(3, borrowed.getDimension());

        // 别名是真的：动原数组，借出的视图跟着变（这条断言同时挡住"看起来同引用其实拷贝"）
        v[0] = -9f;
        assertEquals(-9f, borrowed.vectorRef()[0], 0f);
        assertNotSame(v, borrowed.getVector(), "getVector() must keep copying even for a borrowed point");
    }

    @Test
    void publicConstructorStillCopiesBothVectorAndPayload() {
        float[] v = new float[]{1f, 2f, 3f};
        Map<String, Object> m = new HashMap<>();
        m.put("lang", "zh");

        VectorPoint owned = new VectorPoint("o", v, m);
        assertNotSame(v, owned.vectorRef(), "the public ctor must clone the vector");
        assertNotSame(m, owned.payloadRef(), "the public ctor must copy the payload map");

        v[0] = -9f;
        m.put("late", "after-construction");
        m.remove("lang");
        assertEquals(1f, owned.vectorRef()[0], 0f);
        assertEquals(3, owned.vectorRef().length);
        assertEquals("zh", owned.payloadRef().get("lang"), "post-construction writes leaked in");
        assertTrue(!owned.payloadRef().containsKey("late"), "post-construction writes leaked in");
    }

    /** 两参构造器：向量要 clone，payload 落成一张<b>空但可变</b>的表（{@code setPayload} 要用）。 */
    @Test
    void twoArgCtorLeavesAMutableEmptyPayloadAndAClonedVector() {
        float[] v = new float[]{4f, 5f};
        VectorPoint p = new VectorPoint("t", v);
        assertNotSame(v, p.vectorRef(), "the 2-arg ctor must not alias the caller's array");
        assertTrue(p.payloadRef().isEmpty(), "no payload given, must start empty");
        p.setPayload("lang", "zh");                       // 可变性就是这条测试的猎物
        assertEquals("zh", p.getPayload("lang"));
        assertEquals(1, p.getPayload().size());
    }

    @Test
    void refTreatsNullPayloadAsEmptyNotNull() {
        VectorPoint p = VectorPoint.ref("n", new float[]{1f}, null);
        assertTrue(p.payloadRef() != null, "payloadRef() must never be null");
        assertTrue(p.payloadRef().isEmpty());
        assertEquals(1, p.getDimension());
    }
}
