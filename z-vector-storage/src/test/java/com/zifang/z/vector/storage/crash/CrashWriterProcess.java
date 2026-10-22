package com.zifang.z.vector.storage.crash;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.PersistentVectorStore;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 崩溃恢复验证套件的<b>子进程写入端</b> —— 被测库在<b>另一个 JVM</b> 里跑，
 * 然后被父进程 {@code kill -9}。
 *
 * <p><b>为什么必须开子进程</b>：本卡要验的是「进程被 SIGKILL 之后数据还在不在」。
 * 在同一个 JVM 里没法对自己发 SIGKILL（那会把测试进程一起带走），
 * 用 {@code Runtime.halt()} 模拟也不等价 —— 它不跑 shutdown hook，但仍然走 JVM 自己的退出流程。
 * 只有<b>真的从外面发 SIGKILL</b>，才是断电/掉电那类场景的忠实模拟：
 * 没有 shutdown hook、没有 finally、没有 flush。
 *
 * <p><b>子进程自己绝不调用 close()</b>：{@code PersistentVectorStore.close()} 会
 * 「写快照 + flush WAL + 截断」—— 那是<b>优雅关机</b>的路径，
 * 走它就等于把「崩溃恢复」偷换成「正常重启」，测出来的东西不作数。
 *
 * <p>用法（由 {@link CrashRecoveryConsistencyTest} 拉起）：
 * <pre>
 *   java -cp &lt;cp&gt; com.zifang.z.vector.storage.crash.CrashWriterProcess \
 *        &lt;dataDir&gt; &lt;collection&gt; &lt;mode&gt; &lt;points&gt; &lt;readyFile&gt; &lt;holdMillis&gt;
 * </pre>
 */
public final class CrashWriterProcess {

    /** 时点档 1：checkpoint <b>前</b> —— 全部数据只在 WAL 里，无快照。 */
    public static final String MODE_PRE_CHECKPOINT = "PRE_CHECKPOINT";

    /** 时点档 2：checkpoint <b>后</b> —— 快照已落盘，WAL 已截断，之后又有新写入。 */
    public static final String MODE_POST_CHECKPOINT = "POST_CHECKPOINT";

    /** 时点档 3：WAL <b>半途</b> —— 写入进行中被 kill，尾部可能半截。 */
    public static final String MODE_MID_WAL = "MID_WAL";

    /**
     * 探针档：<b>人为放大 fsync 延迟窗口</b>（模拟慢盘 / IO 抖动）。
     * 子进程握住底层 WAL 文件的监视器不放 ⇒ 后台 flusher 拿不到锁 ⇒ 记录全堵在队列里。
     */
    public static final String MODE_STALLED_FSYNC = "STALLED_FSYNC";

    /** checkpoint 间隔：设成极大值，避免写入过程中自动 checkpoint 把时点档搅乱。 */
    private static final long HUGE_CHECKPOINT_INTERVAL = 100_000_000L;

