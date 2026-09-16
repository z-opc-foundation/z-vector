package com.zifang.z.vector.storage.wal;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL 文件读写单元测试。
 */
class WalFileTest {

    @TempDir
    Path dataDir;

    WalFile wal;

    @BeforeEach
    void setUp() throws IOException {
        wal = new WalFile(dataDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (wal != null) wal.close();
    }

    @Test
    void appendAndRead() throws IOException {
        wal.append(WalRecord.createCollection("docs", 768, DistanceMetric.COSINE, IndexType.HNSW));
        wal.append(WalRecord.upsertPoint("docs",
                new VectorPoint("d1", new float[]{1, 2, 3}, Map.of("lang", "zh"))));

        List<WalRecord> records = wal.readAll();
        assertEquals(2, records.size());
        assertEquals(WalOpType.CREATE_COLLECTION, records.get(0).getOp());
        assertEquals("docs", records.get(0).getCollection());
        assertEquals(WalOpType.UPSERT_POINT, records.get(1).getOp());
        assertEquals("d1", records.get(1).getPayload().contains("d1") ? "d1" : null);
    }

    @Test
    void sequenceNumber() throws IOException {
        assertEquals(0L, wal.getSequenceNumber());
        wal.append(WalRecord.deleteCollection("temp"));
        wal.append(WalRecord.deletePoint("docs", "d1"));
        assertEquals(2L, wal.getSequenceNumber());
    }

    @Test
    void truncate() throws IOException {
        wal.append(WalRecord.createCollection("a", 3, DistanceMetric.L2, IndexType.FLAT));
        wal.append(WalRecord.createCollection("b", 3, DistanceMetric.L2, IndexType.FLAT));
        assertEquals(2L, wal.getSequenceNumber());

        wal.truncate();
        assertEquals(0L, wal.getSequenceNumber());
        assertEquals(0, wal.readAll().size());
    }

    @Test
    void reopenAndReplay() throws IOException {
        wal.append(WalRecord.createCollection("docs", 768, DistanceMetric.COSINE, IndexType.HNSW));
        wal.append(WalRecord.upsertPoint("docs",
                new VectorPoint("d1", new float[]{1, 2, 3}, Map.of("lang", "zh"))));
        wal.close();

        wal = new WalFile(dataDir.toString());
        List<WalRecord> records = wal.readAll();
        assertEquals(2, records.size());
        assertEquals(2L, wal.getSequenceNumber()); // 已 replay
    }

    @Test
    void deletePointRecord() throws IOException {
        wal.append(WalRecord.deletePoint("docs", "d1"));
        WalRecord rec = wal.readAll().get(0);
        assertEquals(WalOpType.DELETE_POINT, rec.getOp());
        assertTrue(rec.getPayload().contains("d1"));
    }

    @Test
    void checkpointRecord() throws IOException {
        wal.append(WalRecord.checkpoint(42L));
        WalRecord rec = wal.readAll().get(0);
        assertEquals(WalOpType.CHECKPOINT, rec.getOp());
        assertTrue(rec.getPayload().contains("42"));
    }
}