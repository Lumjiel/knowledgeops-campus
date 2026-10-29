package com.enterprise.iqk.retrieval;

import java.util.List;

/**
 * 可插拔分词器（spec-B2）：regex 保留旧实现做 A/B 基线，bigram 为中文二元切分。
 * 实现选择由 rag.tokenizer 配置驱动，默认 regex 保证行为不变。
 */
public interface Tokenizer {

    List<String> tokenize(String text);

    String getName();
}
