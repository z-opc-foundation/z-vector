package com.zifang.z.vector.storage.page;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MmapPageReader — 把 PageStore 文件以 read-only mmap 方式加载到内存（按页对齐直接切片）。
 * <p>
 * 设计动机：
 * <ul>
 *   <li>冷读：传统 read() 每次都要 {@code open+seek+readFully+close}，单次开销 ~ms 级；</li>
 *   <li>热读：mmap 后访问等同内存读（~us 级），且内核会按页 prefetch；</li>
 *   <li>崩溃恢复：snapshot 加载大量 page 时，mmap 可以把整个文件一次性映射，避免 N 次 seek；</li>
 *   <li>写后失效：写完一帧后 mmap 视图自动反映新内容（依赖 OS mmap coherence），无需显式 reload。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <pre>
 *   open()      — 首次访问触发，把整个文件 mmap 到 {@link MappedByteBuffer}
 *   read(id)    — 直接切片访问，无系统调用
 *   invalidate  — 写后调用，丢弃旧 buffer（GC 释放底层内存）
 *   close()     — 显式释放
 * </pre>
 *
 * <h2>与 PageStore 的集成</h2>
 * 通过 {@code PageStore.useMmap(true)} 启用；之后 read() 走 mmap 路径，
 * write() 会自动 invalidate 旧 mmap 视图。
 *
 * <h2>mmap 释放</h2>
 * Java 标准 API 没有 unmap；这里通过反射调用 {@code sun.misc.Cleaner.clean()} 主动释放，
 * 否则只能等 GC + System.gc()，可能拖到 Full GC 才归还内存。
 */
public class MmapPageReader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MmapPageReader.class);

    private final Path path;
    private final int pageSize;
    private final AtomicReference<MappedByteBuffer> ref = new AtomicReference<>();

    public MmapPageReader(Path path, int pageSize) {
        this.path = path;
        this.pageSize = pageSize;
    }

    /**
     * 读取指定页（mmap 路径）。
     * <p>
     * 如果文件还没 mmap，首次调用会触发 mmap；后续调用直接切片。
     */
    public Page read(PageId id) throws IOException {
        MappedByteBuffer buf = ref.get();
        if (buf == null) {
            buf = openBuffer();
        }
        long offset = (long) id.pageNo() * pageSize;
        if (offset < 0 || offset + pageSize > buf.capacity()) {
            throw new IOException("Page out of range: pageNo=" + id.pageNo()
                    + " (file mapped=" + buf.capacity() + ")");
        }
        // bulk get：MappedByteBuffer.get(byte[], offset, length) 比逐字节循环快 ~10x
        // 使用 duplicate() 让每个线程持有独立的 position（线程安全）
        byte[] slice = new byte[pageSize];
        java.nio.ByteBuffer view = buf.duplicate();
        view.position((int) offset);
        view.get(slice, 0, pageSize);
        return Page.deserialize(slice, pageSize);
    }

    private synchronized MappedByteBuffer openBuffer() throws IOException {
        MappedByteBuffer existing = ref.get();
        if (existing != null) return existing;
        if (!Files.exists(path)) {
            throw new IOException("PageStore file not found: " + path);
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = ch.size();
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            ref.set(buf);
            LOG.info("MmapPageReader opened: path={}, size={}B ({} pages)",
                    path, size, size / pageSize);
            return buf;
        } catch (IOException e) {
            throw new IOException("Failed to mmap " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * 丢弃当前 mmap 视图（写后调用，让后续 read() 重新 mmap 反映新内容）。
     */
    public synchronized void invalidate() {
        MappedByteBuffer buf = ref.getAndSet(null);
        if (buf != null) {
            unmap(buf);
            LOG.debug("MmapPageReader invalidated: path={}", path);
        }
    }

    /** 当前 mmap 是否仍有效。 */
    public boolean isValid() {
        return ref.get() != null;
    }

    @Override
    public synchronized void close() {
        invalidate();
    }

    /**
     * 主动释放 mmap 内存（sun.misc.Cleaner.clean()），避免依赖 GC。
     */
    private static void unmap(MappedByteBuffer buf) {
        if (buf == null) return;
        try {
            // Java 8/11: sun.misc.Cleaner
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Method cleanerMethod = buf.getClass().getMethod("cleaner");
            cleanerMethod.setAccessible(true);
            Object cleaner = cleanerMethod.invoke(buf);
            if (cleaner != null) {
                Method cleanMethod = cleaner.getClass().getMethod("clean");
                cleanMethod.setAccessible(true);
                cleanMethod.invoke(cleaner);
            }
        } catch (Throwable t) {
            // 某些 JDK 版本可能没有 sun.misc.Cleaner；失败时回退到依赖 GC
            LOG.debug("Failed to unmap buffer via reflection: {}", t.getMessage());
        }
    }
}