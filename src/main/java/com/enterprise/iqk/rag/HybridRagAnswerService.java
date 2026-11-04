package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.constants.SystemConstants;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.CitationService;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.retrieval.EvidenceJudgeService;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.ScoredDocument;
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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * File: HybridRagAnswerService.java
 * <p>
 * 混合检索问答编排器（docs/architecture-hybrid-retrieval.md 流程图的落点）：
 * 六步串联——混合检索 → 证据三维评分 → 生成编号引用 → 组装上下文 →
 * LLM 生成答案（经 ModelRouter 路由与租户预算管控）→ 追加「引用来源」尾注。
 * 返回结构对应文档「返回结构」：answer + citations + evidence + traceId + memoryUsed。
 * 管道整体延迟/请求数按 outcome 打点（rag.hybrid.pipeline.*）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRagAnswerService {

    private final HybridRetrievalService hybridRetrievalService;
    private final EvidenceJudgeService evidenceJudgeService;
    private final CitationService citationService;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;
    private final TenantCostService tenantCostService;

    /**
     * 执行完整混合 RAG 管道。检索为空时短路返回固定文案（不调 LLM）；
     * 调用模型前先估算输入 token 并断言租户预算（超限抛出异常，管道 outcome=error）。
     * 耗时与请求数在 finally 中上报，保证异常路径也有指标。
     *
     * @param prompt         用户问题
     * @param tenantId       租户 ID（内部归一化）
     * @param chatId         会话 ID（检索过滤 + 模型路由 subjectKey）
     * @param conversationId 对话记忆 ID（透传 ChatClient advisor）
     * @param modelProfile   模型档位（路由决策用）
     * @return 答案（含引用尾注）+ 引用 + 证据 + traceId + 检索统计
     */
    public HybridRagResult answer(String prompt, String tenantId, String chatId,
                                   String conversationId, String modelProfile) {
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String pipelineOutcome = "error";

        try {
            String normalizedTenantId = TenantContext.normalize(tenantId);

            // Step 1: Hybrid retrieval (vector + keyword + graph + web)
            HybridRetrievalService.HybridRetrievalResult retrievalResult =
                    hybridRetrievalService.retrieve(prompt, normalizedTenantId, chatId,
                            ragProperties.getRetrieveTopK());

            List<ScoredDocument> retrievedDocs = retrievalResult.documents();
            if (retrievedDocs.isEmpty()) {
                pipelineOutcome = "empty";
                return HybridRagResult.builder()
                        .answer("没有在当前知识库中检索到可用内容。")
                        .citations(List.of())
                        .evidence(List.of())
                        .traceId(traceId)
                        .memoryUsed(List.of())
                        .build();
            }

            // Step 2: Evidence judging
            List<EvidenceItem> evidence = evidenceJudgeService.judge(retrievedDocs, prompt);

            // Step 3: Build citations
            List<CitationItem> citations = citationService.buildCitations(evidence);

            // Step 4: Build context from top evidence
            String context = buildContext(retrievedDocs);

            // Step 5: Generate answer via LLM
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(
                    modelProfile, "rag_hybrid", normalizedTenantId, chatId);
            long inputTokens = tenantCostService.estimateTokens(prompt + "\n" + context);
            tenantCostService.assertBudget(normalizedTenantId, decision.costTier(), inputTokens, 600);

            String answer = chatClient.prompt()
                    .options(ChatOptions.builder().model(decision.model())
                            .temperature(ragProperties.getTemperature()).build())
                    .system(SystemConstants.HYBRID_RAG_ANSWER_SYSTEM)
                    .user("用户问题:%n%s%n%n上下文:%n%s%n".formatted(prompt, context))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            long outputTokens = tenantCostService.estimateTokens(answer);
            tenantCostService.recordUsage(normalizedTenantId, decision.costTier(),
                    inputTokens, outputTokens, "rag_hybrid");

            // Step 6: Append citation footer
            String answerWithCitations = answer + citationService.formatCitationFooter(citations);

            pipelineOutcome = "success";
            return HybridRagResult.builder()
                    .answer(answerWithCitations)
                    .citations(citations)
                    .evidence(evidence)
                    .traceId(traceId)
                    .memoryUsed(List.of())
                    .retrievalStats(HybridRagResult.RetrievalStats.builder()
                            .totalRetrieved(retrievalResult.totalBeforeDedup())
                            .afterDedup(retrievalResult.totalAfterDedup())
                            .finalCount(retrievedDocs.size())
                            .build())
                    .build();

        } finally {
            pipelineSample.stop(Timer.builder("rag.hybrid.pipeline.latency")
                    .description("Overall latency for hybrid RAG pipeline")
                    .tag("outcome", pipelineOutcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            Counter.builder("rag.hybrid.pipeline.requests")
                    .description("Total hybrid RAG pipeline requests")
                    .tag("outcome", pipelineOutcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /** 把检索文档拼成带编号的上下文块：{@code [n] source=…, title=…, chunk=… + 正文}，编号与引用 index 对齐。 */
    private String buildContext(List<ScoredDocument> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            ScoredDocument d = docs.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append("source=").append(d.getSourceType())
                    .append(", title=").append(d.getTitle())
                    .append(", chunk=").append(d.getChunkId())
                    .append("\n")
                    .append(d.getContent())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 混合 RAG 管道的返回结构（对应架构文档「返回结构」）。
     *
     * <ul>
     *   <li>answer：LLM 答案 + 「引用来源」尾注（正文含 [1][2] 引用标记）；</li>
     *   <li>citations / evidence：编号引用与三维评分证据；</li>
     *   <li>traceId：本次管道链路 ID（trace-&lt;12位hex&gt;）；</li>
     *   <li>memoryUsed：预留字段，当前恒为空列表。</li>
     * </ul>
     */
    @Data
    @Builder
    public static class HybridRagResult {
        /** 最终答案（已追加引用尾注）。 */
        private String answer;
        /** 编号引用列表（与证据同序，index 从 1 起）。 */
        private List<CitationItem> citations;
        /** 三维评分证据列表（按综合分降序）。 */
        private List<EvidenceItem> evidence;
        /** 本次管道链路 ID。 */
        private String traceId;
        /** 使用的会话记忆（预留，当前为空）。 */
        private List<String> memoryUsed;
        /** 检索漏斗统计。 */
        private RetrievalStats retrievalStats;

        /** 检索漏斗统计：召回总数 → 去重后 → 最终 top-K。 */
        @Data
        @Builder
        public static class RetrievalStats {
            /** 四路召回合并后的文档总数（去重前）。 */
            private int totalRetrieved;
            /** 指纹去重后的文档数。 */
            private int afterDedup;
            /** 最终返回的 top-K 数量。 */
            private int finalCount;
        }
    }
}
