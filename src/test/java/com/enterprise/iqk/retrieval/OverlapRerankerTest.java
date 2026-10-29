package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OverlapReranker 基线锁定测试（spec-B1）：故意断言当前缺陷行为，作为三级管线的对照基线。
 */
class OverlapRerankerTest {

    private final OverlapReranker reranker = new OverlapReranker(new RegexTokenizer());

    private ScoredDocument doc(String id, String content) {
        return ScoredDocument.builder().docId(id).content(content).build();
    }

    @Test
    void 长文档被压低_锁基线() {
        // 锁基线：B3 修复后需更新——scoreDoc = overlap / docTokens.size()，
        // 相同命中下长文档因分母大被系统性压低，这是当前已知缺陷行为
        String query = "旷课 处分";
        ScoredDocument shortDoc = doc("a", "旷课 处分");
        ScoredDocument longDoc = doc("b", "旷课 处分 " + "其他内容填充词。".repeat(60));

        List<ScoredDocument> ranked = reranker.rerank(query, List.of(shortDoc, longDoc), 2);

        assertThat(ranked.get(0).getDocId()).isEqualTo("a");
        assertThat(ranked.get(1).getFinalScore()).isLessThan(ranked.get(0).getFinalScore());
    }

    @Test
    void 空文档返回零分() {
        List<ScoredDocument> ranked = reranker.rerank("旷课", List.of(doc("empty", "")), 1);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).getFinalScore()).isEqualTo(0.0);
    }

    @Test
    void 零重叠时顺序不变() {
        ScoredDocument first = doc("first", "图书馆开放时间");
        ScoredDocument second = doc("second", "宿舍熄灯时间");

        List<ScoredDocument> ranked = reranker.rerank("奖学金评定", List.of(first, second), 2);

        assertThat(ranked).extracting(ScoredDocument::getDocId).containsExactly("first", "second");
        assertThat(ranked).allSatisfy(d -> assertThat(d.getFinalScore()).isEqualTo(0.0));
    }

    @Test
    void 中英混排能切出token() {
        // 注：regex 分词下中文整句是一个 token（\p{L} 包含汉字），英文数字按符号切开
        ScoredDocument mixed = doc("m", "GPA 3.0 要求");

        List<ScoredDocument> ranked = reranker.rerank("gpa", List.of(mixed), 1);

        assertThat(ranked.get(0).getFinalScore()).isGreaterThan(0.0);
    }
}
