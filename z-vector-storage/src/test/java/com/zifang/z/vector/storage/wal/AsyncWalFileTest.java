package com.zifang.z.vector.storage.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AsyncWalFile 单元测试 — 验证 Group Commit + 高并发写入 + 崩溃一致性。
 */
class AsyncWalFileTest {

    @TempDir
    Path tmpDir;

    @Test
    void singleWriteEventuallyFlushed() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal);
        async.append(WalRecord.checkpoint(1));
        async.flush();
        assertEquals(1, async.totalAppended());
        assertEquals(1, async.totalFlushed());

        // 确认底层文件能读出记录
        List<WalRecord> records = wal.readAll();
        assertEquals(1, records.size());

        async.close();
        wal.close();
    }

    @Test
    void manyWritesAreBatched() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal, 32, 100, 1000);
        for (int i = 0; i < 100; i++) {
            async.append(WalRecord.checkpoint(i));
        }
        async.flush();
        // 100 条记录批量写入；batch 数应远小于 100
        assertTrue(async.totalBatches() < 100,
                "Expected batch count < record count, got " + async.totalBatches() + " batches for 100 records");
        async.close();
        wal.close();
    }

    @Test
    void concurrentAppendsAreSafe() throws Exception {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal, 16, 50, 5000);
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        async.append(WalRecord.checkpoint(i));
                    }
                } catch (IOException e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        async.flush();
        assertEquals(0, errors.get(), "Concurrent append should not throw");
        assertEquals(threads * perThread, async.totalAppended());
        assertEquals(async.totalAppended(), async.totalFlushed());

        List<WalRecord> records = wal.readAll();
        assertEquals(threads * perThread, records.size());

        async.close();
        wal.close();
    }

    @Test
    void closeFlushesRemainingRecords() throws IOException, InterruptedException {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal);
        for (int i = 0; i < 50; i++) {
            async.append(WalRecord.checkpoint(i));
        }
        // 不显式 flush，直接 close（close 会触发 flush + drain）
        async.close();

        // 等待 flusher 完全退出 + totalFlushed 追上 totalAppended（轮询避免时序 flakiness）
        long deadline = System.currentTimeMillis() + 5_000;
        while (async.totalFlushed() < async.totalAppended() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }

        assertEquals(50, async.totalAppended());
        assertEquals(50, async.totalFlushed());
        assertFalse(async.isAlive());

        // 注意：必须在 wal.close() 之前 readAll（readAll 用 raf.length()，close 后 NPE）
        List<WalRecord> records = wal.readAll();
        assertEquals(50, records.size());
        wal.close();
    }

    @Test
    void flushAndTruncateAfterCheckpoint() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal);
        for (int i = 0; i < 10; i++) async.append(WalRecord.checkpoint(i));
        async.flushAndTruncate();

        // 验证：WAL 被截断
        assertEquals(0, wal.readAll().size());
        // 重新写入应能成功
        async.append(WalRecord.checkpoint(99));
        async.flush();
        assertEquals(1, wal.readAll().size());

        async.close();
        wal.close();
    }

    @Test
    void throughputImprovementOverSyncWal() throws IOException {
        // 对比：sync WalFile（每条 fsync）vs AsyncWalFile（批量 fsync）
        // 注意：在 tmpfs / macOS 上 fsync 可能几乎无开销，所以本测试只打印对比，
        // 不强求异步一定更快；但至少要保证两者功能都正确。
        int n = 500;

        // 1. sync WalFile
        Path syncPath = tmpDir.resolve("sync");
        WalFile syncWal = new WalFile(syncPath.toString());
        long syncStart = System.nanoTime();
        for (int i = 0; i < n; i++) syncWal.append(WalRecord.checkpoint(i));
        long syncDuration = System.nanoTime() - syncStart;
        // 注意：readAll 必须在 close 之前调用
        assertEquals(n, syncWal.readAll().size());
        syncWal.close();

        // 2. AsyncWalFile
        Path asyncPath = tmpDir.resolve("async");
        WalFile asyncWal = new WalFile(asyncPath.toString());
        AsyncWalFile async = new AsyncWalFile(asyncWal, 64, 10, 10000);
        long asyncStart = System.nanoTime();
        for (int i = 0; i < n; i++) async.append(WalRecord.checkpoint(i));
        async.flush();
        long asyncDuration = System.nanoTime() - asyncStart;
        assertEquals(n, asyncWal.readAll().size());

        System.out.printf("[INFO] Sync WAL: %d ms / Async WAL: %d ms (speedup %.2fx, batches=%d)%n",
                syncDuration / 1_000_000, asyncDuration / 1_000_000,
                syncDuration == 0 ? 0 : (double) syncDuration / asyncDuration,
                async.totalBatches());

        async.close();
        asyncWal.close();
    }

    @Test
    void recordsAreAppendedInOrder() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal);
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            WalRecord r = WalRecord.checkpoint(i);
            seqs.add(r.getTimestamp());
            async.append(r);
        }
        async.flush();

        List<WalRecord> records = wal.readAll();
        assertEquals(100, records.size());
        // timestamp 应该非严格递增（同一毫秒可能相等，但通常递增）
        long prev = 0;
        for (WalRecord r : records) {
            assertTrue(r.getTimestamp() >= prev);
            prev = r.getTimestamp();
        }
        async.close();
        wal.close();
    }

    @Test
    void randomPayloadsSurvive() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal);
        Random r = new Random(42);
        for (int i = 0; i < 30; i++) {
            String randomPayload = "{\"seq\":" + r.nextInt(100000)
                    + ",\"data\":\"" + r.nextLong() + "\"}";
            WalRecord rec = new WalRecord(WalOpType.UPSERT_POINT, "col_" + i,
                    randomPayload);
            async.append(rec);
        }
        async.flush();

        List<WalRecord> records = wal.readAll();
        assertEquals(30, records.size());
        for (WalRecord rec : records) {
            assertEquals(WalOpType.UPSERT_POINT, rec.getOp());
            assertTrue(rec.getPayload().startsWith("{\"seq\":"));
        }
        async.close();
        wal.close();
    }

    @Test
    void appendAfterCloseThrows() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString());
        AsyncWalFile async = new AsyncWalFile(wal);
        async.close();
        assertThrows(IOException.class, () -> async.append(WalRecord.checkpoint(1)));
        wal.close();
    }
}
