package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    private VectorRetriever vectorRetriever;
    private KeywordRetriever keywordRetriever;
    private GraphRetriever graphRetriever;
    private WebRetriever webRetriever;

    @BeforeEach
    void setUp() {
        vectorRetriever = mock(VectorRetriever.class);
        keywordRetriever = mock(KeywordRetriever.class);
        graphRetriever = mock(GraphRetriever.class);
        webRetriever = mock(WebRetriever.class);
    }

    @Test
    void degradesWhenOneRetrievalSourceFails() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever,
                keywordRetriever,
                graphRetriever,
                webRetriever,
                new SimpleMeterRegistry()
        );

        when(vectorRetriever.retrieve("spring ai", "tenant", "chat")).thenThrow(new IllegalStateException("down"));
        when(keywordRetriever.retrieve("spring ai", "tenant", "chat", 3))
                .thenReturn(List.of(doc("kw-1", "keyword", "same content", 0.4)));
        when(graphRetriever.retrieve("spring ai", "tenant", 3))
                .thenReturn(List.of(doc("graph-1", "graph", "same content", 0.9)));
        when(webRetriever.retrieve("spring ai", 3)).thenReturn(List.of(doc("web-1", "web", "web content", 0.7)));

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("spring ai", "tenant", "chat", 3);

        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("graph-1", "web-1");
        assertThat(result.totalBeforeDedup()).isEqualTo(3);
        assertThat(result.totalAfterDedup()).isEqualTo(2);
    }

    @Test
    void deduplicatesByContentFingerprintAndSortsByFinalScore() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever,
                keywordRetriever,
                graphRetriever,
                webRetriever,
                new SimpleMeterRegistry()
        );

        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "spring ai tutorial basics", 0.9)));
        when(keywordRetriever.retrieve(eq("q"), eq("tenant"), eq("chat"), eq(5)))
                .thenReturn(List.of(doc("kw-1", "keyword", "spring ai tutorial basics", 0.5),
                        doc("kw-2", "keyword", "another unique chunk", 0.4)));
        when(graphRetriever.retrieve(eq("q"), eq("tenant"), eq(5)))
                .thenReturn(List.of());
        when(webRetriever.retrieve(eq("q"), eq(5))).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 5);

        // 3 raw docs, 2 unique fingerprints after dedup
        assertThat(result.totalBeforeDedup()).isEqualTo(3);
        assertThat(result.totalAfterDedup()).isEqualTo(2);
        // vector 0.9 * 0.40 = 0.36 wins over keyword 0.5 * 0.25 = 0.125 for the
        // shared content
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("vec-1", "kw-2");
        assertThat(result.documents().get(0).getFinalScore()).isGreaterThan(
                result.documents().get(1).getFinalScore());
    }

    @Test
    void allSourcesFailingReturnsEmptyResult() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever,
                keywordRetriever,
                graphRetriever,
                webRetriever,
                new SimpleMeterRegistry()
        );

        when(vectorRetriever.retrieve("q", "tenant", "chat")).thenThrow(new RuntimeException("a"));
        when(keywordRetriever.retrieve(any(), any(), any(), anyInt())).thenThrow(new RuntimeException("b"));
        when(graphRetriever.retrieve(any(), any(), anyInt())).thenThrow(new RuntimeException("c"));
        when(webRetriever.retrieve(any(), anyInt())).thenThrow(new RuntimeException("d"));

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 3);

        assertThat(result.documents()).isEmpty();
        assertThat(result.totalBeforeDedup()).isZero();
        assertThat(result.totalAfterDedup()).isZero();
    }

    // ── 权重归一化：非 1 和按比例归一，全 0 回退 BALANCED ─────────

    @Test
    void normalizesWeightsThatDoNotSumToOne() {
        // of(1, 0, 0, 0) 归一化后 vector 权重 1.0：vector 文档 finalScore == retrievalScore，
        // 其余来源权重 0 → finalScore 0
        HybridRetrievalService service = service();
        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "vector content", 0.42)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("kw-1", "keyword", "keyword content", 0.8)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result =
                service.retrieve("q", "tenant", "chat", 5, HybridWeights.of(1, 0, 0, 0));

        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("vec-1", "kw-1");
        assertThat(result.documents().get(0).getFinalScore()).isCloseTo(0.42, within(1e-9));
        assertThat(result.documents().get(1).getFinalScore()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void allZeroWeightsFallBackToBalancedQuarters() {
        HybridRetrievalService service = service();
        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "content", 0.8)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result =
                service.retrieve("q", "tenant", "chat", 5, HybridWeights.of(0, 0, 0, 0));

        // BALANCED：每个来源 0.25 → 0.8 * 0.25 = 0.2
        assertThat(result.documents().get(0).getFinalScore()).isCloseTo(0.2, within(1e-9));
    }

    // ── topK 截断：去重后按 finalScore 降序取前 topK ─────────────

    @Test
    void truncatesDeduplicatedResultsToTopK() {
        HybridRetrievalService service = service();
        when(vectorRetriever.retrieve("q", "tenant", "chat")).thenReturn(List.of(
                doc("v-low", "vector", "low doc", 0.1),
                doc("v-high", "vector", "high doc", 0.9),
                doc("v-mid", "vector", "mid doc", 0.5)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 2);

        assertThat(result.totalAfterDedup()).isEqualTo(3);
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("v-high", "v-mid");
    }

    // ── 指纹去重边界：空白归一 / null content / 200 字符截断 ─────

    @Test
    void fingerprintIgnoresWhitespaceNullContentAndTailBeyond200Chars() {
        HybridRetrievalService service = service();
        String base200 = "x".repeat(200);
        when(vectorRetriever.retrieve("q", "tenant", "chat")).thenReturn(List.of(
                // 空白折叠后与 kw-1 同指纹
                doc("vec-ws", "vector", "spring   ai\ttutorial", 0.9),
                // 200 字符相同，尾部不同，仍应判重
                doc("vec-long-a", "vector", base200 + "-tail-a", 0.8),
                // null content 指纹为空串，彼此判重
                doc("vec-null", "vector", null, 0.7)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        doc("kw-1", "keyword", "spring ai tutorial", 0.5),
                        doc("kw-long-b", "keyword", base200 + "-tail-b", 0.6),
                        doc("kw-null", "keyword", null, 0.3)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 10);

        assertThat(result.totalBeforeDedup()).isEqualTo(6);
        assertThat(result.totalAfterDedup()).isEqualTo(3);
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("vec-ws", "vec-long-a", "vec-null");
    }

    // ── 指标：outcome 标签随结果分类（success / empty / error）────

    @Test
    void publishesLatencyTimerWithOutcomeTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever, keywordRetriever, graphRetriever, webRetriever, registry);
        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "content", 0.5)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        service.retrieve("q", "tenant", "chat", 5);
        assertThat(registry.get("retrieval.hybrid.latency").tag("outcome", "success").timer().count())
                .isEqualTo(1L);

        // 全空结果 → outcome=empty
        when(vectorRetriever.retrieve("q", "tenant", "chat")).thenReturn(List.of());
        service.retrieve("q", "tenant", "chat", 5);
        assertThat(registry.get("retrieval.hybrid.latency").tag("outcome", "empty").timer().count())
                .isEqualTo(1L);
        assertThat(registry.find("retrieval.hybrid.latency").tag("outcome", "error").timer())
                .isNull();
    }

    @Test
    void executorShutdownRejectsFurtherRetrievalsAndRecordsErrorOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever, keywordRetriever, graphRetriever, webRetriever, registry);
        service.shutdownRetrievalExecutor();

        assertThatThrownBy(() -> service.retrieve("q", "tenant", "chat", 3))
                .isInstanceOf(RejectedExecutionException.class);
        // @PreDestroy 之后 retrieve 同步抛出，finally 仍以 outcome=error 记录时延
        assertThat(registry.get("retrieval.hybrid.latency").tag("outcome", "error").timer().count())
                .isEqualTo(1L);
    }

    // ── 源超时：慢源不拖垮整体（约 3 秒）─────────────────────────

    @Test
    void slowSourceTimesOutAndDegradesWithoutBlockingOthers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(vectorRetriever.retrieve("q", "tenant", "chat")).thenAnswer(inv -> {
            // 阻塞超过 3 秒源超时；结束后释放，避免线程泄漏
            release.await(15, TimeUnit.SECONDS);
            return List.of(doc("vec-1", "vector", "slow", 0.9));
        });
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("kw-1", "keyword", "fast", 0.5)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        long start = System.nanoTime();
        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 3 秒超时放行，而不是等慢源完成
        assertThat(elapsedMs).isBetween(2_000L, 10_000L);
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("kw-1");
        assertThat(result.totalBeforeDedup()).isEqualTo(1);
        release.countDown();
    }

    // ── 测试脚手架 ────────────────────────────────────────────────

    private HybridRetrievalService service() {
        return new HybridRetrievalService(
                vectorRetriever, keywordRetriever, graphRetriever, webRetriever, new SimpleMeterRegistry());
    }

    private ScoredDocument doc(String id, String sourceType, String content, double score) {
        return ScoredDocument.builder()
                .docId(id)
                .sourceType(sourceType)
                .title(id)
                .content(content)
                .retrievalScore(score)
                .metadata(Map.of())
                .build();
    }
}
