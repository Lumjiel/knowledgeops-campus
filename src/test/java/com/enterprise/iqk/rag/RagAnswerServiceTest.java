package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.constants.SystemConstants;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.memory.SlotExtractor;
import com.enterprise.iqk.retrieval.RerankPipeline;
import com.enterprise.iqk.security.AuthIdentity;
import com.enterprise.iqk.service.TenantCostService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagAnswerService 的纯单元测试。真实协作者：SimpleMeterRegistry（断言
 * pipeline/retrieval 计数器与 outcome 标签）、RagProperties（默认配置）。
 * mock：VectorStore、ChatClient（{@code RETURNS_SELF} 流式桩，手法与
 * ReactAgentServiceTest 一致）、ModelRouter、TenantCostService、RerankPipeline、
 * MemoryService、SlotExtractor。
 *
 * <p>三条与被测实现对齐的时序约定（勿改测试语义）：
 * <ul>
 *   <li>answer() 的 finally 块总以 outcome 标签（success/empty/error）收口指标，
 *       预算超限/模型调用失败也会计入 error 计数；</li>
 *   <li>记忆读写失败不阻断问答主链路（吞 RuntimeException）；</li>
 *   <li>MemoryService 返回 List 的方法未打桩时 Mockito 默认给空集合，
 *       即「无长期记忆/无 fact/无 short」的正常路径。</li>
 * </ul>
 */
class RagAnswerServiceTest {

    private static final String TENANT = "t1";
    private static final String CHAT_ID = "c1";

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final TenantCostService tenantCostService = mock(TenantCostService.class);
    private final RerankPipeline rerankPipeline = mock(RerankPipeline.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final SlotExtractor slotExtractor = mock(SlotExtractor.class);
    private final RagProperties ragProperties = new RagProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ChatClient.ChatClientRequestSpec chatSpec;
    private final ChatClient chatClient = chatClientReturning("结论 [1]");

    private final RagAnswerService service = new RagAnswerService(vectorStore, chatClient, modelRouter,
            ragProperties, registry, tenantCostService, rerankPipeline, memoryService, slotExtractor);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── debugRetrieve()：只跑检索+精排，不碰 LLM ─────────────────

    @Test
    void debugRetrieveRunsPipelineAndReturnsRankedRows() {
        Document docA = doc("内容A", "a.pdf", 0);
        Document docB = doc("内容B", "b.txt", 2);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA, docB));
        when(rerankPipeline.rerank(eq("高温风险?"), same(List.of(docA, docB)), eq(ragProperties.getRerankTopK())))
                .thenReturn(new RerankPipeline.PipelineResult(List.of(docB, docA), false, 50, 20, 3, 7));

        RagAnswerService.PipelineDebugResult result = service.debugRetrieve("高温风险?", TENANT, CHAT_ID, 10);

        // 行按精排后顺序编号，rank 从 1 开始
        assertThat(result.documents()).hasSize(2);
        assertThat(result.documents().get(0)).containsEntry("rank", 1)
                .containsEntry("content", "内容B")
                .containsEntry("metadata", docB.getMetadata());
        assertThat(result.documents().get(1)).containsEntry("rank", 2);
        assertThat(result.recallCount()).isEqualTo(2);
        assertThat(result.degraded()).isFalse();
        assertThat(result.coarseMs()).isEqualTo(3);
        assertThat(result.fineMs()).isEqualTo(7);

