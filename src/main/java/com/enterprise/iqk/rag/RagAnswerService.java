package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.constants.SystemConstants;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.memory.SlotExtractor;
import com.enterprise.iqk.retrieval.RerankPipeline;
import com.enterprise.iqk.security.AuthIdentity;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.TenantCostService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * File: RagAnswerService.java
 * <p>
 * RAG 问答主链路：向量检索（带租户 + 会话过滤）→ 精排 → 预算校验 → LLM 生成 →
 * 引用与证据组装，并附带三层记忆的读写接线。
 * <ul>
 *   <li>{@link #answer}：完整问答。检索范围为 {@code tenant_id + (chat_id || 共享语料 chat_id)}，
 *       精排后取 rerankTopK 作为上下文；调用前经 {@code TenantCostService} 预算断言，
 *       调用后记录 token 用量；答案尾部追加「引用来源」脚注，结果携带条款级引用卡片
 *       （{@link CitationCard}）与检索轨迹（recall/coarse/fine 计数与耗时）。</li>
 *   <li>{@link #debugRetrieve}：只跑「检索 + 精排」的调试入口，供离线评测与 bad case 定位。</li>
 * </ul>
 * <p>
 * 可观测性：retrieval / rerank / 整体管线三段均有 Timer 与 Counter
 * （{@code rag.*}），按 outcome（success/empty/error）打标，异常向上抛出但
 * 指标照常记录；记忆读写失败静默降级，不阻断问答主链路。
 */
@Slf4j
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
    private final MemoryService memoryService;
    private final SlotExtractor slotExtractor;

    /**
     * 只跑「检索 + 精排」不调用 LLM 的调试入口，与 answer() 完全同一条链路。
     * 供离线评测（Hit@K / MRR）和 bad case 定位使用。prompt 为空时抛
     * {@link IllegalArgumentException}（与 answer() 一致）。
     */
    public PipelineDebugResult debugRetrieve(String prompt, String tenantId, String chatId, int topK) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("prompt is required");
        }
        String normalizedTenantId = TenantContext.normalize(tenantId);
        String filterExpression = "tenant_id == \"" + escapeFilterValue(normalizedTenantId)
                + "\" && (chat_id == \"" + escapeFilterValue(chatId)
                + "\" || chat_id == \"" + escapeFilterValue(ragProperties.getSharedCorpusChatId()) + "\")";
        SearchRequest request = SearchRequest.builder()
                .query(prompt)
                .topK(ragProperties.getRetrievalTopN())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .filterExpression(filterExpression)
                .build();

        long start = System.nanoTime();
        List<Document> retrieved = similaritySearchWithMetrics(request);
        long recallMs = (System.nanoTime() - start) / 1_000_000;

        RerankPipeline.PipelineResult pipeline = retrieved == null || retrieved.isEmpty()
                ? new RerankPipeline.PipelineResult(List.of(), false, 0, 0, 0, 0)
                : rerankWithMetrics(prompt, retrieved);

        List<Document> docs = pipeline.documents();
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (Document d : docs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("metadata", d.getMetadata());
            row.put("content", d.getText());
            rows.add(row);
        }
        return new PipelineDebugResult(rows, retrieved == null ? 0 : retrieved.size(),
                pipeline.degraded(), pipeline.coarseMs(), pipeline.fineMs(), recallMs);
    }

    /**
     * 调试检索结果：documents 为按精排名次的行（rank/metadata/content），
     * recallCount 为召回总数，degraded/coarseMs/fineMs/recallMs 为管线实测数据。
     */
    public record PipelineDebugResult(List<Map<String, Object>> documents, int recallCount,
                                      boolean degraded, long coarseMs, long fineMs, long recallMs) {
    }

    /**
     * 完整 RAG 问答入口。检索为空时直接返回固定话术（不调 LLM，但照常写入记忆）；
     * 命中后走精排 → 预算断言 → LLM → 用量记账，finally 中统一记录管线时延
     * 与请求计数（outcome: success/empty/error）。检索/精排/生成任一环节异常
     * 会向上抛出，调用方（控制器）负责兜底。
     * <p>
     * 边界与部分失败防护：prompt 为空直接 400（{@link IllegalArgumentException}，
     * 全局异常处理器转 badRequest）；精排结果为空按「未检索到」兜底，不带着
     * 空上下文调 LLM（防无根据生成）；LLM 返回空答案以明确异常上抛（绝不把
     * {@code null} 拼进引用脚注）；token 记账失败仅告警，不吞掉已生成的答案。
     * 有意不做强制超时：LLM 调用无法安全中断，超时后放任后台继续只会产生孤儿
     * 计费与重复记忆写入，超时应由底层 HTTP 客户端配置兜底。
     */
    public RagResult answer(String prompt, String tenantId, String chatId, String conversationId, String modelProfile) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("prompt is required");
        }
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        String pipelineOutcome = "error";

        try {
            String normalizedTenantId = TenantContext.normalize(tenantId);
            String memoryUserId = currentUserId(chatId);
            String knownInfo = buildKnownInfo(normalizedTenantId, memoryUserId);
            String filterExpression = "tenant_id == \"" + escapeFilterValue(normalizedTenantId)
                    + "\" && (chat_id == \"" + escapeFilterValue(chatId)
                    + "\" || chat_id == \"" + escapeFilterValue(ragProperties.getSharedCorpusChatId()) + "\")";
            SearchRequest request = SearchRequest.builder()
                    .query(prompt)
                    .topK(ragProperties.getRetrievalTopN())
                    .similarityThreshold(ragProperties.getSimilarityThreshold())
                    .filterExpression(filterExpression)
                    .build();

            List<Document> retrieved = similaritySearchWithMetrics(request);
            if (retrieved == null || retrieved.isEmpty()) {
                pipelineOutcome = "empty";
                persistConversationMemory(normalizedTenantId, memoryUserId, chatId, prompt);
                return RagResult.builder()
                        .answer("没有在当前知识库中检索到可用内容。")
                        .citations(List.of())
                        .evidence(List.of("未检索到匹配文档，请先上传资料或调整检索词。"))
                        .build();
            }

            RerankPipeline.PipelineResult pipeline = rerankWithMetrics(prompt, retrieved);
            List<Document> reranked = pipeline == null || pipeline.documents() == null
                    ? List.of()
                    : pipeline.documents();
            if (reranked.isEmpty()) {
                // 精排后一无所有：与检索为空同等兜底，绝不带着空上下文调 LLM
                pipelineOutcome = "empty";
                persistConversationMemory(normalizedTenantId, memoryUserId, chatId, prompt);
                return RagResult.builder()
                        .answer("没有在当前知识库中检索到可用内容。")
                        .citations(List.of())
                        .evidence(List.of("文档经精排后无可用内容，请调整检索词。"))
                        .build();
            }
            List<Document> selected = reranked.stream()
                    .limit(Math.max(1, ragProperties.getRerankTopK()))
                    .toList();

            String context = buildContext(selected);
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "rag", normalizedTenantId, chatId);
            long inputTokens = tenantCostService.estimateTokens(prompt + "\n" + context);
            tenantCostService.assertBudget(normalizedTenantId, decision.costTier(), inputTokens, 600);
            String answer = chatClient.prompt()
                    .options(ChatOptions.builder().model(decision.model())
                            .temperature(ragProperties.getTemperature()).build())
                    .system(SystemConstants.RAG_ANSWER_SYSTEM + knownInfo)
                    .user("用户问题:%n%s%n%n上下文:%n%s%n".formatted(prompt, context))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                    .call()
                    .content();
            if (!StringUtils.hasText(answer)) {
                // 空 answer 直接拼脚注会产出 "null---..." 这类脏字符串
                throw new IllegalStateException("LLM returned an empty answer (model="
                        + decision.model() + ", profile=" + modelProfile + ")");
            }
            long outputTokens = tenantCostService.estimateTokens(answer);
            try {
                tenantCostService.recordUsage(normalizedTenantId, decision.costTier(), inputTokens, outputTokens, "rag");
            } catch (Exception billingEx) {
                // 答案已经生成：记账失败只损失一笔用量统计，不能让用户请求跟着失败
                log.warn("failed to record token usage for tenant {} ({} input / {} output tokens): {}",
                        normalizedTenantId, inputTokens, outputTokens, billingEx.getMessage(), billingEx);
            }

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
            persistConversationMemory(normalizedTenantId, memoryUserId, chatId, prompt);
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

    /** 向量相似检索，包一层 rag.retrieval.* Timer/Counter（按 outcome 打标）。 */
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

    /** 粗排 + 精排管线调用，包一层 rag.rerank.* Timer（按 outcome 打标）。 */
    private RerankPipeline.PipelineResult rerankWithMetrics(String prompt, List<Document> docs) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            RerankPipeline.PipelineResult reranked = rerankPipeline.rerank(prompt, docs, ragProperties.getRerankTopK());
            // 管线实现理论上可能返回 null 或 documents=null，按 empty 计而不是 NPE
            outcome = reranked == null || reranked.documents() == null || reranked.documents().isEmpty()
                    ? "empty"
                    : "success";
            return reranked;
        } finally {
            sample.stop(Timer.builder("rag.rerank.latency")
                    .description("Latency of local rerank stage")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 把精排后的文档拼成带 [n] 编号的 LLM 上下文（编号与引用脚注一一对应）。 */
    private String buildContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            if (d == null) {
                continue;
            }
            sb.append("[").append(i + 1).append("] ")
                    .append(citationText(d))
                    .append("\n")
                    .append(d.getText())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /** 旧版引用字符串：{@code source=<file_name>, chunk=<chunk_index>}。 */
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
        String snippet = emptyIfBlank(d.getText()).replaceAll("\\s+", " ").trim();
        if (snippet.length() > 180) {
            snippet = snippet.substring(0, 180) + "...";
        }
        return new CitationCard(index, displayName, page, chunkIndex, snippet);
    }

    /** 证据文本：原文空白归一化并截断到 180 字符。 */
    private String evidenceText(Document d) {
        String raw = emptyIfBlank(d.getText()).replaceAll("\\s+", " ").trim();
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

    // ── 三层记忆接线（spec-C2/C3/C4） ─────────────────────────────

    /**
     * userId 取 JWT 身份（AuthIdentity principal）；演示模式（安全关闭）无身份，
     * 退化为会话级键 chat:{chatId}，不新造身份体系。
     */
    private String currentUserId(String chatId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthIdentity identity
                && StringUtils.hasText(identity.getPrincipal())) {
            return identity.getPrincipal();
        }
        return "chat:" + chatId;
    }

    /**
     * 读取时机（spec-C3/C4）：long 记忆最新一条槽位 + 高置信 fact（≥0.8），
     * 空槽位不拼，避免污染 prompt。
     */
    private String buildKnownInfo(String tenantId, String userId) {
        try {
            StringBuilder sb = new StringBuilder();
            List<MemoryItemRecord> longMems = memoryService.queryLongMemory(tenantId, userId, 1);
            if (!longMems.isEmpty()) {
                SlotExtractor.Slots slots = slotExtractor.parse(longMems.get(0).getContent());
                String described = slotExtractor.describe(slots);
                if (!described.isEmpty()) {
                    sb.append("\n\n【已知信息】").append(described);
                }
            }
            List<MemoryItemRecord> facts = memoryService.queryFactMemory(tenantId, 0.8, 5);
            if (!facts.isEmpty()) {
                sb.append("\n\n【常见问题结论】");
                for (MemoryItemRecord fact : facts) {
                    sb.append("\n- ").append(fact.getContent());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // 记忆读失败不阻断问答主链路，但原因要留痕
            log.warn("failed to load known-info memory for tenant {} user {}: {}",
                    tenantId, userId, e.getMessage(), e);
            return "";
        }
    }

    /**
     * 写入时机（spec-C2/C3）：每轮结束抽槽位 → 与已有 long 合并（新覆盖旧、topics 并集）；
     * 每轮写一条 short；short 超过 6 条时把最旧的压成模板摘要（不用 LLM）。
     * fact 不在此自动写入（防污染，spec-C4）。
     */
    private void persistConversationMemory(String tenantId, String userId, String chatId, String prompt) {
        try {
            SlotExtractor.Slots incoming = slotExtractor.extract(prompt);
            if (!slotExtractor.isEmpty(incoming)) {
                List<MemoryItemRecord> longMems = memoryService.queryLongMemory(tenantId, userId, 1);
                SlotExtractor.Slots existing = longMems.isEmpty()
                        ? null
                        : slotExtractor.parse(longMems.get(0).getContent());
                SlotExtractor.Slots merged = slotExtractor.merge(existing, incoming);
                String json = slotExtractor.toJson(merged);
                if (StringUtils.hasText(json)) {
                    memoryService.saveLongMemory(tenantId, userId, json, "dialog:" + chatId);
                }
            }
            memoryService.saveShortMemory(tenantId, userId, titleOf(prompt), "dialog:" + chatId);

            List<MemoryItemRecord> shorts = memoryService.queryShortMemory(tenantId, userId, 8);
            if (shorts.size() > 6) {
                List<MemoryItemRecord> oldest = shorts.subList(6, shorts.size());
                String titles = oldest.stream()
                        .limit(3)
                        .map(MemoryItemRecord::getContent)
                        .collect(java.util.stream.Collectors.joining("；"));
                SlotExtractor.Slots current = slotExtractor.parse(
                        memoryService.queryLongMemory(tenantId, userId, 1).stream()
                                .findFirst()
                                .map(MemoryItemRecord::getContent)
                                .orElse(null));
                String summary = "用户已确认：" + slotExtractor.describe(current)
                        + "；此前问过：" + titles;
                for (MemoryItemRecord item : oldest) {
                    memoryService.deleteMemory(tenantId, item.getMemoryId());
                }
                memoryService.saveShortMemory(tenantId, userId, summary, "summary");
            }
        } catch (RuntimeException e) {
            // 记忆写失败不阻断问答主链路（SpotBugs REC_CATCH_EXCEPTION：try 块无可抛受检异常），但原因要留痕
            log.warn("failed to persist conversation memory for tenant {} chat {}: {}",
                    tenantId, chatId, e.getMessage(), e);
        }
    }

    /** short 记忆标题：prompt 空白归一化后截断到 30 字符。 */
    private String titleOf(String prompt) {
        String clean = emptyIfBlank(prompt).replaceAll("\\s+", " ").trim();
        return clean.length() <= 30 ? clean : clean.substring(0, 30) + "...";
    }

    /** 空白转空串，避免后续处理出现 null。 */
    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    /**
     * 过滤表达式取值转义：反斜杠与双引号各翻倍，防止 chatId/tenantId 中的
     * 特殊字符破坏向量库过滤表达式结构（过滤注入防线）。
     */
    private String escapeFilterValue(String value) {
        String raw = emptyIfBlank(value);
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 追加在答案尾部的「引用来源」脚注（[1][2]… 编号），无引用时返回空串。 */
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

    /**
     * 问答结果：answer 已含引用脚注；citations 为旧格式字符串列表；
     * citationCards 为条款级结构化卡片；evidence 为证据摘要；
     * retrievalTrace 为三级管线实测轨迹（见 {@link #buildRetrievalTrace}）。
     */
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
