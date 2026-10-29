package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 配置驱动的分词器入口（spec-B2）：rag.tokenizer = regex | bigram。
 * 默认 regex，保证重构前后行为不变；四个调用点统一注入本类。
 */
@Primary
@Component
public class ConfigurableTokenizer implements Tokenizer {

    private final RagProperties ragProperties;
    private final RegexTokenizer regexTokenizer;
    private final BigramTokenizer bigramTokenizer;

    public ConfigurableTokenizer(RagProperties ragProperties,
                                 RegexTokenizer regexTokenizer,
                                 BigramTokenizer bigramTokenizer) {
        this.ragProperties = ragProperties;
        this.regexTokenizer = regexTokenizer;
        this.bigramTokenizer = bigramTokenizer;
    }

    @Override
    public List<String> tokenize(String text) {
        return "bigram".equalsIgnoreCase(ragProperties.getTokenizer())
                ? bigramTokenizer.tokenize(text)
                : regexTokenizer.tokenize(text);
    }

    @Override
    public String getName() {
        return "bigram".equalsIgnoreCase(ragProperties.getTokenizer()) ? "bigram" : "regex";
    }
}
