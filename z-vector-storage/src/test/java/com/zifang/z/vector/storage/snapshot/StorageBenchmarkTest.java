package com.zifang.z.vector.storage.snapshot;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageStore;
import com.zifang.z.vector.storage.page.PageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v1 (JSON Snapshot) vs v2 (Page-based HybridSnapshot) 性能基准对比。
 * <p>
 * 测试不同数据规模下的写 / 读时间 + 文件大小，最后打印成对比表。
 * <p>
 * 期望 v2 表现：
 * <ul>
 *   <li>写：通常 v2 ≥ v1（多一层 PageStore 写入），但 v2 写入可分页；</li>
 *   <li>读：v2 ≫ v1（无 Jackson 解析，且 mmap 可加速）；</li>
 *   <li>文件大小：v2 < v1（紧凑二进制 vs JSON 字符串）；</li>
 *   <li>CPU：v2 更低（无字符串/JSON 解析）。</li>
 * </ul>
 */
class StorageBenchmarkTest {

    @TempDir
    Path tmpDir;

    private static final int[] SCALES = {100, 500, 2000};

    @Test
    void compareV1VsV2() throws IOException {
        System.out.println("\n========== Storage Benchmark: v1 (JSON) vs v2 (Page-based) ==========");
        System.out.printf("%-8s %-12s %-14s %-14s %-14s%n",
                "points", "format", "write(ms)", "read(ms)", "size(KB)");
        System.out.println("---------------------------------------------------------------------");

        for (int n : SCALES) {
            int dim = 128;
            List<VectorPoint> points = generatePoints("bench", n, dim);

            // v1 (JSON Snapshot)
            Path v1Dir = tmpDir.resolve("v1-" + n);
            java.nio.file.Files.createDirectories(v1Dir);
            long v1Start = System.nanoTime();
            Snapshot v1Snap = new Snapshot(v1Dir.toString());
            v1Snap.write(Collections.singletonList(
                    new Snapshot.CollectionData("bench", dim,
                            DistanceMetric.COSINE, IndexType.FLAT, new HashMap<>(), points)));
            long v1WriteMs = (System.nanoTime() - v1Start) / 1_000_000;

            long v1ReadStart = System.nanoTime();
            List<Snapshot.CollectionData> v1ReadBack = v1Snap.read();
            long v1ReadMs = (System.nanoTime() - v1ReadStart) / 1_000_000;

            long v1Size = v1Snap.getPath().toFile().length();
            assertEquals(n, v1ReadBack.get(0).points.size());

            System.out.printf("%-8d %-12s %-14d %-14d %-14d%n",
                    n, "v1-json", v1WriteMs, v1ReadMs, v1Size / 1024);

            // v2 (Page-based HybridSnapshot)
            Path v2Dir = tmpDir.resolve("v2-" + n);
            java.nio.file.Files.createDirectories(v2Dir);
            long v2Start = System.nanoTime();
            HybridSnapshot v2Snap = new HybridSnapshot(v2Dir.toString());
            v2Snap.writeV2(Collections.singletonList(
                    new HybridSnapshot.CollectionSnapshot("bench", dim,
                            DistanceMetric.COSINE, IndexType.FLAT, new HashMap<>(), points)));
            long v2WriteMs = (System.nanoTime() - v2Start) / 1_000_000;

            long v2ReadStart = System.nanoTime();
            List<HybridSnapshot.CollectionSnapshot> v2ReadBack = v2Snap.read();
            long v2ReadMs = (System.nanoTime() - v2ReadStart) / 1_000_000;

            long v2Size = totalSize(v2Dir);
            assertEquals(n, v2ReadBack.get(0).points.size());

            System.out.printf("%-8d %-12s %-14d %-14d %-14d%n",
                    n, "v2-page", v2WriteMs, v2ReadMs, v2Size / 1024);

            // 对比摘要
            double sizeRatio = v1Size > 0 ? (double) v2Size / v1Size : 1.0;
            double readSpeedup = v1ReadMs > 0 ? (double) v1ReadMs / Math.max(v2ReadMs, 1) : 1.0;
            System.out.printf("           → size ratio v2/v1=%.2f,  read speedup=%.2fx%n",
                    sizeRatio, readSpeedup);
            System.out.println();
        }

        System.out.println("=====================================================================\n");
    }

    @Test
    void pageStoreFragmentationOverTime() throws IOException {
        System.out.println("\n========== PageStore 碎片化 / 连续追加性能 ==========");
        int dim = 128;
        int pageSize = 65536;  // 64KB
        PageStore store = new PageStore(tmpDir.toString(), "frag");
        PageId id = PageId.of("frag", PageType.DATA, 0);
        byte[] payload = new byte[pageSize - 17 - 4];   // fill the page
        Random r = new Random(42);
        r.nextBytes(payload);

        int writes = 100;
        long start = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            store.write(new Page(PageId.of("frag", PageType.DATA, i), payload));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long size = store.diskSize();
        System.out.printf("Wrote %d pages × %dB = %.2f MB in %d ms (%.2f MB/s)%n",
                writes, pageSize, size / (1024.0 * 1024.0),
                elapsedMs, size / 1024.0 / 1024.0 / Math.max(elapsedMs / 1000.0, 0.001));

        // 列出所有 pageNo（应该全部存在）
        Set<Integer> pageNos = store.listPageNos();
        assertEquals(writes, pageNos.size());
        System.out.printf("Total pages on disk: %d (expected %d)%n", pageNos.size(), writes);
    }

    private static long totalSize(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(dir)) {
            return stream.filter(java.nio.file.Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return java.nio.file.Files.size(p); }
                        catch (IOException e) { return 0; }
                    }).sum();
        }
    }

    private static List<VectorPoint> generatePoints(String prefix, int n, int dim) {
        List<VectorPoint> list = new ArrayList<>(n);
        Random r = new Random(42);
        for (int i = 0; i < n; i++) {
            float[] vec = new float[dim];
            for (int d = 0; d < dim; d++) vec[d] = r.nextFloat();
            Map<String, Object> payload = new HashMap<>();
            payload.put("category", "cat-" + (i % 10));
            payload.put("priority", i % 5);
            list.add(new VectorPoint(prefix + "-" + i, vec, payload));
        }
        return list;
    }
}