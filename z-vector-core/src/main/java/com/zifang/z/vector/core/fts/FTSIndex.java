package com.zifang.z.vector.core.fts;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FTSIndex - 全文检索倒排索引
 * <p>
 * 设计参考 Elasticsearch 的倒排索引 + BM25 排序。
 * 支持中英文分词，默认使用 SimpleTokenizer（空格/标点分割），
 * 可扩展为 jieba 中文分词（通过 SPI 机制）。
 * <p>
 * 索引结构：
 * <ul>
 *   <li>term -> Set文档ID> (倒排表)</li>
 *   <li>文档ID -> term frequency (词频)</li>
 *   <li>文档ID -> document length (文档长度)</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * FTSIndex index = new FTSIndex();
 * index.addDocument("doc1", "Java is a programming language");
 * index.addDocument("doc2", "Java is used for web development");
 *
 * List<FTSResult> results = index.search("Java programming", 10);
 * for (FTSResult r : results) {
 *     System.out.println(r.getDocId() + " score=" + r.getScore());
 * }
 * }</pre>
 */
public class FTSIndex {

    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>(); // term -> docIds
    private final Map<String, Map<String, Integer>> termFrequencies = new ConcurrentHashMap<>(); // docId -> term -> tf
    private final Map<String, Integer> documentLengths = new ConcurrentHashMap<>(); // docId -> length
    private final Map<String, String> documentContents = new ConcurrentHashMap<>(); // docId -> original content
    private int totalDocuments = 0;
    private long totalTerms = 0;

    private final TextAnalyzer analyzer;
    private final BM25Scorer scorer;

    public FTSIndex() {
        this(new SimpleTokenizer(), new BM25Scorer());
    }

    public FTSIndex(TextAnalyzer analyzer, BM25Scorer scorer) {
        this.analyzer = analyzer;
        this.scorer = scorer;
    }

    /**
     * 添加文档到索引
     */
    public void addDocument(String docId, String content) {
        if (docId == null || content == null) return;

        // 移除旧文档（如果存在）
        removeDocument(docId);

        // 分词
        List<String> terms = analyzer.analyze(content);

        // 计算词频
        Map<String, Integer> tf = new HashMap<>();
        for (String term : terms) {
            tf.merge(term, 1, Integer::sum);
        }

        // 存储
        documentContents.put(docId, content);
        documentLengths.put(docId, terms.size());
        termFrequencies.put(docId, tf);

        // 更新倒排索引
        for (String term : tf.keySet()) {
            invertedIndex.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet()).add(docId);
        }

        totalDocuments++;
        totalTerms += terms.size();
    }

    /**
     * 批量添加文档
     */
    public void addDocuments(Map<String, String> documents) {
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            addDocument(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 移除文档
     */
    public void removeDocument(String docId) {
        if (!documentContents.containsKey(docId)) return;

        String content = documentContents.remove(docId);
        documentLengths.remove(docId);
        Map<String, Integer> tf = termFrequencies.remove(docId);

        if (tf != null) {
            for (String term : tf.keySet()) {
                Set<String> docs = invertedIndex.get(term);
                if (docs != null) {
                    docs.remove(docId);
                    if (docs.isEmpty()) {
                        invertedIndex.remove(term);
                    }
                }
            }
        }

        totalDocuments--;
    }

    /**
     * 搜索文档
     */
    public List<FTSResult> search(String query, int topK) {
        List<String> queryTerms = analyzer.analyze(query);
        if (queryTerms.isEmpty()) return Collections.emptyList();

        // 收集所有候选文档
        Set<String> candidateDocs = new HashSet<>();
        for (String term : queryTerms) {
            Set<String> docs = invertedIndex.get(term);
            if (docs != null) {
                candidateDocs.addAll(docs);
            }
        }

        // 计算 BM25 分数
        List<FTSResult> results = new ArrayList<>();
        for (String docId : candidateDocs) {
            double score = scorer.score(queryTerms, docId, this);
            if (score > 0) {
                results.add(new FTSResult(docId, score, documentContents.get(docId)));
            }
        }

        // 按分数降序排序
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        // 返回 topK
        if (results.size() > topK) {
            return results.subList(0, topK);
        }
        return results;
    }

    /**
     * 获取文档长度
     */
    public int getDocumentLength(String docId) {
        return documentLengths.getOrDefault(docId, 0);
    }

    /**
     * 获取词频
     */
    public int getTermFrequency(String docId, String term) {
        Map<String, Integer> tf = termFrequencies.get(docId);
        return tf != null ? tf.getOrDefault(term, 0) : 0;
    }

    /**
     * 获取包含某词的文档数
     */
    public int getDocumentFrequency(String term) {
        Set<String> docs = invertedIndex.get(term);
        return docs != null ? docs.size() : 0;
    }

    /**
     * 获取总文档数
     */
    public int getTotalDocuments() {
        return totalDocuments;
    }

    /**
     * 获取平均文档长度
     */
    public double getAverageDocumentLength() {
        if (totalDocuments == 0) return 0;
        return (double) totalTerms / totalDocuments;
    }

    /**
     * 索引是否为空
     */
    public boolean isEmpty() {
        return totalDocuments == 0;
    }

    /**
     * 清空索引
     */
    public void clear() {
        invertedIndex.clear();
        termFrequencies.clear();
        documentLengths.clear();
        documentContents.clear();
        totalDocuments = 0;
        totalTerms = 0;
    }

    /**
     * 获取索引大小（文档数）
     */
    public int size() {
        return totalDocuments;
    }
}
