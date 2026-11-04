package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.graph.GraphService;
import com.enterprise.iqk.graph.KgEntityRecord;
import com.enterprise.iqk.graph.KgFactRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * File: GraphRetriever.java
 * <p>
 * 知识图谱检索器（四路召回之一）：从查询中提取主关键词，两路召回——
 * 实体（名称/别名搜索，附一跳邻居关系上下文，固定分 0.85）与事实三元组
 * （subject-predicate-object，按 confidence 打分，缺省 0.7），
 * 统一封装为 {@link ScoredDocument}（sourceType=graph）。
 */
@Component
@RequiredArgsConstructor
public class GraphRetriever {

    private final GraphService graphService;
    private final MeterRegistry meterRegistry;
    private final Tokenizer tokenizer;

    /**
     * 执行图谱检索：关键词为空直接返回空列表；否则并行含义上的两段——
     * 实体搜索 + 一跳邻居上下文、事实三元组搜索——合并返回。
     * 耗时经 {@code retrieval.graph.latency} 直方图按 outcome 打点上报。
     *
     * @param query    用户查询文本
     * @param tenantId 租户 ID（图谱数据按租户隔离）
     * @param topK     实体与事实各自的召回上限
     */
    public List<ScoredDocument> retrieve(String query, String tenantId, int topK) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            List<ScoredDocument> results = new ArrayList<>();

            // Extract keywords from query for entity matching
            String keyword = extractMainKeyword(query);
            if (!StringUtils.hasText(keyword)) {
                outcome = "empty";
                return results;
            }

            // Search entities by keyword
            List<KgEntityRecord> entities = graphService.searchEntities(tenantId, keyword, topK);
            for (int i = 0; i < entities.size(); i++) {
                KgEntityRecord e = entities.get(i);
                // Get one-hop neighbors for richer context
                List<GraphService.GraphNeighbor> neighbors = graphService.getNeighbors(tenantId, e.getEntityId());
                String neighborContext = buildNeighborContext(neighbors);

                results.add(ScoredDocument.builder()
                        .docId("graph-entity-" + i)
                        .sourceType("graph")
                        .title(e.getName() + " (" + e.getType() + ")")
                        .chunkId(e.getEntityId())
                        .content(e.getName() + ": " + defaultText(e.getDescription(), "")
                                + (StringUtils.hasText(neighborContext) ? " | " + neighborContext : ""))
                        .retrievalScore(0.85)
                        .metadata(Map.of("entityType", e.getType(), "entityId", e.getEntityId()))
                        .build());
            }

            // Search facts by keyword
            List<KgFactRecord> facts = graphService.searchFacts(tenantId, keyword, topK);
            for (int i = 0; i < facts.size(); i++) {
                KgFactRecord f = facts.get(i);
                results.add(ScoredDocument.builder()
                        .docId("graph-fact-" + i)
                        .sourceType("graph")
                        .title(f.getSubject() + " " + f.getPredicate() + " " + f.getObject())
                        .chunkId(f.getFactId())
                        .content(f.getSubject() + " " + f.getPredicate() + " " + f.getObject())
                        .retrievalScore(f.getConfidence() != null ? f.getConfidence() : 0.7)
                        .metadata(Map.of("factId", f.getFactId()))
                        .build());
            }

            outcome = results.isEmpty() ? "empty" : "success";
            return results;
        } finally {
            sample.stop(Timer.builder("retrieval.graph.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 提取主关键词：取分词后最长的 token（长度 ≥ 2），过短则回退整个查询。 */
    private String extractMainKeyword(String query) {
        if (!StringUtils.hasText(query)) return "";
        // Simple: take the longest token as keyword
        String longest = "";
        for (String token : tokenizer.tokenize(query)) {
            if (token.length() > longest.length()) {
                longest = token;
            }
        }
        return longest.length() >= 2 ? longest : query.trim();
    }

    /** 把一跳邻居拼成「关系 → 实体名; …」上下文串，空列表返回空串。 */
    private String buildNeighborContext(List<GraphService.GraphNeighbor> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (GraphService.GraphNeighbor n : neighbors) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(n.getRelationType()).append(" → ").append(n.getEntity().getName());
        }
        return sb.toString();
    }

    /** 空值回退辅助。 */
    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
