package com.zifang.z.vector.storage.bloom;

/**
 * MurmurHash3 — Austin Appleby 128-bit 变体（public domain）。
 * <p>
 * 实现版本：MurmurHash3_x64_128（64-bit 优化版，输出 128-bit）。
 * <p>
 * 引用：https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp
 */
final class MurmurHash3 {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad4327459377L;

    private MurmurHash3() {}

    /**
     * 计算 128-bit hash，返回 2 个 long（h1, h2）。
     *
     * @param key 输入字符串
     * @param seed 种子（默认 0）
     */
    static long[] hash128(String key, long seed) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return hash128(bytes, seed);
    }

    /** 重载：默认 seed = 0。 */
    static long[] hash128(String key) {
        return hash128(key, 0L);
    }

    static long[] hash128(byte[] data, long seed) {
        int length = data.length;
        int nblocks = length >>> 4; // 16 bytes per block

        long h1 = seed;
        long h2 = seed;

        // body
        for (int i = 0; i < nblocks; i++) {
            int base = i << 4;
            long k1 = getLittleEndianLong(data, base);
            long k2 = getLittleEndianLong(data, base + 8);

            k1 *= C1; k1 = Long.rotateLeft(k1, 31); k1 *= C2; h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27); h1 += h2; h1 = h1 * 5 + 0x52dce729L;

            k2 *= C2; k2 = Long.rotateLeft(k2, 33); k2 *= C1; h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31); h2 += h1; h2 = h2 * 5 + 0x38495ab5L;
        }

        // tail
        int tailStart = nblocks << 4;
        long k1 = 0;
        long k2 = 0;
        switch (length & 15) {
            case 15: k2 ^= ((long) data[tailStart + 14] & 0xff) << 48;
            case 14: k2 ^= ((long) data[tailStart + 13] & 0xff) << 40;
            case 13: k2 ^= ((long) data[tailStart + 12] & 0xff) << 32;
            case 12: k2 ^= ((long) data[tailStart + 11] & 0xff) << 24;
            case 11: k2 ^= ((long) data[tailStart + 10] & 0xff) << 16;
            case 10: k2 ^= ((long) data[tailStart + 9]  & 0xff) << 8;
            case  9: k2 ^= ((long) data[tailStart + 8]  & 0xff);
                     k2 *= C2; k2 = Long.rotateLeft(k2, 33); k2 *= C1; h2 ^= k2;
            case  8: k1 ^= ((long) data[tailStart + 7] & 0xff) << 56;
            case  7: k1 ^= ((long) data[tailStart + 6] & 0xff) << 48;
            case  6: k1 ^= ((long) data[tailStart + 5] & 0xff) << 40;
            case  5: k1 ^= ((long) data[tailStart + 4] & 0xff) << 32;
            case  4: k1 ^= ((long) data[tailStart + 3] & 0xff) << 24;
            case  3: k1 ^= ((long) data[tailStart + 2] & 0xff) << 16;
            case  2: k1 ^= ((long) data[tailStart + 1] & 0xff) << 8;
            case  1: k1 ^= ((long) data[tailStart] & 0xff);
                     k1 *= C1; k1 = Long.rotateLeft(k1, 31); k1 *= C2; h1 ^= k1;
        }

        // finalization
        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;

        return new long[]{h1, h2};
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    private static long getLittleEndianLong(byte[] data, int offset) {
        return ((long) data[offset]     & 0xff)        |
                (((long) data[offset + 1] & 0xff) <<  8) |
                (((long) data[offset + 2] & 0xff) << 16) |
                (((long) data[offset + 3] & 0xff) << 24) |
                (((long) data[offset + 4] & 0xff) << 32) |
                (((long) data[offset + 5] & 0xff) << 40) |
                (((long) data[offset + 6] & 0xff) << 48) |
                (((long) data[offset + 7] & 0xff) << 56);
    }
}
