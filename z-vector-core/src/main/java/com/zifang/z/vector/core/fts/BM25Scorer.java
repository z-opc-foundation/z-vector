package com.zifang.z.vector.core.fts;

import java.util.List;

/**
 * BM25Scorer - BM25 排序算法
 * <p>
 * 实现 Okapi BM25 排序函数，用于全文检索相关性评分。
 * 设计参考 Elasticsearch 的 BM25 实现。
 * <p>
 * 公式：BM25(D, Q) = Σ IDF(qi) * (f(qi, D) * (k1 + 1)) / (f(qi, D) + k1 * (1 - b + b * |D| / avgdl))
 * <p>
 * 参数：
 * <ul>
 *   <li>k1 = 1.5（词频饱和度）</li>
 *   <li>b = 0.75（文档长度归一化）</li>
 * </ul>
 */
public class BM25Scorer {

    private static final double DEFAULT_K1 = 1.5;
    private static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;

    public BM25Scorer() {
        this(DEFAULT_K1, DEFAULT_B);
    }

    public BM25Scorer(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    /**
     * 计算查询与文档的 BM25 分数
     *
     * @param queryTerms 查询词列表
     * @param docId 文档ID
     * @param index FTS索引
     * @return BM25 分数
     */
    public double score(List<String> queryTerms, String docId, FTSIndex index) {
        if (queryTerms == null || queryTerms.isEmpty()) return 0;

        double score = 0;
        int docLength = index.getDocumentLength(docId);
        double avgDocLength = index.getAverageDocumentLength();

        for (String term : queryTerms) {
            int tf = index.getTermFrequency(docId, term);
            int df = index.getDocumentFrequency(term);
            int N = index.getTotalDocuments();

            if (tf == 0 || df == 0) continue;

            // IDF 部分
            double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);

            // TF 归一化部分
            double tfNorm = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docLength / avgDocLength));

            score += idf * tfNorm;
        }

        return score;
    }

    public double getK1() {
        return k1;
    }

    public double getB() {
        return b;
    }
}
