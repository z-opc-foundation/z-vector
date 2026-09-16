package com.zifang.z.vector.core.fts;

import java.util.List;

/**
 * TextAnalyzer - 文本分析器接口
 * <p>
 * 负责将文本拆分为词元（terms），支持不同语言的分词策略。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link SimpleTokenizer} - 空格/标点分割（默认）</li>
 *   <li>{@link JiebaTokenizer} - jieba 中文分词（需外部依赖）</li>
 * </ul>
 */
public interface TextAnalyzer {

    /**
     * 分析文本，返回词元列表
     *
     * @param text 输入文本
     * @return 词元列表（已转小写、去停用词）
     */
    List<String> analyze(String text);

    /**
     * 获取分析器名称
     */
    String getName();
}
