package com.zifang.z.vector.storage;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code setDefaultIndex} 在持久化实现上同样要兑现 —— 这一段逻辑在 {@code InMemoryVectorStore} 和
 * {@code PersistentVectorStore} 里各写了一份，两份完全可以各漂各的，所以两边的尺分开钉。
 * <p>
 * 另外钉住语义边界：默认索引是<b>进程配置</b>，不是持久化状态。重开一个不设默认值的 store，
 * 新建集合仍然走 FLAT，而重启前建好的 HNSW 集合仍然带着自己的类型回来。
 */
class PersistentVectorStoreDefaultIndexTest {

    private static VectorCollection threeArg(PersistentVectorStore store, String name) {
        store.createCollection(name, 8, DistanceMetric.COSINE);
        VectorCollection vc = store.getCollection(name);
        assertNotNull(vc, "集合没建出来: " + name);
        return vc;
    }

    @Test
    void defaultIndexReachesNewCollections(@TempDir Path dir) {
        PersistentVectorStore store = new PersistentVectorStore(dir.toString());
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("M", 8);
            store.setDefaultIndex(IndexType.HNSW, params);
            VectorCollection vc = threeArg(store, "docs");
            assertEquals(IndexType.HNSW, vc.getIndexType(),
                    "持久化实现没兑现默认索引（建出来是 " + vc.getIndexType() + "）");
            assertEquals(8, num(vc.getConfig().get("M")), "indexParams 没跟着进 schema");

            // 显式那一支不受影响
            store.createCollection("explicit", 8, DistanceMetric.L2, IndexType.IVF, null);
            assertEquals(IndexType.IVF, store.getCollection("explicit").getIndexType());
        } finally {
            store.close();
        }
    }

    @Test
    void typeOnlyStillApplies(@TempDir Path dir) {
        PersistentVectorStore store = new PersistentVectorStore(dir.toString());
        try {
            store.setDefaultIndex(IndexType.IVF, null);
            assertEquals(IndexType.IVF, threeArg(store, "vecs").getIndexType(),
                    "没给参数就把默认索引吞掉（半兑现形状）");
        } finally {
            store.close();
        }
    }

    /** WAL 里记的是集合真正用的索引类型，不是 FLAT —— 否则重启后集合会悄悄降级。 */
    @Test
    void walRecordsTheDefaultIndexType(@TempDir Path dir) {
        PersistentVectorStore store = new PersistentVectorStore(dir.toString());
        try {
            store.setDefaultIndex(IndexType.HNSW, null);
            threeArg(store, "wal-check");
        } finally {
            store.close();
        }
        PersistentVectorStore again = new PersistentVectorStore(dir.toString());
        try {
            VectorCollection vc = again.getCollection("wal-check");
            assertNotNull(vc, "重启后集合没了");
            assertEquals(IndexType.HNSW, vc.getIndexType(),
                    "WAL/快照把默认索引建出来的集合降级回 FLAT 了");
            // 新进程没设默认值：新建的集合仍然走内置 FLAT
            assertEquals(IndexType.FLAT, threeArg(again, "fresh").getIndexType(),
                    "默认索引是进程配置，不该跨重启传染");
        } finally {
            again.close();
        }
    }

    private static int num(Object v) {
        assertNotNull(v, "键不在 config 里");
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }
}
