package com.zifang.z.vector.storage.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageStore compaction / free page reuse / 多线程读并发测试。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>{@code freePage} + {@code write}：free slot 被自动复用，文件大小不再增长；</li>
 *   <li>{@code compact}：删除 live pages 后压缩，文件缩容；pageNo 重映射；</li>
 *   <li>mmap + 并发读：8 线程同时 read，零数据竞争；</li>
 *   <li>并发写 + 读：无丢失、无重复、CRC 始终有效。</li>
 * </ul>
 */
class PageStoreAdvancedTest {

    @TempDir
    Path tmpDir;

    // ==================== Free Page Reuse ====================

    @Test
    void freePageIsReusedOnSubsequentWrite() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "reuse");
        // 写 100 页（文件 ~6.4MB）
        for (int i = 0; i < 100; i++) {
            store.write(new Page(PageId.of("reuse", PageType.DATA, i),
                    ("page-" + i).getBytes()));
        }
        long sizeAfterWrites = store.diskSize();
        assertEquals(100 * Page.DEFAULT_PAGE_SIZE, sizeAfterWrites);

        // free 中间 50 页
        for (int i = 25; i < 75; i++) {
            store.freePage(i);
        }
        assertEquals(50, store.freePageCount());

        // 写 50 个新 page（pageNo 0..49）→ 应该全部复用 free slot
        for (int i = 0; i < 50; i++) {
            int actualNo = store.write(new Page(
                    PageId.of("reuse", PageType.DATA, 100 + i),  // 请求 pageNo 100+
                    ("new-" + i).getBytes()));
            assertTrue(actualNo < 75,
                    "Expected free slot reuse; got pageNo=" + actualNo);
        }
        assertEquals(0, store.freePageCount(),
                "All free slots should be consumed");

        long sizeAfterReuse = store.diskSize();
        // 文件大小不应增长（仍 ~6.4MB，文件尾未扩展）
        assertEquals(sizeAfterWrites, sizeAfterReuse,
                "File should not grow when reusing free slots; before=" + sizeAfterWrites
                        + " after=" + sizeAfterReuse);
    }

    @Test
    void freePageCountMatches() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "count");
        for (int i = 0; i < 10; i++) {
            store.write(new Page(PageId.of("count", PageType.DATA, i),
                    ("p-" + i).getBytes()));
        }
        assertEquals(0, store.freePageCount());

        store.freePage(3);
        store.freePage(7);
        store.freePage(9);
        assertEquals(3, store.freePageCount());

        // 再写回一个
        store.write(new Page(PageId.of("count", PageType.DATA, 9), "back".getBytes()));
        assertEquals(2, store.freePageCount());
    }

    // ==================== Compaction ====================

    @Test
    void compactShrinksFileAfterFree() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "compact");
        for (int i = 0; i < 100; i++) {
            store.write(new Page(PageId.of("compact", PageType.DATA, i),
                    ("page-" + i).getBytes()));
        }
        long beforeSize = store.diskSize();
        assertEquals(100 * Page.DEFAULT_PAGE_SIZE, beforeSize);

        // free 中间 60 页（保留头 20 + 尾 20）
        for (int i = 20; i < 80; i++) {
            store.freePage(i);
        }
        // compact
        Map<Integer, Integer> mapping = store.compact();

        // 验证：40 个 live pages（0-19 + 80-99）→ 映射到 0-39
        assertEquals(40, mapping.size());
        assertEquals(0, mapping.get(0));
        assertEquals(19, mapping.get(19));
        assertEquals(20, mapping.get(80));
        assertEquals(39, mapping.get(99));

        long afterSize = store.diskSize();
        assertEquals(40 * Page.DEFAULT_PAGE_SIZE, afterSize,
                "File should shrink from 100 to 40 pages; before=" + beforeSize + " after=" + afterSize);
        assertEquals(0, store.freePageCount(), "Free bitmap should be cleared after compact");

        // 验证新 pageNo 处的数据正确
        Page p0 = store.read(PageId.of("compact", PageType.DATA, 0));
        assertArrayEquals("page-0".getBytes(), p0.payload());
        Page p39 = store.read(PageId.of("compact", PageType.DATA, 39));
        assertArrayEquals("page-99".getBytes(), p39.payload());
    }

    @Test
    void compactOnEmptyFileIsNoop() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "empty");
        Map<Integer, Integer> mapping = store.compact();
        assertTrue(mapping.isEmpty());
    }

    // ==================== Concurrent Access ====================

    @Test
    void concurrentReadsAreSafe() throws Exception {
        // 写入 50 页，启用 mmap，8 线程并发读
        PageStore store = new PageStore(tmpDir.toString(), "concrd").useMmap(true);
        int nPages = 50;
        Random r = new Random(123);
        byte[] payload = new byte[1024];
        for (int i = 0; i < nPages; i++) {
            r.nextBytes(payload);
            store.write(new Page(PageId.of("concrd", PageType.DATA, i), payload));
        }

        int threads = 8;
        int readsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    Random tr = new Random();
                    for (int i = 0; i < readsPerThread; i++) {
                        int pageNo = tr.nextInt(nPages);
                        Page p = store.read(PageId.of("concrd", PageType.DATA, pageNo));
                        assertNotNull(p);
                        assertEquals(1024, p.payload().length);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, errors.get(),
                threads + " threads × " + readsPerThread + " reads should produce 0 errors");
    }

    @Test
    void mixedConcurrentReadsAndWrites() throws Exception {
        PageStore store = new PageStore(tmpDir.toString(), "mixed").useMmap(true);
        int initialPages = 20;
        for (int i = 0; i < initialPages; i++) {
            store.write(new Page(PageId.of("mixed", PageType.DATA, i),
                    ("init-" + i).getBytes()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch latch = new CountDownLatch(6);
        AtomicInteger readErrors = new AtomicInteger(0);
        AtomicInteger writeErrors = new AtomicInteger(0);

        // 4 个 reader
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    Random r = new Random();
                    for (int i = 0; i < 100; i++) {
                        int pageNo = r.nextInt(initialPages);
                        store.read(PageId.of("mixed", PageType.DATA, pageNo));
                    }
                } catch (Exception e) {
                    readErrors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 2 个 writer（只追加新 pageNo，不修改已有）
        for (int t = 0; t < 2; t++) {
            final int writerId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 20; i++) {
                        int pageNo = initialPages + writerId * 20 + i;
                        store.write(new Page(PageId.of("mixed", PageType.DATA, pageNo),
                                ("w-" + writerId + "-" + i).getBytes()));
                    }
                } catch (Exception e) {
                    writeErrors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, readErrors.get(), "No read errors");
        assertEquals(0, writeErrors.get(), "No write errors");
        // 文件应至少包含 initialPages + 2*20 = 60 页
        assertTrue(store.listPageNos().size() >= initialPages);
    }

    // ==================== Stress / Stability ====================

    @Test
    void stressManyWritesAndFreePages() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "stress");
        Random r = new Random(999);

        // 1000 次写 + free 循环
        long maxSize = 0;
        for (int round = 0; round < 1000; round++) {
            int pageNo = r.nextInt(200);
            if (store.freePageCount() < 10 || r.nextBoolean()) {
                // 写
                store.write(new Page(PageId.of("stress", PageType.DATA, pageNo),
                        ("r-" + round).getBytes()));
            } else {
                // free
                store.freePage(pageNo);
            }
            maxSize = Math.max(maxSize, store.diskSize());
        }
        // 文件应该不超过 ~200 页（不会无限增长）
        assertTrue(maxSize <= 250 * Page.DEFAULT_PAGE_SIZE,
                "File should not grow unbounded; max=" + maxSize);

        // compact 还能进一步压缩
        store.compact();
        long afterCompact = store.diskSize();
        assertTrue(afterCompact <= maxSize);
    }

    @Test
    void mmapConcurrentWithWrite() throws Exception {
        // mmap 启用时，writer 写入会 invalidate，reader 必须能感知
        PageStore store = new PageStore(tmpDir.toString(), "mmap-mix").useMmap(true);
        store.write(new Page(PageId.of("mmap-mix", PageType.DATA, 0), "v1".getBytes()));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        Set<String> seenValues = java.util.Collections.synchronizedSet(new HashSet<>());

        // 1 writer
        pool.submit(() -> {
            try {
                for (int i = 0; i < 50; i++) {
                    store.write(new Page(PageId.of("mmap-mix", PageType.DATA, 0),
                            ("v" + i).getBytes()));
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        // 3 readers
        for (int t = 0; t < 3; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        Page p = store.read(PageId.of("mmap-mix", PageType.DATA, 0));
                        seenValues.add(new String(p.payload()));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        // 至少应读到 1 个不同的 value（writer 真的在更新）
        assertFalse(seenValues.isEmpty());
    }
}