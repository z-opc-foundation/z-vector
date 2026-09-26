package com.zifang.z.vector.storage.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageStore 单元测试 — 验证页面读写的正确性、CRC 校验、页号分配。
 */
class PageStoreTest {

    @TempDir
    Path tmpDir;

    @Test
    void writeAndReadRoundtrip() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        byte[] payload = "hello world".getBytes();
        Page page = new Page(id, payload);
        store.write(page);

        Page read = store.read(id);
        assertEquals(id, read.id());
        assertEquals(payload.length, read.payloadLen());
        assertArrayEquals(payload, read.payload());
    }

    @Test
    void overwriteExistingPage() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "v1".getBytes()));
        store.write(new Page(id, "v2 longer payload".getBytes()));

        Page read = store.read(id);
        assertArrayEquals("v2 longer payload".getBytes(), read.payload());
    }

    @Test
    void multiplePagesIndependent() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        for (int i = 0; i < 10; i++) {
            PageId id = PageId.of("test", PageType.DATA, i);
            byte[] payload = ("page_" + i + "_" + new Random(i).nextInt()).getBytes();
            store.write(new Page(id, payload));
        }
        for (int i = 0; i < 10; i++) {
            PageId id = PageId.of("test", PageType.DATA, i);
            Page p = store.read(id);
            assertEquals(id, p.id());
        }
    }

    @Test
    void listPageNosReturnsAll() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        Set<Integer> written = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            int no = i * 2; // 0, 2, 4, 6, 8 — 稀疏
            PageId id = PageId.of("test", PageType.DATA, no);
            store.write(new Page(id, ("x" + no).getBytes()));
            written.add(no);
        }
        Set<Integer> listed = store.listPageNos();
        assertEquals(written, listed);
    }

    @Test
    void pageIdCollectionMismatchThrows() throws IOException {
        PageStore store1 = new PageStore(tmpDir.toString(), "a");
        PageStore store2 = new PageStore(tmpDir.toString(), "b");
        // store1 写一个页面，page id 来自 store2 应被拒绝
        PageId wrong = PageId.of("b", PageType.DATA, 0);
        assertThrows(IllegalArgumentException.class, () -> store1.write(new Page(wrong, "x".getBytes())));
    }

    @Test
    void crcDetectsCorruption() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        byte[] payload = "important data".getBytes();
        Page page = new Page(id, payload);
        byte[] serialized = page.serialize();
        // 手动破坏 payload 区域
        serialized[20] ^= 0xFF;
        assertThrows(IllegalArgumentException.class,
                () -> Page.deserialize(serialized, Page.DEFAULT_PAGE_SIZE));
    }

    /**
     * 页头里的 payloadLen 是从磁盘读来的字节，没校验过就不能当数组长度用。
     * <p>
     * 这里构造一份 "CRC 自洽、但 payloadLen 声称整页都是数据" 的页：CRC 按改写后的头重算过，
     * 所以 CRC 那道关一定放行 —— 能拦住它的只有 payloadLen 边界检查。少了它，两条反序列化
     * 路径会分别抛 BufferUnderflowException / IndexOutOfBoundsException，把"页头坏了"
     * 报成一个看不出所以然的运行时异常。
     */
    @Test
    void payloadLenBeyondPageRejected() {
        PageId id = PageId.of("test", PageType.DATA, 0);
        int pageSize = Page.DEFAULT_PAGE_SIZE;
        byte[] forged = new Page(id, "real payload".getBytes()).serialize();
        writeInt(forged, 13, pageSize);            // payloadLen = 整页，超过 pageSize-HEADER-CRC
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(forged, 0, pageSize - Page.CRC_SIZE);
        writeInt(forged, pageSize - Page.CRC_SIZE, (int) crc.getValue());

        java.nio.ByteBuffer view = java.nio.ByteBuffer.wrap(forged);
        assertThrows(IllegalArgumentException.class, () -> Page.deserialize(forged, pageSize),
                "byte[] parser trusted an out-of-range payloadLen");
        assertThrows(IllegalArgumentException.class, () -> Page.deserialize(view, pageSize),
                "ByteBuffer parser trusted an out-of-range payloadLen");
    }

    private static void writeInt(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >>> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    @Test
    void readNonexistentFileThrows() {
        PageStore store = new PageStore(tmpDir.toString(), "missing");
        PageId id = PageId.of("missing", PageType.DATA, 0);
        assertThrows(IOException.class, () -> store.read(id));
    }

    @Test
    void deleteRemovesFile() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "data".getBytes()));
        assertTrue(java.nio.file.Files.exists(store.file()));
        store.delete();
        assertFalse(java.nio.file.Files.exists(store.file()));
    }

    @Test
    void largePayloadSurvives() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        PageId id = PageId.of("test", PageType.DATA, 0);
        // 50KB payload（接近 64KB page size 上限）
        Random r = new Random(42);
        byte[] payload = new byte[50_000];
        r.nextBytes(payload);
        store.write(new Page(id, payload));
        Page read = store.read(id);
        assertArrayEquals(payload, read.payload());
    }

    @Test
    void diskSizeReflectsContent() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), "test");
        assertEquals(0, store.diskSize());
        PageId id = PageId.of("test", PageType.DATA, 0);
        store.write(new Page(id, "data".getBytes()));
        assertEquals(Page.DEFAULT_PAGE_SIZE, store.diskSize());
    }
}
