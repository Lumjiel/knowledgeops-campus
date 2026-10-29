package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * BM25 粗排测试（spec-B3）。
 */
class Bm25RerankerTest {

    private final Bm25Reranker reranker = new Bm25Reranker(new BigramTokenizer());

    private ScoredDocument doc(String id, String content) {
        return ScoredDocument.builder().docId(id).content(content).build();
    }

    @Test
    void 词频高者分高() {
        String query = "旷课";
        ScoredDocument twice = doc("twice", "旷课旷课累计处理");
        ScoredDocument once = doc("once", "旷课处理办法");

        List<ScoredDocument> ranked = reranker.rerank(query, List.of(once, twice), 2);

        assertThat(ranked.get(0).getDocId()).isEqualTo("twice");
    }

    @Test
    void 长文档不被过度惩罚() {
        String query = "旷课";
        ScoredDocument shortDoc = doc("short", "旷课说明");
        ScoredDocument longDoc = doc("long", "旷课相关条款。" + "其他细则说明内容。".repeat(30));

        List<ScoredDocument> ranked = reranker.rerank(query, List.of(shortDoc, longDoc), 2);

        // 长文档得分应低于短文档（长度归一化生效），但不得被压成 0
        assertThat(ranked).extracting(ScoredDocument::getDocId).startsWith("short");
        assertThat(ranked).extracting(ScoredDocument::getFinalScore)
                .allSatisfy(score -> assertThat(score).isGreaterThan(0.0));
    }

    @Test
    void 低频词权重大于高频词() {
        // rare 只在 1/3 文档出现，common 在 3/3 文档都出现，tf 同为 1、文档等长
        String query = "稀有词 常用词";
        ScoredDocument hasRare = doc("rare", "稀有词 内容");
        ScoredDocument hasCommon = doc("common", "常用词 内容");
        List<ScoredDocument> corpus = List.of(
                hasRare, hasCommon,
                doc("filler1", "常用词 填充"),
                doc("filler2", "常用词 填充"));

        List<ScoredDocument> ranked = reranker.rerank(query, corpus, 4);

        assertThat(ranked.get(0).getDocId()).isEqualTo("rare");
    }

    @Test
    void 空候选返回空() {
        assertThat(reranker.rerank("旷课", List.of(), 5)).isEmpty();
    }

    @Test
    void 纯停用词不崩() {
        List<ScoredDocument> docs = List.of(doc("a", "旷课处分"), doc("b", "转专业条件"));

        assertThatCode(() -> {
            List<ScoredDocument> ranked = reranker.rerank("！！！", docs, 2);
            assertThat(ranked).hasSize(2);
            assertThat(ranked).allSatisfy(d -> assertThat(d.getFinalScore()).isEqualTo(0.0));
        }).doesNotThrowAnyException();
    }
}
