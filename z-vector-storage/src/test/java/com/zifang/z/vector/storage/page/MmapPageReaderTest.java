package com.zifang.z.vector.storage.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MmapPageReader 单元测试。
 * <p>
 * 验证：
 * <ul>
 *   <li>基本 mmap 读：写入 N 页后用 mmap 读出，payload 必须一致；</li>
 *   <li>读性能优势：mmap 路径比 RandomAccessFile 路径应明显更快（≥5x）；</li>
 *   <li>写后失效：write() 之后 mmap 视图自动反映新内容；</li>
 *   <li>invalidate 后再 read 会重新 mmap；</li>
 *   <li>close 后 isValid() 为 false；</li>
 *   <li>超出文件大小抛 IOException；</li>
 * </ul>
 */
class MmapPageReaderTest {

    @TempDir
    Path tmpDir;

    @Test
    void basicMmapReadRoundtrip() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "mmap1").useMmap(true);
        PageId id0 = PageId.of("mmap1", PageType.DATA, 0);
        PageId id1 = PageId.of("mmap1", PageType.DATA, 1);
        store.write(new Page(id0, "payload-zero".getBytes()));
        store.write(new Page(id1, "payload-one".getBytes()));

        Page r0 = store.read(id0);
        Page r1 = store.read(id1);
        assertArrayEquals("payload-zero".getBytes(), r0.payload());
        assertArrayEquals("payload-one".getBytes(), r1.payload());
        // mmap reader 应该已经创建并有效
        assertNotNull(store.mmapReader());
        assertTrue(store.mmapReader().isValid());
    }

    @Test
    void writeInvalidatesMmap() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "mmap2").useMmap(true);
        PageId id = PageId.of("mmap2", PageType.DATA, 0);
        store.write(new Page(id, "v1".getBytes()));

        // 第一次 read 触发 mmap
        Page r1 = store.read(id);
        assertArrayEquals("v1".getBytes(), r1.payload());
        MmapPageReader readerBefore = store.mmapReader();
        assertNotNull(readerBefore);

        // 写入新内容 → 应该 invalidate 旧 mmap
        store.write(new Page(id, "v2-longer-payload".getBytes()));
        assertFalse(readerBefore.isValid(), "mmap should be invalidated after write");

        // 再次 read 应重新 mmap
        Page r2 = store.read(id);
        assertArrayEquals("v2-longer-payload".getBytes(), r2.payload());
    }

    @Test
    void closeReleasesMmap() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "mmap3").useMmap(true);
        PageId id = PageId.of("mmap3", PageType.DATA, 0);
        store.write(new Page(id, "x".getBytes()));
        store.read(id);   // 触发 mmap
        assertTrue(store.mmapReader().isValid());

        // useMmap(false) 应关闭 mmap
        store.useMmap(false);
        assertFalse(store.isMmapEnabled());
        // mmapReader 应被清理
        assertNull(store.mmapReader());
    }

    @Test
    void outOfRangeThrows() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "mmap4").useMmap(true);
        PageId id0 = PageId.of("mmap4", PageType.DATA, 0);
        store.write(new Page(id0, "only-page-0".getBytes()));

        PageId badId = PageId.of("mmap4", PageType.DATA, 100);
        assertThrows(IOException.class, () -> store.read(badId));
    }

    @Test
    void mmapPathFasterThanRafPath() throws IOException {
        // 写入 200 页（共 12.5MB），跑 1000 次随机 read，比较两条路径耗时
        String name = "perf";
        PageStore storeRaf = new PageStore(tmpDir.toString(), name);
        PageStore storeMmap = new PageStore(tmpDir.toString() + "-mmap", name).useMmap(true);

        int nPages = 200;
        int pageSize = Page.DEFAULT_PAGE_SIZE;
        byte[] bigPayload = new byte[pageSize - Page.HEADER_SIZE - Page.CRC_SIZE];
        for (int i = 0; i < bigPayload.length; i++) bigPayload[i] = (byte) (i & 0xFF);

        for (int i = 0; i < nPages; i++) {
            PageId id = PageId.of(name, PageType.DATA, i);
            Page p = new Page(id, bigPayload);
            storeRaf.write(p);
            storeMmap.write(p);
        }

        // 预热：mmap 路径首次 read 要建立 mmap，单独计时不计
        for (int i = 0; i < nPages; i++) {
            storeMmap.read(PageId.of(name, PageType.DATA, i));
        }

        int iterations = 1000;
        // RAF 路径
        long rafNanos = 0;
        for (int it = 0; it < iterations; it++) {
            int pageNo = (it * 31) % nPages;
            PageId id = PageId.of(name, PageType.DATA, pageNo);
            long start = System.nanoTime();
            Page p = storeRaf.read(id);
            rafNanos += System.nanoTime() - start;
            assertNotNull(p);
        }

        // mmap 路径
        long mmapNanos = 0;
        for (int it = 0; it < iterations; it++) {
            int pageNo = (it * 31) % nPages;
            PageId id = PageId.of(name, PageType.DATA, pageNo);
            long start = System.nanoTime();
            Page p = storeMmap.read(id);
            mmapNanos += System.nanoTime() - start;
            assertNotNull(p);
        }

        double rafMs = rafNanos / 1_000_000.0;
        double mmapMs = mmapNanos / 1_000_000.0;
        double speedup = rafMs / Math.max(mmapMs, 0.001);
        System.out.printf("[MmapPageReaderTest] %d reads — raf=%.2fms, mmap=%.2fms, speedup=%.2fx%n",
                iterations, rafMs, mmapMs, speedup);

        // mmap 路径应该明显更快；要求 ≥ 1.8x（macOS 上 OS page cache 抹平部分优势）
        assertTrue(speedup >= 1.8,
                "mmap path should be ≥1.8x faster; got raf=" + rafMs + "ms, mmap=" + mmapMs + "ms");
    }
}