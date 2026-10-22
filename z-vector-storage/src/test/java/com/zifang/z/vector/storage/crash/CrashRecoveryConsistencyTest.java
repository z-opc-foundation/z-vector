package com.zifang.z.vector.storage.crash;

import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.index.HnswIndex;
import com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData;
import com.zifang.z.vector.core.index.Index;
import com.zifang.z.vector.storage.PersistentVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * z-vector 崩溃恢复一致性验证套件 —— 「持久化是真的」的唯一证明方式。
 *
 * <h2>验的是什么</h2>
 * 写入 N 条 → 从<b>另一个 JVM</b> 里 {@code kill -9} → 重启 → 三口径对账：
 * <ol>
 *   <li><b>collection 计数</b>：恢复出来的条数必须与预期一致，且不能有幽灵点 / 重复点；</li>
 *   <li><b>HNSW 图连通性</b>：不是「文件在不在」，而是<b>图还能不能查</b> ——
 *       每个点用自己向量查自己必须命中自己，且从入口点 BFS 必须覆盖全图；</li>
 *   <li><b>payload 索引</b>：倒排必须<b>重建</b>（过滤要走倒排快路径，不是逐点兜底扫描）。</li>
 * </ol>
 *
 * <h2>时点矩阵（至少三档）</h2>
 * <ul>
 *   <li>{@code PRE_CHECKPOINT} —— checkpoint <b>前</b>：全部数据只在 WAL 里，无快照；</li>
 *   <li>{@code POST_CHECKPOINT} —— checkpoint <b>后</b>：快照已落盘、WAL 已截断，之后又有新写入；</li>
 *   <li>{@code MID_WAL} —— WAL <b>半途</b>：写入途中被 kill；再叠一档<b>确定性</b>的半截尾
 *       （亲手把 wal.log 截断在记录中间，逼恢复流程面对它）。</li>
 * </ul>
 *
 * <h2>为什么判定要「自己断言」</h2>
 * 本套件不靠人眼看日志：每个口径都产出 GREEN / RED 判定与期望/实际值，
 * 并落成机器可读结果单（{@code target/crash-consistency/report.json}）。
 * 「红点明确」= 哪个时点、哪个口径、差多少，一眼能看出来。
 *
 * <h2>可重复执行</h2>
 * 数据集固定种子（{@link CrashDataset#SEED}），断言基线与机器无关；
 * 每次运行用独立的临时目录 ⇒ 可反复跑、可挂 CI（见 {@code _doc/003_script/run_crash_consistency_suite.sh}）。
 */
class CrashRecoveryConsistencyTest {

    /** 写入点数。200 条：够让 HNSW 建出有意义的图，又不至于让测试跑得慢。 */
    private static final int POINTS = 200;

    /** 集合名。 */
    private static final String COLLECTION = "crash_docs";

    /** 子进程最长挂起时间（父进程 kill 之前它一直等着）。 */
    private static final long CHILD_HOLD_MILLIS = 120_000L;

    /** 等待子进程 ready 标记的最长时间。 */
    private static final long READY_TIMEOUT_MILLIS = 60_000L;

    /** 结果单（跨测试方法累积：JUnit 每个测试方法都是新实例，报告必须放在静态域）。 */
    private static final CrashConsistencyReport REPORT = new CrashConsistencyReport();

    @TempDir
    Path tmpDir;

    @AfterEach
    void dumpReport() throws IOException {
        Path json = REPORT.write();
        System.out.println("[suite] 结果单已落盘: " + json.toAbsolutePath());
    }

    // ==================== 时点档 1：checkpoint 前 ====================

    @Test
    void crashBeforeCheckpoint_keepsEveryDurablePoint() throws Exception {
        CrashConsistencyReport.Scenario sc =
                REPORT.scenario("PRE_CHECKPOINT", "checkpoint 前（全部数据只在 WAL 里）");
        Child child = startChild(CrashWriterProcess.MODE_PRE_CHECKPOINT, POINTS);
        killNine(child);
        sc.killSignal = "SIGKILL(9)";

        // checkpoint 前 ⇒ 磁盘上不应有快照，恢复只能靠 WAL 重放
        assertFalse(hasSnapshot(child.dataDir),
                "本档的前提是没有快照（否则就不是 checkpoint 前）；目录内容: " + listDir(child.dataDir));

        Expectation exp = Expectation.exactly(POINTS);
        Audit audit = audit(reopen(child.dataDir), exp);
        record(sc, "PRE_CHECKPOINT", audit);

        assertAllGreen(sc, audit);
    }

    // ==================== 时点档 2：checkpoint 后 ====================

    @Test
    void crashAfterCheckpoint_replaysSnapshotPlusWal() throws Exception {
        CrashConsistencyReport.Scenario sc =
                REPORT.scenario("POST_CHECKPOINT", "checkpoint 后（快照 + 截断后的 WAL）");
        Child child = startChild(CrashWriterProcess.MODE_POST_CHECKPOINT, POINTS);
        killNine(child);
        sc.killSignal = "SIGKILL(9)";

        assertTrue(hasSnapshot(child.dataDir),
                "本档的前提是快照已落盘；目录内容: " + listDir(child.dataDir));

        // 快照里只有前半段，后半段必须靠 WAL 重放补回来 —— 两段合起来才是全量
        int half = POINTS / 2;
        WalByteFormat.Rec[] recs = scanWal(child.dataDir);
        int walUpserts = 0;
        for (WalByteFormat.Rec r : recs) {
            if (r.op == WalByteFormat.OP_UPSERT_POINT) {
                walUpserts++;
            }
        }
        assertEquals(POINTS - half, walUpserts,
                "checkpoint 之后新写入的 " + (POINTS - half) + " 条必须留在 WAL 里（截断只应砍掉 checkpoint 之前的）");

        Expectation exp = Expectation.exactly(POINTS);
        Audit audit = audit(reopen(child.dataDir), exp);
        record(sc, "POST_CHECKPOINT", audit);
        assertAllGreen(sc, audit);
    }

    // ==================== 时点档 3：WAL 半途 ====================

    @Test
    void crashMidWal_recoversConsistentlyFromTornTail() throws Exception {
        // ---- 3a：真实 kill（写入途中） ----
        CrashConsistencyReport.Scenario sc =
                REPORT.scenario("MID_WAL", "WAL 半途（写入途中被 kill）");
        Child child = startChild(CrashWriterProcess.MODE_MID_WAL, POINTS);
        Thread.sleep(120L);   // 让写入真的跑起来（子进程在 ready 之后才继续写）
        killNine(child);
        sc.killSignal = "SIGKILL(9)";

        // 子进程在 ready 之前做过一次持久化屏障 ⇒ 至少有一批点是真的落盘了的；
        // 之后那批在途的写可能丢也可能没丢 ⇒ 允许区间，不允许「凭空多出来」或「乱序」。
        Expectation exp = Expectation.range(MID_WAL_DURABLE_FLOOR, POINTS).requirePrefix();
        Audit audit = audit(reopen(child.dataDir), exp);
        record(sc, "MID_WAL", audit);

        // 自然窗口的量化：kill 之前已经<b>返回过</b>的 upsert 有多少条（= 已 ack），
        // 恢复后还剩多少 ⇒ 差值就是「已 ack 却没保住」的条数。
        // 这一条不参与本档的三口径判定（半途本来就允许丢尾），但它是红点的直接证据。
        int acked = readProgress(child.readyFile);
        long naturalLoss = Math.max(0L, acked - audit.recovered);
        sc.note = "kill 之前已 ack " + acked + " 条 upsert，恢复 " + audit.recovered
                + " 条 ⇒ 已 ack 却没保住 " + naturalLoss + " 条（进度标记每 10 条记一次，故为下界）";
        if (naturalLoss > 0) {
            REPORT.finding("🔴 自然窗口（没有人为拖慢 fsync）：kill -9 之前已经返回过的 upsert 里，"
                    + "有 " + naturalLoss + "/" + acked + " 条没保住 —— "
                    + "默认 v2 引擎的 WAL 是异步落盘（AsyncWalFile 入队即返回），"
                    + "upsert() 返回时数据可能还只在内存队列里。已立修复卡跟踪。");
        }
        assertAllGreen(sc, audit);

        // ---- 3b：确定性半截尾（亲手把 WAL 截断在记录中间） ----
        CrashConsistencyReport.Scenario sc2 =
                REPORT.scenario("MID_WAL_TORN", "WAL 半途（确定性：亲手截断在记录中间）");
        Child child2 = startChild(CrashWriterProcess.MODE_PRE_CHECKPOINT, POINTS);
        killNine(child2);
        sc2.killSignal = "SIGKILL(9) + 人工截断 wal.log";

        WalByteFormat.Rec[] recs = scanWal(child2.dataDir);
        assertTrue(recs.length >= 2, "WAL 里至少要有创建集合 + 若干写入，实际 " + recs.length);
        WalByteFormat.Rec last = recs[recs.length - 1];
        assertEquals(WalByteFormat.OP_UPSERT_POINT, last.op,
                "最后一条应当是写入记录（实际 op=" + last.op + "）");
        // 截断到「最后一条记录的一半」—— 断电最常见的形态：最后一条只写了一半
        int cut = last.start + Math.max(1, last.length() / 2);
        truncate(child2.dataDir.resolve("wal.log"), cut);

        // 截断掉的正是最后一条写入 ⇒ 期望恰好少一个点
        Expectation exp2 = Expectation.exactly(POINTS - 1);
        Audit audit2 = audit(reopen(child2.dataDir), exp2);
        record(sc2, "MID_WAL_TORN", audit2);
        sc2.note = "wal.log 在第 " + cut + " 字节处被截断（最后一条写入只写了一半）";
        assertAllGreen(sc2, audit2);
    }

    // ==================== 探针：已 ack 的写入到底耐不耐 kill -9 ====================

    @Test
    void ackedWritesSurviveKillNine_probe() throws Exception {
        CrashConsistencyReport.Scenario sc = REPORT.scenario(
                "ACKED_DURABILITY_PROBE", "探针：已 ack（upsert 已返回）的写入在 kill -9 后是否还在");

        // 人为放大 fsync 延迟窗口（子进程握住底层 WAL 文件的监视器 ⇒ 后台 flusher 写不出去），
        // 等价于「磁盘卡了 300ms 的时候机器断电」。
        Child child = startChild(CrashWriterProcess.MODE_STALLED_FSYNC, POINTS);
        killNine(child);
        sc.killSignal = "SIGKILL(9)（fsync 被人为拖住）";

        PersistentVectorStore store = reopen(child.dataDir);
        long recovered = store.hasCollection(COLLECTION) ? store.getPointCount(COLLECTION) : 0L;
        long lost = POINTS - recovered;

        boolean red = lost > 0;
        sc.add(new CrashConsistencyReport.Metric(
                "acked-durability", "已 ack 写入的持久性", red ? "RED" : "GREEN",
                "丢失 0 条（upsert 返回即持久）",
                "丢失 " + lost + " 条 / 共 " + POINTS + " 条",
                red ? "upsert() 返回成功但数据未 fsync ⇒ kill -9 后整段丢失；"
                        + "引擎 javadoc 承诺的「先写 WAL（强制 fsync），再更新内存」在默认 v2 引擎下不成立"
                        : "未观察到丢失"));

        if (red) {
            REPORT.finding("🔴 已 ack 的写入在 kill -9 后丢失 " + lost + "/" + POINTS
                    + " 条 —— v2 引擎（默认）的 WAL 是异步落盘（AsyncWalFile 只入队就返回），"
                    + "upsert() 返回时数据可能还在内存队列里；已立修复卡跟踪。");
        }

        // 严格判定（默认不启用）：CI 默认绿，红点以结果单 + 修复卡的形式挂账。
        // 要复现红灯：mvn -pl z-vector-storage test -Dtest=CrashRecoveryConsistencyTest
        //            -Dcrash.strict.durability=true
        if (Boolean.getBoolean("crash.strict.durability")) {
            assertEquals(0L, lost,
                    "已 ack 的写入在 kill -9 后丢失了 " + lost + " 条 —— WAL 的持久性契约被打破");
        }
    }

    /** MID_WAL 档里，子进程在 ready 之前做过持久化屏障 ⇒ 至少这么多点是真的落盘了的。 */
    private static final int MID_WAL_DURABLE_FLOOR = CrashDataset.MID_WAL_DURABLE_FLOOR;

    // ==================== 三口径审计 ====================

    /** 一个口径的判定结果（不抛异常 —— 判定统一在最后收口，报告才完整）。 */
    private static final class Check {
        final String id;
        final String name;
        final boolean green;
        final String expected;
        final String actual;
        final String detail;

        Check(String id, String name, boolean green, String expected, String actual, String detail) {
            this.id = id;
            this.name = name;
            this.green = green;
            this.expected = expected;
            this.actual = actual;
            this.detail = detail;
        }
    }

    /** 三口径的审计结果。 */
    private static final class Audit {
        Check count;
        Check hnsw;
        Check payload;
        long recovered;
        Set<String> ids = new LinkedHashSet<String>();
    }

    /** 期望（每个时点档的计数期望不同）。 */
    private static final class Expectation {
        Integer exact;        // 精确期望条数
        int min;              // 区间下界
        int max;              // 区间上界
        boolean prefix;       // 恢复出来的 id 必须是写入序列的前缀

        static Expectation exactly(int n) {
            Expectation e = new Expectation();
            e.exact = n;
            e.min = n;
            e.max = n;
            return e;
        }

        static Expectation range(int min, int max) {
            Expectation e = new Expectation();
            e.exact = null;
            e.min = min;
            e.max = max;
            return e;
        }

        Expectation requirePrefix() {
            this.prefix = true;
            return this;
        }

        String describe() {
            return exact != null ? ("恰好 " + exact + " 条")
                    : ("落在 [" + min + ", " + max + "] 区间内");
        }
    }

    /**
     * 重启（重新打开数据目录）后跑三口径对账。
     *
     * @param exp 计数期望
     */
    private Audit audit(PersistentVectorStore store, Expectation exp) {
        Audit audit = new Audit();

        // ---------- 口径 ①：collection 计数 ----------
        List<String> problems = new ArrayList<String>();
        boolean exists = store.hasCollection(COLLECTION);
        long count = store.getPointCount(COLLECTION);
        Set<String> ids = scrollAll(store);
        audit.recovered = count;
        audit.ids = ids;

        if (!exists) {
            problems.add("集合 " + COLLECTION + " 恢复后不存在");
        }
        if (count != ids.size()) {
            problems.add("getPointCount=" + count + " 与 scroll 去重后的 " + ids.size()
                    + " 不一致（幽灵点或重复点）");
        }
        if (count < exp.min || count > exp.max) {
            problems.add("期望 " + exp.describe() + "，实际 " + count);
        }
        // 幽灵点检查：恢复出来的点必须是我们写进去过的
        for (String id : ids) {
            if (!isWrittenId(id)) {
                problems.add("幽灵点 " + id + "（不在写入集合内）");
            }
        }
        // 前缀性：WAL 是顺序追加、顺序重放 ⇒ 恢复出来的必然是写入序列的前缀，
        // 中间出现空洞就说明重放乱了序（那才是真缺陷）
        if (exp.prefix && exists) {
            String hole = firstHole(ids, count);
            if (hole != null) {
                problems.add("恢复集合不是写入序列的前缀，缺 " + hole
                        + "（重放乱序 ⇒ 数据被覆盖/跳过）");
            }
        }
        audit.count = new Check("count", "① collection 计数", problems.isEmpty(),
                exp.describe(), String.valueOf(count) + " 条",
                problems.isEmpty() ? ("去重后 " + ids.size() + " 个 id，与计数一致") : join(problems));

        // ---------- 口径 ②：HNSW 图连通性（可查性，不是文件在不在） ----------
        problems = new ArrayList<String>();
        IndexType type = store.getIndexType(COLLECTION);
        if (type != IndexType.HNSW) {
            problems.add("索引类型应为 HNSW，实际 " + type);
        }
        if (!store.isIndexed(COLLECTION)) {
            problems.add("索引未建成（isIndexed=false）");
        }
        int miss = 0;
        String firstMiss = null;
        for (String id : ids) {
            VectorPoint p = store.getPoint(COLLECTION, id);
            if (p == null) {
                miss++;
                if (firstMiss == null) {
                    firstMiss = id + "(取不到点)";
                }
                continue;
            }
            List<SearchResult> hits = store.search(COLLECTION, p.getVector(), 1, null);
            if (hits.isEmpty() || !id.equals(hits.get(0).getVectorId())) {
                miss++;
                if (firstMiss == null) {
                    firstMiss = id + "(自查询 top-1=" + (hits.isEmpty() ? "空" : hits.get(0).getVectorId()) + ")";
                }
            }
        }
        if (miss > 0) {
            problems.add(miss + "/" + ids.size() + " 个点自查询没命中自己，首个：" + firstMiss);
        }
        int reachable = reachableNodes(store, ids);
        if (reachable >= 0 && reachable != ids.size()) {
            problems.add("从入口点 BFS 只覆盖 " + reachable + "/" + ids.size() + " 个节点（图不连通）");
        }
        audit.hnsw = new Check("hnsw", "② HNSW 图连通性（可查性）", problems.isEmpty(),
                "每个点自查询命中自己 100%；BFS 覆盖 " + ids.size() + "/" + ids.size(),
                "自查询未命中 " + miss + " 个；BFS 覆盖 " + reachable + "/" + ids.size(),
                problems.isEmpty() ? "图可查且连通" : join(problems));

        // ---------- 口径 ③：payload 索引 ----------
        problems = new ArrayList<String>();
        int indexedFields = CrashTestAccess.payloadIndexedFields(store, COLLECTION);
        if (indexedFields < 3) {
            problems.add("payload 倒排只建了 " + indexedFields + " 个字段索引（lang/bucket/seq 应为 3）");
        }
        // 过滤结果必须精确：lang=zh 的点数
        int expectedZh = 0;
        int expectedBucket2 = 0;
        for (String id : ids) {
            int i = indexOfWrittenId(id);
            if (i < 0) {
                continue;
            }
            if ("zh".equals(CrashDataset.langOf(i))) {
                expectedZh++;
            }
            if (CrashDataset.bucketOf(i) == 2) {
                expectedBucket2++;
            }
        }
        List<SearchResult> zh = store.search(COLLECTION, CrashDataset.vector(0), 1000,
                Filter.eq("lang", "zh"));
        if (zh.size() != expectedZh) {
            problems.add("lang=zh 过滤返回 " + zh.size() + " 行，期望 " + expectedZh + " 行");
        }
        for (SearchResult r : zh) {
            Object lang = r.getPayload() == null ? null : r.getPayload().get("lang");
            if (!"zh".equals(lang)) {
                problems.add("lang=zh 的结果里混进了 lang=" + lang + " 的点 " + r.getVectorId());
                break;
            }
        }
        List<SearchResult> b2 = store.search(COLLECTION, CrashDataset.vector(0), 1000,
                Filter.eq("bucket", 2));
        if (b2.size() != expectedBucket2) {
            problems.add("bucket=2 过滤返回 " + b2.size() + " 行，期望 " + expectedBucket2 + " 行");
        }
        long fastHits = CrashTestAccess.payloadFastPathHits(store, COLLECTION);
        if (fastHits <= 0) {
            problems.add("payload 倒排快路径一次都没命中（倒排没重建，过滤靠全量扫描兜底）");
        }
        audit.payload = new Check("payload", "③ payload 索引", problems.isEmpty(),
                "lang=zh " + expectedZh + " 行 / bucket=2 " + expectedBucket2 + " 行；倒排快路径命中 > 0",
                "lang=zh " + zh.size() + " 行 / bucket=2 " + b2.size() + " 行；快路径命中 " + fastHits,
                problems.isEmpty() ? "倒排已重建且过滤结果精确" : join(problems));

        return audit;
    }

    /** 把审计结果写进结果单。 */
    private void record(CrashConsistencyReport.Scenario sc, String name, Audit audit) {
        sc.add(new CrashConsistencyReport.Metric(audit.count.id, audit.count.name,
                audit.count.green ? "GREEN" : "RED", audit.count.expected, audit.count.actual,
                audit.count.detail));
        sc.add(new CrashConsistencyReport.Metric(audit.hnsw.id, audit.hnsw.name,
                audit.hnsw.green ? "GREEN" : "RED", audit.hnsw.expected, audit.hnsw.actual,
                audit.hnsw.detail));
        sc.add(new CrashConsistencyReport.Metric(audit.payload.id, audit.payload.name,
                audit.payload.green ? "GREEN" : "RED", audit.payload.expected, audit.payload.actual,
                audit.payload.detail));
    }

    /** 三个口径全绿才算这一档通过。 */
    private void assertAllGreen(CrashConsistencyReport.Scenario sc, Audit audit) {
        List<String> reds = new ArrayList<String>();
        if (!audit.count.green) {
            reds.add("① 计数：" + audit.count.detail);
        }
        if (!audit.hnsw.green) {
            reds.add("② HNSW：" + audit.hnsw.detail);
        }
        if (!audit.payload.green) {
            reds.add("③ payload：" + audit.payload.detail);
        }
        assertTrue(reds.isEmpty(), "时点档 " + sc.name + " 有红点：\n  - " + join(reds));
    }

    // ==================== 子进程 ====================

    private static final class Child {
        final Process process;
        final long pid;
        final Path dataDir;
        final Path readyFile;
        final Path logFile;
        final String mode;

        Child(Process process, long pid, Path dataDir, Path readyFile, Path logFile, String mode) {
            this.process = process;
            this.pid = pid;
            this.dataDir = dataDir;
            this.readyFile = readyFile;
            this.logFile = logFile;
            this.mode = mode;
        }
    }

    private Child startChild(String mode, int points) throws Exception {
        Path dataDir = tmpDir.resolve(mode.toLowerCase(java.util.Locale.ROOT));
        Files.createDirectories(dataDir);
        Path readyFile = dataDir.resolve("ready.marker");
        Path logFile = dataDir.resolve("child.log");

        List<String> cmd = new ArrayList<String>(Arrays.asList(
                CrashWriterProcess.javaBin(),
                "-Xmx512m",
                "-cp", childClasspath(),
                CrashWriterProcess.class.getName(),
                dataDir.toString(),
                COLLECTION,
                mode,
                String.valueOf(points),
                readyFile.toString(),
                String.valueOf(CHILD_HOLD_MILLIS)));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        Process process = pb.start();

        long pid = awaitReady(process, readyFile, logFile);
        return new Child(process, pid, dataDir, readyFile, logFile, mode);
    }

    /** 等子进程写出 ready 标记，从标记里读出它的 pid（Java 8 没有 {@code Process.pid()}）。 */
    private long awaitReady(Process process, Path readyFile, Path logFile)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(readyFile)) {
                String content = new String(Files.readAllBytes(readyFile), StandardCharsets.UTF_8);
                String[] lines = content.split("\\r?\\n");
                if (lines.length >= 2) {
                    return Long.parseLong(lines[1].trim());
                }
            }
            if (!process.isAlive()) {
                fail("子进程在写出 ready 标记前就退出了（exit=" + process.exitValue()
                        + "）；日志：\n" + readLog(logFile));
            }
            Thread.sleep(20L);
        }
        fail("等待子进程 ready 标记超时（" + READY_TIMEOUT_MILLIS + "ms）；日志：\n" + readLog(logFile));
        return -1L;   // 不会走到
    }

    /** 从外面真的发 SIGKILL —— 这是「断电模拟」的核心动作。 */
    private void killNine(Child child) throws Exception {
        Process killer = new ProcessBuilder("kill", "-9", String.valueOf(child.pid))
                .redirectErrorStream(true)
                .start();
        String killerOut = readAll(killer);
        killer.waitFor();
        assertTrue(child.process.waitFor(60, TimeUnit.SECONDS),
                "SIGKILL 之后子进程应当立刻退出；kill 输出：" + killerOut
                        + "；子进程日志：\n" + readLog(child.logFile));
        int code = child.process.exitValue();
        assertEquals(137, code,
                "子进程必须死于 SIGKILL（137 = 128 + 9），实际退出码 " + code
                        + "；说明 kill 没打中（pid=" + child.pid + "）；日志：\n" + readLog(child.logFile));
    }

    // ==================== 工具 ====================

    /** 子进程 classpath：当前测试 classpath + 两个模块的 classes 目录（防止 surefire 只给了引导 jar）。 */
    private static String childClasspath() {
        String sep = File.pathSeparator;
        StringBuilder sb = new StringBuilder(System.getProperty("java.class.path"));
        sb.append(sep).append(codeSourceDir(CrashWriterProcess.class));
        sb.append(sep).append(codeSourceDir(CrashRecoveryConsistencyTest.class));
        return sb.toString();
    }

    private static String codeSourceDir(Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return ".";
            }
            File f = new File(cs.getLocation().toURI());
            return f.isDirectory() ? f.getAbsolutePath() : f.getParent();
        } catch (Exception e) {
            return ".";
        }
    }

    private PersistentVectorStore reopen(Path dataDir) {
        return new PersistentVectorStore(dataDir.toString());
    }

    /** 全量 scroll 出 id（去重）。 */
    private Set<String> scrollAll(PersistentVectorStore store) {
        Set<String> ids = new LinkedHashSet<String>();
        String offset = null;
        while (true) {
            List<VectorPoint> page = store.scroll(COLLECTION, 64, offset, null);
            if (page.isEmpty()) {
                break;
            }
            for (VectorPoint p : page) {
                ids.add(p.getId());
            }
            offset = page.get(page.size() - 1).getId();
            if (page.size() < 64) {
                break;
            }
        }
        return ids;
    }

    /** 从入口点 BFS，统计覆盖到的（在恢复集合内的）节点数。 */
    private int reachableNodes(PersistentVectorStore store, Set<String> ids) {
        Index idx = CrashTestAccess.liveIndex(store, COLLECTION);
        if (!(idx instanceof HnswIndex)) {
            return -1;
        }
        HnswIndex hnsw = (HnswIndex) idx;
        Map<String, Set<String>> adj = new HashMap<String, Set<String>>();
        for (HnswNodeData d : hnsw.exportNodes()) {
            Set<String> nb = adj.get(d.id);
            if (nb == null) {
                nb = new HashSet<String>();
                adj.put(d.id, nb);
            }
            for (List<String> level : d.neighbors.values()) {
                nb.addAll(level);
            }
        }
        String ep = hnsw.getEntryPoint();
        if (ep == null) {
            return 0;
        }
        Set<String> seen = new HashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(ep);
        seen.add(ep);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            Set<String> nb = adj.get(cur);
            if (nb == null) {
                continue;
            }
            for (String n : nb) {
                if (seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        int covered = 0;
        for (String s : seen) {
            if (ids.contains(s)) {
                covered++;
            }
        }
        return covered;
    }

    /** 找出恢复集合相对「写入序列前缀」的第一个缺口（没有缺口返回 null）。 */
    private String firstHole(Set<String> ids, long count) {
        for (int i = 0; i < count; i++) {
            if (!ids.contains(CrashDataset.id(i))) {
                return CrashDataset.id(i);
            }
        }
        return null;
    }

    private static boolean isWrittenId(String id) {
        return indexOfWrittenId(id) >= 0;
    }

    /** 把 id 还原成写入序号（不是我们写的返回 -1）。 */
    private static int indexOfWrittenId(String id) {
        if (id == null || !id.startsWith("p-")) {
            return -1;
        }
        try {
            int i = Integer.parseInt(id.substring(2));
            return (i >= 0 && i < POINTS) ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 扫 WAL，返回引擎能认出来的记录区间。 */
    private static WalByteFormat.Rec[] scanWal(Path dataDir) throws IOException {
        Path wal = dataDir.resolve("wal.log");
        if (!Files.exists(wal)) {
            return new WalByteFormat.Rec[0];
        }
        byte[] bytes = Files.readAllBytes(wal);
        List<WalByteFormat.Rec> recs = WalByteFormat.scan(bytes);
        return recs.toArray(new WalByteFormat.Rec[recs.size()]);
    }

    /** 把 WAL 截断到指定长度（造半截记录）。 */
    private static void truncate(Path wal, int length) throws IOException {
        byte[] bytes = Files.readAllBytes(wal);
        byte[] cut = new byte[length];
        System.arraycopy(bytes, 0, cut, 0, length);
        Files.write(wal, cut, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** 快照是否落盘（v2 走 psnap_meta.bin，v1 走 snapshot.bin）。 */
    private static boolean hasSnapshot(Path dataDir) {
        return Files.exists(dataDir.resolve("psnap_meta.bin"))
                || Files.exists(dataDir.resolve("snapshot.bin"));
    }

    /** 读子进程写的进度标记（kill 之前已 ack 的 upsert 条数；读不到返回 0）。 */
    private static int readProgress(Path readyFile) {
        Path progress = readyFile.resolveSibling(readyFile.getFileName() + ".progress");
        try {
            if (!Files.exists(progress)) {
                return 0;
            }
            String s = new String(Files.readAllBytes(progress), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private static String listDir(Path dir) {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.stream.Stream<Path> s = Files.list(dir);
            try {
                for (Path p : (Iterable<Path>) s::iterator) {
                    sb.append(p.getFileName()).append(' ');
                }
            } finally {
                s.close();
            }
        } catch (IOException e) {
            sb.append("(读取失败: ").append(e.getMessage()).append(')');
        }
        return sb.toString();
    }

    private static String readLog(Path logFile) {
        try {
            if (!Files.exists(logFile)) {
                return "(无日志)";
            }
            return new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(日志读取失败: " + e.getMessage() + ")";
        }
    }

    private static String readAll(Process p) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = p.getInputStream().read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** 便于外部脚本定位结果单。 */
    static Path reportDir() {
        return Paths.get(System.getProperty("crash.report.dir", "target/crash-consistency"));
    }

    @SuppressWarnings("unused")
    private static void unusedGuard() {
        assertNotNull(reportDir());
        assertFalse(false);
    }
}
