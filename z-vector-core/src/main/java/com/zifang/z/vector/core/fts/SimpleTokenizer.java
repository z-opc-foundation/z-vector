package com.zifang.z.vector.core.fts;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SimpleTokenizer - 简单分词器
 * <p>
 * 基于空格和标点分割，支持英文和基本中文。
 * 默认去除停用词（the, a, an, is, are, etc.）。
 */
public class SimpleTokenizer implements TextAnalyzer {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        // English stop words
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "need", "dare", "ought",
        "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
        "as", "into", "through", "during", "before", "after", "above", "below",
        "between", "out", "off", "over", "under", "again", "further", "then",
        "once", "here", "there", "when", "where", "why", "how", "all", "any",
        "both", "each", "few", "more", "most", "other", "some", "such", "no",
        "nor", "not", "only", "own", "same", "so", "than", "too", "very",
        "and", "but", "or", "because", "until", "while", "it", "its",
        // Chinese stop words
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
        "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
        "没有", "看", "好", "自己", "这", "他", "她", "它"
    ));

    @Override
    public List<String> analyze(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> terms = new ArrayList<>();
        String lower = text.toLowerCase();

        // 提取连续的 Unicode 字母/数字序列
        java.util.regex.Matcher matcher = WORD_PATTERN.matcher(lower);
        while (matcher.find()) {
            String token = matcher.group();
            if (STOP_WORDS.contains(token) || token.length() <= 1) {
                continue;
            }

            // 判断是否包含中文字符
            if (containsChinese(token)) {
                // 中文: 逐字拆分 + bigram
                addChineseBigrams(token, terms);
            } else {
                // 英文: 直接作为 term
                terms.add(token);
            }
        }

        return terms;
    }

    /**
     * 判断字符串是否包含中文字符
     */
    private boolean containsChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                return true;
            }
        }
        return false;
    }

    /**
     * 对中文 token 生成 unigram + bigram:
     * "编程语言" → ["编", "程", "言", "编程", "程语", "语言"]
     * 这样搜索 "编程" 或单字都能命中。
     */
    private void addChineseBigrams(String token, List<String> terms) {
        // 提取中文字符序列
        List<Character> chars = new ArrayList<>();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                chars.add(c);
            }
        }

        // unigram（单字）
        for (char c : chars) {
            String s = String.valueOf(c);
            if (!STOP_WORDS.contains(s)) {
                terms.add(s);
            }
        }

        // bigram（双字组合）
        for (int i = 0; i < chars.size() - 1; i++) {
            terms.add("" + chars.get(i) + chars.get(i + 1));
        }
    }

    @Override
    public String getName() {
        return "simple";
    }
}
