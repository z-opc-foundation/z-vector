package com.zifang.z.vector.storage.bloom;

/**
 * Bloom Filter — 用 k 个 hash 函数 + m 位数组实现的概率性集合。
 * <p>
 * 用于加速"某 id 是否存在"的判断（如重启时避免遍历已删除 id）：
 * <ul>
 *   <li>不存在判断一定正确（无假阴性）；</li>
 *   <li>存在判断可能假阳性（误判率由 m/n/k 决定）。</li>
 * </ul>
 *
 * <h2>容量与误判率</h2>
 * 给定期望元素数 {@code n} 和误判率 {@code p}：
 * <ul>
 *   <li>位数组大小 {@code m = -n * ln(p) / (ln(2)^2)}</li>
 *   <li>hash 函数数 {@code k = (m/n) * ln(2)}</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * {@link #add(String)} / {@link #mightContain(String)} 都不是线程安全的；
 * 调用方需要在外层做并发控制（PersistentVectorStore 用 ConcurrentHashMap 隔离）。
 */
public class BloomFilter {

    private final long expectedInsertions;
    private final double falsePositiveRate;
    private final int bitArraySize;     // m
    private final int numHashFunctions; // k
    private final byte[] bits;          // 用 byte[] 存储（避免 BitSet 的开销）

    /** 已插入的元素数（仅用于统计）。 */
    private long insertedCount = 0;

    public BloomFilter(long expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) throw new IllegalArgumentException("n must be > 0");
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("p must be in (0, 1)");
        }
        this.expectedInsertions = expectedInsertions;
        this.falsePositiveRate = falsePositiveRate;
        // 计算最优 m / k
        int m = optimalBitArraySize(expectedInsertions, falsePositiveRate);
        int k = optimalNumHashFunctions(m, expectedInsertions);
        this.bitArraySize = m;
        this.numHashFunctions = k;
        this.bits = new byte[((m + 7) >>> 3)]; // ceil(m/8)
    }

    /** 从现有位数组反序列化（用于加载磁盘上的 bloom filter）。 */
    public BloomFilter(long expectedInsertions, double falsePositiveRate,
                       byte[] bits, long insertedCount) {
        this.expectedInsertions = expectedInsertions;
        this.falsePositiveRate = falsePositiveRate;
        int m = optimalBitArraySize(expectedInsertions, falsePositiveRate);
        int k = optimalNumHashFunctions(m, expectedInsertions);
        this.bitArraySize = m;
        this.numHashFunctions = k;
        int expectedBytes = (m + 7) >>> 3;
        if (bits == null || bits.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "bits size mismatch: expected " + expectedBytes + ", got "
                            + (bits == null ? 0 : bits.length));
        }
        this.bits = bits;
        this.insertedCount = insertedCount;
    }

    // ==================== 核心 API ====================

    /** 添加一个元素（不可逆；不支持删除）。 */
    public void add(String key) {
        long[] hashes = hash(key);
        for (int i = 0; i < numHashFunctions; i++) {
            int idx = (int) (Math.abs(hashes[i]) % bitArraySize);
            bits[idx >>> 3] |= (1 << (idx & 7));
        }
        insertedCount++;
    }

    /**
     * 判断元素是否可能存在。
     * <p>
     * 返回 true 表示「可能存在」（有 {@code falsePositiveRate} 概率误判）；
     * 返回 false 表示「一定不存在」。
     */
    public boolean mightContain(String key) {
        long[] hashes = hash(key);
        for (int i = 0; i < numHashFunctions; i++) {
            int idx = (int) (Math.abs(hashes[i]) % bitArraySize);
            if ((bits[idx >>> 3] & (1 << (idx & 7))) == 0) {
                return false;
            }
        }
        return true;
    }

    // ==================== 元信息 ====================

    public long expectedInsertions() { return expectedInsertions; }
    public double falsePositiveRate() { return falsePositiveRate; }
    public int bitArraySize() { return bitArraySize; }
    public int numHashFunctions() { return numHashFunctions; }
    public long insertedCount() { return insertedCount; }
    public byte[] bits() { return bits; }

    /** 当前填充率（[0, 1]）。 */
    public double fillRatio() {
        long ones = 0;
        for (byte b : bits) {
            ones += Integer.bitCount(b & 0xFF);
        }
        return (double) ones / bitArraySize;
    }

    // ==================== 内部 ====================

    /**
     * 计算 k 个 hash：基于 murmur3-128 拆成两个 64-bit hash，再组合生成更多。
     * <p>
     * 公式：{@code h_i = h1 + i * h2}（Kirsch-Mitzenmacher 技巧，2006），
     * 只需 2 个独立 hash 就能模拟 k 个。
     */
    private long[] hash(String key) {
        long[] h128 = MurmurHash3.hash128(key);
        long[] result = new long[numHashFunctions];
        for (int i = 0; i < numHashFunctions; i++) {
            result[i] = h128[0] + (long) i * h128[1];
        }
        return result;
    }

    /** 最优位数组大小：m = -n * ln(p) / (ln(2)^2) */
    private static int optimalBitArraySize(long n, double p) {
        double ln2 = Math.log(2);
        double m = -n * Math.log(p) / (ln2 * ln2);
        return Math.max(64, (int) Math.ceil(m));
    }

    /** 最优 hash 函数数：k = (m/n) * ln(2) */
    private static int optimalNumHashFunctions(long m, long n) {
        double k = ((double) m / n) * Math.log(2);
        return Math.max(1, (int) Math.round(k));
    }
}
