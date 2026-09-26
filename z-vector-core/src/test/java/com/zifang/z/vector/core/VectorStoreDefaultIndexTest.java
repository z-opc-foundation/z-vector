package com.zifang.z.vector.core;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code setDefaultIndex} 在存储层兑现的尺。
 * <p>
 * 装配层的 {@code ZVectorConfigContractTest} 管"yml 里的键有没有走到这里"，这一支管"到了这里
 * 有没有真的换掉索引"。改前 3 参 {@code createCollection} 是把 {@code IndexType.FLAT} 写死在
 * 代码里的，配置无论怎么配都到不了这里。
 */
class VectorStoreDefaultIndexTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
    }

    private VectorCollection create(String name) {
        store.createCollection(name, 8, DistanceMetric.COSINE);
        VectorCollection vc = store.getCollection(name);
        assertNotNull(vc, "集合没建出来: " + name);
        return vc;
    }

    /** D1：只给类型也必须换 —— 抓"只有同时给了参数才生效"那种半兑现形状。 */
    @Test
    void d1_typeOnlyReachesCollection() {
        store.setDefaultIndex(IndexType.HNSW, null);
        assertEquals(IndexType.HNSW, create("a").getIndexType());
    }

    /** D2：参数跟着走。 */
    @Test
    void d2_paramsReachCollection() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("M", 32);
        params.put("efConstruction", 400);
        store.setDefaultIndex(IndexType.HNSW, params);
        VectorCollection vc = create("b");
        assertEquals(IndexType.HNSW, vc.getIndexType());
        assertEquals(32, num(vc.getConfig().get("M")), "M 没进集合 schema");
        assertEquals(400, num(vc.getConfig().get("efConstruction")), "efConstruction 没进集合 schema");
    }

    /** D3：索引真的按参数建出来，不只是 schema 上写了一行。 */
    @Test
    void d3_indexIsActuallyBuiltWithThoseParams() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("M", 4);
        params.put("efConstruction", 16);
        store.setDefaultIndex(IndexType.HNSW, params);
        store.createCollection("c", 4, DistanceMetric.L2);
        assertEquals(IndexType.HNSW, store.getCollection("c").getIndexType(),
                "先钉住类型：否则这一例在用 FLAT 也能搜到 5 条，白测");
        for (int i = 0; i < 20; i++) {
            store.upsert("c", new VectorPoint("p" + i, vec(i), new HashMap<String, Object>()));
        }
        store.buildIndex("c");
        assertEquals(5, store.search("c", vec(3), 5, null).size(),
                "默认索引没真建起来：top-5 拿不满 ⇒ 参数只是记在 schema 上没落到索引");
    }

    private static float[] vec(int seed) {
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) v[i] = (seed + i) % 7 + 1f;
        return v;
    }

    /** D4：显式指定的那一支不许被默认值顶掉。 */
    @Test
    void d4_explicitIndexTypeWins() {
        store.setDefaultIndex(IndexType.HNSW, null);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("nlist", 4);
        store.createCollection("explicit", 8, DistanceMetric.L2, IndexType.IVF, params);
        VectorCollection vc = store.getCollection("explicit");
        assertEquals(IndexType.IVF, vc.getIndexType());
        assertEquals(4, num(vc.getConfig().get("nlist")));
    }

    /** D5：null 恢复内置默认（不配 = 老行为，这一条保证这次改动没偷偷换默认索引）。 */
    @Test
    void d5_nullRestoresBuiltinFlat() {
        store.setDefaultIndex(IndexType.IVF, null);
        assertEquals(IndexType.IVF, create("x").getIndexType());
        store.setDefaultIndex(null, null);
        assertEquals(IndexType.FLAT, create("y").getIndexType(),
                "setOrDefaultIndex(null) 之后必须回到内置 FLAT");
    }

    /**
     * D6：调用方之后改自己的 Map，不许把已生效的配置一起改掉 —— 配置快照存的是副本。
     * 反面案例：装配层那个 Map 是 Spring 绑出来的对象，被外部改动就等于"配置在运行中漂移"。
     */
    @Test
    void d6_callerMapMutationDoesNotLeak() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("M", 16);
        store.setDefaultIndex(IndexType.HNSW, params);
        params.put("M", 999);
        params.put("efSearch", 1);
        VectorCollection vc = create("z");
        assertEquals(16, num(vc.getConfig().get("M")), "存了调用方的活引用，配置会随外部改动漂");
        assertTrue(vc.getConfig().get("efSearch") == null,
                "同上：后来加的键也不该出现");
    }

    /** D7：非法取值不许静默接受（装配层会解析字符串，存储层这一环得挡住 null 之外的胡来）。 */
    @Test
    void d7_emptyMapIsAcceptedAsNoParams() {
        store.setDefaultIndex(IndexType.HNSW, new HashMap<String, Object>());
        VectorCollection vc = create("empty");
        assertEquals(IndexType.HNSW, vc.getIndexType());
        assertNotNull(vc.getConfig(), "config 不许是 null，调用方要能直接读");
        assertTrue(vc.getConfig().isEmpty());
    }

    /** 类型留空的语义 = 不覆盖；这一条是 D5 的复测面：两次设 null 之后仍然走 FLAT。 */
    @Test
    void d8_nullTypeKeepsPreviousBehaviour() {
        store.setDefaultIndex(null, null);
        assertEquals(IndexType.FLAT, create("after-null").getIndexType());
    }

    private static int num(Object v) {
        assertNotNull(v, "键不在 config 里");
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }
}
