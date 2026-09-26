package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.L2Distance;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Index#getRef(String)} 的借用契约。
 *
 * <p>它是快路径省分配的全部依据：<b>交出来的数组就是节点存的那一块</b>。所以这里钉两件事，
 * 缺一件都会让"省分配"变成"悄悄改了语义"：
 * <ul>
 *   <li>同一个 id 两次 {@code getRef} 拿到<b>同一个</b>数组对象 ⇒ 它确实指向存储本体
 *       （哪天有人把它换成"重建一份再返回"，这条立刻红，而 {@code CollectionFastPathAllocationTest}
 *       只会给出一条字节数读数）；</li>
 *   <li>公开版 {@code get} 两次拿到<b>不同</b>的数组 ⇒ 对外边界仍然是复制，借用没有漏到 API 外面
 *       （{@code Collection} 那一侧的同一件事由
 *       {@code PayloadIndexFastPathTest.publicPointStillIsACopy} 钉）。</li>
 * </ul>
 * <p>另外钉住 {@code getRef} 与 {@code get} 在"未知 id / 已删 id（墓碑）"上判据一致：
 * 快路径拿 {@code null} 当"倒排与索引不同步"的信号，两者答案不一样就会把同步的表误判成脏。
 */
class GetRefBorrowingTest {

    private static final int DIM = 8;

    private static HnswIndex index() {
        return new HnswIndex(new L2Distance(), DIM, 16, 100, 50, 20260926L);
    }

    private static VectorPoint point(String id, float fill, String tag) {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) v[i] = fill + i;
        Map<String, Object> payload = new HashMap<>();
        payload.put("tag", tag);
        return new VectorPoint(id, v, payload);
    }

    @Test
    void borrowedLookupsAliasTheStoredVectorWhilePublicOnesCopy() {
        HnswIndex idx = index();
        idx.add(point("a", 1f, "x"));
        idx.add(point("b", 5f, "x"));

        float[] first = idx.getRef("a").vectorRef();
        float[] second = idx.getRef("a").vectorRef();
        assertSame(first, second,
                "getRef must hand back the node's own array — a fresh copy per call means the"
                        + " fast path is paying the clone it was rewritten to avoid");
        assertSame(idx.getRef("a").payloadRef(), idx.getRef("a").payloadRef(),
                "same for the payload map: it is the stored one, not a re-copy");

        assertNotSame(idx.get("a").vectorRef(), idx.get("a").vectorRef(),
                "get() is the public boundary and must keep copying — the other side of that line"
                        + " is pinned by PayloadIndexFastPathTest.publicPointStillIsACopy");
        assertNotSame(idx.get("a").payloadRef(), idx.get("a").payloadRef());

        // 内容仍然是同一份：借用不等于读到旧数据
        assertEquals(idx.get("a").getDimension(), idx.getRef("a").getDimension());
        for (int i = 0; i < DIM; i++) {
            assertTrue(idx.get("a").vectorRef()[i] == idx.getRef("a").vectorRef()[i],
                    "value drift at " + i);
        }
    }

    @Test
    void getRefAgreesWithGetOnUnknownAndTombstonedIds() {
        HnswIndex idx = index();
        idx.add(point("a", 1f, "x"));
        idx.add(point("b", 5f, "x"));

        assertNull(idx.get("missing"));
        assertNull(idx.getRef("missing"), "unknown id: both accessors must say null");

        assertTrue(idx.remove("a"));
        assertNull(idx.get("a"), "a tombstoned id is not readable through get()");
        assertNull(idx.getRef("a"),
                "getRef must answer like get(): the fast path reads null as"
                        + " \"inverted index out of sync, fall back to the windowed path\"");
        // 墓碑之外的那个点不受影响
        assertEquals(5f, idx.getRef("b").vectorRef()[0], 0f);
    }

    /** FLAT / IVF 不重写 getRef：默认实现就是 {@code get}，两侧必须给出同一个点。 */
    @Test
    void indexesWithoutABorrowingOverrideFallBackToGet() {
        FlatIndex flat = new FlatIndex(new L2Distance(), DIM);
        VectorPoint p = point("a", 2f, "x");
        flat.add(p);
        assertSame(flat.get("a"), flat.getRef("a"),
                "FlatIndex stores VectorPoints, so its getRef is get() — no second copy, no view");
        assertEquals(DistanceMetric.L2, new L2Distance().metric());
    }
}
