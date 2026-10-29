package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 旧实现原样保留（spec-B2）：连续汉字会整句成单 token（已知缺陷，作为 A/B 基线）。
 * ⚠ 唯一允许出现 \p{L} 正则的地方。
 */
@Component
public class RegexTokenizer implements Tokenizer {

    @Override
    public List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    @Override
    public String getName() {
        return "regex";
    }
}
