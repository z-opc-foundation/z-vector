package com.zifang.z.vector.storage.engine;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.bloom.BloomFilter;
import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageType;
import com.zifang.z.vector.storage.wal.WalFile;
import com.zifang.z.vector.storage.wal.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StorageEngine 集成测试 — 验证 page + bloom + async WAL 三者协同工作。
 */
class StorageEngineTest {

    @TempDir
    Path tmpDir;

    @Test
    void bloomFilterTracksUpserts() throws IOException {
        StorageEngine engine = newStorage();
        for (int i = 0; i < 100; i++) {
            VectorPoint p = new VectorPoint("doc_" + i, new float[]{1, 2, 3});
            engine.markBloom("docs", p.getId());
            engine.appendWal(WalRecord.upsertPoint("docs", p));
        }
        engine.flushWal();

        // bloom 应包含所有 100 个 id
        for (int i = 0; i < 100; i++) {
            assertTrue(engine.mightContain("docs", "doc_" + i));
        }
        // 不应包含的 id（不保证 false negative，但 bloom 报告 false negative 不可能）
        for (int i = 100; i < 110; i++) {
            // 这里不强求 false positive 一定出现；只验证调用不抛异常
            engine.mightContain("docs", "doc_" + i);
        }
        engine.close();
    }

