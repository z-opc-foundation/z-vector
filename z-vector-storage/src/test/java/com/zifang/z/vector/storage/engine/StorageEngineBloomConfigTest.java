package com.zifang.z.vector.storage.engine;

import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.bloom.BloomFilter;
import com.zifang.z.vector.storage.wal.WalFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StorageEngine 的 Bloom 配置必须<b>真的落到过滤器上</b>。
 *
 * <p>改动前的形状：5 参构造器收下 {@code bloomExpected}/{@code bloomFpRate}，用它们打了一行
 * {@code LOG.info("... bloom={}@{}/page ...")}，然后<b>一个字段都没存</b> ——
 * {@code getOrCreateBloom} 与 {@code rebuildBloom} 两处 {@code new BloomFilter(...)} 写死的都是
 * {@code DEFAULT_*} 常量。于是"配了 bloom 参数"这件事的全部效果就是日志里多两个数字：
 * 位数组大小、hash 个数、误判率一律按 100_000@1% 走。把 1_000 条 id 的集合配成
 * {@code 10_000@0.001}，拿到的还是 1% 的误判率；反之把千万级集合配成小 expected 也毫无作用
 * （bloom 只会饱和，不会按配置缩）。
 *
 * <p>每条"配置生效"的尺子都配<b>阳性对照</b>：既证明指定值到了过滤器上，也证明<b>没指定时
 * 仍是 1.0.3 的默认值</b>，否则"B 生效"可能只是我把两边都改成了 B。
 */
class StorageEngineBloomConfigTest {

    @TempDir
    Path tmpDir;

    private StorageEngine engine(long expected, double fpRate) throws IOException {
        return new StorageEngine(tmpDir.toString(), new WalFile(tmpDir.toString()),
                expected, fpRate, StorageEngine.DEFAULT_BUFFER_PAGES);
    }

