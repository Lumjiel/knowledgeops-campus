package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对应 docs/architecture-hybrid-retrieval.md「引用溯源」一节：
 * CitationService 为每条证据生成编号引用（index 从 1 起），
 * 并可格式化「引用来源」页脚文本。
 */
class CitationServiceTest {

    private CitationService service;

    @BeforeEach
    void setUp() {
        service = new CitationService();
    }

    // ── 编号引用：index 从 1 连续递增，字段逐一透传 ───────────────

    @Test
    void buildsNumberedCitationsFromEvidenceInOrder() {
        List<CitationItem> citations = service.buildCitations(List.of(
                evidence("vector", "enterprise-ai-report.pdf", "chunk-3", 0.86),
                evidence("graph", "知识图谱实体", "chunk-7", 0.64),
                evidence("web", "external-blog", "chunk-9", 0.55)));

        assertThat(citations).extracting(CitationItem::getIndex)
                .containsExactly(1, 2, 3);
        assertThat(citations).extracting(CitationItem::getSourceType)
                .containsExactly("vector", "graph", "web");
        assertThat(citations).extracting(CitationItem::getTitle)
                .containsExactly("enterprise-ai-report.pdf", "知识图谱实体", "external-blog");
        assertThat(citations).extracting(CitationItem::getChunkId)
                .containsExactly("chunk-3", "chunk-7", "chunk-9");
        // confidence 直接取证据综合分
        assertThat(citations.get(0).getConfidence()).isEqualTo(0.86);
    }

    @Test
    void citationCarriesUrlAndExcerptFromEvidence() {
        EvidenceItem evidence = EvidenceItem.builder()
                .sourceType("vector")
                .title("report.pdf")
                .url("http://example.com/report.pdf")
                .chunkId("chunk-3")
                .score(0.86)
                .snippet("根据 Gartner 2025 年报告，AI Agent 在企业服务领域的采用率…")
                .build();

        List<CitationItem> citations = service.buildCitations(List.of(evidence));

        CitationItem citation = citations.get(0);
        assertThat(citation.getUrl()).isEqualTo("http://example.com/report.pdf");
        // excerpt 复用证据 snippet 作为原文摘录
        assertThat(citation.getExcerpt())
                .isEqualTo("根据 Gartner 2025 年报告，AI Agent 在企业服务领域的采用率…");
    }

    @Test
    void emptyEvidenceYieldsNoCitations() {
        assertThat(service.buildCitations(List.of())).isEmpty();
    }

    // ── 引用页脚：[n] 标题 (来源) 可信度: n% ──────────────────────

    @Test
    void footerListsEachCitationWithTitleSourceAndConfidence() {
        List<CitationItem> citations = service.buildCitations(List.of(
                evidence("vector", "enterprise-ai-report.pdf", "chunk-3", 0.866),
                evidence("keyword", "faq.md", "chunk-1", 0.62)));

        String footer = service.formatCitationFooter(citations);

        assertThat(footer)
                .startsWith("\n\n---\n引用来源:\n")
                .contains("[1] enterprise-ai-report.pdf (vector) 可信度: 87%\n")
                .contains("[2] faq.md (keyword) 可信度: 62%\n");
        // 保持编号顺序
        assertThat(footer.indexOf("[1]")).isLessThan(footer.indexOf("[2]"));
    }

    @Test
    void footerFallsBackForBlankTitleAndZeroConfidence() {
        List<CitationItem> citations = service.buildCitations(List.of(
                EvidenceItem.builder()
                        .sourceType("graph")
                        .title("  ")
                        .score(0.0)
                        .build()));

        String footer = service.formatCitationFooter(citations);

        // 空白标题回退「未知来源」；confidence 为 0 时不追加可信度
        assertThat(footer)
                .contains("[1] 未知来源 (graph)\n")
                .doesNotContain("可信度");
    }

    @Test
    void footerOfNullOrEmptyCitationsIsEmptyString() {
        assertThat(service.formatCitationFooter(null)).isEmpty();
        assertThat(service.formatCitationFooter(List.of())).isEmpty();
    }

    // ── 测试脚手架 ────────────────────────────────────────────────

    private EvidenceItem evidence(String sourceType, String title, String chunkId, double score) {
        return EvidenceItem.builder()
                .sourceType(sourceType)
                .title(title)
                .chunkId(chunkId)
                .score(score)
                .snippet("excerpt of " + chunkId)
                .build();
    }
}
