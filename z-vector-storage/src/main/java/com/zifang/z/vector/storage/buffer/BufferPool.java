package com.zifang.z.vector.storage.buffer;

import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BufferPool — LRU 页面缓存，避免每次都走磁盘。
 * <p>
 * 设计参考 RocksDB 的 BlockCache + PostgreSQL 的 Buffer Pool：
 * <ul>
 *   <li>固定容量（页数）；</li>
 *   <li>命中：直接返回内存中页面；</li>
 *   <li>未命中：从 {@link PageStore} 读取，加载到缓存（必要时驱逐）；</li>
 *   <li>脏页：调用 {@link #markDirty(PageId)} 标记，下次 {@link #flushDirty()} 写回；</li>
 *   <li>驱逐策略：LRU（访问顺序）；</li>
 *   <li>写策略：{@link #flushDirty()} 由调用方触发（避免持有写锁太久）。</li>
 * </ul>
 *
 * <h2>并发模型</h2>
 * <ul>
 *   <li>{@link #fetch(PageId, PageStore)}：可重入读，可并发；</li>
 *   <li>{@link #markDirty(PageId)} / {@link #flushDirty()}：内部加锁，串行。</li>
 * </ul>
 */
public class BufferPool {

    private static final Logger LOG = LoggerFactory.getLogger(BufferPool.class);

    /** 默认容量：256 页（256 × 64KB = 16MB）。 */
    public static final int DEFAULT_CAPACITY_PAGES = 256;

    private final int capacityPages;
    private final Map<PageId, Page> cache;
    private final Object lock = new Object();

    // ==================== 监控指标 ====================
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong flushes = new AtomicLong(0);

    public BufferPool() {
        this(DEFAULT_CAPACITY_PAGES);
    }

    public BufferPool(int capacityPages) {
        if (capacityPages <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacityPages = capacityPages;
        // accessOrder=true：LinkedHashMap 按访问顺序，命中时自动移到尾部
        this.cache = new LinkedHashMap<>(capacityPages + 16, 0.75f, true);
    }

    /** 缓存容量（页数）。 */
    public int capacity() { return capacityPages; }

    /** 当前缓存页数。 */
    public int size() {
        synchronized (lock) {
            return cache.size();
        }
    }

    /** 命中次数。 */
    public long hits() { return hits.get(); }
    /** 未命中次数。 */
    public long misses() { return misses.get(); }
    /** 驱逐次数。 */
    public long evictions() { return evictions.get(); }
    /** 写盘次数。 */
    public long flushes() { return flushes.get(); }
    /** 命中率（[0, 1]）。 */
    public double hitRate() {
        long h = hits.get(), m = misses.get();
        long total = h + m;
        return total == 0 ? 0.0 : (double) h / total;
    }

    // ==================== 核心 API ====================

    /**
     * 获取一个 page（如果缓存命中则直接返回，否则从 PageStore 加载）。
     *
     * @param id   PageId
     * @param store 对应的 PageStore
     * @return Page 对象（不可变）
     */
    public Page fetch(PageId id, PageStore store) throws IOException {
        synchronized (lock) {
            Page cached = cache.get(id);
            if (cached != null) {
                hits.incrementAndGet();
                return cached;
            }
        }
        // 缓存未命中：从磁盘加载（不在锁内，避免 IO 阻塞其他读）
        misses.incrementAndGet();
        Page loaded = store.read(id);
        synchronized (lock) {
            // 双检查：可能在加载过程中已被其他线程加入
            Page existing = cache.get(id);
            if (existing != null) {
                return existing;
            }
            // 容量检查 + LRU 驱逐
            evictIfNeeded();
            cache.put(id, loaded);
            return loaded;
        }
    }

    /** 直接把 page 放入缓存（不读磁盘，用于刚写入的 page）。 */
    public void put(Page page) {
        synchronized (lock) {
            evictIfNeeded();
            cache.put(page.id(), page);
        }
    }

    /** 标记一个缓存中的 page 为脏（需要写盘）。 */
    public void markDirty(PageId id) {
        synchronized (lock) {
            Page p = cache.get(id);
            if (p != null) {
                p.dirty = true;
            }
        }
    }

    /**
     * 把所有 dirty 页写回 PageStore。
     * <p>
     * 写完后清 dirty 标记并递增 flushes 计数器。返回写回的页数。
     * <p>
     * <b>注意</b>：调用方需保证 PageStore 与 PageId 来自同一个集合。
     *
     * @param writer 处理 dirty page 的回调（典型用法：调 PageStore.write）
     */
    public int flushDirty(java.util.function.Consumer<Page> writer) {
        int n = 0;
        synchronized (lock) {
            for (Map.Entry<PageId, Page> e : cache.entrySet()) {
                Page p = e.getValue();
                if (p.dirty) {
                    writer.accept(p);
                    p.dirty = false;
                    n++;
                }
            }
        }
        if (n > 0) flushes.addAndGet(n);
        return n;
    }

    /** 清空缓存（关闭 store 时调用）。 */
    public void clear() {
        synchronized (lock) {
            cache.clear();
        }
    }

    /** 驱逐指定 page（write-back 后再 evict）。 */
    public void invalidate(PageId id) {
        synchronized (lock) {
            cache.remove(id);
        }
    }

    // ==================== 内部 ====================

    /**
     * 容量满时驱逐最久未访问的 page。
     * <p>
     * 当前简单实现：直接驱逐，不写回（write-back 由调用方显式触发）。
     * 如果是 dirty，警告用户数据可能丢失。
     */
    private void evictIfNeeded() {
        while (cache.size() >= capacityPages) {
            // 找到最久未访问的（LinkedHashMap 自动按访问顺序排序）
            java.util.Iterator<Map.Entry<PageId, Page>> it = cache.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<PageId, Page> eldest = it.next();
            if (eldest.getValue().dirty) {
                LOG.warn("Evicting dirty page {} (data may be lost — call flushDirty() first)",
                        eldest.getKey());
            }
            it.remove();
            evictions.incrementAndGet();
        }
    }
}
