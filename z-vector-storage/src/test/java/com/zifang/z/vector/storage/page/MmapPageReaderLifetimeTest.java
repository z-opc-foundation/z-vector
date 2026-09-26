package com.zifang.z.vector.storage.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mmap 视图的生命周期 —— 钉的是两条："换视图不许把别人正在读的那段地址抽掉"，以及"视图盖不住
 * 那一页时不能当错误报"。都是 250（JDK 1.8.0_362）全量跑 {@code mvn clean test} 时暴露的：
 *
 * <ul>
 *   <li>{@code PageStoreAdvancedTest.mixedConcurrentReadsAndWrites} 随机报
 *       {@code No read errors ==> expected: <0> but was: <1>}（读盘上一定向存在的一页却拿不到），
 *       同一支在另一些轮次里<b>直接把 surefire 的 fork 打死</b>：
 *       {@code The forked VM terminated without properly saying goodbye ... Process Exit Code: 134
 *       / Crashed tests: PageStoreAdvancedTest}，hs_err 里
 *       {@code SIGSEGV (SEGV_MAPERR)}，栈是 {@code Unsafe.getByte ← DirectByteBuffer.get ←
 *       Page.readInt ← Page.deserialize ← MmapPageReader.read}。</li>
 *   <li>另一支同族缺陷是 capacity：{@code PageStore.write} 会 {@code setLength} 把文件变长，
 *       而旧映射的长度是映射那一刻的文件大小 —— 读新追加的页会撞上
 *       {@code offset + pageSize > buf.capacity()} 这句"out of range"，可那一页好端端在盘上。
 *       同一次实测还量到：别的 fd 写过的<b>已有</b>页在同一块只读映射里当场可见（页缓存连贯），
 *       所以只有"超出 capacity"这一种情况需要重映射。</li>
 * </ul>
 *
 * <p>机制不是猜的，两支独立探针量出来的（{@code ~/.cache/zv-mmap/MmapRaceProbe.java}，250 / JDK 8）：
 * ① 对一块映射 {@code duplicate()} 之后再 clean 掉那份映射（反射调 {@code sun.misc.Cleaner.clean()}），
 * 然后读那个视图 ⇒ 子进程退出码 <b>134</b> + {@code SIGSEGV}；② 反过来只把父映射丢引用交给 GC ⇒
 * 视图在读的时候<b>还活着</b>（8 次 GC 后内容逐字节相同，退出码 0）。所以"派生视图替读者保住映射"
 * 这件事只有 GC 那条路有平台保证，{@link MmapPageReader} 里因此<b>没有任何显式 unmap</b>。
 *
 * <p><b>但行为尺不是充分条件</b> —— 这一点是被实测打了脸才写进来的：修这个 bug 的过程中我先写的一版
 * 实现是"读者计数 + 等计数归零再 clean"（<b>只在工作树里存在过、从未提交</b>；提交的历史版本是
 * {@code invalidate()} 当场 {@code unmap}），它在当时那三支尺子下全绿（250：2920 次 invalidate / 1558 次读 /
 * 0 异常），可同一份代码跑 {@code MmapRaceProbe race} 仍然 rc=134。打点证据（同一棵树，4 代探针）：
 * 待释放队列里会挂着"还留在 {@code ref} 上、随时会被新读者拿走"的映射，每轮 20—36 次；写密集下
 * 计数几乎不归零，一轮探针攒了 13 块 × ~4MB 无人释放；而 unmap 之后内核会把同一段地址还给新映射
 * （实测：{@code AddressReuseProbe} 在 250 / 1.8.0_362 上 map→clean→map 六次，6/6 同地址；对照组"同时持有
 * 两块映射"地址必不同），所以"拆错人"是随机 SIGSEGV 而不是偶发报错。
 * <p>
 * 结论：并发测试抓不到这种设计，所以除行为尺之外还有两条<b>结构</b>守卫 ——
 * {@link #readerNeverOwnsAnExplicitRelease()}（这个类一旦重新长出"自己释放映射"的样子就先红）和
 * {@link #viewSwapAndInstallShareOneMonitor()}（换视图与装视图必须互斥，否则 inode 换完会被旧映射
 * 装回去）。另有一条 {@link #invalidateIsWhatMakesACompactedFileVisible()} 钉住取舍的另一半：
 * 不显式 unmap ≠ 可以不调 invalidate。
 */
class MmapPageReaderLifetimeTest {

    private static final int PAGE = Page.DEFAULT_PAGE_SIZE;
    private static final int READERS = 3;
    /**
     * churner 至少制造这么多次 invalidate（= 每次写完都会做的那一步）。
     */
    private static final int INVALIDATE_CYCLES = 2000;
    /**
     * 读者合计至少读到这么多页，否则"没抛异常"是空的。invalidate 现在只是丢个引用，比 read
     * （要重新 mmap 整个文件）快得多，所以 churner 得等读者进到位再动手，见
     * {@link #concurrentInvalidateNeverUnmapsFromUnderALiveReader()} 的注释。
     */
    private static final int MIN_TOTAL_READS = 1500;
    /** 纯保险：正常情况下循环是被上面两个配额放行的，跑到这里说明有线程没按预期动。 */
    private static final int MAX_CYCLES = 20_000;
    /** 两处因果等待的自旋上限，纯粹防"读者全死了还在这儿转"；一次 yield 通常就解除。 */
    private static final int SPIN_LIMIT = 2_000_000;

    @TempDir
    Path tmpDir;

    /** 建一个含 {@code n} 个有效页的文件，返回它的路径。写入走普通 RAF 路径（不 mmap）。 */
    private Path pages(String name, int n) throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), name);
        for (int i = 0; i < n; i++) {
            store.write(new Page(PageId.of(name, PageType.DATA, i), body(name, i)));
        }
        return store.file();
    }

    private static byte[] body(String name, int pageNo) {
        byte[] b = new byte[128];
        byte[] tag = name.getBytes();
        for (int i = 0; i < b.length; i++) b[i] = (byte) (tag[i % tag.length] + i * 31 + pageNo);
        return b;
    }

    private static PageId id(String name, int pageNo) {
        return PageId.of(name, PageType.DATA, pageNo);
    }

    /**
     * 视图比目标页短 ⇒ 重映射后读到，而不是抛"out of range"。
     * <p>
     * 改前：{@code IOException: Page out of range: pageNo=4 (file mapped=262144)} —— 而第 4 页
     * 已经落盘。这里刻意<b>不</b>经过 {@code PageStore.write}（它会顺手 invalidate），直接改文件，
     * 这样"映射过期"这一支是单独钉住的。
     */
    @Test
    void readingAPageAppendedAfterTheMappingWasTakenStillReads() throws IOException {
        String name = "mmap-grow";
        int seed = 4;
        Path file = pages(name, seed);
        MmapPageReader reader = new MmapPageReader(file, PAGE);
        assertEquals(128, reader.read(id(name, 0)).payload().length,
                "第一次 read 会把整个文件 mmap 下来（capacity = 此刻的文件长度）");
        assertTrue(reader.isValid());

        // 追加第 5 页：只改文件，不碰 reader 的视图。
        Page appended = new Page(id(name, seed), body(name, seed));
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength((long) (seed + 1) * PAGE);
            raf.seek((long) seed * PAGE);
            raf.write(appended.serialize());
            raf.getFD().sync();
        }
        assertEquals((long) (seed + 1) * PAGE, Files.size(file), "文件确实变长了");

        Page got = reader.read(id(name, seed));
        assertTrue(java.util.Arrays.equals(body(name, seed), got.payload()),
                "映射之外新追加的页必须靠重映射读到，而不是报 out of range");

        // 真的越过文件尾时仍然要报错，且不能无限重试。
        IOException e = assertThrows(IOException.class, () -> reader.read(id(name, seed + 50)));
        assertTrue(e.getMessage().contains("out of range"),
                "越过文件尾还是要报原来的错，实际: " + e.getMessage());
    }

    /**
     * 读者在映射里读，另一个线程<b>趁有读者在里面</b>的时候 invalidate：一次异常都不许有，
     * 而且进程必须活着。
     * <p>
     * 改前这一支会把 fork 打成 SIGSEGV（退出码 134）。判据只有"没红"是不够的 —— 所以
     * ① 要求读者真读到了东西（{@code MIN_TOTAL_READS} 下限，防止两侧都空跑），② 每页 payload
     * 都要对（防止"不崩但读到垃圾"），③ 收尾断言抛出的第一个异常。
     * <p>
     * <b>churner 得按读者的节奏走，两道闸各管一半</b>：动手前先等 {@code inFlight > 0}（保证
     * invalidate 真的落在"有读者正压在某个视图上"那一刻，这才是要打的那一下），动手后再等
     * {@code enters} 前进一格（保证一次 invalidate 换一个读者进位）。后者不加会直接跑偏：换成
     * "只丢引用"之后 {@code invalidate()} 便宜了两三个数量级，让 churner 无脑空转，它 ~20ms 就
     * 烧光 {@code MAX_CYCLES} 的配额，读者还没起步就被 {@code stop} 掉（实测 500k 循环 / 57 次读，
     * 被 {@code MIN_TOTAL_READS} 当场拦下报"空跑"）。两处都是因果等待（读者一进/一出就解除），
     * 不是 sleep。
     * <p>
     * 但也别把这支当成"生命周期全对了"的凭据 —— 上一版带显式 clean 的实现（读者计数 + 待释放队列）
     * 在这里同样是全绿的，却在独立探针 {@code MmapRaceProbe race} 上照样 rc=134，见类注释。
     * 那一层由 {@link #readerNeverOwnsAnExplicitRelease()} 钉住。
     */
    @Test
    void concurrentInvalidateNeverUnmapsFromUnderALiveReader() throws Exception {
        String name = "mmap-race";
        final int nPages = 24;
        final MmapPageReader reader = new MmapPageReader(pages(name, nPages), PAGE);
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Throwable> firstError = new AtomicReference<Throwable>();
        final AtomicLong reads = new AtomicLong();
        /** 读者"开始一次 read"的次数；churner 用它把自己限速成"一次 invalidate 换一次读者进位"。 */
        final AtomicLong enters = new AtomicLong();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger readersAlive = new AtomicInteger(READERS);
        final CountDownLatch readersReady = new CountDownLatch(READERS);
        final CountDownLatch readersDone = new CountDownLatch(READERS);

        for (int t = 0; t < READERS; t++) {
            final int seed = t;
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    readersReady.countDown();
                    java.util.Random r = new java.util.Random(1000L + seed);
                    try {
                        while (!stop.get()) {
                            int pageNo = r.nextInt(nPages);
                            enters.incrementAndGet();
                            inFlight.incrementAndGet();
                            try {
                                Page p = reader.read(id(name, pageNo));
                                if (!java.util.Arrays.equals(body(name, pageNo), p.payload())) {
                                    throw new IllegalStateException("pageNo=" + pageNo
                                            + " 读到的 payload 不是写进去的那份");
                                }
                                reads.incrementAndGet();
                            } catch (Throwable e) {
                                firstError.compareAndSet(null, e);
                                return;
                            } finally {
                                inFlight.decrementAndGet();
                            }
                        }
                    } finally {
                        readersAlive.decrementAndGet();
                        readersDone.countDown();
                    }
                }
            });
            th.setDaemon(true);
            th.start();
        }

        assertTrue(readersReady.await(10, java.util.concurrent.TimeUnit.SECONDS), "读者线程起不来");
        int cycles = 0;
        int waitingForReader = 0, waitingForNextRead = 0;
        while (cycles < MAX_CYCLES && (cycles < INVALIDATE_CYCLES || reads.get() < MIN_TOTAL_READS)
                && readersAlive.get() > 0) {
            while (inFlight.get() == 0 && readersAlive.get() > 0 && ++waitingForReader < SPIN_LIMIT) {
                Thread.yield();
            }
            if (inFlight.get() == 0) break;          // 读者都退了：让下面的断言说清楚是谁先红
            long mark = enters.get();
            reader.invalidate();
            cycles++;
            // 等"这一次 invalidate 之后确实有读者新开了一次 read"，才允许进下一轮。
            while (enters.get() == mark && readersAlive.get() > 0 && ++waitingForNextRead < SPIN_LIMIT) {
                Thread.yield();
            }
        }
        stop.set(true);
        assertTrue(readersDone.await(60, java.util.concurrent.TimeUnit.SECONDS), "读者线程没退出");

        Throwable err = firstError.get();
        assertNull(err, "并发 invalidate 期间 reader.read 抛了（改前这里是 SIGSEGV，fork 直接没）: "
                + err);
        // 两侧都要真的动过：读者一次读都没有、或 churner 一次 invalidate 都没有，上面两条断言都是空的。
        assertTrue(cycles >= INVALIDATE_CYCLES, "churner 只跑了 " + cycles + " 次 invalidate");
        assertTrue(reads.get() >= MIN_TOTAL_READS,
                "读者只做了 " + reads.get() + " 次读，说明这支没打到并发路径（空跑）");
        System.out.printf("[MmapPageReaderLifetimeTest] %d invalidate cycles, %d reads, 0 errors"
                + " (spins: %d / %d)%n", cycles, reads.get(), waitingForReader, waitingForNextRead);
    }

    /**
     * {@code read()} 交出去的 {@link Page} 必须自带字节，不能在返回后还指着映射。
     * <p>
     * 既然本类不再自己 unmap、全部交给 GC，这条就是"GC 什么时候能把地址还回来"的前提：只要有一个
     * Page 还压着视图，它连带钉住的就是<b>整份文件</b>的映射。哪天 {@code Page.deserialize} 改成把
     * 映射的视图直接交给调用方，累积效应会是"每次 snapshot 加载都留一截地址空间"，而且
     * {@code ATOMIC_MOVE} 换 inode 之后（{@code MmapRaceProbe inode} 实测：旧映射继续读到一份
     * 看起来完全合理的旧内容）就会变成静默的陈旧读 —— 不报错、不崩，只是查不到。下面这条会先红，
     * 比那种现场好查。
     */
    @Test
    void returnedPageDoesNotAliasTheMapping() throws IOException {
        String name = "mmap-alias";
        MmapPageReader reader = new MmapPageReader(pages(name, 3), PAGE);
        Page p = reader.read(id(name, 2));
        byte[] snapshot = p.payload().clone();
        reader.invalidate();          // 视图退役：此刻若还指着映射，下面的读就是悬垂
        reader.read(id(name, 0));     // 换一块新映射
        assertTrue(java.util.Arrays.equals(snapshot, p.payload()),
                "已经交出去的 Page 不许在视图退役后变内容");
        assertTrue(java.util.Arrays.equals(body(name, 2), p.payload()),
                "变的内容还得是原来那一页");
    }

    /**
     * 结构守卫：{@link MmapPageReader} 不许长出"自己释放映射"的样子。
     * <p>
     * 为什么不满足于"并发测试没红"：上一版实现（{@code AtomicLong readers} + {@code Queue retired}
     * + 反射 {@code Cleaner.clean()}）在本文件那三支尺子下<b>全绿</b>，却照样把
     * {@code MmapRaceProbe race} 打成 rc=134 —— 队列里挂着仍在 {@code ref} 上的映射（每轮 20—36 次），
     * 而写密集下计数不归零、攒了 13 块 ~4MB 没人拆。这种缺陷在行为测试里是"随机崩"，
     * 在结构上是一眼就能认出来的形状，所以钉在结构上。
     * <p>
     * 两条判据都是负向断言，因此按"负向断言必须自带猎物"各配一个阳性对照：同一把尺子量一个已知的
     * 坏形状必须报红，否则这里绿只是因为尺子坏了。
     */
    @Test
    void readerNeverOwnsAnExplicitRelease() {
        java.util.List<String> methods = new java.util.ArrayList<String>();
        for (java.lang.reflect.Method m : MmapPageReader.class.getDeclaredMethods()) {
            methods.add(m.getName());
        }
        assertTrue(forbiddenMethods(methods).isEmpty(),
                "MmapPageReader 又长出了自己释放映射的方法 " + forbiddenMethods(methods)
                        + "：duplicate() 的视图与父映射共用地址段，clean 会抽走在途读者脚下的地址（SIGSEGV）");

        java.util.List<String> fieldTypes = new java.util.ArrayList<String>();
        for (java.lang.reflect.Field f : MmapPageReader.class.getDeclaredFields()) {
            fieldTypes.add(f.getType().getSimpleName());
        }
        assertTrue(releaseShapeFields(fieldTypes).isEmpty(),
                "又出现了读者计数 / 待释放队列 " + releaseShapeFields(fieldTypes)
                        + "：实测它挡不住 unmap 拆错人，见本条注释");

        // 阳性对照：把上一版真实存在过的那个形状喂给同一把尺子，它必须认出猎物。
        java.util.List<String> previousDesign = new java.util.ArrayList<String>(java.util.Arrays.asList(
                "read", "invalidate", "isValid", "close", "openBuffer", "unmap", "retire", "drainRetired"));
        assertEquals(java.util.Arrays.asList("unmap", "retire", "drainRetired"),
                forbiddenMethods(previousDesign), "方法尺失效了：上一版那三个名字本该被抓出来");
        assertEquals(java.util.Arrays.asList("AtomicLong", "ConcurrentLinkedQueue"),
                releaseShapeFields(new java.util.ArrayList<String>(java.util.Arrays.asList(
                        "Logger", "Path", "int", "AtomicReference", "AtomicLong", "Queue",
                        "ConcurrentLinkedQueue"))),
                "字段尺失效了：上一版的 readers/retired 两个字段类型本该被抓出来");
    }

    /**
     * {@code PageStore.compact()} 是 {@code 临时文件 → ATOMIC_MOVE 换 inode → invalidate()}
     * （{@code PageStore.java:280,286}）。换掉 inode 之后，旧映射指着的还是<b>旧那个 inode</b>
     * 的页缓存 —— 这一条是 {@link MmapPageReader#invalidate()} 存在的唯一理由，也是它不能被
     * "页缓存反正连贯、写过的当场看得到" 顶替的地方：那条实测结论只对<b>同一个 inode</b> 的
     * 就地写成立（类注释里 23808→0 那一次量的就是它）。
     * <p>
     * 所以这里把 compact 的三步原样复刻一遍，并且两头都钉：
     * <ul>
     *   <li>过了 invalidate ⇒ 读到新内容（少了这一步就是产品 bug：压缩后仍读旧页）；</li>
     *   <li>没过 invalidate ⇒ 读到的<b>还是旧内容</b> —— 这条是阳性对照。它证明"旧映射会读到
     *       合理但陈旧的字节的"（{@code MmapRaceProbe inode} 量的同一件事），也就证明上面那条
     *       断言不是空跑：否则"把 invalidate 改成空方法"这个变异体在所有尺子上都是绿的。</li>
     * </ul>
     */
    @Test
    void invalidateIsWhatMakesACompactedFileVisible() throws IOException {
        String name = "mmap-compact";
        Path file = pages(name, 2);
        MmapPageReader stale = new MmapPageReader(file, PAGE);
        MmapPageReader fresh = new MmapPageReader(file, PAGE);
        assertEquals(128, stale.read(id(name, 0)).payload().length, "先把映射拿下来");
        assertEquals(128, fresh.read(id(name, 0)).payload().length);

        // 另起一份内容不同的文件，按 compact 的方式原子替换掉 src（换 inode，不是就地改写）。
        byte[] compacted = new byte[128];
        for (int i = 0; i < compacted.length; i++) compacted[i] = (byte) (77 - i);
        Path tmp = tmpDir.resolve("pages.tmp");
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
            raf.setLength(0);
            for (int p = 0; p < 2; p++) {
                raf.write(new Page(id(name, p), compacted).serialize());
            }
            raf.getFD().sync();
        }
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 有些文件系统不给 ATOMIC_MOVE；这条尺子要的就是"换 inode"这个形状，退让没有意义。
            throw new AssertionError("需要 ATOMIC_MOVE 才能复刻 compact 的形状: " + e.getMessage(), e);
        }

        fresh.invalidate();           // compact 里紧跟在 Files.move 后面的那一步
        assertTrue(java.util.Arrays.equals(compacted, fresh.read(id(name, 0)).payload()),
                "invalidate 之后必须读到换过的那个文件的内容");
        assertTrue(java.util.Arrays.equals(body(name, 0), stale.read(id(name, 0)).payload()),
                "没 invalidate 的那一份读到的还得是旧 inode 的旧页 —— 这条不成立，上面那条就是空跑，"
                        + "而且 invalidate() 在这套实现里就没有作用了");
    }

    /**
     * 换视图（{@code invalidate}）和装视图（{@code openBuffer}）必须共用一把锁。
     * <p>
     * 排掉的交错：{@code ref} 为空、读者正在 {@code openBuffer} 里 mmap ⇒ 与此同时
     * {@code PageStore.compact()} 换掉 inode 并 {@code invalidate()}（那会儿 {@code ref}
     * 还没被赋值，摘了个空）⇒ 读者随后把<b>旧 inode</b> 的映射装进 {@code ref}。之后再没有
     * 任何东西让它失效（换 inode 不改 capacity，走不到重映射那一支），压缩后的页号会一直读到
     * 压缩前的旧页 —— 页号会被复用，所以这是"读到别人的数据"，不是"读不到"。
     * <p>
     * 这个窗口在本文件里<b>没有</b>行为尺子能确定性地打：要撞它得在 {@code openBuffer} 的
     * mmap 系统调用中间停住另一个线程，而生产代码里没有那样的接缝（我不会为了测试往实现里塞钩子）。
     * 所以钉在结构上：三个碰 {@code #ref} 的方法都在同一个监视器里。判据带阳性对照
     * （{@link #syncProbe()} / {@link #plainProbe()}），否则"全都 synchronized"这句绿
     * 可能只是因为尺子读不出修饰符。
     */
    @Test
    void viewSwapAndInstallShareOneMonitor() {
        java.util.List<String> unguarded = new java.util.ArrayList<String>();
        for (java.lang.reflect.Method m : MmapPageReader.class.getDeclaredMethods()) {
            if (MUST_HOLD_MONITOR.contains(m.getName())
                    && !java.lang.reflect.Modifier.isSynchronized(m.getModifiers())) {
                unguarded.add(m.getName());
            }
        }
        assertEquals(new java.util.ArrayList<String>(), unguarded,
                "这些方法会读改 ref，却不与 openBuffer 互斥 " + unguarded
                        + "：换 inode 与装视图之间就有了把旧映射装回去的窗口");
        // 三条都得存在，否则上面那句是"没找到违规"而不是"三条都合格"。
        assertEquals(MUST_HOLD_MONITOR, declaredAndGuarded(),
                "MmapPageReader 的方法形状变了：守卫的分母已经对不上实现");

        // 阳性对照：同一把尺子必须分得清 synchronized 与普通方法。
        assertEquals(java.util.Arrays.asList("plainProbe"),
                unguarded(java.util.Arrays.asList("syncProbe", "plainProbe")),
                "阳性对照不成立：尺子认不出未加锁的方法（那上面那句绿就是空的）");
    }

    /** 会读改 {@code ref} 的方法，按字典序；守卫的分母就是它。 */
    private static final java.util.List<String> MUST_HOLD_MONITOR =
            java.util.Collections.unmodifiableList(
                    java.util.Arrays.asList("close", "invalidate", "openBuffer"));

    /** 存在且带监视器的、守卫要求的那几条方法。 */
    private java.util.List<String> declaredAndGuarded() {
        java.util.List<String> hit = new java.util.ArrayList<String>();
        for (java.lang.reflect.Method m : MmapPageReader.class.getDeclaredMethods()) {
            if (MUST_HOLD_MONITOR.contains(m.getName())
                    && java.lang.reflect.Modifier.isSynchronized(m.getModifiers())) {
                hit.add(m.getName());
            }
        }
        java.util.Collections.sort(hit);
        return hit;
    }

    /** 把守卫用的判据单独抽出来，好让阳性对照量的是同一段代码。 */
    private static java.util.List<String> unguarded(java.util.List<String> names) {
        java.util.List<String> hit = new java.util.ArrayList<String>();
        for (String n : names) {
            boolean sync;
            try {
                sync = java.lang.reflect.Modifier.isSynchronized(
                        MmapPageReaderLifetimeTest.class.getDeclaredMethod(n).getModifiers());
            } catch (NoSuchMethodException e) {
                throw new AssertionError("阳性对照的方法不在了: " + n);
            }
            if (!sync) hit.add(n);
        }
        java.util.Collections.sort(hit);
        return hit;
    }

    private synchronized void syncProbe() { /* 阳性对照的"合格"样本 */ }

    private void plainProbe() { /* 阳性对照的"不合格"样本 */ }

    /** 名字里带"释放/退休/排水"语义的方法 = 显式 unmap 那一类，本类不许有。 */
    private static java.util.List<String> forbiddenMethods(java.util.List<String> names) {
        java.util.List<String> hit = new java.util.ArrayList<String>();
        for (String n : names) {
            String s = n.toLowerCase();
            if (s.contains("unmap") || s.contains("clean") || s.contains("drain")
                    || s.contains("retire") || s.contains("release")) {
                hit.add(n);
            }
        }
        return hit;
    }

    /** 计数 + 队列这两类字段一出现，就意味着实现又回到"自己决定什么时候 unmap"。 */
    private static java.util.List<String> releaseShapeFields(java.util.List<String> typeNames) {
        java.util.List<String> hit = new java.util.ArrayList<String>();
        for (String t : typeNames) {
            if (t.startsWith("AtomicLong") || t.startsWith("LongAdder") || t.startsWith("AtomicInteger")
                    || t.startsWith("ConcurrentLinkedQueue") || t.startsWith("LinkedList")
                    || t.startsWith("ArrayList")) {
                hit.add(t);
            }
        }
        return hit;
    }
}
