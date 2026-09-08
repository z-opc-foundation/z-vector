package com.zifang.z.vector.core.fts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FTSIndex 全文检索测试
 */
class FTSIndexTest {

    private FTSIndex index;

    @BeforeEach
    void setUp() {
        index = new FTSIndex();
    }

    @Test
    void addAndSearchDocument() {
        index.addDocument("doc1", "Java is a programming language");
        index.addDocument("doc2", "Java is used for web development");

        List<FTSResult> results = index.search("Java", 10);
        assertEquals(2, results.size());
    }

    @Test
    void searchWithMultipleTerms() {
        index.addDocument("doc1", "Java is a programming language");
        index.addDocument("doc2", "Python is a programming language");
        index.addDocument("doc3", "Java is used for web development");

        List<FTSResult> results = index.search("Java", 10);
        assertTrue(results.size() >= 2); // 至少有 Java 相关的文档
    }

    @Test
    void searchRelevanceRanking() {
        index.addDocument("doc1", "Java Java Java");
        index.addDocument("doc2", "Java programming");
        index.addDocument("doc3", "Python programming");

        List<FTSResult> results = index.search("Java", 10);
        assertEquals(2, results.size());
        // doc1 应该排在 doc2 前面（更多词频）
        assertEquals("doc1", results.get(0).getDocId());
    }

    @Test
    void removeDocument() {
        index.addDocument("doc1", "Java is a programming language");
        index.addDocument("doc2", "Java is used for web development");

        index.removeDocument("doc1");

        List<FTSResult> results = index.search("Java", 10);
        assertEquals(1, results.size());
        assertEquals("doc2", results.get(0).getDocId());
    }

    @Test
    void searchEmptyIndex() {
        List<FTSResult> results = index.search("Java", 10);
        assertEquals(0, results.size());
    }

    @Test
    void searchNoMatch() {
        index.addDocument("doc1", "Java is a programming language");

        List<FTSResult> results = index.search("Python", 10);
        assertEquals(0, results.size());
    }

    @Test
    void batchSizeAdd() {
        java.util.Map<String, String> docs = new java.util.HashMap<>();
        docs.put("doc1", "Java is a programming language");
        docs.put("doc2", "Python is a programming language");
        docs.put("doc3", "Java is used for web development");

        index.addDocuments(docs);

        assertEquals(3, index.size());
    }

    @Test
    void clearIndex() {
        index.addDocument("doc1", "Java is a programming language");
        index.addDocument("doc2", "Java is used for web development");

        index.clear();

        assertEquals(0, index.size());
        assertTrue(index.isEmpty());
    }

    @Test
    void getDocumentLength() {
        index.addDocument("doc1", "Java is a programming language");

        int length = index.getDocumentLength("doc1");
        assertTrue(length > 0);
    }

    @Test
    void getTermFrequency() {
        index.addDocument("doc1", "Java Java Java is a language");

        int tf = index.getTermFrequency("doc1", "java");
        assertEquals(3, tf);
    }

    @Test
    void getDocumentFrequency() {
        index.addDocument("doc1", "Java is a programming language");
        index.addDocument("doc2", "Java is used for web development");

        int df = index.getDocumentFrequency("java");
        assertEquals(2, df);
    }

    @Test
    void searchWithChinese() {
        index.addDocument("doc1", "Java 是一种编程语言");
        index.addDocument("doc2", "Python 也是一种编程语言");

        List<FTSResult> results = index.search("编程", 10);
        assertTrue(results.size() > 0);
    }
}
