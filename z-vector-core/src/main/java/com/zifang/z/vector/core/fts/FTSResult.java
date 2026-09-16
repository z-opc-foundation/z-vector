package com.zifang.z.vector.core.fts;

/**
 * FTSResult - 全文检索结果
 */
public class FTSResult {

    private final String docId;
    private final double score;
    private final String content;

    public FTSResult(String docId, double score, String content) {
        this.docId = docId;
        this.score = score;
        this.content = content;
    }

    public String getDocId() {
        return docId;
    }

    public double getScore() {
        return score;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return "FTSResult{docId='" + docId + "', score=" + score + "}";
    }
}
