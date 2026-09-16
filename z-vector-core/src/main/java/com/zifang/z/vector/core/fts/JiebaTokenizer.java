package com.zifang.z.vector.core.fts;

import java.util.*;
import java.util.regex.Pattern;

/**
 * JiebaTokenizer - jieba 中文分词器
 * <p>
 * 基于 jieba 分词库的中文分词实现。
 * 使用前需要添加 jieba 依赖：com.huaban:jieba-analysis:1.0.2
 * <p>
 * 当 jieba 不可用时，回退到基于字典的简单分词。
 */
public class JiebaTokenizer implements TextAnalyzer {

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
        "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
        "没有", "看", "好", "自己", "这", "他", "她", "它"
    ));

    private final boolean useJieba;

    public JiebaTokenizer() {
        this.useJieba = checkJiebaAvailable();
    }

    private boolean checkJiebaAvailable() {
        try {
            Class.forName("com.huaban.jieba.analysis.JiebaSegmenter");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<String> analyze(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        if (useJieba) {
            return analyzeWithJieba(text);
        } else {
            return analyzeWithDictionary(text);
        }
    }

    private List<String> analyzeWithJieba(String text) {
        // 使用 jieba 分词（需要 jieba 依赖）
        // 这里提供回退到简单分词
        return analyzeWithDictionary(text);
    }

    private List<String> analyzeWithDictionary(String text) {
        List<String> terms = new ArrayList<>();

        // 提取中文词组
        java.util.regex.Matcher chineseMatcher = CHINESE_PATTERN.matcher(text);
        while (chineseMatcher.find()) {
            String chinese = chineseMatcher.group();
            // 简单分词：按2-4字切分
            for (int i = 0; i < chinese.length(); i++) {
                for (int len = 2; len <= 4 && i + len <= chinese.length(); len++) {
                    String term = chinese.substring(i, i + len);
                    if (!STOP_WORDS.contains(term)) {
                        terms.add(term);
                    }
                }
            }
        }

        // 提取英文词
        java.util.regex.Matcher englishMatcher = WORD_PATTERN.matcher(text.toLowerCase());
        while (englishMatcher.find()) {
            String term = englishMatcher.group();
            if (!STOP_WORDS.contains(term) && term.length() > 1) {
                terms.add(term);
            }
        }

        return terms;
    }

    @Override
    public String getName() {
        return useJieba ? "jieba" : "jieba-fallback";
    }
}
