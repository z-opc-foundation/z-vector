package com.zifang.z.vector.storage.engine;

import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.bloom.BloomFilter;
import com.zifang.z.vector.storage.buffer.BufferPool;
import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageStore;
import com.zifang.z.vector.storage.page.PageType;
import com.zifang.z.vector.storage.wal.AsyncWalFile;
import com.zifang.z.vector.storage.wal.WalFile;
import com.zifang.z.vector.storage.wal.WalMode;
import com.zifang.z.vector.storage.wal.WalRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * StorageEngine — 把页面化存储 + LRU 缓冲池 + Bloom Filter + WAL 串成统一门面。
 * <p>
 * 这是 z-vector 本地存储 v2 的入口，组合以下原语：
 * <ul>
 *   <li>{@link AsyncWalFile}：批量 fsync 的 WAL，提升写入吞吐（仅 {@link WalMode#ASYNC} 模式启用）</li>
 *   <li>{@link BufferPool}：LRU 页缓存，避免重复磁盘读</li>
 *   <li>{@link PageStore}：固定大小页面（64KB）的随机寻址存储</li>
 *   <li>{@link BloomFilter}：每个集合的快速存在性判断（避免遍历已删 id）</li>
 * </ul>
 *
 * <h2>WAL 模式（{@link WalMode}）</h2>
 * <ul>
 *   <li>{@link WalMode#SYNC}（<b>生产默认</b>）：每条记录一次 write + 一次 fsync，
 *       {@code upsert()} 返回即持久 ⇒ <b>RPO = 0</b>。</li>
 *   <li>{@link WalMode#ASYNC}：入队即返回，后台整批 fsync ⇒ RPO ≠ 0
 *       （丢失窗口 = 崩溃瞬间队列中尚未落盘的那一段，详见 {@link WalMode}）。</li>
 * </ul>
 * 两种模式都<b>不改</b> {@link AsyncWalFile} 的落盘实现：SYNC 走的是 {@link WalFile#append}
 * （每条一次 fsync）这条本来就存在的同步路径，ASYNC 才起后台 flusher。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   StorageEngine engine = new StorageEngine("/data/zvec", new WalFile("/data/zvec"),
 *           StorageEngine.DEFAULT_BLOOM_EXPECTED, StorageEngine.DEFAULT_BLOOM_FP_RATE,
 *           StorageEngine.DEFAULT_BUFFER_PAGES, WalMode.SYNC);
 *   // upsert
 *   engine.appendWal(WalRecord.upsertPoint("docs", point));
 *   engine.markBloom("docs", point.getId());
 *
 *   // 读
 *   if (engine.mightContain("docs", "doc-42")) {
 *       Page page = engine.fetchPage(PageId.of("docs", PageType.DATA, 0));
 *       ...
 *   }
 *
 *   // checkpoint
 *   engine.flushWal();
 *   // ... 写 snapshot ...
 *   engine.truncateWal();
 *
 *   engine.close();
 * }</pre>
 *
 * <h2>与现有 PersistentVectorStore 的关系</h2>
 * 当前是<b>可选增强层</b>：现有 WAL + Snapshot 流程保持不变，StorageEngine 提供
 * 增量能力（Bloom Filter + BufferPool + WAL 模式化落盘）。
 * <p>
 * 后续可演进为统一底层（PersistentVectorStore 内部持有 StorageEngine）。
 */
public class StorageEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StorageEngine.class);

    /** Bloom Filter 默认期望元素数（每个集合）。 */
    public static final long DEFAULT_BLOOM_EXPECTED = 100_000L;
    /** Bloom Filter 默认误判率。 */
    public static final double DEFAULT_BLOOM_FP_RATE = 0.01;
    /** BufferPool 默认页容量。 */
    public static final int DEFAULT_BUFFER_PAGES = 256;

    private final String dataDir;
    private final long bloomExpected;
    private final double bloomFpRate;
    private final WalMode walMode;
    /** 同步模式直写的底层 WAL（ASYNC 模式下由 {@link #asyncWal} 包装）。 */
    private final WalFile walFile;
    /** 仅 ASYNC 模式创建（SYNC 模式不起后台 flusher 线程）。 */
    private final AsyncWalFile asyncWal;
    /** 同步模式的计数（ASYNC 模式用 {@link AsyncWalFile} 自带计数）。 */
    private final java.util.concurrent.atomic.AtomicLong syncAppended =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong syncFlushed =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong syncBatches =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final BufferPool bufferPool;
    private final ConcurrentMap<String, BloomFilter> bloomFilters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, PageStore> pageStores = new ConcurrentHashMap<>();

    public StorageEngine(String dataDir, WalFile walFile) {
        this(dataDir, walFile, DEFAULT_BLOOM_EXPECTED, DEFAULT_BLOOM_FP_RATE, DEFAULT_BUFFER_PAGES);
    }

    /**
     * <b>历史构造器（保持 ASYNC 语义不变）</b>。
     * <p>
     * 生产默认的同步 WAL 由 {@code PersistentVectorStore} 决定（它显式传
     * {@link WalMode#SYNC}）；本构造器保留旧的异步行为，避免既有调用方（含既有测试）
     * 的语义被无声改掉。需要明确模式请用
     * {@link #StorageEngine(String, WalFile, long, double, int, WalMode)}。
     */
    public StorageEngine(String dataDir, WalFile walFile,
                          long bloomExpected, double bloomFpRate, int bufferPages) {
        this(dataDir, walFile, bloomExpected, bloomFpRate, bufferPages, WalMode.ASYNC);
    }

    /**
     * 显式指定 WAL 模式的构造器。
     *
     * @param walMode {@link WalMode#SYNC} = 每条记录一次 fsync（RPO = 0，生产默认）；
     *                {@link WalMode#ASYNC} = 后台整批 fsync（RPO ≠ 0，见 {@link WalMode}）
     */
    public StorageEngine(String dataDir, WalFile walFile,
                          long bloomExpected, double bloomFpRate, int bufferPages,
                          WalMode walMode) {
        // 这两个值在这里就校验，而不是留到第一个集合建 bloom 时才炸：构造器是配置进来的地方，
        // 而 bloom 可能要等到恢复期 rebuildBloom 才第一次被创建（届时栈已经离配置很远了）。
        if (bloomExpected <= 0) throw new IllegalArgumentException("bloomExpected must be > 0");
        if (bloomFpRate <= 0 || bloomFpRate >= 1) {
            throw new IllegalArgumentException("bloomFpRate must be in (0, 1)");
        }
        if (walMode == null) throw new IllegalArgumentException("walMode must not be null");
        this.dataDir = dataDir;
        this.bloomExpected = bloomExpected;
        this.bloomFpRate = bloomFpRate;
        this.walMode = walMode;
        this.walFile = walFile;
        // 只有异步模式才需要后台 flusher：同步模式下每条写入当场 fsync，没有"待落盘队列"。
        this.asyncWal = (walMode == WalMode.ASYNC) ? new AsyncWalFile(walFile) : null;
        this.bufferPool = new BufferPool(bufferPages);
        LOG.info("StorageEngine initialized: dataDir={}, bloom={}@{}/page, buffer={} pages, walMode={}",
                dataDir, bloomExpected, bloomFpRate, bufferPages, walMode);
    }

    /** 生效中的 WAL 模式。 */
    public WalMode walMode() { return walMode; }

    /** 生效中的 bloom 期望元素数 / 误判率 —— 与构造时传进来的那一对是同一个来源。 */
    public long bloomExpected() { return bloomExpected; }
    public double bloomFpRate() { return bloomFpRate; }

    // ==================== WAL ====================

    /**
     * 追加一条 WAL 记录。
     * <ul>
     *   <li>{@link WalMode#SYNC}：当场 {@link WalFile#append}（一次 write + 一次 fsync）
     *       ⇒ 返回即持久，RPO = 0。</li>
     *   <li>{@link WalMode#ASYNC}：入队即返回，后台整批 fsync ⇒ RPO ≠ 0。</li>
     * </ul>
     */
    public void appendWal(WalRecord record) throws IOException {
        if (asyncWal != null) {
            asyncWal.append(record);
            return;
        }
        walFile.append(record);
        syncAppended.incrementAndGet();
        syncFlushed.incrementAndGet();
        // 同步模式每条记录自成一"批"（一次 fsync 一次 write）。
        syncBatches.incrementAndGet();
    }

    /**
     * 阻塞直到此前提交的 WAL 全部落盘。
     * <p>
     * 同步模式下每条 append 当场 fsync，因此这里无事可做（立即返回）。
     */
    public void flushWal() throws IOException {
        if (asyncWal != null) {
            asyncWal.flush();
        }
    }

    /** flush 后截断 WAL（checkpoint 后调用）。 */
    public void flushAndTruncateWal() throws IOException {
        if (asyncWal != null) {
            asyncWal.flushAndTruncate();
            return;
        }
        synchronized (walFile) {
            walFile.truncate();
        }
    }

    /** 后台 flusher 监控。 */
    public long walAppended() {
        return asyncWal != null ? asyncWal.totalAppended() : syncAppended.get();
    }

    public long walFlushed() {
        return asyncWal != null ? asyncWal.totalFlushed() : syncFlushed.get();
    }

    /** 落盘批次数（同步模式每条记录一批 ⇒ 与 walAppended 相同；异步模式是 group commit 批数）。 */
    public long walBatches() {
        return asyncWal != null ? asyncWal.totalBatches() : syncBatches.get();
    }

    /** 待落盘队列长度（同步模式恒为 0：没有队列这回事）。 */
    public int walQueueSize() {
        return asyncWal != null ? asyncWal.queueSize() : 0;
    }

    // ==================== Page ====================

    /**
     * 通过 BufferPool 读取一个 page（先查缓存，未命中则从 PageStore 加载）。
     */
    public Page fetchPage(PageId id) throws IOException {
        PageStore store = pageStores.computeIfAbsent(id.collectionId(),
                cid -> new PageStore(dataDir, cid, "cid=" + cid));
        return bufferPool.fetch(id, store);
    }

    /** 直接把 page 写入 PageStore 并加入缓存（标 dirty）。 */
    public void writePage(Page page) throws IOException {
        PageStore store = pageStores.computeIfAbsent(page.id().collectionId(),
                cid -> new PageStore(dataDir, cid, "cid=" + cid));
        store.write(page);
        bufferPool.put(page);
        page.dirty = false;
    }

    public BufferPool bufferPool() { return bufferPool; }

    /** 强制把 buffer pool 中所有 dirty 页写回 PageStore。 */
    public int flushDirtyPages() {
        return bufferPool.flushDirty(page -> {
            try {
                PageStore store = pageStores.computeIfAbsent(page.id().collectionId(),
                        cid -> new PageStore(dataDir, cid, "cid=" + cid));
                store.write(page);
            } catch (IOException e) {
                LOG.warn("Failed to flush dirty page {}", page.id(), e);
            }
        });
    }

    // ==================== Bloom Filter ====================

    /**
     * 获取或创建指定集合的 BloomFilter（首次创建时按配置初始化）。
     * <p>
     * 返回的实例是线程安全的（用 ConcurrentHashMap 隔离），但 BloomFilter.add 不是线程安全；
     * 调用方需保证 add 在并发写时只从一个线程触发（推荐在 PersistentVectorStore 写入路径串行）。
     */
    public BloomFilter getOrCreateBloom(String collection) {
        return bloomFilters.computeIfAbsent(collection,
                k -> new BloomFilter(bloomExpected, bloomFpRate));
    }

    public void markBloom(String collection, String id) {
        getOrCreateBloom(collection).add(id);
    }

    public boolean mightContain(String collection, String id) {
        BloomFilter bf = bloomFilters.get(collection);
        if (bf == null) return true;  // 未初始化 → 不阻挡（保守）
        return bf.mightContain(id);
    }

    /**
     * 重新构建某集合的 Bloom Filter — 一般在启动时调用（从 snapshot + WAL 恢复）。
     * <p>
     * 已有 bloom 会被覆盖。
     */
    public void rebuildBloom(String collection, List<VectorPoint> points) {
        // 下限用**配置的** expected，不是常量：配了 10_000 的集合恢复 3 个点时，
        // 不该被撑成 100_000 容量（×fp 率下的位数组是实打实的堆）。
        BloomFilter bf = new BloomFilter(
                Math.max(points.size() * 2L, bloomExpected), bloomFpRate);
        for (VectorPoint p : points) bf.add(p.getId());
        bloomFilters.put(collection, bf);
        LOG.info("Bloom filter rebuilt for '{}': {} points, fillRatio={}",
                collection, points.size(), String.format("%.2f%%", bf.fillRatio() * 100));
    }

    public long bloomInsertedCount(String collection) {
        BloomFilter bf = bloomFilters.get(collection);
        return bf == null ? 0 : bf.insertedCount();
    }

    public double bloomFillRatio(String collection) {
        BloomFilter bf = bloomFilters.get(collection);
        return bf == null ? 0.0 : bf.fillRatio();
    }

    // ==================== 指标 ====================

    public String metrics() {
        return String.format(
                "StorageEngine{walMode=%s, walAppended=%d, walFlushed=%d, walBatches=%d, walQueue=%d, "
                        + "bufferHits=%d, bufferMisses=%d, bufferEvictions=%d, bufferHitRate=%.2f%%, "
                        + "bufferSize=%d/%d, bloomFilters=%d}",
                walMode, walAppended(), walFlushed(), walBatches(), walQueueSize(),
                bufferPool.hits(), bufferPool.misses(), bufferPool.evictions(),
                bufferPool.hitRate() * 100,
                bufferPool.size(), bufferPool.capacity(),
                bloomFilters.size());
    }

    // ==================== 关闭 ====================

    @Override
    public void close() throws IOException {
        flushDirtyPages();
        if (asyncWal != null) {
            asyncWal.close();
        }
        LOG.info("StorageEngine closed. Final metrics: {}", metrics());
    }
}
