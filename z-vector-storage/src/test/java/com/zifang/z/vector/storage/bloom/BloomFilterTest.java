package com.zifang.z.vector.storage.bloom;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BloomFilter 单元测试 — 验证 add / mightContain + 误判率。
 */
class BloomFilterTest {

    @Test
    void addAndContainsExactMatch() {
        BloomFilter bf = new BloomFilter(1000, 0.01);
        bf.add("alice");
        bf.add("bob");
        bf.add("charlie");

        assertTrue(bf.mightContain("alice"));
        assertTrue(bf.mightContain("bob"));
        assertTrue(bf.mightContain("charlie"));
    }

    @Test
    void mightContainNeverFalseNegative() {
        // 关键性质：插入过的元素一定返回 true（无假阴性）
        BloomFilter bf = new BloomFilter(10_000, 0.001);
        Set<String> inserted = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            String s = "user_" + i;
            bf.add(s);
            inserted.add(s);
        }
        for (String s : inserted) {
            assertTrue(bf.mightContain(s), "False negative for: " + s);
        }
    }

    @Test
    void falsePositiveRateWithinBound() {
        // 期望 1% 误判率，1 万个元素。实测误判率应远低于 5%
        double targetP = 0.01;
        int n = 10_000;
        int m = 20_000;  // 不在集合中的元素
        BloomFilter bf = new BloomFilter(n, targetP);
        Set<String> inserted = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String s = "in_" + i;
            bf.add(s);
            inserted.add(s);
        }
        int falsePositives = 0;
        for (int i = 0; i < m; i++) {
            String s = "out_" + i;
            if (!inserted.contains(s) && bf.mightContain(s)) {
                falsePositives++;
            }
        }
        double actualP = (double) falsePositives / m;
        assertTrue(actualP < 0.05,
                "False positive rate too high: " + actualP + " (target=" + targetP + ")");
    }

    @Test
    void emptyFilterReturnsFalseForAll() {
        BloomFilter bf = new BloomFilter(100, 0.01);
        assertFalse(bf.mightContain("anything"));
        assertFalse(bf.mightContain("user_42"));
    }

    @Test
    void invalidParametersThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilter(0, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilter(-1, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilter(100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilter(100, 1.0));
    }

    @Test
    void serializationRoundtrip() {
        BloomFilter original = new BloomFilter(1000, 0.01);
        for (int i = 0; i < 500; i++) original.add("id_" + i);

        BloomFilter restored = new BloomFilter(
                original.expectedInsertions(),
                original.falsePositiveRate(),
                original.bits(),
                original.insertedCount());

        // 验证插入的元素仍然能查到
        for (int i = 0; i < 500; i++) {
            assertTrue(restored.mightContain("id_" + i));
        }
        assertEquals(original.numHashFunctions(), restored.numHashFunctions());
        assertEquals(original.bitArraySize(), restored.bitArraySize());
    }

    @Test
    void fillRatioIncreasesAsAdded() {
        BloomFilter bf = new BloomFilter(1000, 0.01);
        double r0 = bf.fillRatio();
        for (int i = 0; i < 500; i++) bf.add("k_" + i);
        double r1 = bf.fillRatio();
        assertTrue(r1 > r0, "Fill ratio should increase after adding items");
    }

    @Test
    void murmurHashDeterministic() {
        // MurmurHash3 必须确定性（同一输入 → 同一输出）
        long[] h1 = MurmurHash3.hash128("test", 42L);
        long[] h2 = MurmurHash3.hash128("test", 42L);
        assertArrayEquals(h1, h2);
        long[] h3 = MurmurHash3.hash128("test", 0L);
        // 不同 seed 输出不同
        assertNotEquals(h1[0], h3[0]);
    }

    @Test
    void murmurHashDifferentInputsDifferentOutputs() {
        Random r = new Random(123);
        Set<Long> seenH1 = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String s = "key_" + r.nextInt();
            long[] h = MurmurHash3.hash128(s);
            // hash 必须分散（1000 个不同输入碰撞极少）
            seenH1.add(h[0]);
        }
        assertTrue(seenH1.size() > 990,
                "MurmurHash3 outputs not well-distributed: " + seenH1.size() + "/1000");
    }
}
