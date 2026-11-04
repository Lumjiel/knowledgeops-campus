package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 对应 docs/architecture-hybrid-retrieval.md「证据评分」一节：
 * 三维评分 relevance(0.50) + authority(0.30) + timeliness(0.20)，
 * 权威度取自来源类型（graph > vector > keyword > web），
 * 时效度取自元数据时间戳分档。
 */
class EvidenceJudgeServiceTest {

    private EvidenceJudgeService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // 与生产默认一致（rag.tokenizer 默认 regex），保证关键词命中判定确定性
        service = new EvidenceJudgeService(registry, new RegexTokenizer());
    }

    // ── 综合评分：score = relevance×0.50 + authority×0.30 + timeliness×0.20 ──

    @Test
    void compositeScoreCombinesThreeDimensionsWithDocumentedWeights() {
        // finalScore 0.50，query 两个词全命中 → boost 0.10 → relevance 0.60
        // vector 权威度 0.75；无时间戳 → 时效度默认 0.70
        ScoredDocument doc = doc("vec-1", "vector", "redis cache 穿透方案", 0.50);

        List<EvidenceItem> items = service.judge(List.of(doc), "redis cache");

        assertThat(items).hasSize(1);
        EvidenceItem item = items.get(0);
        assertThat(item.getRelevanceScore()).isCloseTo(0.60, within(1e-9));
        assertThat(item.getAuthorityScore()).isCloseTo(0.75, within(1e-9));
        assertThat(item.getTimelinessScore()).isCloseTo(0.70, within(1e-9));
        assertThat(item.getScore()).isCloseTo(0.60 * 0.50 + 0.75 * 0.30 + 0.70 * 0.20, within(1e-9));
    }

    // ── 相关性：query-doc 关键词命中加分，封顶 +0.3 且总分不超过 1.0 ──

    @Test
    void relevanceBoostCapsAtThirtyPercentAndClampsToOne() {
        // 8 个词全部命中 → boost 理论 0.40，实际封顶 0.30；0.9 + 0.3 → 钳到 1.0
        ScoredDocument doc = doc("vec-1", "vector",
                "alpha beta gamma delta epsilon zeta eta theta", 0.9);

        List<EvidenceItem> items = service.judge(List.of(doc),
                "alpha beta gamma delta epsilon zeta eta theta");

        assertThat(items.get(0).getRelevanceScore()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void singleCharTokensDoNotContributeBoost() {
        // token 长度 < 2 不参与命中："a" 被跳过，仅 "cache" 命中 → boost 0.05
        ScoredDocument doc = doc("vec-1", "vector", "a cache", 0.50);

        List<EvidenceItem> items = service.judge(List.of(doc), "a cache");

        assertThat(items.get(0).getRelevanceScore()).isCloseTo(0.55, within(1e-9));
    }

    @Test
    void blankQueryOrBlankContentSkipRelevanceBoost() {
        ScoredDocument noQueryMatch = doc("vec-1", "vector", "some content", 0.40);
        ScoredDocument blankContent = ScoredDocument.builder()
                .docId("vec-2")
                .sourceType("vector")
                .title("vec-2")
                .content("   ")
                .finalScore(0.40)
                .metadata(Map.of())
                .build();

        List<EvidenceItem> items = service.judge(List.of(noQueryMatch, blankContent), "  ");

        assertThat(items).extracting(EvidenceItem::getRelevanceScore)
                .containsExactly(0.40, 0.40);
    }

    // ── 权威性：来源类型分层 graph 0.90 > vector 0.75 > keyword 0.65 > web 0.50 ──

    @Test
    void authorityFollowsSourceTypeHierarchy() {
        List<ScoredDocument> docs = List.of(
                doc("g-1", "graph", "content", 0.5),
                doc("v-1", "vector", "content", 0.5),
                doc("k-1", "keyword", "content", 0.5),
                doc("w-1", "web", "content", 0.5),
                doc("x-1", "unknown", "content", 0.5));

        List<EvidenceItem> items = service.judge(docs, "content");

        assertThat(items).extracting(EvidenceItem::getAuthorityScore)
                .containsExactly(0.90, 0.75, 0.65, 0.50, 0.50);
    }

    // ── 时效性：元数据时间戳分档 <30d:1.0 / <90d:0.9 / <180d:0.8 / <365d:0.7 / 其余 0.5 ──

    @Test
    void timelinessTiersFromCreatedAtMetadata() {
        long day = 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        List<ScoredDocument> docs = List.of(
                docWithMeta("d-10", Map.of("created_at", now - 10 * day)),
                docWithMeta("d-45", Map.of("created_at", now - 45 * day)),
                docWithMeta("d-120", Map.of("created_at", now - 120 * day)),
                docWithMeta("d-200", Map.of("created_at", now - 200 * day)),
                docWithMeta("d-400", Map.of("created_at", now - 400 * day)));

        List<EvidenceItem> items = service.judge(docs, "q");

        assertThat(items).extracting(EvidenceItem::getTimelinessScore)
                .containsExactly(1.0, 0.9, 0.8, 0.7, 0.5);
    }

    @Test
    void timelinessBoundaryAtThirtyDaysFallsIntoNinetyDayTier() {
        long day = 24L * 60 * 60 * 1000;
        List<ScoredDocument> docs = List.of(
                docWithMeta("d-29", Map.of("created_at", System.currentTimeMillis() - 29 * day)),
                docWithMeta("d-30", Map.of("created_at", System.currentTimeMillis() - 30 * day)));

        List<EvidenceItem> items = service.judge(docs, "q");

        assertThat(items).extracting(EvidenceItem::getTimelinessScore)
                .containsExactly(1.0, 0.9);
    }

    @Test
    void missingOrInvalidTimestampFallsBackToNeutralTimeliness() {
        List<ScoredDocument> docs = List.of(
                // 无 metadata → 中性 0.70
                doc("d-none", "vector", "content", 0.5),
                // metadata 存在但无时间戳键 → 0.70
                docWithMeta("d-empty", Map.of("other", "x")),
                // 非数字时间戳 → 解析失败回退 0.70
                docWithMeta("d-bad", Map.of("timestamp", "not-a-number")),
                // timestamp 键作为 created_at 的回退键同样生效
                docWithMeta("d-fallback", Map.of("timestamp", System.currentTimeMillis())));

        List<EvidenceItem> items = service.judge(docs, "q");

        assertThat(items).extracting(EvidenceItem::getTimelinessScore)
                .containsExactly(0.70, 0.70, 0.70, 1.0);
    }

    // ── 排序与裁剪：按综合分降序；snippet 折叠空白并截断 180 字符 ──

    @Test
    void itemsSortedByCompositeScoreDescending() {
        // 权威度相同（均 vector），relevance 由 finalScore 决定 → 综合分随 finalScore 降序
        List<ScoredDocument> docs = List.of(
                doc("v-low", "vector", "content", 0.10),
                doc("v-high", "vector", "content", 0.90),
                doc("v-mid", "vector", "content", 0.50));

        List<EvidenceItem> items = service.judge(docs, "q");

        assertThat(items).extracting(EvidenceItem::getScore)
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void snippetCollapsesWhitespaceAndTruncatesTo180Chars() {
        String longText = "word ".repeat(60); // 300 字符，空白折叠后 299
        List<EvidenceItem> items = service.judge(
                List.of(doc("v-1", "vector", "  a   b\tc  ", 0.5),
                        doc("v-2", "vector", longText, 0.5)), "q");

        assertThat(items.get(0).getSnippet()).isEqualTo("a b c");
        assertThat(items.get(1).getSnippet())
                .startsWith("word word ")
                .endsWith("...")
                .hasSize(183); // 180 + "..."
    }

    // ── 字段透传与指标 ──

    @Test
    void carriesSourceFieldsAndReasonIntoEvidenceItem() {
        ScoredDocument doc = ScoredDocument.builder()
                .docId("v-1")
                .sourceType("vector")
                .title("enterprise-ai-report.pdf")
                .url("http://example.com/a.pdf")
                .chunkId("chunk-3")
                .content("redis cache")
                .finalScore(0.50)
                .metadata(Map.of())
                .build();

        List<EvidenceItem> items = service.judge(List.of(doc), "redis cache");

        EvidenceItem item = items.get(0);
        assertThat(item.getSourceType()).isEqualTo("vector");
        assertThat(item.getTitle()).isEqualTo("enterprise-ai-report.pdf");
        assertThat(item.getUrl()).isEqualTo("http://example.com/a.pdf");
        assertThat(item.getChunkId()).isEqualTo("chunk-3");
        // reason 采用「来源标签 + 三维百分比」固定格式
        assertThat(item.getReason())
                .contains("向量语义匹配")
                .contains("相关度")
                .contains("权威度75%")
                .contains("时效度70%");
    }

    @Test
    void publishesEvidenceJudgeLatencyTimer() {
        service.judge(List.of(doc("v-1", "vector", "content", 0.5)), "q");

        assertThat(registry.get("evidence.judge.latency").timer().count())
                .isEqualTo(1L);
    }

    // ── 测试脚手架 ────────────────────────────────────────────────

    private ScoredDocument doc(String id, String sourceType, String content, double finalScore) {
        return ScoredDocument.builder()
                .docId(id)
                .sourceType(sourceType)
                .title(id)
                .content(content)
                .finalScore(finalScore)
                .metadata(Map.of())
                .build();
    }

    private ScoredDocument docWithMeta(String id, Map<String, Object> metadata) {
        return ScoredDocument.builder()
                .docId(id)
                .sourceType("vector")
                .title(id)
                .content("content")
                .finalScore(0.5)
                .metadata(metadata)
                .build();
    }
}
