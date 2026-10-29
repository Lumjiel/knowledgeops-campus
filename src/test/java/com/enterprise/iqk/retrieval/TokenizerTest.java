package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分词器测试（spec-B2）：bigram 规则 + regex 基线行为锁定。
 */
class TokenizerTest {

    private final BigramTokenizer bigram = new BigramTokenizer();
    private final RegexTokenizer regex = new RegexTokenizer();

    @Test
    void bigram_汉字数字混合切分符合规格示例() {
        // 规格示例："旷课50学时" → ["旷课","课5","50","学时"]
        List<String> tokens = bigram.tokenize("旷课50学时");

        assertThat(tokens).containsExactlyInAnyOrder("旷课", "课5", "50", "学时");
    }

    @Test
    void bigram_三字以上中文能切出多个token() {
        // 反向验证锚点：此测试在 bigram 退化为单 token 时必须变红
        List<String> tokens = bigram.tokenize("旷课处分办法");

        assertThat(tokens.size()).isGreaterThan(1);
        assertThat(tokens).contains("旷课", "处分");
    }

    @Test
    void bigram_标点归一化为空格() {
        List<String> tokens = bigram.tokenize("旷课，给予处分！");

        assertThat(tokens).contains("旷课", "给予", "处分");
        assertThat(tokens).noneMatch(t -> t.contains("，"));
    }

    @Test
    void bigram_英文整词保留() {
        // 数字里的 "." 是分隔符："3.0" → ["3","0"]（与 regex 行为一致）
        List<String> tokens = bigram.tokenize("GPA 3.0 university");

        assertThat(tokens).contains("gpa", "3", "0", "university");
    }

    @Test
    void regex_中文整句单token_锁基线() {
        // 锁基线：regex 分词的已知缺陷行为——连续汉字不切分
        List<String> tokens = regex.tokenize("旷课50学时");

        assertThat(tokens).containsExactly("旷课50学时");
    }

    @Test
    void 空文本返回空集() {
        assertThat(bigram.tokenize("")).isEmpty();
        assertThat(bigram.tokenize(null)).isEmpty();
        assertThat(regex.tokenize("")).isEmpty();
    }
}
