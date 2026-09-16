package com.zifang.z.vector.storage.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步 WAL — 把 append 操作批量化，由后台线程一次性 fsync。
 * <p>
 * 设计动机：{@link WalFile#append} 每次都同步 fsync（~ms 级别），高并发写入时瓶颈在 IO。
 * 通过后台线程批量刷盘（Group Commit），吞吐可提升 5-10x。
 *
 * <h2>工作流程</h2>
 * <pre>
 * Producer threads
 *     │
 *     ▼ enqueue
 * ┌────────────────┐    drain   ┌─────────────────────┐
 * │ BlockingQueue  │ ──────────▶│ AsyncFlusher 线程    │
 * │ (有界，默认 10K)│  batch    │  1. 收集一批 records │
 * └────────────────┘            │  2. 一次 write + fsync│
 *                              │  3. 唤醒等待者        │
 *                              └─────────────────────┘
 * </pre>
 *
 * <h2>同步保证</h2>
 * <ul>
 *   <li>{@link #append(WalRecord)}：把 record 加入队列立即返回（不阻塞）。</li>
 *   <li>{@link #flush()}：强制等待队列清空 + fsync（用于 snapshot 前）。</li>
 *   <li>{@link #close()}：同 flush + 关闭后台线程。</li>
 * </ul>
 *
 * <h2>权衡</h2>
 * <ul>
 *   <li>延迟 vs 吞吐：批量越大吞吐越高，但单 record 落盘延迟也越高；</li>
 *   <li>崩溃窗口：未刷盘的 record 在崩溃时会丢失（与异步系统一致）；</li>
 *   <li>默认配置：batch 64 条 或 10ms 二者满足其一即触发刷盘。</li>
 * </ul>
 */
public class AsyncWalFile {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncWalFile.class);

    /** 默认批量大小：积累 64 条记录即刷盘。 */
    public static final int DEFAULT_BATCH_SIZE = 64;
    /** 默认最大等待时间：即使记录数不够，10ms 后也强制刷盘。 */
    public static final long DEFAULT_MAX_LATENCY_MS = 10;
    /** 默认队列容量：满后 put 阻塞（背压）。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    private final WalFile walFile;
    private final int batchSize;
    private final long maxLatencyMs;
    private final LinkedBlockingQueue<WalRecord> queue;
    private final Thread flusher;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // 监控指标
    private final AtomicLong totalAppended = new AtomicLong(0);
    private final AtomicLong totalFlushed = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);

    /** 构造时启动后台 flusher 线程。 */
    public AsyncWalFile(WalFile walFile) {
        this(walFile, DEFAULT_BATCH_SIZE, DEFAULT_MAX_LATENCY_MS, DEFAULT_QUEUE_CAPACITY);
    }

    public AsyncWalFile(WalFile walFile, int batchSize, long maxLatencyMs, int queueCapacity) {
        this.walFile = walFile;
        this.batchSize = batchSize;
        this.maxLatencyMs = maxLatencyMs;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.flusher = new Thread(this::run, "zvec-async-wal-flusher");
        this.flusher.setDaemon(true);
        this.flusher.start();
        LOG.info("AsyncWalFile started: batchSize={}, maxLatency={}ms, queueCapacity={}",
                batchSize, maxLatencyMs, queueCapacity);
    }

    /**
     * 提交一条记录（非阻塞，加入队列即返回）。
     * <p>
     * 如果队列满则阻塞（背压），保证不丢失数据。
     */
    public void append(WalRecord record) throws IOException {
        if (closed.get()) throw new IOException("AsyncWalFile is closed");
        if (record == null) throw new IllegalArgumentException("record is null");
        try {
            queue.put(record);
            totalAppended.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while enqueuing WAL record", e);
        }
    }

    /**
     * 阻塞等待直到所有已 enqueue 的记录都落盘。
     * <p>
     * 实现：循环检查 {@code totalFlushed >= totalAppended} 直到满足。
     */
    public void flush() throws IOException {
        long target = totalAppended.get();
        // 已关闭但仍有 pending 时仍等待（close() 中会先 flush 再 interrupt）
        long deadline = System.currentTimeMillis() + 30_000;
        while (totalFlushed.get() < target) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("AsyncWalFile.flush timeout: appended="
                        + target + " flushed=" + totalFlushed.get());
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while flushing", e);
            }
        }
    }

    /**
     * 先 flush 所有 pending records，再截断底层 WAL 文件（用于 checkpoint 后）。
     * <p>
     * 调用方应在 {@link com.zifang.z.vector.storage.snapshot.Snapshot} 写入成功后再调用本方法，
     * 以避免截断后 snapshot 丢失已 apply 但未持久化的 WAL 记录。
     */
    public void flushAndTruncate() throws IOException {
        flush();
        synchronized (walFile) {
            walFile.truncate();
        }
    }

    /** 同步截断（仅当 flush 已确认后调用）。 */
    public void truncate() throws IOException {
        synchronized (walFile) {
            walFile.truncate();
        }
    }

    /** 关闭后台 flusher 并 flush 所有 pending records。 */
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        // 先 flush，再等 flusher 退出
        flush();
        // 中断 flusher，让它退出
        flusher.interrupt();
        try {
            flusher.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("AsyncWalFile closed: appended={}, flushed={}, batches={}",
                totalAppended.get(), totalFlushed.get(), totalBatches.get());
    }

    // ==================== 监控 ====================

    public long totalAppended() { return totalAppended.get(); }
    public long totalFlushed() { return totalFlushed.get(); }
    public long totalBatches() { return totalBatches.get(); }
    public int queueSize() { return queue.size(); }

    /** 后台 flusher 是否还活着 */
    public boolean isAlive() { return flusher.isAlive(); }

    // ==================== 内部 ====================

    private void run() {
        List<WalRecord> batch = new ArrayList<>(batchSize);
        long lastFlushTime = System.currentTimeMillis();

        while (true) {
            // 先尝试取一条记录（阻塞 maxLatencyMs）
            WalRecord first;
            try {
                first = queue.poll(maxLatencyMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // 收到中断：刷掉剩余 batch 再退出
                if (!batch.isEmpty()) {
                    try {
                        flushBatch(batch);
                    } catch (IOException ioe) {
                        LOG.warn("Final flush failed during shutdown", ioe);
                    }
                    batch.clear();
                }
                Thread.currentThread().interrupt();
                return;
            }
            if (first != null) {
                batch.add(first);
                queue.drainTo(batch, batchSize - batch.size());
            }
            // 触发刷盘条件：满 batch 或超时
            boolean full = batch.size() >= batchSize;
            boolean timedOut = !batch.isEmpty()
                    && System.currentTimeMillis() - lastFlushTime >= maxLatencyMs;
            if (full || timedOut) {
                try {
                    flushBatch(batch);
                } catch (IOException e) {
                    LOG.error("AsyncWalFile flushBatch failed", e);
                    // 继续运行（不让 flusher 异常退出）
                }
                batch.clear();
                lastFlushTime = System.currentTimeMillis();
            }
            // 退出条件：已关闭 且 队列空 且 当前 batch 空
            if (closed.get() && queue.isEmpty() && batch.isEmpty()) {
                break;
            }
        }
    }

    /** 把一批 records 写入底层 WalFile（一次 fsync）。 */
    private void flushBatch(List<WalRecord> batch) throws IOException {
        if (batch.isEmpty()) return;
        synchronized (walFile) {
            for (WalRecord r : batch) {
                walFile.append(r);
            }
        }
        totalFlushed.addAndGet(batch.size());
        totalBatches.incrementAndGet();
    }
}
