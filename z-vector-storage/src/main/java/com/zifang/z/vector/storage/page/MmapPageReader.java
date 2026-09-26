package com.zifang.z.vector.storage.page;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 *   <li>写后可见：同一个 inode 的页缓存是连贯的 —— 别的 fd 用 {@code RandomAccessFile} 写过的
 *       字节，这块只读映射当场就能读到（250 / JDK 8 实测：同一映射、改前 23808、改后 0），
 *       所以写**已有页**不需要重映射；只有文件变长超出 capacity 时才必须重映射。</li>
 * </ul>
 *
 * <h2>生命周期：换视图 = 只丢引用，绝不自己 unmap</h2>
 * <pre>
 *   read(id)    — 直接切片访问；没有映射时首次访问触发 mmap
 *   invalidate  — 写/替换文件后调用，把当前视图从 {@link #ref} 摘掉（下一次 read 重新 mmap）
 *   close()     — 同 invalidate
 * </pre>
 * 被摘掉的映射不做任何显式释放，等它不可达之后由 JVM 自己的 {@code Cleaner} 归还地址段。
 * <p>
 * <b>为什么不主动 unmap</b>：Java 标准 API 没有 unmap，只能反射调 {@code Cleaner.clean()}，
 * 而 {@code duplicate()} 出来的视图与父映射共用同一段地址 —— 父对象一 clean，别人手里的视图
 * 立刻指向已 unmap 的地址，读到的是 SIGSEGV 而不是异常，整个进程（跑测试时就是 surefire 的 fork）
 * 当场没了。实测（250 / 1.8.0_362）：{@code duplicate()} 后 clean 父映射再读那个视图 ⇒ 退出码 134，
 * hs_err 里 {@code SIGSEGV (SEGV_MAPERR)}，栈 {@code Unsafe.copyMemory ← DirectByteBuffer.get([BII)
 * ← Page.deserialize ← MmapPageReader.read}。
 * <p>
 * <b>为什么也不"引用计数 + 等读者散尽再 clean"</b>（这里试过，被实测否掉了）：计数看着严丝合缝
 * ——先 {@code readers++} 再取 {@code ref}，retire 方就能看到计数、把 clean 推迟。但同一份代码在
 * 250 上跑 800 次混合读写会稳定暴露两件事：① 待释放队列里会出现"还挂在 {@code ref} 上、随时会被
 * 新读者拿走"的映射（每次 run 20—36 次 {@code alreadyQueued=true}），拆它就是拆别人正在读的那块；
 * ② 写密集下 {@code readers} 几乎不归零，队列里的映射根本没人拆 —— 一轮探针就攒了 13 块 × ~4MB。
 * 而地址段会被内核回收复用（实测：{@code AddressReuseProbe} 在 250 / 1.8.0_362 上 map→clean→map
 * 六次，6/6 次拿到同一个地址；对照组"同时持有两块映射"地址必不同，所以这个 SAME 说的是 clean），
 * 所以这种"拆错人"不是偶发
 * 报错，是随机 SIGSEGV。计数要正确就得和 JIT 的存活分析、内核的地址复用抢同一件事，赢不了。
 * <p>
 * 交给 GC 是这块唯一有平台保证的做法：派生视图会把它依赖的父映射一起钉住（实测：父对象显式丢弃 +
 * 8 次 GC 之后，由它 duplicate 出来的视图读到的字节和丢弃前一模一样）。代价是地址段不再"立刻"还，
 * 换成不会拆错人 —— 这个取舍由 {@code MmapPageReaderLifetimeTest} 的并发尺和一条结构守卫钉住
 * （守卫：本类一旦重新长出 unmap/clean 之类的方法就先红）。
 * <p>
 * 不显式 unmap 不等于 invalidate 可以省：{@code PageStore.compact()} 用 {@code ATOMIC_MOVE}
 * 换了 inode，旧映射会一直读到旧 inode 的陈旧字节（实测），所以换文件之后仍然必须丢掉视图。
 * <p>
 * 读到的 {@link Page} 已经把 payload 拷进自己的 {@code byte[]}，不会在 read() 返回后还指着映射
 * （{@code MmapPageReaderLifetimeTest.returnedPageDoesNotAliasTheMapping} 钉的就是这个前提）。
 */
public class MmapPageReader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MmapPageReader.class);

    private final Path path;
    private final int pageSize;

    /** 当前视图；{@code null} 表示还没 mmap 或已经被 {@link #invalidate()} 丢掉。 */
    private final AtomicReference<MappedByteBuffer> ref = new AtomicReference<>();

    public MmapPageReader(Path path, int pageSize) {
        this.path = path;
        this.pageSize = pageSize;
    }

    /**
     * 读取指定页（mmap 路径）。
     * <p>
     * 两种"这块映射不够用"都要重映射，不能当错误报：① 并发写刚 {@link #invalidate()} 过；
     * ② 文件在映射之后又变长（{@code PageStore.write} 会 {@code setLength} 扩容），要读的页落在
     * 旧 capacity 之外。此时旧代码会抛一句"io out of range"，而那一页其实好端端在盘上 ——
     * {@code PageStoreAdvancedTest.mixedConcurrentReadsAndWrites} 在 250 上随机报的
     * {@code No read errors ==> expected: <0> but was: <1>} 就是它。
     * <p>
     * 重映射最多试一次：新映射是按此刻的文件长度拿的，还盖不住这个页号就是真的越过文件尾。
     * 核对"拿到的还是不是同一块"是多余且有害的 —— 并发 invalidate 很密时那种核对会把读者饿成
     * 假报错（明明 capacity 够，却报 out of range）。
     */
    public Page read(PageId id) throws IOException {
        long offset = (long) id.pageNo() * pageSize;
        if (offset < 0) {
            throw new IOException("Invalid pageNo: " + id.pageNo());
        }
        boolean remapped = false;
        while (true) {
            MappedByteBuffer buf = ref.get();
            if (buf == null) buf = openBuffer();
            if (offset + pageSize > buf.capacity()) {
                if (remapped) {
                    throw new IOException("Page out of range: pageNo=" + id.pageNo()
                            + " (file mapped=" + buf.capacity() + ")");
                }
                remapped = true;
                // 只丢引用：可能还有别的读者压在这块上，它会在不可达之后被 JVM 回收。
                ref.compareAndSet(buf, null);
                continue;
            }
            // duplicate() 让每个线程持有独立的 position（线程安全），它本身只分配一个小对象；
            // 之后交给 Page.deserialize(ByteBuffer) —— 不再先拷一整页 byte[]（那是 64KB/次 read
            // 的分配，把 mmap "少拷贝" 的收益整个吃掉了）。
            java.nio.ByteBuffer view = buf.duplicate();
            view.position((int) offset);
            return Page.deserialize(view, pageSize);
        }
    }

    /**
     * 丢弃当前 mmap 视图（写后调用，让后续 read() 重新 mmap 反映新内容）。
     * <p>
     * 只是把引用摘掉，不 unmap —— 见类注释：显式 unmap 会拆掉在途读者手里的视图。
     * <p>
     * <b>{@code synchronized} 不是冗余的</b>：它和 {@link #openBuffer()} 共用一把锁，为的是排掉
     * 这个交错 —— 读者发现没有视图、正在 {@code openBuffer} 里 mmap，此时 {@code compact()}
     * 换掉 inode 并 invalidate（那会儿 {@code ref} 还是 null，摘了个空），紧接着 mmap 把
     * <b>旧 inode</b> 的映射装进 {@code ref}。那之后就没有任何东西会让它失效了：换 inode 不改变
     * capacity，于是压缩后的页号会一直读到压缩前的旧页（页号还会被复用 ⇒ 不是"读不到"是"读错"）。
     * 代价是 invalidate 可能等一次 mmap，这与换视图之前几版实现一致。
     */
    public synchronized void invalidate() {
        if (ref.getAndSet(null) != null) {
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
}
