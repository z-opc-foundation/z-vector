package com.zifang.z.vector.storage.wal;

import com.zifang.z.vector.api.VectorPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group commit 的三条牙。
 *
 * <p>背景：{@link AsyncWalFile} 的类注释承诺"批量 64 条一次 fsync"，但它实际是对批里每条调
 * {@link WalFile#append}，而 append 每条都 {@code getFD().sync()} ⇒ 批量只省了锁、没省 IO。
 * 250（JDK 8 / 慢盘）实测一次 fsync 8.3ms、2000 条批量落盘 8ms（比值 2086x），
 * {@code MemoryStabilityTest} 因此在 {@code AsyncWalFile.flush} 的 30s 超时上翻红两次
 * （appended=7501 flushed=3520 / appended=20002 flushed=18587）；本机 NVMe 只要 67us，跑多久都不会红。
 * 所以这里既有"慢机器上才会红"的回归用例（C），也有与机器无关的结构用例（A、B）。
 */
class WalGroupCommitTest {

    @TempDir
    Path dataDir;

    /** 计数的替身：记录写入走了哪条 API。这是"每段一次 fsync"的唯一可机检代理。 */
    private static final class CountingWal extends WalFile {
        final AtomicInteger perRecordAppends = new AtomicInteger();
        final AtomicInteger batchCalls = new AtomicInteger();
        final AtomicInteger recordsThroughBatchApi = new AtomicInteger();

        CountingWal(String dir) throws IOException {
            super(dir);
        }

        @Override
        public synchronized void append(WalRecord record) throws IOException {
            perRecordAppends.incrementAndGet();
            super.append(record);
        }

        @Override
        public synchronized void appendBatch(List<WalRecord> records) throws IOException {
            batchCalls.incrementAndGet();
            recordsThroughBatchApi.addAndGet(records.size());
            super.appendBatch(records);
        }
    }

    private static List<WalRecord> records(int n) {
        List<WalRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(WalRecord.upsertPoint("docs", new VectorPoint("d" + i, new float[]{i, i + 1, i + 2})));
        }
        return out;
    }

    private static TreeMap<String, byte[]> snapshot(Path dir) throws IOException {
        TreeMap<String, byte[]> out = new TreeMap<>();
        File[] files = dir.toFile().listFiles();
        assertTrue(files != null && files.length > 0, "目录里什么都没有 ⇒ 对拍不作数");
        for (File f : files) out.put(f.getName(), Files.readAllBytes(f.toPath()));
        return out;
    }

    /**
     * A. 整批写下去的字节，必须和逐条 append 一字不差 —— 包括"一批跨好几个段"的情况。
     * 分段判据是这次改动最容易悄悄改掉的东西（越界那条留在老段、下一条才 rotate）。
     */
    @Test
    void batchAppendWritesTheSameBytesAsPerRecordAppend() throws IOException {
        List<WalRecord> all = records(60);
        long segmentSize = 300L;   // 单条记录 ~150B ⇒ 每段两三条，必然在批内 rotate

        Path singleDir = dataDir.resolve("single");
        Path batchDir = dataDir.resolve("batch");

        WalFile single = new WalFile(singleDir.toString(), segmentSize);
        try {
            for (WalRecord r : all) single.append(r);
        } finally {
            single.close();
        }
        WalFile batched = new WalFile(batchDir.toString(), segmentSize);
        try {
            for (int i = 0; i < all.size(); i += 7) {          // 批量大小与每条都不同，专门制造跨段
                batched.appendBatch(all.subList(i, Math.min(i + 7, all.size())));
            }
        } finally {
            batched.close();
        }

        TreeMap<String, byte[]> a = snapshot(singleDir);
        TreeMap<String, byte[]> b = snapshot(batchDir);
        // 阳性对照：这批数据必须真的跨了段，否则本用例只在验证"两个空文件相等"
        assertFalse(a.keySet().size() < 2,
                "只写出 1 个段文件 ⇒ 没有触发 rotate，跨段对拍这一项是空跑");
        assertEquals(a.keySet(), b.keySet(), "段文件集合不同 ⇒ rotate 时机被改掉了");
        for (String name : a.keySet()) {
            assertArrayEquals(a.get(name), b.get(name), name + " 内容不同");
        }

        // 重开以后两条路径必须给出同一份可重放历史。
        // 注意别拿 getSequenceNumber() 当"总记录数"：countRecords() 只数当前段（wal.log），
        // rotate 过的段不计入，所以重启后它是"最后一段的条数"。目前只有 checkpoint 记录带这个值、
        // 没有正确性依赖 ⇒ 记账不动它，本用例也不钉它。
        WalFile reopenSingle = new WalFile(singleDir.toString(), segmentSize);
        WalFile reopenBatched = new WalFile(batchDir.toString(), segmentSize);
        try {
            assertEquals(reopenSingle.readAll().size(), all.size(),
                    "重启后逐条写的那份 WAL 少放了记录");
            assertEquals(reopenBatched.readAll().size(), all.size(),
                    "重启后批量写的那份 WAL 少放了记录 —— 跨段的字节对拍没通过");
            assertEquals(reopenSingle.segmentCount(), reopenBatched.segmentCount());
            List<WalRecord> replayBatched = reopenBatched.readAll();
            // payload 里带 id（同款判法见 WalFileTest.appendAndRead）：首尾对得上才叫"顺序没乱"
            assertTrue(replayBatched.get(0).getPayload().contains("\"d0\""),
                    "第一条重放出来的不是 d0 ⇒ 段间重放顺序乱了");
            assertTrue(replayBatched.get(all.size() - 1).getPayload().contains("\"d59\""),
                    "最后一条不是 d59 ⇒ 重放顺序乱了");
        } finally {
            reopenSingle.close();
            reopenBatched.close();
        }
    }

    /**
     * B. {@link AsyncWalFile} 必须把整批交给一次 {@code appendBatch}，一条记录一次 append 都不许有。
     * fsync 次数 = batchCalls（一个段一次），所以"perRecordAppends == 0"就是 group commit 的实质；
     * 谁把它改回逐条 append，这条立刻红，而且和盘的快慢无关。
     */
    @Test
    void asyncFlushSendsWholeBatchesInsteadOfOneRecordAtATime() throws IOException {
        int n = 200;
        CountingWal wal = new CountingWal(dataDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal, 8, 5L, 10_000);
        try {
            for (WalRecord r : records(n)) async.append(r);
            async.flush();
        } finally {
            async.close();
        }
        assertEquals(0, wal.perRecordAppends.get(),
                "有 " + wal.perRecordAppends.get() + " 条记录走了逐条 append ⇒ 每条一次 fsync，"
                        + "group commit 又变回只省锁不省 IO（250 上实测 8.3ms/条）");
        assertEquals(n, wal.recordsThroughBatchApi.get(),
                "经批量 API 落盘的记录数不等于提交数 ⇒ 有记录从别的路上漏掉或重复写入");
        assertTrue(wal.batchCalls.get() > 0, "一次批量调用都没有 ⇒ 量具没打到被测路径");
        // 平均批量 >= 4：生产者这一趟是微秒级灌完 200 条，flusher 只会拿到满批（8 条）。
        // 留一倍余量给慢机上的超时刷盘 —— 不许拿这个余量当"批量大小"的断言。
        assertTrue(wal.batchCalls.get() * 4 <= n,
                "batchCalls=" + wal.batchCalls.get() + " 对 n=" + n + " ⇒ 平均批量 < 4，"
                        + "批量没真的攒起来");
        assertEquals(n, async.totalFlushed());
    }

    /**
     * C. 深队列 + flush 必须落在 flush() 自己的 30s 之内。
     * 这条只有在慢 fsync 的机器上才有判别力：250 上逐条 fsync 是 8000 × 8.3ms ≈ 66s（红），
     * 批量后是 125 次 ≈ 1s（绿）；本机两种写法都在一秒内，所以它在 Mac 上恒绿 ——
     * 判别力记账在类注释里，别把本机跑绿当成它有效。
     */
    @Test
    void deepQueueFlushReachesItsTarget() throws IOException {
        int n = 8000;
        WalFile wal = new WalFile(dataDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal, AsyncWalFile.DEFAULT_BATCH_SIZE,
                AsyncWalFile.DEFAULT_MAX_LATENCY_MS, 10_000);
        try {
            for (WalRecord r : records(n)) async.append(r);
            async.flush();
            assertEquals(n, async.totalFlushed(), "flush() 返回时队列没清空 ⇒ 计数或刷盘线程有问题");
            assertEquals(0, async.queueSize());
            assertEquals(n, wal.readAll().size(), "落盘的记录数必须等于提交数，一条都不能少");
        } finally {
            async.close();
        }
    }
}