    private static List<VectorPoint> points(int n) {
        List<VectorPoint> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new VectorPoint("id-" + i, new float[]{i, i + 1, i + 2}));
        }
        return list;
    }

    /** B1：{@code getOrCreateBloom} 建出来的过滤器用的是构造时那一对值。 */
    @Test
    void configuredBloomReachesThePerCollectionFilter() throws IOException {
        StorageEngine e = engine(1_000L, 0.001);
        BloomFilter bf = e.getOrCreateBloom("docs");
        assertEquals(1_000L, bf.expectedInsertions(), "配置里的 expected 没进过滤器");
        assertEquals(0.001, bf.falsePositiveRate(), "配置里的 fp 率没进过滤器");
        // 结构对照：与"直接按同一对配置 new 出来"的位数组/哈希数完全一致
        BloomFilter direct = new BloomFilter(1_000L, 0.001);
        assertEquals(direct.bitArraySize(), bf.bitArraySize());
        assertEquals(direct.numHashFunctions(), bf.numHashFunctions());
        // 引擎自己报出来的那一对，就是日志里那一（同一个来源，不存在两处各说一套）
        assertEquals(1_000L, e.bloomExpected());
        assertEquals(0.001, e.bloomFpRate());

        // 阳性对照：默认构造器仍是 1.0.3 的那一对常量
        StorageEngine byDefault = new StorageEngine(tmpDir.toString(), new WalFile(tmpDir.toString()));
        BloomFilter def = byDefault.getOrCreateBloom("other");
        assertEquals(StorageEngine.DEFAULT_BLOOM_EXPECTED, def.expectedInsertions(),
                "默认构造器被改动了 ⇒ 上面那条'配置生效'就不是把默认值比下去的结果");
        assertEquals(StorageEngine.DEFAULT_BLOOM_FP_RATE, def.falsePositiveRate());
        e.close();
        byDefault.close();
    }

    /** B2：{@code rebuildBloom} 的下限跟着<b>配置</b>走，同时保留"按点数留 2 倍余量"。 */
    @Test
    void rebuildHonoursConfiguredFloorAndKeepsHeadroomRule() throws IOException {
        StorageEngine e = engine(1_000L, 0.01);
        e.rebuildBloom("docs", points(3));
        BloomFilter small = e.getOrCreateBloom("docs");
        assertEquals(1_000L, small.expectedInsertions(),
                "恢复 3 个点却撑到常量默认容量（改动前是 100_000）");
        assertEquals(new BloomFilter(1_000L, 0.01).bitArraySize(), small.bitArraySize());

        // 2 倍余量这条规则不许被改坏：点数超过配置下限时按点数走
        e.rebuildBloom("big", points(5_000));
        BloomFilter big = e.getOrCreateBloom("big");
        assertEquals(10_000L, big.expectedInsertions(), "points.size()*2 的余量规则丢了");
        assertEquals(5_000L, e.bloomInsertedCount("big"));

        // 无假阴性：重建之后每个 id 都必须"可能存在"
        for (int i = 0; i < 5_000; i++) {
            assertTrue(e.mightContain("big", "id-" + i), "rebuild 之后出现假阴性：id-" + i);
        }
        e.close();
    }

    /** B3：fp 率是<b>结构</b>上的量，不是只存进字段好看的数。 */
    @Test
    void tighterFalsePositiveRateActuallyGrowsTheFilter() throws IOException {
        StorageEngine loose = engine(10_000L, 0.1);
        StorageEngine tight = engine(10_000L, 0.0001);
        BloomFilter a = loose.getOrCreateBloom("c");
        BloomFilter b = tight.getOrCreateBloom("c");
        assertTrue(b.bitArraySize() > a.bitArraySize(),
                "同样 expected 下，更严的 fp 率位数组却没变大 ⇒ fp 根本没送到 BloomFilter");
        assertTrue(b.numHashFunctions() >= a.numHashFunctions(),
                "更严的 fp 率应当至少不比松的多用更少的 hash");
        assertEquals(a.expectedInsertions(), b.expectedInsertions(), "两条尺的 expected 必须同，否则比的是别的量");
        loose.close();
        tight.close();
    }

    /** B4：坏配置当场拒，而不是拖到第一个集合建 bloom 时才炸。 */
    @Test
    void badConfigIsRejectedAtConstruction() throws IOException {
        final WalFile wal = new WalFile(tmpDir.toString());
        final String dir = tmpDir.toString();
        assertThrows(IllegalArgumentException.class,
                () -> new StorageEngine(dir, wal, 0L, 0.01, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageEngine(dir, wal, -5L, 0.01, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageEngine(dir, wal, 100L, 0.0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageEngine(dir, wal, 100L, 1.0, 4));
        // 阳性对照：同一批参数换成合法值必须建得起来（否则上面四条是白判）
        StorageEngine ok = new StorageEngine(dir, wal, 100L, 0.5, 4);
        assertEquals(100L, ok.getOrCreateBloom("c").expectedInsertions());
        ok.close();
    }

    /** B5：每个集合各自一份 bloom（README 的承诺），配置对每一份都生效。 */
    @Test
    void everyCollectionGetsItsOwnConfiguredFilter() throws IOException {
        StorageEngine e = engine(2_000L, 0.02);
        e.markBloom("a", "x");
        e.markBloom("b", "y");
        assertNotSame(e.getOrCreateBloom("a"), e.getOrCreateBloom("b"), "两个集合共用了一份 bloom");
        assertEquals(1L, e.bloomInsertedCount("a"));
        assertEquals(1L, e.bloomInsertedCount("b"));
        for (String c : new String[]{"a", "b"}) {
            assertEquals(2_000L, e.getOrCreateBloom(c).expectedInsertions(), "集合 " + c + " 没用配置");
            assertEquals(0.02, e.getOrCreateBloom(c).falsePositiveRate(), "集合 " + c + " 没用配置");
        }
        // 已有的两条无假阴性 + 未 mark 的集合不阻挡查询（mightContain 的保守分支）
        assertTrue(e.mightContain("a", "x"));
        assertTrue(e.mightContain("b", "y"));
        assertTrue(e.mightContain("never-seen", "anything"),
                "没建过 bloom 的集合应当一律放行，而不是把查询全挡掉");
        e.close();
    }
}