    private CrashWriterProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: CrashWriterProcess <dataDir> <collection> <mode> <points> "
                    + "<readyFile> <holdMillis>");
            System.exit(2);
        }
        String dataDir = args[0];
        String collection = args[1];
        String mode = args[2];
        int points = Integer.parseInt(args[3]);
        Path readyFile = Paths.get(args[4]);
        long holdMillis = Long.parseLong(args[5]);

        System.out.println("[child] pid=" + pid() + " mode=" + mode + " points=" + points
                + " dataDir=" + dataDir);
        System.out.flush();

        if (MODE_STALLED_FSYNC.equals(mode)) {
            runStalled(dataDir, collection, points, readyFile, holdMillis);
        } else {
            runNormal(dataDir, collection, mode, points, readyFile, holdMillis);
        }
    }

    // ==================== 正常时点档 ====================

    private static void runNormal(String dataDir, String collection, String mode,
                                  int points, Path readyFile, long holdMillis) throws Exception {
        PersistentVectorStore store = openStore(dataDir);
        try {
            createCollection(store, collection);

            if (MODE_POST_CHECKPOINT.equals(mode)) {
                // 前半段：写完做一次 checkpoint（快照落盘 + WAL 截断）
                int half = points / 2;
                writeRange(store, collection, 0, half);
                store.flush(collection);
                System.out.println("[child] checkpoint done after " + half + " points");
                // 后半段：checkpoint 之后的新写入 —— 崩溃后必须靠 WAL 重放补回来
                writeRange(store, collection, half, points - half);
            } else if (MODE_MID_WAL.equals(mode)) {
                // 先把前一批点真的 fsync 掉（持久化屏障）—— 否则被杀之后一条都不剩，
                // 「恢复出来的是不是写入序列的前缀」这种断言就没东西可对了。
                writeRange(store, collection, 0, CrashDataset.MID_WAL_DURABLE_FLOOR);
                CrashTestAccess.durabilityBarrier(store);
                System.out.println("[child] " + CrashDataset.MID_WAL_DURABLE_FLOOR
                        + " points are durable; the rest is still in flight");
                markReady(readyFile, "WAL_WRITING");
                // 剩下的写入**放慢**：一条一毫秒 ⇒ 父进程的 kill 真的会落在写入途中，
                // 而不是「子进程早就写完了，kill 只是打断了一个闲着的进程」。
                for (int i = CrashDataset.MID_WAL_DURABLE_FLOOR; i < points; i++) {
                    store.upsert(collection, CrashDataset.point(i));
                    // 记进度：父进程要靠它算出「kill 之前有多少条 upsert 已经返回过」
                    //（= 已 ack 的条数），才能把「已 ack 却丢了」和「本来就没写完」分开。
                    // 每条都记：少记一条，丢的条数就会少算一条（那就成了自欺欺人的口径）。
                    writeProgress(readyFile, i + 1);
                    try {
                        Thread.sleep(1L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } else {
                // PRE_CHECKPOINT：全部写入都在 WAL 里，**不**做 checkpoint
                writeRange(store, collection, 0, points);
            }

            if (!MODE_MID_WAL.equals(mode)) {
                // 持久化屏障：阻塞到已提交的 WAL 全部 fsync 落盘。
                // 过了这道屏障，「kill -9 之后数据还在吗」才有确定答案 ——
                // 否则测的是「后台 flusher 跑没跑」，那是时序，不是持久化。
                CrashTestAccess.durabilityBarrier(store);
                System.out.println("[child] durability barrier passed");
                markReady(readyFile, "READY");
            }
            System.out.flush();
            hold(holdMillis);
        } finally {
            // 故意不 close()：close() 会写快照 + flush WAL，那是优雅关机，不是崩溃。
            System.out.println("[child] exiting without close() (simulated crash window over)");
            System.out.flush();
        }
    }

    // ==================== fsync 延迟窗口探针 ====================

    private static void runStalled(String dataDir, String collection, int points,
                                   Path readyFile, long holdMillis) throws Exception {
        PersistentVectorStore store = openStore(dataDir);
        // 握住 WAL 文件的监视器：后台 flusher 的 flushBatch 要 synchronized(walFile)，
        // 拿不到锁 ⇒ 写不出去。相当于「磁盘卡住了 300ms」。
        final Object walLock = CrashTestAccess.wal(store);
        synchronized (walLock) {
            createCollection(store, collection);
            writeRange(store, collection, 0, points);
            System.out.println("[child] " + points + " acked writes are stuck in the async WAL queue"
                    + " (fsync stalled)");
            markReady(readyFile, "STALLED");
            System.out.flush();
            hold(holdMillis);
        }
    }

    // ==================== 工具 ====================

    private static PersistentVectorStore openStore(String dataDir) {
        // checkpoint 间隔设成极大值：时点档由脚本显式控制，不让引擎自动 checkpoint 搅乱时点
        return new PersistentVectorStore(dataDir, HUGE_CHECKPOINT_INTERVAL, true);
    }

    private static void createCollection(PersistentVectorStore store, String collection) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("M", 16);
        params.put("efConstruction", 100);
        params.put("efSearch", 64);
        store.createCollection(collection, CrashDataset.DIM, DistanceMetric.COSINE,
                IndexType.HNSW, params, "crash-recovery-suite");
    }

    private static void writeRange(PersistentVectorStore store, String collection, int from, int count) {
        List<VectorPoint> batch = CrashDataset.points(from, count);
        for (VectorPoint p : batch) {
            store.upsert(collection, p);
        }
    }

    /**
     * 落 ready 标记（原子写：先写临时文件再 rename，父进程不会读到半截内容）。
     */
    private static void markReady(Path readyFile, String phase) {
        try {
            Path parent = readyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = readyFile.resolveSibling(readyFile.getFileName() + ".tmp");
            String content = phase + "\n" + pid() + "\n";
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, readyFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("写 ready 标记失败: " + e.getMessage(), e);
        }
    }

    /** 写进度标记（原子写）：父进程据此算「kill 之前已 ack 的条数」。 */
    private static void writeProgress(Path readyFile, int acked) {
        try {
            Path progress = readyFile.resolveSibling(readyFile.getFileName() + ".progress");
            Path tmp = progress.resolveSibling(progress.getFileName() + ".tmp");
            Files.write(tmp, String.valueOf(acked).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, progress, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 进度标记只是给报告用的观测手段，写不上去不该影响被测行为
            System.err.println("[child] 写进度标记失败: " + e.getMessage());
        }
    }

    private static String pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();  // 形如 "12345@host"
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    /** 挂住不退出，等父进程来 kill。 */
    private static void hold(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 供父进程拼 classpath 用：确认 java 可执行文件路径的取法在各处一致。 */
    static String javaBin() {
        return new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();
    }
}
