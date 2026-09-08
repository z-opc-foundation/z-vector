package com.zifang.z.vector.storage.wal;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.VectorPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL 段轮转（segment rotation）测试。
 * <p>
 * 设计参考 RocksDB log file numbering + SQLite WAL checkpoint.
 * <p>
 * 覆盖：
 * <ul>
 *   <li>超过段阈值后自动 rotate；</li>
 *   <li>启动时正确重放所有段；</li>
 *   <li>truncate 删除所有历史段；</li>
 *   <li>段大小限制生效。</li>
 * </ul>
 */
class WalFileRotationTest {

    @TempDir
    Path tmpDir;

    @Test
    void rotatesWhenSegmentSizeExceeded() throws IOException {
        // 设置很小的段大小（4KB）来强制 rotate
        WalFile wal = new WalFile(tmpDir.toString(), 4 * 1024);
        try {
            // 每条记录约 50B，写 200 条（~10KB），应该产生 2-3 个段
            for (int i = 0; i < 200; i++) {
                wal.append(WalRecord.upsertPoint("c", new VectorPoint(
                        "id-" + i + "-" + Math.random(),   // random id 增长单条大小
                        randomVec(8))));
            }
            int segs = wal.segmentCount();
            assertTrue(segs >= 2, "Should have rotated at least once; segments=" + segs);
            System.out.printf("[WalFileRotation] wrote 200 records → %d segments%n", segs);

            // 读取所有段
            List<WalRecord> all = wal.readAll();
            assertEquals(200, all.size(), "Should replay all 200 records across segments");
        } finally {
            wal.close();
        }
    }

    @Test
    void truncateDeletesAllSegments() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString(), 2 * 1024);
        try {
            for (int i = 0; i < 100; i++) {
                wal.append(WalRecord.checkpoint(i));
            }
            assertTrue(wal.segmentCount() >= 2);

            wal.truncate();
            assertEquals(1, wal.segmentCount(),
                    "After truncate, only the active segment should remain");

            // 验证历史段文件已被删除
            for (int i = 1; i <= 5; i++) {
                Path seg = tmpDir.resolve("wal_" + i + ".log");
                assertFalse(java.nio.file.Files.exists(seg),
                        "Segment " + seg + " should be deleted after truncate");
            }
        } finally {
            wal.close();
        }
    }

    @Test
    void replayAcrossSegmentsPreservesOrder() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString(), 3 * 1024);
        try {
            // 写 80 条，强制 rotate 至少 1 次
            for (int i = 0; i < 80; i++) {
                wal.append(WalRecord.checkpoint(i));
            }
            int segs = wal.segmentCount();
            assertTrue(segs >= 2);

            // 重新打开（模拟重启）
            wal.close();
            WalFile wal2 = new WalFile(tmpDir.toString(), 3 * 1024);
            try {
                List<WalRecord> all = wal2.readAll();
                assertEquals(80, all.size());
                // 顺序应保持（按写入顺序），用 timestamp 单调性代替
                long prev = Long.MIN_VALUE;
                for (int i = 0; i < 80; i++) {
                    long ts = all.get(i).getTimestamp();
                    assertTrue(ts >= prev, "timestamps must be monotonic at " + i);
                    prev = ts;
                }
            } finally {
                wal2.close();
            }
        } catch (Exception e) {
            wal.close();
            throw e;
        }
    }

    @Test
    void manualRotate() throws IOException {
        WalFile wal = new WalFile(tmpDir.toString(), 1024 * 1024);   // 1MB 大阈值，几乎不会自动 rotate
        try {
            wal.append(WalRecord.checkpoint(1));
            wal.append(WalRecord.checkpoint(2));
            assertEquals(1, wal.segmentCount());

            wal.rotate();
            assertEquals(2, wal.segmentCount(),
                    "After manual rotate, should have 1 rotated + 1 active = 2");

            wal.append(WalRecord.checkpoint(3));
            // 重放应该看到全部 3 条（顺序：2 段按 wal_1.log → wal.log）
            List<WalRecord> all = wal.readAll();
            assertEquals(3, all.size());
        } finally {
            wal.close();
        }
    }

    @Test
    void persistentVectorStoreSurvivesRotation() throws IOException {
        // 端到端：PersistentVectorStore 触发 WAL rotate 后，重启应能完整恢复
        String dir = tmpDir.resolve("e2e-rotate").toString();
        com.zifang.z.vector.storage.PersistentVectorStore s1 =
                new com.zifang.z.vector.storage.PersistentVectorStore(dir);
        try {
            s1.createCollection("docs", 4, DistanceMetric.L2);
            // 写 200 条强制多次 checkpoint + WAL 累积
            for (int i = 0; i < 200; i++) {
                float[] v = new float[]{i % 10, (i / 10) % 10, 0, 0};
                s1.upsert("docs", new VectorPoint("p-" + i, v));
                if (i % 50 == 0) s1.flush("docs");
            }
        } finally {
            s1.close();
        }

        // 重启
        com.zifang.z.vector.storage.PersistentVectorStore s2 =
                new com.zifang.z.vector.storage.PersistentVectorStore(dir);
        try {
            assertEquals(200, s2.getPointCount("docs"));
        } finally {
            s2.close();
        }
    }

    private static float[] randomVec(int dim) {
        Random r = new Random();
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = r.nextFloat();
        return v;
    }

    // 兼容 WalRecord 接口
    private static class _Unused { ArrayList<?> x; }
}