package com.zifang.z.vector.storage.snapshot;

import com.zifang.z.vector.storage.buffer.BufferPool;
import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageStore;
import com.zifang.z.vector.storage.page.PageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageSnapshot 单元测试 — 验证增量 snapshot 的写 / 读 / loadInto 流程。
 */
class PageSnapshotTest {

    @TempDir
    Path tmpDir;

    @Test
    void writeAndReadRoundtrip() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageSnapshot psnap = new PageSnapshot(tmpDir.toString());

        // 写 3 个 page 到 PageStore
        PageId id0 = PageId.of("test", PageType.DATA, 0);
        PageId id1 = PageId.of("test", PageType.DATA, 1);
        PageId id2 = PageId.of("test", PageType.INDEX, 5);
        store.write(new Page(id0, "p0".getBytes()));
        store.write(new Page(id1, "p1".getBytes()));
        store.write(new Page(id2, "p2".getBytes()));

        // 写 psnap
        List<PageSnapshot.Entry> entries = new ArrayList<>();
        entries.add(new PageSnapshot.Entry(id0));
        entries.add(new PageSnapshot.Entry(id1));
        entries.add(new PageSnapshot.Entry(id2));
        psnap.write(entries, store);

        assertTrue(psnap.exists());

        // 读 psnap
        List<PageSnapshot.Entry> readBack = psnap.read();
        assertEquals(3, readBack.size());
        assertEquals(id0, readBack.get(0).pageId);
        assertEquals(id1, readBack.get(1).pageId);
        assertEquals(id2, readBack.get(2).pageId);
    }

    @Test
    void emptyEntriesSkipsWrite() throws IOException {
        PageSnapshot psnap = new PageSnapshot(tmpDir.toString());
        psnap.write(new ArrayList<>(), null);
        assertFalse(psnap.exists());
    }

    @Test
    void loadIntoPrePopulatesBufferPool() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageSnapshot psnap = new PageSnapshot(tmpDir.toString());
        BufferPool pool = new BufferPool(16);

        // 写 5 个 page
        List<PageSnapshot.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PageId id = PageId.of("test", PageType.DATA, i);
            store.write(new Page(id, ("p" + i).getBytes()));
            entries.add(new PageSnapshot.Entry(id));
        }
        psnap.write(entries, store);

        // 用 facade 加载到 BufferPool
        Map<Integer, PageStore> storeMap = new HashMap<>();
        storeMap.put(store.collectionId(), store);
        int loaded = psnap.loadInto(new PageSnapshot.BufferPoolFacade() {
            @Override
            public Page readPage(PageId id) throws IOException {
                return storeMap.get(id.collectionId()).read(id);
            }

            @Override
            public void cachePage(Page page) {
                pool.put(page);
            }
        });
        assertEquals(5, loaded);
        assertEquals(5, pool.size());

        // 校验：5 个 page 都能命中
        for (int i = 0; i < 5; i++) {
            PageId id = PageId.of("test", PageType.DATA, i);
            Page cached = pool.fetch(id, store);
            assertArrayEquals(("p" + i).getBytes(), cached.payload());
        }
    }

    @Test
    void magicCorruptionDetected() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageSnapshot psnap = new PageSnapshot(tmpDir.toString());

        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "x".getBytes()));
        psnap.write(java.util.Collections.singletonList(
                new PageSnapshot.Entry(id)), store);

        // 破坏 magic
        byte[] bytes = java.nio.file.Files.readAllBytes(psnap.metaPath());
        bytes[0] = (byte) (bytes[0] ^ 0xFF);
        java.nio.file.Files.write(psnap.metaPath(), bytes);

        assertThrows(IOException.class, psnap::read);
    }

    @Test
    void deleteRemovesFile() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageSnapshot psnap = new PageSnapshot(tmpDir.toString());

        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "x".getBytes()));
        psnap.write(java.util.Collections.singletonList(
                new PageSnapshot.Entry(id)), store);
        assertTrue(psnap.exists());

        psnap.delete();
        assertFalse(psnap.exists());
    }

    @Test
    void readNonexistentReturnsEmpty() throws IOException {
        PageSnapshot psnap = new PageSnapshot(tmpDir.toString());
        assertTrue(psnap.read().isEmpty());
    }

    @Test
    void largeNumberOfEntriesRoundtrips() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageSnapshot psnap = new PageSnapshot(tmpDir.toString());

        int n = 500;
        List<PageSnapshot.Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            PageId id = PageId.of("test", PageType.DATA, i);
            store.write(new Page(id, ("p" + i).getBytes()));
            entries.add(new PageSnapshot.Entry(id));
        }
        psnap.write(entries, store);

        List<PageSnapshot.Entry> readBack = psnap.read();
        assertEquals(n, readBack.size());
        for (int i = 0; i < n; i++) {
            assertEquals(entries.get(i).pageId, readBack.get(i).pageId);
        }
    }
}
