package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 中文二元切分（spec-B2），不引第三方分词依赖。
 * 规则：标点先归一化为空格；连续汉字串每 2 字一个 token（长度 1 保留原样）；
 * 英文数字串整段保留；汉字串后紧跟字母数字串时补一个跨界 bigram。
 * 例："旷课50学时" → ["旷课", "课5", "50", "学时"]。
 */
@Component
public class BigramTokenizer implements Tokenizer {

    @Override
    public List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}\\p{L}\\p{Nd}]+", " ")
                .trim();
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String run : normalized.split("\\s+")) {
            tokenizeRun(run, tokens);
        }
        return tokens;
    }

    private void tokenizeRun(String run, List<String> tokens) {
        int i = 0;
        while (i < run.length()) {
            if (isHan(run.charAt(i))) {
                int j = i;
                while (j < run.length() && isHan(run.charAt(j))) {
                    j++;
                }
                addHanBigrams(tokens, run.substring(i, j));
                if (j < run.length()) {
                    // 汉字串 → 字母数字串的跨界 bigram："旷课50" 的 "课5"
                    tokens.add("" + run.charAt(j - 1) + run.charAt(j));
                }
                i = j;
            } else {
                int j = i;
                while (j < run.length() && !isHan(run.charAt(j))) {
                    j++;
                }
                tokens.add(run.substring(i, j));
                i = j;
            }
        }
    }

    private void addHanBigrams(List<String> tokens, String han) {
        if (han.length() == 1) {
            tokens.add(han);
            return;
        }
        for (int k = 0; k < han.length() - 1; k++) {
            tokens.add(han.substring(k, k + 2));
        }
    }

    private boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    @Override
    public String getName() {
        return "bigram";
    }
}
