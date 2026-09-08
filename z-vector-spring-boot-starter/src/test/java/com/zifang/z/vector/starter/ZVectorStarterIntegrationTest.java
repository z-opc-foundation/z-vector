package com.zifang.z.vector.starter;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot 自动装配集成测试。
 */
@SpringBootTest(classes = ZVectorStarterIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "zvector.storage-type=in-memory",
        "zvector.server.port=0"  // 禁用 REST（测试不需要）
})
class ZVectorStarterIntegrationTest {

    @Autowired
    VectorStore vectorStore;

    @Test
    void autowireVectorStore() {
        assertNotNull(vectorStore);
    }

    @Test
    void useVectorStoreViaSpring() {
        vectorStore.createCollection("docs", 3, DistanceMetric.L2);
        vectorStore.upsert("docs", new VectorPoint("d1", new float[]{1, 0, 0},
                Map.of("lang", "zh")));
        vectorStore.upsert("docs", new VectorPoint("d2", new float[]{0, 1, 0},
                Map.of("lang", "en")));

        List<SearchResult> results = vectorStore.search("docs",
                new float[]{1, 0, 0}, 2, null);
        assertEquals(2, results.size());
        assertEquals("d1", results.get(0).getVectorId());
    }

    @Test
    void listAndDeleteCollection() {
        vectorStore.createCollection("temp", 64, DistanceMetric.L2);
        assertTrue(vectorStore.listCollections().contains("temp"));
        vectorStore.deleteCollection("temp");
        assertFalse(vectorStore.listCollections().contains("temp"));
    }

    @EnableAutoConfiguration
    @Configuration
    public static class TestConfig {
    }
}