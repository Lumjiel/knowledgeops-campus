package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.constants.SystemConstants;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.retrieval.RerankPipeline;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.TenantCostService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;
    private final TenantCostService tenantCostService;
    private final RerankPipeline rerankPipeline;

    public RagResult answer(String prompt, String tenantId, String chatId, String conversationId, String modelProfile) {
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        String pipelineOutcome = "error";

        try {
            String normalizedTenantId = TenantContext.normalize(tenantId);
            String filterExpression = "tenant_id == \"" + escapeFilterValue(normalizedTenantId)
                    + "\" && chat_id == \"" + escapeFilterValue(chatId) + "\"";
            SearchRequest request = SearchRequest.builder()
                    .query(prompt)
                    .topK(ragProperties.getRetrievalTopN())
                    .similarityThreshold(ragProperties.getSimilarityThreshold())
                    .filterExpression(filterExpression)
                    .build();

            List<Document> retrieved = similaritySearchWithMetrics(request);
            if (retrieved == null || retrieved.isEmpty()) {
                pipelineOutcome = "empty";
                return RagResult.builder()
                        .answer("没有在当前知识库中检索到可用内容。")
                        .citations(List.of())
                        .evidence(List.of("未检索到匹配文档，请先上传资料或调整检索词。"))
                        .build();
            }

            RerankPipeline.PipelineResult pipeline = rerankWithMetrics(prompt, retrieved);
            List<Document> selected = pipeline.documents().stream()
                    .limit(Math.max(1, ragProperties.getRerankTopK()))
                    .toList();

            String context = buildContext(selected);
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "rag", normalizedTenantId, chatId);
            long inputTokens = tenantCostService.estimateTokens(prompt + "\n" + context);
            tenantCostService.assertBudget(normalizedTenantId, decision.costTier(), inputTokens, 600);
            String answer = chatClient.prompt()
                    .options(ChatOptions.builder().model(decision.model())
                            .temperature(ragProperties.getTemperature()).build())
                    .system(SystemConstants.RAG_ANSWER_SYSTEM)
                    .user("用户问题:%n%s%n%n上下文:%n%s%n".formatted(prompt, context))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                    .call()
                    .content();
            long outputTokens = tenantCostService.estimateTokens(answer);
            tenantCostService.recordUsage(normalizedTenantId, decision.costTier(), inputTokens, outputTokens, "rag");

            List<String> citations = selected.stream()
                    .map(this::citationText)
                    .toList();
            List<CitationCard> citationCards = new java.util.ArrayList<>();
            for (int i = 0; i < selected.size(); i++) {
                citationCards.add(citationCard(selected.get(i), i + 1));
            }
            List<String> evidence = selected.stream()
                    .map(this::evidenceText)
                    .toList();
            String answerWithFooter = answer + formatCitationFooter(citations);

            pipelineOutcome = "success";
            return RagResult.builder()
                    .answer(answerWithFooter)
                    .citations(citations)
                    .citationCards(citationCards)
                    .evidence(evidence)
                    .retrievalTrace(buildRetrievalTrace(pipeline))
                    .build();
        } finally {
            pipelineSample.stop(Timer.builder("rag.pipeline.latency")
                    .description("Overall latency for RAG answer pipeline")
                    .tag("outcome", pipelineOutcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            Counter.builder("rag.pipeline.requests")
                    .description("Total number of RAG pipeline requests")
                    .tag("outcome", pipelineOutcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private List<Document> similaritySearchWithMetrics(SearchRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            List<Document> docs = vectorStore.similaritySearch(request);
            outcome = (docs == null || docs.isEmpty()) ? "empty" : "success";
            return docs;
        } finally {
            sample.stop(Timer.builder("rag.retrieval.latency")
                    .description("Latency of vector similarity search")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            Counter.builder("rag.retrieval.requests")
                    .description("Number of vector retrieval requests")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private RerankPipeline.PipelineResult rerankWithMetrics(String prompt, List<Document> docs) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            RerankPipeline.PipelineResult reranked = rerankPipeline.rerank(prompt, docs, ragProperties.getRerankTopK());
            outcome = reranked.documents().isEmpty() ? "empty" : "success";
            return reranked;
        } finally {
            sample.stop(Timer.builder("rag.rerank.latency")
                    .description("Latency of local rerank stage")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private String buildContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(citationText(d))
                    .append("\n")
                    .append(d.getFormattedContent())
                    .append("\n\n");
        }
        return sb.toString();
    }

    private String citationText(Document d) {
        Object file = d.getMetadata().getOrDefault("file_name", "unknown");
        Object chunk = d.getMetadata().getOrDefault("chunk_index", "?");
        return "source=" + file + ", chunk=" + chunk;
    }

    /**
     * 条款级引用卡片（spec-A1）：在旧 citationText 之外的结构化补充，citations 原格式不动。
     * page_number 取自 PDF 入库 metadata，缺失时返回 null——不编页码。
     */
    private CitationCard citationCard(Document d, int index) {
        Object file = d.getMetadata().getOrDefault("file_name", "unknown");
        String fileName = String.valueOf(file);
        String displayName = fileName.endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        Integer page = null;
        Object rawPage = d.getMetadata().get("page_number");
        if (rawPage instanceof Number pageNumber) {
            page = pageNumber.intValue();
        } else if (rawPage != null && StringUtils.hasText(String.valueOf(rawPage))) {
            try {
                page = Integer.parseInt(String.valueOf(rawPage).trim());
            } catch (NumberFormatException ignored) {
                page = null;
            }
        }
        int chunkIndex;
        Object rawChunk = d.getMetadata().getOrDefault("chunk_index", 0);
        if (rawChunk instanceof Number chunkNumber) {
            chunkIndex = chunkNumber.intValue();
        } else {
            try {
                chunkIndex = Integer.parseInt(String.valueOf(rawChunk).trim());
            } catch (NumberFormatException ignored) {
                chunkIndex = 0;
            }
        }
        String snippet = emptyIfBlank(d.getFormattedContent()).replaceAll("\\s+", " ").trim();
        if (snippet.length() > 180) {
            snippet = snippet.substring(0, 180) + "...";
        }
        return new CitationCard(index, displayName, page, chunkIndex, snippet);
    }

    private String evidenceText(Document d) {
        String raw = emptyIfBlank(d.getFormattedContent()).replaceAll("\\s+", " ").trim();
        if (raw.length() <= 180) {
            return raw;
        }
        return raw.substring(0, 180) + "...";
    }

    /** 三级管线的真实计数与耗时（spec-A5 检索轨迹面板的数据来源），全部实测值 */
    private java.util.Map<String, Object> buildRetrievalTrace(RerankPipeline.PipelineResult pipeline) {
        java.util.Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("recall", pipeline.recallCount());
        trace.put("coarse", pipeline.coarseCount());
        trace.put("fine", pipeline.documents().size());
        trace.put("coarseMs", pipeline.coarseMs());
        trace.put("fineMs", pipeline.fineMs());
        trace.put("degraded", pipeline.degraded());
        trace.put("tokenizer", ragProperties.getTokenizer());
        trace.put("coarseReranker", ragProperties.getReranker());
        trace.put("fineReranker", ragProperties.getFineReranker());
        return trace;
    }

    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    private String escapeFilterValue(String value) {
        String raw = emptyIfBlank(value);
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String formatCitationFooter(List<String> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n---\n引用来源:\n");
        for (int i = 0; i < citations.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(citations.get(i)).append("\n");
        }
        return sb.toString();
    }

    @Data
    @Builder
    public static class RagResult {
        private String answer;
        private List<String> citations;
        private List<CitationCard> citationCards;
        private List<String> evidence;
        private java.util.Map<String, Object> retrievalTrace;
    }

    /**
     * 条款级引用卡片：index 1-based 与 [1][2] 编号对应；page 缺失为 null；
     * snippet 为原文前 180 字（空白归一化）。
     */
    public record CitationCard(
            int index,
            String file,
            Integer page,
            int chunkIndex,
            String snippet
    ) {
    }
}
