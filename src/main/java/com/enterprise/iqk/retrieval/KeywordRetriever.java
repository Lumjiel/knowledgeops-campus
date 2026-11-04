package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keyword-based retriever using text overlap scoring on document titles and content.
 * Complements VectorRetriever by catching exact term matches that semantic search may miss.
 */
@Component
@RequiredArgsConstructor
public class KeywordRetriever {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;
    private final Tokenizer tokenizer;
    private final RagProperties ragProperties;

    /**
     * 执行关键词检索：以向量库为文档源（宽松阈值 0.25、topK×2 候选），
     * 再按「标题词重叠 × 0.6 + 内容词重叠 × 0.4」重打分（重叠系数 = 命中词数 /
     * 目标词表大小，目标为空得 0 分），得分 &gt; 0.05 才保留，降序取 topK。
     * 补充向量检索：捕获「Redis 缓存穿透」这类精确术语匹配。
     * 耗时经 {@code retrieval.keyword.latency} 直方图按 outcome 打点上报。
     */
    public List<ScoredDocument> retrieve(String query, String tenantId, String chatId, int topK) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            // Use vector store as document source, then re-rank by keyword overlap
            String filter = "tenant_id == \"" + escapeFilter(tenantId)
                    + "\" && (chat_id == \"" + escapeFilter(chatId)
                    + "\" || chat_id == \"" + escapeFilter(ragProperties.getSharedCorpusChatId()) + "\")";
            List<Document> docs = vectorStore.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query(query)
                            .topK(Math.max(topK * 2, 20))
                            .similarityThreshold(0.25)
                            .filterExpression(filter)
                            .build());

            Set<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty()) {
                outcome = "empty";
                return List.of();
            }

            List<ScoredDocument> results = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i);
                Set<String> titleTokens = tokenize(metaStr(d, "file_name", ""));
                Set<String> contentTokens = tokenize(d.getText());

                double titleOverlap = overlapScore(queryTokens, titleTokens);
                double contentOverlap = overlapScore(queryTokens, contentTokens);
                double score = titleOverlap * 0.6 + contentOverlap * 0.4;

                if (score > 0.05) {
                    results.add(ScoredDocument.builder()
                            .docId("kw-" + i)
                            .sourceType("keyword")
                            .title(metaStr(d, "file_name", "unknown"))
                            .chunkId("chunk-" + metaStr(d, "chunk_index", String.valueOf(i)))
                            .content(d.getText())
                            .retrievalScore(score)
                            .metadata(d.getMetadata())
                            .build());
                }
            }
            results.sort(Comparator.comparingDouble(ScoredDocument::getRetrievalScore).reversed());
            outcome = results.isEmpty() ? "empty" : "success";
            return results.stream().limit(topK).collect(Collectors.toList());
        } finally {
            sample.stop(Timer.builder("retrieval.keyword.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 重叠系数：|query ∩ target| / |target|（目标为空时 0）。 */
    private double overlapScore(Set<String> query, Set<String> target) {
        if (target.isEmpty()) return 0.0;
        long overlap = query.stream().filter(target::contains).count();
        return (double) overlap / target.size();
    }

    /** 分词去重并保持出现顺序（LinkedHashSet），保证同文同分。 */
    private Set<String> tokenize(String text) {
        return new java.util.LinkedHashSet<>(tokenizer.tokenize(text));
    }

    /** 读元数据字符串，缺失时返回 fallback。 */
    private String metaStr(Document d, String key, String fallback) {
        Object v = d.getMetadata().get(key);
        return v == null ? fallback : v.toString();
    }

    private String escapeFilter(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
