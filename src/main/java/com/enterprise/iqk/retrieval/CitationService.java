package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates numbered citations with source, chunk, confidence, and excerpt.
 */
@Service
public class CitationService {

    /**
     * 为证据列表生成编号引用（index 从 1 递增），字段直取证据的
     * source/title/url/chunkId，confidence 取证据综合分，excerpt 取 snippet。
     *
     * @param evidence 已按分数降序的证据列表
     * @return 与 evidence 同序的编号引用列表
     */
    public List<CitationItem> buildCitations(List<EvidenceItem> evidence) {
        List<CitationItem> citations = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            EvidenceItem e = evidence.get(i);
            citations.add(CitationItem.builder()
                    .index(i + 1)
                    .sourceType(e.getSourceType())
                    .title(e.getTitle())
                    .url(e.getUrl())
                    .chunkId(e.getChunkId())
                    .confidence(e.getScore())
                    .excerpt(e.getSnippet())
                    .build());
        }
        return citations;
    }

    /**
     * 把引用渲染为追加到答案尾部的「引用来源」段落：每行
     * {@code [n] 标题 (来源类型) 可信度: nn%}（可信度为 0 时省略）；
     * 无引用返回空串。
     */
    public String formatCitationFooter(List<CitationItem> citations) {
        if (citations == null || citations.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n---\n引用来源:\n");
        for (CitationItem c : citations) {
            sb.append("[").append(c.getIndex()).append("] ");
            sb.append(StringUtils.hasText(c.getTitle()) ? c.getTitle() : "未知来源");
            sb.append(" (").append(c.getSourceType()).append(")");
            if (c.getConfidence() > 0) {
                sb.append(" 可信度: ").append(Math.round(c.getConfidence() * 100)).append("%");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
