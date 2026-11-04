package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Scores evidence items on three dimensions:
 * - Relevance: how well the evidence matches the query
 * - Authority: source credibility (e.g., internal doc > web)
 * - Timeliness: recency of the information
 */
@Service
@RequiredArgsConstructor
public class EvidenceJudgeService {

    private final MeterRegistry meterRegistry;
    private final Tokenizer tokenizer;

    private static final double RELEVANCE_WEIGHT = 0.50;
    private static final double AUTHORITY_WEIGHT = 0.30;
    private static final double TIMELINESS_WEIGHT = 0.20;

    /**
     * 对混合检索结果逐条做三维评分并降序返回（docs/architecture-hybrid-retrieval.md
     * 「证据评分」）：
     * <ul>
     *   <li>相关性（权重 0.50）：检索融合分为基，查询词命中内容时每词 +0.05、上限 +0.3；</li>
     *   <li>权威性（权重 0.30）：来源类型固定档位 graph 0.90 &gt; vector 0.75 &gt; keyword 0.65 &gt; web 0.50；</li>
     *   <li>时效性（权重 0.20）：元数据时间戳分档（&lt;30 天 1.0 → &lt;365 天 0.7 → 更早 0.5），
     *       无时间戳取中性 0.70。</li>
     * </ul>
     * 综合分 = relevance × 0.50 + authority × 0.30 + timeliness × 0.20。
     * 耗时经 {@code evidence.judge.latency} 直方图打点上报。
     *
     * @param documents 融合后的候选文档
     * @param query     原始用户查询（相关性加分用）
     * @return 按综合分降序的证据列表
     */
    public List<EvidenceItem> judge(List<ScoredDocument> documents, String query) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<EvidenceItem> items = new ArrayList<>();
            for (ScoredDocument doc : documents) {
                double relevance = scoreRelevance(doc, query);
                double authority = scoreAuthority(doc);
                double timeliness = scoreTimeliness(doc);
                double composite = relevance * RELEVANCE_WEIGHT
                        + authority * AUTHORITY_WEIGHT
                        + timeliness * TIMELINESS_WEIGHT;

                items.add(EvidenceItem.builder()
                        .sourceType(doc.getSourceType())
                        .title(doc.getTitle())
                        .url(doc.getUrl())
                        .chunkId(doc.getChunkId())
                        .score(composite)
                        .reason(buildReason(doc, relevance, authority, timeliness))
                        .relevanceScore(relevance)
                        .authorityScore(authority)
                        .timelinessScore(timeliness)
                        .snippet(truncate(doc.getContent(), 180))
                        .build());
            }
            items.sort(Comparator.comparingDouble(EvidenceItem::getScore).reversed());
            return items;
        } finally {
            sample.stop(Timer.builder("evidence.judge.latency")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 相关性：融合分为基 + 查询词（≥2 字符）在正文中的命中加分，封顶 1.0。 */
    private double scoreRelevance(ScoredDocument doc, String query) {
        // Use the retrieval score as a base, boosted by keyword overlap
        double base = doc.getFinalScore();
        if (!StringUtils.hasText(query) || !StringUtils.hasText(doc.getContent())) {
            return base;
        }
        String lowerContent = doc.getContent().toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        long hits = 0;
        for (String token : new java.util.LinkedHashSet<>(tokenizer.tokenize(lowerQuery))) {
            if (token.length() >= 2 && lowerContent.contains(token)) {
                hits++;
            }
        }
        double boost = Math.min(0.3, hits * 0.05);
        return Math.min(1.0, base + boost);
    }

    /** 权威性：按来源类型的固定档位（graph &gt; vector &gt; keyword &gt; web）。 */
    private double scoreAuthority(ScoredDocument doc) {
        return switch (doc.getSourceType()) {
            case "graph" -> 0.90;   // structured knowledge graph data
            case "vector" -> 0.75;  // internal document chunks
            case "keyword" -> 0.65; // keyword matches (same source, lower confidence)
            case "web" -> 0.50;     // external web content
            default -> 0.50;
        };
    }

    /** 时效性：metadata 的 created_at/timestamp（epoch 毫秒）按距今天数分档；缺失或不可解析取中性 0.70。 */
    private double scoreTimeliness(ScoredDocument doc) {
        // Default: no timestamp metadata → neutral score
        if (doc.getMetadata() == null) return 0.70;
        Object timestamp = doc.getMetadata().get("created_at");
        if (timestamp == null) timestamp = doc.getMetadata().get("timestamp");
        if (timestamp == null) return 0.70;
        try {
            long epoch = Long.parseLong(timestamp.toString());
            long now = System.currentTimeMillis();
            long daysAgo = (now - epoch) / (1000 * 60 * 60 * 24);
            if (daysAgo < 30) return 1.0;
            if (daysAgo < 90) return 0.9;
            if (daysAgo < 180) return 0.8;
            if (daysAgo < 365) return 0.7;
            return 0.5;
        } catch (NumberFormatException e) {
            return 0.70;
        }
    }

    /** 组装可读评分理由：来源中文标签 + 三个维度的百分比。 */
    private String buildReason(ScoredDocument doc, double relevance,
                                double authority, double timeliness) {
        String sourceLabel = switch (doc.getSourceType()) {
            case "vector" -> "向量语义匹配";
            case "keyword" -> "关键词精确匹配";
            case "graph" -> "知识图谱关联";
            case "web" -> "外部搜索";
            default -> "未知来源";
        };
        return String.format("%s，相关度%.0f%%，权威度%.0f%%，时效度%.0f%%",
                sourceLabel, relevance * 100, authority * 100, timeliness * 100);
    }

    /** 折叠空白后截断至 maxLen，超长补省略号。 */
    private String truncate(String text, int maxLen) {
        if (!StringUtils.hasText(text)) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "...";
    }
}
