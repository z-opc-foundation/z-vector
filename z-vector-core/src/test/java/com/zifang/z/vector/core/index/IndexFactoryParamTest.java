package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IndexFactory} 参数解析测试。
 * <p>
 * 钉的缺陷：持久化层（v1 JSON 快照与 v2 页快照）把 indexParams 的值统一写成字符串
 * （{@code PageSnapshot.writeAll} 用 {@code String.valueOf(value)}，{@code readV2} 读回来是
 * {@code String}），而 {@code intParam} 原先只认 {@code Number}，其余一律回落到默认值。
 * 结果是<b>每次重启后 M / efConstruction / efSearch / nlist / nprobe 全部静默变回默认</b>
 * —— 用户配了 M=32 却跑成 M=16，召回率和时延一起漂移，且不抛任何异常。
 */
class IndexFactoryParamTest {

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void numericParamsAreHonoured() {
        HnswIndex idx = (HnswIndex) IndexFactory.create(IndexType.HNSW, DistanceMetric.L2, 8,
                params("M", 32, "efConstruction", 400, "efSearch", 100));
        assertEquals(32, idx.getM());
        assertEquals(400, idx.getEfConstruction());
        assertEquals(100, idx.getEfSearch());
    }

    @Test
    void stringParamsSurviveSnapshotRoundTrip() {
        // 这就是快照读回来的真实形状：值全是 String
        HnswIndex idx = (HnswIndex) IndexFactory.create(IndexType.HNSW, DistanceMetric.COSINE, 8,
                params("M", "32", "efConstruction", "400", "efSearch", "100"));
        assertEquals(32, idx.getM(), "字符串形式的 M 必须被解析，不能回落成 16");
        assertEquals(400, idx.getEfConstruction());
        assertEquals(100, idx.getEfSearch());
    }

    @Test
    void ivfStringParamsAreHonoured() {
        IvfIndex idx = (IvfIndex) IndexFactory.create(IndexType.IVF, DistanceMetric.L2, 8,
                params("nlist", "256", "nprobe", "16", "maxIter", "50"));
        assertEquals(256, idx.getNlist(), "字符串 nlist 不应回落成默认 64");
        assertEquals(16, idx.getNprobe());
    }

    @Test
    void floatLookingStringsAreAccepted() {
        // JSON 里 16 很容易被写成 16.0
        HnswIndex idx = (HnswIndex) IndexFactory.create(IndexType.HNSW, DistanceMetric.L2, 8,
                params("M", "16.0", "efSearch", "80.0"));
        assertEquals(16, idx.getM());
        assertEquals(80, idx.getEfSearch());
    }

    @Test
    void absentOrBlankParamFallsBackToDefault() {
        HnswIndex idx = (HnswIndex) IndexFactory.create(IndexType.HNSW, DistanceMetric.L2, 8,
                params("M", "  ", "efSearch", null));
        assertEquals(HnswIndex.DEFAULT_M, idx.getM(), "空白值应回落默认而不是抛错");
        assertEquals(HnswIndex.DEFAULT_EF_SEARCH, idx.getEfSearch());

        HnswIndex bare = (HnswIndex) IndexFactory.create(IndexType.HNSW, DistanceMetric.L2, 8, null);
        assertEquals(HnswIndex.DEFAULT_M, bare.getM());
    }

    @Test
    void nonNumericStringFailsLoudlyInsteadOfSilentlyDefaulting() {
        // "M": " thirty two " 之前会被静默忽略并回落 M=16 —— 与本次要修的病根同源。
        Map<String, Object> bad = new HashMap<>();
        bad.put("M", "thirty-two");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IndexFactory.create(IndexType.HNSW, DistanceMetric.L2, 8, bad));
        assertTrue(e.getMessage().contains("M"), e.getMessage());
    }

    @Test
    void oversizedMIsRejectedAtConstructionNotAtFirstInsert() {
        // M<=0 在 HnswIndex 构造里就有校验；确认工厂不会把坏值吞成默认后"看起来正常"
        Map<String, Object> zero = params("M", "0");
        assertThrows(IllegalArgumentException.class,
                () -> IndexFactory.create(IndexType.HNSW, DistanceMetric.L2, 8, zero));
    }
}
