package com.zifang.z.vector.storage.wal;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.PersistentVectorStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * WAL 落盘模式的<b>写吞吐对照基准</b> —— 给「持久性换吞吐」这笔账一个实测数字。
 *
 * <h2>对照的是什么</h2>
 * <ul>
 *   <li><b>before = {@link WalMode#ASYNC}</b>：入队即返回、后台整批 fsync（group commit）。
 *       这是默认档翻成同步<b>之前</b>的行为，也是「旧默认」的吞吐基线。</li>
 *   <li><b>after = {@link WalMode#SYNC}</b>：每条记录一次 write + 一次 fsync，
 *       {@code upsert()} 返回即持久（RPO = 0）—— 现默认档。</li>
 * </ul>
 *
 * <h2>两种写法分别测</h2>
 * 单条 {@code upsert()} 与批量 {@code upsertBatch()} 必须分开看：
 * 同步档下二者走同一条「每条一次 fsync」的路（batch 并没有自动变快），
 * 异步档下二者才都有 group commit 的摊薄效果。
 *
 * <h2>刻意不做的事</h2>
 * 本测试<b>不断言任何性能阈值</b>：CI 机器的磁盘差异太大，阈值断言只会变成随机红。
 * 它只断言「两个档位都跑完了、都产出了数字」，性能结论由结果单（report.md / report.json）承担。
 *
 * <h2>跑法</h2>
 * <pre>
 *   mvn -pl z-vector-storage test -Dtest=WalModeThroughputBenchmarkTest
 *   # 或走脚本（与崩溃一致性套件同形）：./_doc/003_script/run_wal_mode_benchmark.sh
 * </pre>
 * 产物：{@code z-vector-storage/target/wal-mode-benchmark/report.{json,md}}
 */
class WalModeThroughputBenchmarkTest {

    /** 每档写的点数。1000 条：够摊出 fsync 的常数，又不至于让 CI 跑太久。 */
    private static final int POINTS = 1000;

    /** 向量维度。64：让基准测的是 WAL 落盘，不是向量算术。 */
    private static final int DIM = 64;

    /** 批量写的批大小。64 = {@code AsyncWalFile} 的默认 group commit 批大小。 */
    private static final int BATCH_SIZE = 64;

    /** 预热条数（不计入统计）：把 JIT 编译和页缓存的冷启动摊掉。 */
    private static final int WARMUP = 100;

    /** checkpoint 间隔设成极大：基准只量写路径，不让自动 checkpoint 插进来。 */
    private static final long HUGE_CHECKPOINT_INTERVAL = 100_000_000L;

    /** 结果单输出目录（相对 z-vector-storage 模块根）。 */
    private static final Path REPORT_DIR = Paths.get("target", "wal-mode-benchmark");

    @Test
    void measureWalModeWriteThroughput() throws Exception {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("points", POINTS);
        report.put("dim", DIM);
        report.put("batchSize", BATCH_SIZE);
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("note", "before = ASYNC（fd25a8d 之前的默认行为：入队即返回）；"
                + "after = SYNC（现默认：每条一次 fsync，ack 即持久 RPO = 0）");

        List<Object> rows = new ArrayList<Object>();

        // before：旧默认档（异步 group commit）
        rows.add(measure("ASYNC", "before（旧默认档）"));
        // after：现默认档（同步逐条 fsync）
        rows.add(measure("SYNC", "after（现默认档）"));

        report.put("rows", rows);
        writeReport(report);
    }

    // ==================== 测量 ====================

    /** 跑一档（sync / async），返回这一档的实测行。 */
    private Map<String, Object> measure(String modeName, String label) throws IOException {
        WalMode mode = WalMode.valueOf(modeName);
        Path dir = REPORT_DIR.resolve("bench-" + modeName.toLowerCase(java.util.Locale.ROOT));
        deleteRecursively(dir);
        Files.createDirectories(dir);

        PersistentVectorStore store = new PersistentVectorStore(
                dir.toString(), HUGE_CHECKPOINT_INTERVAL, true, mode);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("mode", modeName);
        row.put("label", label);
        try {
            store.createCollection("bench", DIM, DistanceMetric.COSINE,
                    IndexType.FLAT, null, "wal-mode-throughput-benchmark");

            // 预热：不算进统计
            for (int i = 0; i < WARMUP; i++) {
                store.upsert("bench", point(i));
            }

            // ① 单条 upsert
            Timing single = timeUpserts(store, WARMUP, WARMUP + POINTS);
            row.put("singleUpsert", singleStats(single));

            // ② 批量 upsert
            Timing batch = timeBatches(store, WARMUP + POINTS, POINTS);
            row.put("batchUpsert", batchStats(batch));
        } finally {
            // close() 会 flush 队列 + 写快照：异步档残留的队列必须在这里排干净，
            // 否则它的后台 fsync 会跟下一档的测量抢磁盘，把对照测歪。
            store.close();
        }
        System.out.println("[bench] " + modeName + " => " + row);
        return row;
    }

    /** 一次测量的结果：总耗时 + 每次操作的延迟序列（ns）。 */
    private static final class Timing {
        final long totalNs;
        final List<Long> latencies;

        Timing(long totalNs, List<Long> latencies) {
            this.totalNs = totalNs;
            this.latencies = latencies;
        }
    }

    /** 逐条 upsert。 */
    private Timing timeUpserts(PersistentVectorStore store, int from, int count) {
        List<Long> latencies = new ArrayList<Long>(count);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long t0 = System.nanoTime();
            store.upsert("bench", point(from + i));
            latencies.add(System.nanoTime() - t0);
        }
        return new Timing(System.nanoTime() - start, latencies);
    }

    /** 批量 upsert（批大小 BATCH_SIZE）。 */
    private Timing timeBatches(PersistentVectorStore store, int from, int count) {
        List<VectorPoint> all = new ArrayList<VectorPoint>(count);
        for (int i = 0; i < count; i++) {
            all.add(point(from + i));
        }
        int batches = (count + BATCH_SIZE - 1) / BATCH_SIZE;
        List<Long> latencies = new ArrayList<Long>(batches);
        long start = System.nanoTime();
        for (int b = 0; b < batches; b++) {
            int lo = b * BATCH_SIZE;
            int hi = Math.min(lo + BATCH_SIZE, count);
            long t0 = System.nanoTime();
            store.upsertBatch("bench", all.subList(lo, hi));
            latencies.add(System.nanoTime() - t0);
        }
        return new Timing(System.nanoTime() - start, latencies);
    }

    // ==================== 统计 ====================

    private Map<String, Object> singleStats(Timing timing) {
        return stats(timing.totalNs, POINTS, timing.latencies, "每条 upsert");
    }

    private Map<String, Object> batchStats(Timing timing) {
        int batches = timing.latencies.size();
        int points = batches * BATCH_SIZE;
        return stats(timing.totalNs, points, timing.latencies, "每批 upsertBatch");
    }

    private Map<String, Object> stats(long totalNs, int ops, List<Long> latencies, String unit) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("unit", unit);
        m.put("ops", ops);
        m.put("totalMs", totalNs / 1_000_000.0);
        m.put("opsPerSec", totalNs > 0 ? (ops * 1_000_000_000.0 / totalNs) : 0.0);
        m.put("avgMs", latencies.isEmpty() ? 0.0 : (totalNs / 1_000_000.0) / ops);
        m.put("p50Ms", percentileMs(latencies, 50));
        m.put("p99Ms", percentileMs(latencies, 99));
        return m;
    }

    private double percentileMs(List<Long> latencies, int pct) {
        if (latencies.isEmpty()) {
            return 0.0;
        }
        long[] sorted = new long[latencies.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = latencies.get(i);
        }
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx] / 1_000_000.0;
    }

    // ==================== 结果单 ====================

    private void writeReport(Map<String, Object> report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path json = REPORT_DIR.resolve("report.json");
        Files.write(json, toJson(report).getBytes(StandardCharsets.UTF_8));
        Path md = REPORT_DIR.resolve("report.md");
        Files.write(md, toMarkdown(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("[bench] 结果单: " + json.toAbsolutePath());
        System.out.println("[bench] 结果单: " + md.toAbsolutePath());
        System.out.println(toMarkdown(report));
    }

    @SuppressWarnings("unchecked")
    private String toMarkdown(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# WAL 落盘模式 · 写吞吐对照（before / after）\n\n");
        sb.append("- 写入点数: ").append(report.get("points"))
                .append("，维度: ").append(report.get("dim"))
                .append("，批大小: ").append(report.get("batchSize")).append("\n");
        sb.append("- 环境: ").append(report.get("os")).append(" / JDK ")
                .append(report.get("java")).append("\n");
        sb.append("- 口径: ").append(report.get("note")).append("\n\n");
        sb.append("| 档位 | 写法 | 吞吐(ops/s) | 总耗时(ms) | 平均(ms) | p50(ms) | p99(ms) |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Object o : (List<Object>) report.get("rows")) {
            Map<String, Object> row = (Map<String, Object>) o;
            for (String key : new String[]{"singleUpsert", "batchUpsert"}) {
                Map<String, Object> s = (Map<String, Object>) row.get(key);
                sb.append(String.format("| %s | %s | %.1f | %.1f | %.3f | %.3f | %.3f |%n",
                        row.get("mode"), s.get("unit"), (Double) s.get("opsPerSec"),
                        (Double) s.get("totalMs"), (Double) s.get("avgMs"),
                        (Double) s.get("p50Ms"), (Double) s.get("p99Ms")));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String toJson(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        for (Map.Entry<String, Object> e : report.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": ");
            appendJsonValue(sb, e.getValue(), 2);
            sb.append(",\n");
        }
        sb.append("  \"_end\": true\n}\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendJsonValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                sb.append(spaces(indent + 2)).append("\"").append(e.getKey()).append("\": ");
                appendJsonValue(sb, e.getValue(), indent + 2);
                if (++i < ((Map<String, Object>) value).size()) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(spaces(indent)).append("}");
        } else if (value instanceof List) {
            sb.append("[");
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                appendJsonValue(sb, list.get(i), indent);
                if (i < list.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append("\"").append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"");
        }
    }

    // ==================== 工具 ====================

    /** Java 8 没有 String.repeat。 */
    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static VectorPoint point(int i) {
        Random rnd = new Random(i);
        float[] v = new float[DIM];
        for (int j = 0; j < DIM; j++) {
            v[j] = rnd.nextFloat();
        }
        return new VectorPoint("p-" + i, v);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 基准清理失败不影响测量结果
                    }
                });
    }
}
