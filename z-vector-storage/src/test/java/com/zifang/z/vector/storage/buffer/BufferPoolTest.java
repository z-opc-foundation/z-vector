package com.zifang.z.vector.storage.buffer;

import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageStore;
import com.zifang.z.vector.storage.page.PageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BufferPool 单元测试 — 验证 LRU 缓存、命中/未命中、驱逐、写回。
 */
class BufferPoolTest {

    @TempDir
    Path tmpDir;

    @Test
    void fetchMissThenHit() throws IOException {
        BufferPool pool = new BufferPool(16);
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "hello".getBytes()));

        // 首次 fetch: 缓存未命中
        Page p1 = pool.fetch(id, store);
        assertEquals(0, pool.hits());
        assertEquals(1, pool.misses());

        // 第二次 fetch: 缓存命中
        Page p2 = pool.fetch(id, store);
        assertEquals(1, pool.hits());
        assertEquals(1, pool.misses());
        assertSame(p1, p2, "Cached page should be the same instance");
    }

    @Test
    void lruEvictionAtCapacity() throws IOException {
        BufferPool pool = new BufferPool(3);
        PageStore store = new PageStore(tmpDir.toString(), "test");

        // 写入 4 个 page
        PageId[] ids = new PageId[4];
        for (int i = 0; i < 4; i++) {
            ids[i] = PageId.of("test", PageType.DATA, i);
            store.write(new Page(ids[i], ("p" + i).getBytes()));
        }
        // fetch 4 个，触发 LRU 驱逐
        for (PageId id : ids) pool.fetch(id, store);
        assertEquals(3, pool.size(), "Cache should hold only 3 pages");
        assertEquals(1, pool.evictions(), "One eviction should have happened");
    }

    @Test
    void lruReordersOnAccess() throws IOException {
        BufferPool pool = new BufferPool(3);
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId[] ids = new PageId[4];
        for (int i = 0; i < 4; i++) {
            ids[i] = PageId.of("test", PageType.DATA, i);
            store.write(new Page(ids[i], ("p" + i).getBytes()));
        }
        // 顺序 fetch 0, 1, 2 → 缓存 [0, 1, 2]
        pool.fetch(ids[0], store);
        pool.fetch(ids[1], store);
        pool.fetch(ids[2], store);
        // 再次访问 0（提升到最新）→ 缓存 [1, 2, 0]
        pool.fetch(ids[0], store);
        // fetch 3 → 应当驱逐 1（最久未访问）
        pool.fetch(ids[3], store);

        // 0 仍在缓存中
        Page p0 = pool.fetch(ids[0], store);
        assertNotNull(p0);
        // 1 已被驱逐（需要重新从磁盘加载）
        // 验证：再次 fetch 1 应是 misses++ 而非 hits++
        long missesBefore = pool.misses();
        pool.fetch(ids[1], store);
        assertEquals(missesBefore + 1, pool.misses());
    }

    @Test
    void putBypassesDisk() throws IOException {
        BufferPool pool = new BufferPool(8);
        Page page = new Page(PageId.of("t", PageType.DATA, 0), "mem-only".getBytes());
        pool.put(page);
        assertEquals(1, pool.size());
        assertSame(page, pool.fetch(page.id(), null));
    }

    @Test
    void markDirtyAndFlushDirty() throws IOException {
        BufferPool pool = new BufferPool(8);
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        Page p = new Page(id, "dirty-data".getBytes());
        p.dirty = true;
        pool.put(p);

        // 把所有 dirty 页写回（用 writer 函数）
        Set<PageId> written = new HashSet<>();
        int n = pool.flushDirty(page -> {
            // 模拟真实场景：写回 PageStore
            written.add(page.id());
        });
        assertEquals(1, n);
        assertEquals(1, pool.flushes());
        assertTrue(written.contains(id));
        // dirty 标记被清除
        assertFalse(p.dirty);
    }

    @Test
    void clearRemovesAllEntries() throws IOException {
        BufferPool pool = new BufferPool(4);
        PageStore store = new PageStore(tmpDir.toString(), "test");
        for (int i = 0; i < 3; i++) {
            PageId id = PageId.of("test", PageType.DATA, i);
            store.write(new Page(id, ("p" + i).getBytes()));
            pool.fetch(id, store);
        }
        assertEquals(3, pool.size());
        pool.clear();
        assertEquals(0, pool.size());
    }

    @Test
    void invalidateRemovesSingleEntry() throws IOException {
        BufferPool pool = new BufferPool(4);
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "x".getBytes()));
        pool.fetch(id, store);
        assertEquals(1, pool.size());
        pool.invalidate(id);
        assertEquals(0, pool.size());
    }

    @Test
    void hitRateCalculatedCorrectly() throws IOException {
        BufferPool pool = new BufferPool(8);
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "x".getBytes()));

        // 1 miss, 5 hits
        pool.fetch(id, store);
        for (int i = 0; i < 5; i++) pool.fetch(id, store);
        assertEquals(5.0 / 6.0, pool.hitRate(), 1e-6);
    }

    @Test
    void emptyPoolHitRateIsZero() {
        BufferPool pool = new BufferPool(8);
        assertEquals(0.0, pool.hitRate(), 1e-6);
    }
}