    @Test
    void rebuildBloomFromPointsWorks() throws IOException {
        StorageEngine engine = newStorage();
        List<VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            points.add(new VectorPoint("p_" + i, new float[]{i, i + 1}));
        }
        engine.rebuildBloom("docs", points);
        assertEquals(50, engine.bloomInsertedCount("docs"));
        for (VectorPoint p : points) {
            assertTrue(engine.mightContain("docs", p.getId()));
        }
        engine.close();
    }

    @Test
    void pageReadWriteThroughBufferPool() throws IOException {
        StorageEngine engine = newStorage(8);
        PageId id = PageId.of("docs", PageType.DATA, 0);
        Page page = new Page(id, "hello page".getBytes());
        engine.writePage(page);

        // 写完后缓存中应有此 page
        Page cached = engine.fetchPage(id);
        assertArrayEquals("hello page".getBytes(), cached.payload());

        // BufferPool hit: 同一 id 第二次 fetch 应是 hit
        engine.fetchPage(id);
        engine.fetchPage(id);
        assertTrue(engine.bufferPool().hits() >= 2);
        engine.close();
    }

    @Test
    void bufferPoolHitRateImprovesWithRepeats() throws IOException {
        StorageEngine engine = newStorage(64);
        // 写入 10 个 page
        for (int i = 0; i < 10; i++) {
            PageId id = PageId.of("docs", PageType.DATA, i);
            engine.writePage(new Page(id, ("p" + i).getBytes()));
        }
        // 反复读 page 0
        for (int i = 0; i < 50; i++) {
            engine.fetchPage(PageId.of("docs", PageType.DATA, 0));
        }
        // 命中率应远高于 50%（因为 page 0 反复 hit）
        assertTrue(engine.bufferPool().hitRate() > 0.5,
                "Expected hit rate > 0.5, got " + engine.bufferPool().hitRate());
        engine.close();
    }

    @Test
    void flushDirtyPagesWritesThroughBufferPool() throws IOException {
        StorageEngine engine = newStorage(8);
        PageId id = PageId.of("docs", PageType.DATA, 0);
        Page page = new Page(id, "dirty-data".getBytes());
        page.dirty = true;
        engine.bufferPool().put(page);
        int flushed = engine.flushDirtyPages();
        assertEquals(1, flushed);

        // 重新读应拿到相同 payload
        Page reloaded = engine.fetchPage(id);
        assertArrayEquals("dirty-data".getBytes(), reloaded.payload());
        engine.close();
    }

    @Test
    void asyncWalBatchesWrites() throws IOException {
        StorageEngine engine = newStorage();
        for (int i = 0; i < 200; i++) {
            engine.appendWal(WalRecord.checkpoint(i));
        }
        engine.flushWal();
        // 200 条记录应该被批处理（< 200 batches）
        assertTrue(engine.walBatches() < 200,
                "Expected batch count < 200, got " + engine.walBatches());
        engine.close();
    }

    @Test
    void integrationFullWorkflow() throws IOException {
        // 完整工作流：upsert → mark bloom → write page → flush wal → close
        StorageEngine engine = newStorage(16);

        Random r = new Random(42);
        List<VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            float[] v = new float[4];
            for (int j = 0; j < 4; j++) v[j] = r.nextFloat();
            VectorPoint p = new VectorPoint("d_" + i, v,
                    java.util.Map.of("idx", i));
            points.add(p);
        }

        // 1. 写入 WAL + mark bloom
        for (VectorPoint p : points) {
            engine.appendWal(WalRecord.upsertPoint("docs", p));
            engine.markBloom("docs", p.getId());
        }
        engine.flushWal();

        // 2. 写入 DATA page
        for (int i = 0; i < points.size(); i++) {
            VectorPoint p = points.get(i);
            PageId pid = PageId.of("docs", PageType.DATA, i);
            String payload = "doc=" + p.getId() + ",idx=" + p.getPayload().get("idx");
            engine.writePage(new Page(pid, payload.getBytes()));
        }
        engine.flushDirtyPages();

        // 3. 验证 bloom 全包含
        for (VectorPoint p : points) {
            assertTrue(engine.mightContain("docs", p.getId()));
        }

        // 4. 验证 page 可读
        for (int i = 0; i < points.size(); i++) {
            PageId pid = PageId.of("docs", PageType.DATA, i);
            Page read = engine.fetchPage(pid);
            String text = new String(read.payload());
            assertTrue(text.startsWith("doc=d_" + i),
                    "Expected page " + i + " to start with doc=d_" + i + " but was: " + text);
        }

        // 5. 关闭
        engine.close();

        // 6. 验证 WAL 已落盘
        WalFile wal = new WalFile(tmpDir.toString());
        List<WalRecord> records = wal.readAll();
        assertEquals(100, records.size());
        wal.close();
    }

    @Test
    void metricsReportIncludesAllComponents() throws IOException {
        StorageEngine engine = newStorage();
        // 触发一些操作让指标非零
        for (int i = 0; i < 10; i++) {
            engine.markBloom("c", "id_" + i);
            engine.appendWal(WalRecord.checkpoint(i));
        }
        engine.flushWal();

        String m = engine.metrics();
        assertTrue(m.contains("walAppended=10"));
        assertTrue(m.contains("walFlushed=10"));
        assertTrue(m.contains("bloomFilters=1"));
        engine.close();
    }

    @Test
    void multipleCollectionsHaveIndependentBlooms() throws IOException {
        StorageEngine engine = newStorage();
        engine.markBloom("c1", "id_1");
        engine.markBloom("c2", "id_2");

        assertTrue(engine.mightContain("c1", "id_1"));
        assertFalse(engine.mightContain("c1", "id_2"));  // c1 不应包含 id_2
        assertTrue(engine.mightContain("c2", "id_2"));
        assertFalse(engine.mightContain("c2", "id_1"));  // c2 不应包含 id_1
        engine.close();
    }

    @Test
    void pageStorePersistsAcrossReopen() throws IOException {
        // 写一页 → 关闭 → 重启 → 应能从磁盘读到
        StorageEngine engine1 = newStorage(8);
        PageId id = PageId.of("docs", PageType.DATA, 0);
        engine1.writePage(new Page(id, "persistent data".getBytes()));
        engine1.flushDirtyPages();
        engine1.close();

        // 用新 StorageEngine 读（BufferPool 是空的，所以会从 PageStore 加载）
        StorageEngine engine2 = newStorage(8);
        Page read = engine2.fetchPage(id);
        assertArrayEquals("persistent data".getBytes(), read.payload());
        assertEquals(1, engine2.bufferPool().misses(),
                "First read after reopen should be a miss");
        engine2.close();
    }

    // ==================== 工具 ====================

    private StorageEngine newStorage() throws IOException {
        return newStorage(StorageEngine.DEFAULT_BUFFER_PAGES);
    }

    private StorageEngine newStorage(int bufferPages) throws IOException {
        WalFile wal = new WalFile(tmpDir.toString());
        return new StorageEngine(tmpDir.toString(), wal,
                StorageEngine.DEFAULT_BLOOM_EXPECTED,
                StorageEngine.DEFAULT_BLOOM_FP_RATE,
                bufferPages);
    }

    @SuppressWarnings("unused")
    private void ensureDistanceMetric() {
        // 引用避免 import warning
        DistanceMetric.L2.toString();
        IndexType.HNSW.toString();
    }
}
