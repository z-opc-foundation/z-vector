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
import com.zifang.z.vector.storage.wal.WalRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * StorageEngine — 把页面化存储 + LRU 缓冲池 + Bloom Filter + 异步 WAL 串成统一门面。
 * <p>
 * 这是 z-vector 本地存储 v2 的入口，组合以下原语：
 * <ul>
 *   <li>{@link AsyncWalFile}：批量 fsync 的 WAL，提升写入吞吐</li>
 *   <li>{@link BufferPool}：LRU 页缓存，避免重复磁盘读</li>
 *   <li>{@link PageStore}：固定大小页面（64KB）的随机寻址存储</li>
 *   <li>{@link BloomFilter}：每个集合的快速存在性判断（避免遍历已删 id）</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   StorageEngine engine = new StorageEngine("/data/zvec", new WalFile("/data/zvec"));
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
 * 增量能力（Bloom Filter + BufferPool + Async WAL）。
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
    private final AsyncWalFile asyncWal;
    private final BufferPool bufferPool;
    private final ConcurrentMap<String, BloomFilter> bloomFilters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, PageStore> pageStores = new ConcurrentHashMap<>();

    public StorageEngine(String dataDir, WalFile walFile) {
        this(dataDir, walFile, DEFAULT_BLOOM_EXPECTED, DEFAULT_BLOOM_FP_RATE, DEFAULT_BUFFER_PAGES);
    }

    public StorageEngine(String dataDir, WalFile walFile,
                          long bloomExpected, double bloomFpRate, int bufferPages) {
        this.dataDir = dataDir;
        this.asyncWal = new AsyncWalFile(walFile);
        this.bufferPool = new BufferPool(bufferPages);
        LOG.info("StorageEngine initialized: dataDir={}, bloom={}@{}/page, buffer={} pages",
                dataDir, bloomExpected, bloomFpRate, bufferPages);
    }

    // ==================== WAL（异步）====================

    public void appendWal(WalRecord record) throws IOException {
        asyncWal.append(record);
    }

    public void flushWal() throws IOException {
        asyncWal.flush();
    }

    public void flushAndTruncateWal() throws IOException {
        asyncWal.flushAndTruncate();
    }

    /** 后台 flusher 监控。 */
    public long walAppended() { return asyncWal.totalAppended(); }
    public long walFlushed() { return asyncWal.totalFlushed(); }
    public long walBatches() { return asyncWal.totalBatches(); }
    public int walQueueSize() { return asyncWal.queueSize(); }

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
                k -> new BloomFilter(DEFAULT_BLOOM_EXPECTED, DEFAULT_BLOOM_FP_RATE));
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
        BloomFilter bf = new BloomFilter(
                Math.max(points.size() * 2L, DEFAULT_BLOOM_EXPECTED),
                DEFAULT_BLOOM_FP_RATE);
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
                "StorageEngine{walAppended=%d, walFlushed=%d, walBatches=%d, walQueue=%d, "
                        + "bufferHits=%d, bufferMisses=%d, bufferEvictions=%d, bufferHitRate=%.2f%%, "
                        + "bufferSize=%d/%d, bloomFilters=%d}",
                asyncWal.totalAppended(), asyncWal.totalFlushed(), asyncWal.totalBatches(),
                asyncWal.queueSize(),
                bufferPool.hits(), bufferPool.misses(), bufferPool.evictions(),
                bufferPool.hitRate() * 100,
                bufferPool.size(), bufferPool.capacity(),
                bloomFilters.size());
    }

    // ==================== 关闭 ====================

    @Override
    public void close() throws IOException {
        flushDirtyPages();
        asyncWal.close();
        LOG.info("StorageEngine closed. Final metrics: {}", metrics());
    }
}
