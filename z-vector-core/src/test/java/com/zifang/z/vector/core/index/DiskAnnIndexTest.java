package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.DistanceMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiskAnnIndex 磁盘索引测试
 */
class DiskAnnIndexTest {

    private DiskAnnIndex index;

    @BeforeEach
    void setUp() {
        index = new DiskAnnIndex(128, DistanceMetric.L2);
    }

    @Test
    void addVector() {
        float[] vector = new float[128];
        for (int i = 0; i < 128; i++) {
            vector[i] = (float) Math.random();
        }

        int nodeId = index.addVector(vector);
        assertEquals(0, nodeId);
        assertEquals(1, index.size());
    }

    @Test
    void addMultipleVectors() {
        for (int i = 0; i < 100; i++) {
            float[] vector = new float[128];
            for (int j = 0; j < 128; j++) {
                vector[j] = (float) Math.random();
            }
            index.addVector(vector);
        }

        assertEquals(100, index.size());
    }

    @Test
    void searchKnn() {
        // 添加一些向量
        for (int i = 0; i < 50; i++) {
            float[] vector = new float[128];
            for (int j = 0; j < 128; j++) {
                vector[j] = (float) Math.random();
            }
            index.addVector(vector);
        }

        System.out.println("Index size: " + index.size());

        // 搜索
        float[] query = new float[128];
        for (int i = 0; i < 128; i++) {
            query[i] = (float) Math.random();
        }

        List<Integer> results = index.search(query, 10);
        System.out.println("Search results: " + results.size());
        System.out.println("Index entry point: " + index.size());
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            System.out.println("Result " + i + ": " + results.get(i));
        }
        assertEquals(10, results.size());
    }

    @Test
    void dimensionMismatch() {
        float[] vector = new float[64]; // 错误的维度
        assertThrows(IllegalArgumentException.class, () -> {
            index.addVector(vector);
        });
    }

    @Test
    void clearIndex() {
        for (int i = 0; i < 10; i++) {
            float[] vector = new float[128];
            index.addVector(vector);
        }

        index.clear();
        assertEquals(0, index.size());
    }

    @Test
    void saveAndLoad(@TempDir Path tempDir) throws Exception {
        // 添加向量
        for (int i = 0; i < 20; i++) {
            float[] vector = new float[128];
            for (int j = 0; j < 128; j++) {
                vector[j] = (float) Math.random();
            }
            index.addVector(vector);
        }

        // 保存
        index.setIndexDir(tempDir);
        index.save(tempDir);
        assertFalse(index.isDirty());

        // 加载
        DiskAnnIndex loaded = DiskAnnIndex.load(tempDir, DistanceMetric.L2);
        assertEquals(20, loaded.size());
    }

    @Test
    void searchEmptyIndex() {
        float[] query = new float[128];
        List<Integer> results = index.search(query, 10);
        assertEquals(0, results.size());
    }

    @Test
    void searchSingleVector() {
        float[] vector = new float[128];
        for (int i = 0; i < 128; i++) {
            vector[i] = 1.0f;
        }
        index.addVector(vector);

        float[] query = new float[128];
        List<Integer> results = index.search(query, 10);
        assertEquals(1, results.size());
    }
}
