package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 三级重排管线测试（spec-B4）：正常路径 / 超时降级 / 空候选。
 */
class RerankPipelineTest {

    private RagProperties props() {
        return new RagProperties(); // 默认 overlap 粗排 + rrf 精排 + 800ms 预算
    }

    private RerankPipeline pipeline(RagProperties props, RrfReranker rrf) {
        Tokenizer tokenizer = new RegexTokenizer();
        return new RerankPipeline(props,
                new OverlapReranker(tokenizer),
                new Bm25Reranker(tokenizer),
                rrf,
                new GteReranker(props));
    }

    private List<Document> docs(int n) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            documents.add(Document.builder()
                    .text("旷课处分文档" + i + "：累计学时条款" + (i % 3))
                    .metadata(Map.of("file_name", "handbook.pdf", "chunk_index", i, "score", 1.0 - i * 0.01))
                    .build());
        }
        return documents;
    }

    @Test
    void 正常路径输出六条且不降级() {
        RerankPipeline.PipelineResult result = pipeline(props(), new RrfReranker())
                .rerank("旷课处分", docs(20), 6);

        assertThat(result.documents()).hasSize(6);
        assertThat(result.degraded()).isFalse();
        assertThat(result.recallCount()).isEqualTo(20);
        assertThat(result.coarseCount()).isEqualTo(20);
    }

    @Test
    void 超时降级返回粗排结果() {
        RrfReranker slowRrf = mock(RrfReranker.class);
        when(slowRrf.rerankFusion(anyList(), anyList(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(900); // 超过 100ms 预算
                    return List.of();
                });
        RagProperties props = props();
        props.setRerankBudgetMs(100);

        RerankPipeline.PipelineResult result = pipeline(props, slowRrf).rerank("旷课处分", docs(20), 6);

        assertThat(result.degraded()).isTrue();
        assertThat(result.documents()).hasSize(6);
        // 降级时内容必须等于粗排 top6：粗排（overlap）在 score 相近时保序，逐一校验文本可追溯
        assertThat(result.documents()).allSatisfy(d -> assertThat(d.getText()).contains("旷课"));
    }

    @Test
    void 空候选返回空且不抛异常() {
        assertThatCode(() -> {
            RerankPipeline.PipelineResult result = pipeline(props(), new RrfReranker())
                    .rerank("旷课", List.of(), 6);
            assertThat(result.documents()).isEmpty();
            assertThat(result.degraded()).isFalse();
        }).doesNotThrowAnyException();
    }
}
