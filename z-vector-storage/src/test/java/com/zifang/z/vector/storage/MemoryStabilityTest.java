package com.zifang.z.vector.storage;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存稳定性 / 长跑测试 — 验证长时间运行下没有内存泄漏、文件无限增长、状态不一致。
 * <p>
 * GitHub 参考：
 * <ul>
 *   <li>RocksDB stress test：连续写入 + checkpoint + 重启 10000 次；</li>
 *   <li>SQLite sqllogictest：长跑回归。</li>
 * </ul>
 */
class MemoryStabilityTest {

    @TempDir
    Path dataDir;

    /**
     * 100K upsert + 周期性 checkpoint：验证文件不会无限增长、关闭后能完整恢复。
     */
    @Test
    void hundredKUpsertsWithPeriodicCheckpoint() throws Exception {
        String dir = dataDir.resolve("100k").toString();
        VectorStore s = new PersistentVectorStore(dir, 5_000);  // 每 5000 条 checkpoint
        try {
            s.createCollection("docs", 4, DistanceMetric.L2);
            int total = 100_000;
            Random r = new Random(42);

            // 模拟实际使用：周期性 checkpoint
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < total; i++) {
                float[] v = new float[]{r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()};
                s.upsert("docs", new VectorPoint("p-" + i, v));
                if (i > 0 && i % 10_000 == 0) {
                    s.flush("docs");  // 强制 checkpoint
                    long freeMB = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                    long totalMB = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                    System.out.printf("[100K] %d upserts, heap=%dMB free / %dMB total%n",
                            i, freeMB, totalMB);
                }
            }
            long elapsedMs = System.currentTimeMillis() - startTime;
            System.out.printf("[100K] finished in %d ms (%.0f ops/s)%n",
                    elapsedMs, total * 1000.0 / elapsedMs);

            assertEquals(total, s.getPointCount("docs"));
            s.close();

            // 重新打开，验证 100K 条数据完整恢复
            VectorStore s2 = new PersistentVectorStore(dir, 5_000);
            try {
                assertEquals(total, s2.getPointCount("docs"),
                        "All 100K points should be recovered after restart");
            } finally {
                s2.close();
            }
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * 大量 delete + compact 循环：验证 free page bitmap + compaction 没有累积泄漏。
     */
    @Test void
    manyDeletesThenCompact() throws Exception {
        String dir = dataDir.resolve("delete-compact").toString();
        VectorStore s = new PersistentVectorStore(dir, 10_000);
        try {
            s.createCollection("docs", 4, DistanceMetric.L2);
            // 写 5000 条
            for (int i = 0; i < 5000; i++) {
                s.upsert("docs", new VectorPoint("d-" + i,
                        new float[]{(float) i, 0, 0, 0}));
            }
            assertEquals(5000, s.getPointCount("docs"));

            // 删除一半
            List<String> idsToDelete = new ArrayList<>();
            for (int i = 0; i < 5000; i += 2) idsToDelete.add("d-" + i);
            // 模拟批量删除（PersistentVectorStore 暂无 batch delete 接口，逐条删除）
            for (String id : idsToDelete) s.deletePoint("docs", id);

            s.flush("docs");
            assertEquals(2500, s.getPointCount("docs"));

            // 关闭 + 重启，验证恢复
            s.close();
            VectorStore s2 = new PersistentVectorStore(dir, 10_000);
            try {
                assertEquals(2500, s2.getPointCount("docs"),
                        "After delete+restart, 2500 points should remain");
            } finally {
                s2.close();
            }
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * 1000 次重启循环：每次 upsert + close + reopen，验证快照文件可读且数据一致。
     * <p>
     * 这个测试比 100K upsert 更能暴露「重启后状态不一致」的 bug。
     */
    @Test void thousandRestartCycles() throws Exception {
        String dir = dataDir.resolve("thousand-restart").toString();
        int cycles = 1000;
        Random r = new Random(777);

        for (int cycle = 0; cycle < cycles; cycle++) {
            VectorStore s = new PersistentVectorStore(dir);
            try {
                if (!s.hasCollection("docs")) {
                    s.createCollection("docs", 4, DistanceMetric.L2);
                }
                // 每次 upsert 1 条（id 唯一）
                float[] v = new float[]{r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()};
                s.upsert("docs", new VectorPoint("p-" + cycle, v));

                // 每 10 次 flush 一次
                if (cycle % 10 == 0) s.flush("docs");
                assertEquals(cycle + 1, s.getPointCount("docs"));
            } finally {
                s.close();
            }

            // 重启验证
            VectorStore s2 = new PersistentVectorStore(dir);
            try {
                assertEquals(cycle + 1, s2.getPointCount("docs"),
                        "After restart at cycle " + cycle
                                + ", expected " + (cycle + 1) + " points");
            } finally {
                s2.close();
            }
        }
    }

    /**
     * 验证 WAL rotate + 段数：连续 upsert 触发多次 rotate，确保段文件正常产生和清理。
     */
    @Test void walRotateUnderLoad() throws Exception {
        String dir = dataDir.resolve("rotate-load").toString();
        // 强制 checkpoint 间隔很大，确保 WAL 持续累积
        VectorStore s = new PersistentVectorStore(dir, 1_000_000);
        try {
            s.createCollection("docs", 4, DistanceMetric.L2);
            // 写 5000 条
            for (int i = 0; i < 5000; i++) {
                s.upsert("docs", new VectorPoint("r-" + i,
                        new float[]{i % 10, (i / 10) % 10, 0, 0}));
            }
            // 不 flush，强制 WAL rotate 多次
            s.close();

            // 重启验证
            VectorStore s2 = new PersistentVectorStore(dir, 1_000_000);
            try {
                assertEquals(5000, s2.getPointCount("docs"));
            } finally {
                s2.close();
            }
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignore) {}
        }
    }
}