        // 检索请求：topN/阈值来自配置，过滤器同时圈定会话域与共享语料域
        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("高温风险?");
        assertThat(request.getTopK()).isEqualTo(ragProperties.getRetrievalTopN());
        assertThat(request.getFilterExpression())
                .isEqualTo("tenant_id == \"t1\" && (chat_id == \"c1\" || chat_id == \"shared-corpus\")");
        verifyNoLlmCall();
    }

    @Test
    void debugRetrieveEscapesQuotesInFilterValues() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.debugRetrieve("q", "t\"x", "c\"1", 10);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        // 引号必须转义，否则注入的 chat_id 可以越权读到其他租户/会话语料
        assertThat(requestCaptor.getValue().getFilterExpression())
                .isEqualTo("tenant_id == \"t\\\"x\" && (chat_id == \"c\\\"1\" || chat_id == \"shared-corpus\")");
    }

    @Test
    void debugRetrieveSkipsRerankWhenNothingRetrieved() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagAnswerService.PipelineDebugResult result = service.debugRetrieve("q", TENANT, CHAT_ID, 10);

        assertThat(result.documents()).isEmpty();
        assertThat(result.recallCount()).isZero();
        assertThat(result.degraded()).isFalse();
        assertThat(result.coarseMs()).isZero();
        assertThat(result.fineMs()).isZero();
        verify(rerankPipeline, never()).rerank(anyString(), any(), anyInt());
        verifyNoLlmCall();
    }

    // ── answer()：空召回与主链路 ─────────────────────────────────

    @Test
    void answerReturnsEmptyResponseWhenNothingRetrieved() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagAnswerService.RagResult result = service.answer("未知问题?", TENANT, CHAT_ID, "conv-1", null);

        assertThat(result.getAnswer()).isEqualTo("没有在当前知识库中检索到可用内容。");
        assertThat(result.getCitations()).isEmpty();
        assertThat(result.getCitationCards()).isEmpty();
        assertThat(result.getEvidence()).containsExactly("未检索到匹配文档，请先上传资料或调整检索词。");
        verifyNoLlmCall();
        verify(rerankPipeline, never()).rerank(anyString(), any(), anyInt());
        // 空召回也会写入会话记忆（short 一条）
        verify(memoryService).saveShortMemory(eq(TENANT), eq("chat:" + CHAT_ID), eq("未知问题?"), eq("dialog:" + CHAT_ID));
        // 指标以 empty outcome 收口
        assertThat(registry.counter("rag.pipeline.requests", "outcome", "empty").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.retrieval.requests", "outcome", "empty").count()).isEqualTo(1.0);
    }

    @Test
    void answerBuildsCitationsFooterCardsAndRecordsUsage() {
        Document docA = doc("内容A", "a.pdf", 0);
        docA.getMetadata().put("page_number", 3);
        Document docB = doc("内容B", "b.txt", 2);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA, docB));
        when(rerankPipeline.rerank(eq("高温风险?"), same(List.of(docA, docB)), eq(ragProperties.getRerankTopK())))
                .thenReturn(new RerankPipeline.PipelineResult(List.of(docA, docB), false, 50, 20, 3, 7));
        when(modelRouter.resolve(eq("balanced"), eq("rag"), eq(TENANT), eq(CHAT_ID)))
                .thenReturn(new ModelRouter.ModelRouteDecision("balanced", "rag-model", "tier-a", false, "ok", null, null, null));
        when(tenantCostService.estimateTokens(anyString())).thenReturn(100L, 20L);

        RagAnswerService.RagResult result = service.answer("高温风险?", TENANT, CHAT_ID, "conv-1", "balanced");

        // 回答正文 + 引用脚注
        String expectedFooter = "\n\n---\n引用来源:\n[1] source=a.pdf, chunk=0\n[2] source=b.txt, chunk=2\n";
        assertThat(result.getAnswer()).isEqualTo("结论 [1]" + expectedFooter);
        assertThat(result.getCitations()).containsExactly("source=a.pdf, chunk=0", "source=b.txt, chunk=2");
        assertThat(result.getEvidence()).containsExactly("内容A", "内容B");

        // 条款级引用卡片：.pdf 去后缀、page_number 透传、index 与 [1][2] 对应
        assertThat(result.getCitationCards()).hasSize(2);
        RagAnswerService.CitationCard card1 = result.getCitationCards().get(0);
        assertThat(card1.index()).isEqualTo(1);
        assertThat(card1.file()).isEqualTo("a");
        assertThat(card1.page()).isEqualTo(3);
        assertThat(card1.chunkIndex()).isZero();
        assertThat(card1.snippet()).isEqualTo("内容A");
        assertThat(result.getCitationCards().get(1).file()).isEqualTo("b.txt");
        assertThat(result.getCitationCards().get(1).page()).isNull();

        // 检索轨迹为管线实测值 + 配置快照
        assertThat(result.getRetrievalTrace())
                .containsEntry("recall", 50)
                .containsEntry("coarse", 20)
                .containsEntry("fine", 2)
                .containsEntry("coarseMs", 3L)
                .containsEntry("fineMs", 7L)
                .containsEntry("degraded", false)
                .containsEntry("tokenizer", "regex")
                .containsEntry("coarseReranker", "overlap")
                .containsEntry("fineReranker", "rrf");

        // 预算校验在模型调用前，用量在调用后按 (输入, 输出) 记账
        verify(tenantCostService).assertBudget(TENANT, "tier-a", 100L, 600L);
        verify(tenantCostService).recordUsage(TENANT, "tier-a", 100L, 20L, "rag");

        // 无长期记忆/无 fact 时 system 提示词不带【已知信息】
        verify(chatSpec).system(SystemConstants.RAG_ANSWER_SYSTEM);
        // user 消息携带编号化上下文
        String context = "[1] source=a.pdf, chunk=0\n内容A\n\n[2] source=b.txt, chunk=2\n内容B\n\n";
        verify(chatSpec).user("用户问题:%n%s%n%n上下文:%n%s%n".formatted("高温风险?", context));

        assertThat(registry.counter("rag.pipeline.requests", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.retrieval.requests", "outcome", "success").count()).isEqualTo(1.0);
    }

    @Test
    void answerLimitsSelectedDocumentsToRerankTopKGuard() {
        Document docA = doc("内容A", "a.pdf", 0);
        Document docB = doc("内容B", "b.txt", 1);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA, docB));
        when(rerankPipeline.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankPipeline.PipelineResult(List.of(docA, docB), false, 50, 20, 1, 1));
        when(modelRouter.resolve(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ModelRouter.ModelRouteDecision("balanced", "m", "tier-a", false, "ok", null, null, null));
        // rerankTopK 配置为 0 时按 max(1, topK)=1 兜底，避免 selected 为空
        ragProperties.setRerankTopK(0);

        RagAnswerService.RagResult result = service.answer("q", TENANT, CHAT_ID, "conv-1", "balanced");

        assertThat(result.getCitations()).hasSize(1);
        assertThat(result.getAnswer()).contains("[1] ").doesNotContain("[2] ");
        assertThat(result.getCitationCards()).hasSize(1);
    }

    @Test
    void answerParsesCitationCardPageAndChunkVariants() {
        Document intPage = doc("甲", "手册.pdf", 0);
        intPage.getMetadata().put("page_number", 5);
        Document stringPage = doc("乙", "指南.pdf", 1);
        stringPage.getMetadata().put("page_number", " 7 ");
        Document badPage = doc("丙", "附录.pdf", "x");
        badPage.getMetadata().put("page_number", "abc");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(intPage, stringPage, badPage));
        when(rerankPipeline.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankPipeline.PipelineResult(List.of(intPage, stringPage, badPage), false, 30, 10, 1, 1));
        when(modelRouter.resolve(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ModelRouter.ModelRouteDecision("balanced", "m", "tier-a", false, "ok", null, null, null));

        RagAnswerService.RagResult result = service.answer("q", TENANT, CHAT_ID, "conv-1", "balanced");

        List<RagAnswerService.CitationCard> cards = result.getCitationCards();
        // Number 类型直接取值；数字字符串去空白后解析；非法值页码置 null 不编页码
        assertThat(cards.get(0).page()).isEqualTo(5);
        assertThat(cards.get(1).page()).isEqualTo(7);
        assertThat(cards.get(2).page()).isNull();
        // 非数字 chunk_index 兜 0
        assertThat(cards.get(2).chunkIndex()).isZero();
        assertThat(cards.get(2).file()).isEqualTo("附录");
    }

    // ── answer()：三层记忆接线 ──────────────────────────────────

    @Test
    void answerPrependsMemoryContextToSystemPrompt() {
        stubHappyPipeline();
        MemoryItemRecord longMem = MemoryItemRecord.builder().memoryId("m1").content("{\"grade\":3}").build();
        when(memoryService.queryLongMemory(TENANT, "chat:" + CHAT_ID, 1)).thenReturn(List.of(longMem));
        when(slotExtractor.parse("{\"grade\":3}")).thenReturn(new SlotExtractor.Slots(3, null, null, null));
        when(slotExtractor.describe(new SlotExtractor.Slots(3, null, null, null))).thenReturn("年级=高三");
        MemoryItemRecord fact1 = MemoryItemRecord.builder().memoryId("f1").content("高温停工规定").build();
        MemoryItemRecord fact2 = MemoryItemRecord.builder().memoryId("f2").content("极端天气预警流程").build();
        when(memoryService.queryFactMemory(TENANT, 0.8, 5)).thenReturn(List.of(fact1, fact2));

        service.answer("高温风险?", TENANT, CHAT_ID, "conv-1", "balanced");

        verify(chatSpec).system(SystemConstants.RAG_ANSWER_SYSTEM
                + "\n\n【已知信息】年级=高三"
                + "\n\n【常见问题结论】\n- 高温停工规定\n- 极端天气预警流程");
    }

    @Test
    void answerToleratesMemoryReadFailure() {
        stubHappyPipeline();
        when(memoryService.queryLongMemory(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("记忆库不可用"));

        RagAnswerService.RagResult result = service.answer("高温风险?", TENANT, CHAT_ID, "conv-1", "balanced");

        // 记忆读失败降级为无已知信息，问答主链路不受影响
        assertThat(result.getAnswer()).startsWith("结论 [1]");
        verify(chatSpec).system(SystemConstants.RAG_ANSWER_SYSTEM);
    }

    @Test
    void answerToleratesMemoryWriteFailure() {
        stubHappyPipeline();
        when(slotExtractor.extract(anyString())).thenThrow(new RuntimeException("记忆服务挂了"));

        RagAnswerService.RagResult result = service.answer("高温风险?", TENANT, CHAT_ID, "conv-1", "balanced");

        assertThat(result.getAnswer()).startsWith("结论 [1]");
        verify(memoryService, never()).saveShortMemory(anyString(), anyString(), anyString(), anyString());
        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void answerUsesJwtIdentityForMemoryKeys() {
        stubHappyPipeline();
        AuthIdentity identity = AuthIdentity.builder().principal("user-9").build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(identity, null));

        service.answer("高温风险?", TENANT, CHAT_ID, "conv-1", "balanced");

        // 有 JWT 身份时记忆键用身份而非会话键；读记忆在 buildKnownInfo 与持久化各一次
        verify(memoryService, times(2)).queryLongMemory(TENANT, "user-9", 1);
        verify(memoryService).saveShortMemory(eq(TENANT), eq("user-9"), anyString(), eq("dialog:" + CHAT_ID));
    }

    @Test
    void answerMergesAndSavesLongMemoryWhenSlotsExtracted() {
        stubHappyPipeline();
        MemoryItemRecord longMem = MemoryItemRecord.builder().memoryId("m1").content("{\"grade\":2}").build();
        when(memoryService.queryLongMemory(TENANT, "chat:" + CHAT_ID, 1)).thenReturn(List.of(longMem));
        SlotExtractor.Slots incoming = new SlotExtractor.Slots(3, "北京大学", null, List.of("物理"));
        SlotExtractor.Slots existing = new SlotExtractor.Slots(2, null, null, List.of("数学"));
        SlotExtractor.Slots merged = new SlotExtractor.Slots(3, "北京大学", null, List.of("数学", "物理"));
        when(slotExtractor.extract("我今年高三，关注物理。")).thenReturn(incoming);
        when(slotExtractor.isEmpty(incoming)).thenReturn(false);
        when(slotExtractor.parse("{\"grade\":2}")).thenReturn(existing);
        when(slotExtractor.merge(existing, incoming)).thenReturn(merged);
        when(slotExtractor.toJson(merged)).thenReturn("{\"grade\":3}");
        when(slotExtractor.describe(existing)).thenReturn(""); // 无已知信息，system 提示词不带【已知信息】

        service.answer("我今年高三，关注物理。", TENANT, CHAT_ID, "conv-1", "balanced");

        verify(memoryService).saveLongMemory(TENANT, "chat:" + CHAT_ID, "{\"grade\":3}", "dialog:" + CHAT_ID);
        verify(memoryService).saveShortMemory(eq(TENANT), eq("chat:" + CHAT_ID), anyString(), eq("dialog:" + CHAT_ID));
    }

    @Test
    void answerCompactsShortMemoryBeyondSix() {
        stubHappyPipeline();
        List<MemoryItemRecord> shorts = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            shorts.add(MemoryItemRecord.builder().memoryId("s" + i).content("第" + i + "问").build());
        }
        when(memoryService.queryShortMemory(TENANT, "chat:" + CHAT_ID, 8)).thenReturn(shorts);
        // 压缩时再次读 long 记忆拼「用户已确认」段
        MemoryItemRecord longMem = MemoryItemRecord.builder().memoryId("m1").content("{\"grade\":3}").build();
        when(memoryService.queryLongMemory(TENANT, "chat:" + CHAT_ID, 1)).thenReturn(List.of(longMem));
        when(slotExtractor.parse("{\"grade\":3}")).thenReturn(new SlotExtractor.Slots(3, null, null, null));
        when(slotExtractor.describe(new SlotExtractor.Slots(3, null, null, null))).thenReturn("年级=高三");
        // 抽槽为空（isEmpty=true）→ 跳过 long 合并，但 short 照常写入并触发压缩检查
        SlotExtractor.Slots emptySlots = new SlotExtractor.Slots(null, null, null, null);
        when(slotExtractor.extract(anyString())).thenReturn(emptySlots);
        when(slotExtractor.isEmpty(emptySlots)).thenReturn(true);

        service.answer("最新一问?", TENANT, CHAT_ID, "conv-1", "balanced");

        // 第 7 条起为最旧（索引 6），逐条删除后写一条 summary 模板
        verify(memoryService).deleteMemory(TENANT, "s6");
        verify(memoryService, times(1)).deleteMemory(anyString(), anyString());
        verify(memoryService).saveShortMemory(TENANT, "chat:" + CHAT_ID,
                "用户已确认：年级=高三；此前问过：第6问", "summary");
    }

    // ── answer()：失败路径仍以 error outcome 收口指标 ────────────

    @Test
    void answerStopsPipelineCounterAsErrorWhenBudgetExceeded() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("内容", "a.txt", 0)));
        when(rerankPipeline.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankPipeline.PipelineResult(List.of(doc("内容", "a.txt", 0)), false, 10, 5, 1, 1));
        when(modelRouter.resolve(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ModelRouter.ModelRouteDecision("balanced", "m", "tier-a", false, "ok", null, null, null));
        doThrowBudget();

        assertThatThrownBy(() -> service.answer("q", TENANT, CHAT_ID, "conv-1", "balanced"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("预算超限");

        verify(chatClient, never()).prompt();
        verify(tenantCostService, never()).recordUsage(anyString(), anyString(), anyLong(), anyLong(), anyString());
        assertThat(registry.counter("rag.pipeline.requests", "outcome", "error").count()).isEqualTo(1.0);
    }

    @Test
    void answerStopsPipelineCounterAsErrorWhenModelCallFails() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("内容", "a.txt", 0)));
        when(rerankPipeline.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankPipeline.PipelineResult(List.of(doc("内容", "a.txt", 0)), false, 10, 5, 1, 1));
        when(modelRouter.resolve(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ModelRouter.ModelRouteDecision("balanced", "m", "tier-a", false, "ok", null, null, null));
        when(chatClient.prompt()).thenThrow(new IllegalStateException("模型不可用"));

        assertThatThrownBy(() -> service.answer("q", TENANT, CHAT_ID, "conv-1", "balanced"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型不可用");

        // 模型调用失败发生在记忆写入前，且计数器仍以 error 收口
        verify(memoryService, never()).saveShortMemory(anyString(), anyString(), anyString(), anyString());
        assertThat(registry.counter("rag.pipeline.requests", "outcome", "error").count()).isEqualTo(1.0);
    }

    // ── 测试脚手架 ──────────────────────────────────────────────

    /** 主链路公共桩：召回两条 + 精排原样返回 + 路由成功 + token 计量。 */
    private void stubHappyPipeline() {
        Document docA = doc("内容A", "a.pdf", 0);
        Document docB = doc("内容B", "b.txt", 1);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA, docB));
        when(rerankPipeline.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankPipeline.PipelineResult(List.of(docA, docB), false, 50, 20, 3, 7));
        when(modelRouter.resolve(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ModelRouter.ModelRouteDecision("balanced", "m", "tier-a", false, "ok", null, null, null));
        when(tenantCostService.estimateTokens(anyString())).thenReturn(100L, 20L);
    }

    private void doThrowBudget() {
        org.mockito.Mockito.doThrow(new IllegalStateException("预算超限"))
                .when(tenantCostService)
                .assertBudget(anyString(), anyString(), anyLong(), anyLong());
    }

    private void verifyNoLlmCall() {
        verify(chatClient, never()).prompt();
        verify(modelRouter, never()).resolve(any(), anyString(), anyString(), anyString());
        verify(tenantCostService, never()).estimateTokens(anyString());
    }

    private Document doc(String text, String fileName, Object chunkIndex) {
        return Document.builder()
                .text(text)
                .metadata("file_name", fileName)
                .metadata("chunk_index", chunkIndex)
                .build();
    }

    private ChatClient chatClientReturning(String... callContents) {
        ChatClient client = mock(ChatClient.class);
        chatSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(chatSpec);
        when(chatSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(callContents[0],
                java.util.Arrays.copyOfRange(callContents, 1, callContents.length));
        return client;
    }
}
