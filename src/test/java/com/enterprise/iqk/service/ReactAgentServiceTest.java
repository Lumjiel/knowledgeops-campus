package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.AgentObservation;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ReactAgentService 的纯单元测试。决策解析与响应格式化使用真实
 * {@link ReactDecisionParser} / {@link ReactResponseFormatter}（与生产装配一致），
 * 外部协作者全部 mock：AgentHarnessService、ChatClient、ModelRouter、TenantCostService。
 *
 * <p>注意两点与姊妹类 WorkflowReactAgentService 的行为差异（有意用测试固化）：
 * 本类 MAX_STEPS=4、executeAction 不带 workflow 引擎（taskId/stepId 传空串）；
 * 决策解析独立在 ReactDecisionParser 中（fallback 分支与 Workflow 版私有副本已漂移）。
 */
class ReactAgentServiceTest {

    private static final String FINISH_JSON =
            "{\"thought\":\"信息足够\",\"action\":\"finish\",\"answer\":\"北京校区共 3 所。\"}";
    private static final String RAG_SEARCH_JSON =
            "{\"thought\":\"需要检索\",\"action\":\"rag_search\",\"action_input\":{\"query\":\"校区\"}}";

    // ── chat()：请求校验与降级 ────────────────────────────────────

    @Test
    void rejectsRequestsWithoutPromptOrChatIdBeforeCallingExternalDependencies() {
        ReactAgentService service = new ReactAgentService(
                mock(AgentHarnessService.class),
                mock(ChatClient.class),
                mock(ModelRouter.class),
                mock(TenantCostService.class),
                mock(MeterRegistry.class),
                new ReactDecisionParser(new ObjectMapper()),
                new ReactResponseFormatter(new ObjectMapper())
        );
        ReactChatRequestVO missingPrompt = new ReactChatRequestVO();
        missingPrompt.setChatId("chat-1");
        ReactChatRequestVO missingChatId = new ReactChatRequestVO();
        missingChatId.setPrompt("hello");

        assertThatThrownBy(() -> service.chat(missingPrompt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("prompt is required");
        assertThatThrownBy(() -> service.chat(missingChatId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chatId is required");
    }

    @Test
    void marksTheResponseAsFallbackWhenThePlannerModelIsUnavailable() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = mock(ChatClient.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(chatClient.prompt()).thenThrow(new IllegalStateException("model unavailable"));
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("quality", "model-a", "premium", false, "profile_match", "", "", null)
        );
        ReactAgentService service = new ReactAgentService(
                harness,
                chatClient,
                modelRouter,
                mock(TenantCostService.class),
                mock(MeterRegistry.class),
                new ReactDecisionParser(new ObjectMapper()),
                new ReactResponseFormatter(new ObjectMapper())
        );
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("高温健康风险有哪些？");
        request.setChatId("chat-1");
        request.setModelProfile("quality");

        assertThat(service.chat(request).getFallback()).isTrue();
        verify(harness, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    // ── chat()：正常路径 ──────────────────────────────────────────

    @Test
    void chatReturnsPlannerFinishAnswerWithoutToolExecution() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        ReactAgentService service = service(harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getOk()).isEqualTo(1);
        assertThat(response.getMsg()).isEqualTo("ok");
        assertThat(response.getChatId()).isEqualTo("chat-1");
        assertThat(response.getAnswer()).isEqualTo("北京校区共 3 所。");
        assertThat(response.getFallback()).isFalse();
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getRouteProfile()).isEqualTo("quality");
        assertThat(response.getTrace()).hasSize(1);
        assertThat(response.getTrace().get(0).getAction()).isEqualTo("finish");
        assertThat(response.getTrace().get(0).getObservation())
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("status", "completed")
                .containsEntry("fallback", false);

        verifyNoInteractions(harness);
    }

    @Test
    void chatSanitizesJsonLeakedIntoFinalAnswerByExtractingAnswerField() {
        // cheap 模型偶发把决策 JSON 当答案下发：answer 字段本身是又一层决策 JSON
        String leakingPlannerJson =
                "{\"thought\":\"信息足够\",\"action\":\"finish\",\"answer\":\"{\\\"answer\\\":\\\"真正的答案\\\"}\"}";
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(leakingPlannerJson);
        ReactAgentService service = service(harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).isEqualTo("真正的答案");
        assertThat(response.getFallback()).isFalse();
    }

    @Test
    void chatSubstitutesPlaceholderWhenLeakedJsonCannotBeSanitized() {
        // answer 以 "{" 开头但提取不出 answer 字段 → 消毒层给出固定占位答案而不是裸 JSON
        String unparseableLeak =
                "{\"thought\":\"信息足够\",\"action\":\"finish\",\"answer\":\"{\"}";
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(unparseableLeak);
        ReactAgentService service = service(harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).isEqualTo("（模型输出异常，请重试）");
    }

    @Test
    void chatExecutesToolActionThenFinishesOnLaterPlannerCall() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("school_db", Map.of("schools", List.of("北京校区")), 5L));
        ChatClient chatClient = chatClientReturning(RAG_SEARCH_JSON, FINISH_JSON);
        ReactAgentService service = service(harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).isEqualTo("北京校区共 3 所。");
        assertThat(response.getTrace()).hasSize(2);
        assertThat(response.getTrace().get(0).getAction()).isEqualTo("rag_search");
        assertThat(response.getTrace().get(1).getAction()).isEqualTo("finish");
        assertThat(response.getTrace().get(0).getObservation())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("source", "school_db");

        org.mockito.ArgumentCaptor<AgentAction> actionCaptor =
                org.mockito.ArgumentCaptor.forClass(AgentAction.class);
        verify(harness).execute(actionCaptor.capture());
        AgentAction action = actionCaptor.getValue();
        assertThat(action.action()).isEqualTo("rag_search");
        assertThat(action.actionInput()).containsEntry("query", "校区");
        assertThat(action.tenantId()).isEqualTo("public");
        assertThat(action.chatId()).isEqualTo("chat-1");
        // 本类不走 workflow 引擎：taskId/stepId 固定传空串
        assertThat(action.taskId()).isEmpty();
        assertThat(action.stepId()).isEmpty();
    }

    @Test
    void chatSummarizesViaModelAfterMaxStepsWithoutFinish() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("rag", Map.of("hits", List.of("片段")), 3L));
        // MAX_STEPS=4：4 次规划都不 finish，第 5 次 callModel 是 summarizeAnswer
        ChatClient chatClient = chatClientReturning(
                RAG_SEARCH_JSON, RAG_SEARCH_JSON, RAG_SEARCH_JSON, RAG_SEARCH_JSON, "最终总结答案");
        ReactAgentService service = service(harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).isEqualTo("最终总结答案");
        assertThat(response.getFallback()).isFalse();
        assertThat(response.getTrace()).hasSize(4);
        assertThat(response.getTrace()).allSatisfy(step -> assertThat(step.getAction()).isEqualTo("rag_search"));
        verify(harness, times(4)).execute(any(AgentAction.class));
    }

    @Test
    void chatReturnsDeterministicFallbackWhenFinalSummaryModelReturnsBlank() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("rag", Map.of("hits", List.of("片段")), 3L));
        // 4 次规划 + 1 次总结，总结返回空白 → summarizeAnswer 落入确定性兜底并标记 fallback
        ChatClient chatClient = chatClientReturning(
                RAG_SEARCH_JSON, RAG_SEARCH_JSON, RAG_SEARCH_JSON, RAG_SEARCH_JSON, "");
        ReactAgentService service = service(harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).isEqualTo("当前未能生成最终答案，请稍后重试。");
        assertThat(response.getFallback()).isTrue();
    }

    @Test
    void chatAttachesCitationFooterWhenPlannerFailureFallsBackToSchoolFlow() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientThrowing(new IllegalStateException("model unavailable"));
        ReactAgentService service = service(harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getFallback()).isTrue();
        assertThat(response.getAnswer()).contains("已识别为校区查询请求");
        assertThat(response.getAnswer()).contains("引用来源:");
        assertThat(response.getAnswer()).contains("[1] source=fallback://school_query_flow, chunk=1");
        assertThat(response.getCitations())
                .containsExactly("source=fallback://school_query_flow, chunk=1");
        assertThat(response.getTrace()).hasSize(1);
        assertThat(response.getTrace().get(0).getThought())
                .isEqualTo("Planner unavailable; fallback to deterministic school-query answer.");
        assertThat(response.getTrace().get(0).getObservation())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("fallback", true);
        verifyNoInteractions(harness);
    }

    @Test
    void chatPropagatesTenantFromMdcToRouterAndBudgetGuard() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        TenantCostService cost = mock(TenantCostService.class);
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        ReactAgentService service = service(harness, chatClient, cost);

        MDC.put("tenant_id", "acme");
        try {
            ReactChatResponseVO response = service.chat(request("请查询校区"));
            assertThat(response.getOk()).isEqualTo(1);
        } finally {
            MDC.remove("tenant_id");
        }

        verify(cost).assertBudget(eq("acme"), eq("premium"), anyLong(), anyLong());
        // callModel 的估算调用共 3 次：system prompt、user prompt、模型输出
        verify(cost, times(3)).estimateTokens(anyString());
    }

    // ── stream() ──────────────────────────────────────────────────

    @Test
    void streamEmitsTraceThenSplitTokensThenDoneForDirectAnswer() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        ReactAgentService service = service(harness, chatClient, mock(TenantCostService.class), registry);

        List<String> events = service.stream(request("请查询校区"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // 答案不足 24 字符 → splitForStreaming 产生 1 个 token 段
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).startsWith("event: trace\n").contains("\"action\":\"finish\"");
        assertThat(events.get(1)).isEqualTo("event: token\ndata: {\"token\":\"北京校区共 3 所。\"}\n\n");
        assertThat(events.get(2)).startsWith("event: done\n")
                .contains("\"answer\":\"北京校区共 3 所。\"")
                .contains("\"fallback\":false");

        assertThat(registry.get("react.stream.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("react.stream.first_token.latency").tag("outcome", "success").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("react.stream.total.latency").tag("outcome", "success").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void streamBuffersAndSanitizesWhenAnswerStartsWithJsonBrace() {
        // 直接答案以 "{" 开头 → switchOnFirst 缓冲全量、提取 answer 字段后以单 token 下发
        String leakingAnswer = "{\"answer\":\"流式答案\"}";
        String leakingPlannerJson = "{\"thought\":\"信息足够\",\"action\":\"finish\",\"answer\":\""
                + leakingAnswer.replace("\"", "\\\"") + "\"}";
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(leakingPlannerJson);
        ReactAgentService service = service(harness, chatClient);

        List<String> events = service.stream(request("请查询校区"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).hasSize(3);
        assertThat(events.get(1)).isEqualTo("event: token\ndata: {\"token\":\"流式答案\"}\n\n");
        assertThat(events.get(2)).startsWith("event: done\n").contains("\"answer\":\"流式答案\"");
    }

    @Test
    void streamStreamsFinalAnswerFromModelWhenPlannerNeverFinishes() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("rag", Map.of("hits", List.of("片段")), 3L));
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(7);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatClient chatClient = chatClientWithModelStream(
                RAG_SEARCH_JSON, Flux.just("流", "式"));
        ReactAgentService service = service(harness, chatClient, cost, registry);

        List<String> events = service.stream(request("请查询校区"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // MAX_STEPS=4：4 条 trace + 2 个 token + 1 个 done
        assertThat(events).hasSize(7);
        assertThat(events.subList(4, 6)).containsExactly(
                "event: token\ndata: {\"token\":\"流\"}\n\n",
                "event: token\ndata: {\"token\":\"式\"}\n\n");
        assertThat(events.get(6)).startsWith("event: done\n").contains("\"answer\":\"流式\"");

        verify(harness, times(4)).execute(any(AgentAction.class));
        // callModelStream 的 doFinally 在流终止后恰好记一次用量
        verify(cost, times(1)).recordUsage(eq("public"), eq("premium"), anyLong(), anyLong(), eq("react_final"));
        assertThat(registry.get("react.stream.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
    }

    /**
     * 注意：规划模型抛错不会触发 onErrorResume——reason() 的 catch(RuntimeException)
     * 会把它吞掉并降级为 fallback 决策。真正能到达 error 事件的是流中途断掉
     * （callModelStream 返回的 Flux 出错，穿过 tokenFlux 无处可接）。
     */
    @Test
    void streamConvertsMidStreamModelFailureIntoErrorEvent() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("rag", Map.of("hits", List.of("片段")), 3L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatClient chatClient = chatClientWithModelStream(
                RAG_SEARCH_JSON, Flux.error(new IllegalStateException("upstream down")));
        ReactAgentService service = service(harness, chatClient, mock(TenantCostService.class), registry);

        List<String> events = service.stream(request("请查询校区"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // MAX_STEPS=4：4 条 trace 已发出，随后转为单个 error 事件（不会出现 done）
        assertThat(events).hasSize(5);
        assertThat(events.subList(0, 4)).allSatisfy(event -> assertThat(event).startsWith("event: trace\n"));
        assertThat(events.get(4)).isEqualTo("event: error\ndata: {\"message\":\"upstream down\"}\n\n");

        verify(harness, times(4)).execute(any(AgentAction.class));
        assertThat(registry.get("react.stream.requests").tag("outcome", "error").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("react.stream.total.latency").tag("outcome", "error").timer().count())
                .isEqualTo(1L);
        assertThat(registry.find("react.stream.first_token.latency").timer()).isNull();
    }

    @Test
    void streamReportsValidationErrorAsErrorEventWithoutCallingModel() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        ReactAgentService service = service(harness, chatClient, mock(TenantCostService.class), registry);

        ReactChatRequestVO invalid = new ReactChatRequestVO();
        invalid.setChatId("chat-1"); // prompt 缺失

        List<String> events = service.stream(invalid)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).containsExactly("event: error\ndata: {\"message\":\"prompt is required\"}\n\n");
        verifyNoInteractions(chatClient, harness);
        assertThat(registry.get("react.stream.requests").tag("outcome", "error").counter().count())
                .isEqualTo(1.0);
    }

    // ── 构造辅助 ──────────────────────────────────────────────────

    private ReactAgentService service(AgentHarnessService harness, ChatClient chatClient) {
        return service(harness, chatClient, mock(TenantCostService.class), new SimpleMeterRegistry());
    }

    private ReactAgentService service(AgentHarnessService harness, ChatClient chatClient,
                                      TenantCostService cost) {
        return service(harness, chatClient, cost, new SimpleMeterRegistry());
    }

    private ReactAgentService service(AgentHarnessService harness, ChatClient chatClient,
                                      TenantCostService cost, MeterRegistry registry) {
        return new ReactAgentService(harness, chatClient, modelRouterRouting(), cost, registry,
                new ReactDecisionParser(new ObjectMapper()), new ReactResponseFormatter(new ObjectMapper()));
    }

    private ModelRouter modelRouterRouting() {
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(any(), any(), any(), any())).thenReturn(
                new ModelRouter.ModelRouteDecision("quality", "model-a", "premium", false, "profile_match", "", "", null));
        return modelRouter;
    }

    private ReactChatRequestVO request(String prompt) {
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt(prompt);
        request.setChatId("chat-1");
        request.setModelProfile("quality");
        return request;
    }

    /**
     * 规划/总结走 routedPrompt(...).system().user().call().content()。
     * spec 用 RETURNS_SELF：options/system/user/advisors 返回 spec 自身且 advisors 的
     * Consumer 参数不会被调用，规避 advisors(Consumer)/advisors(Advisor...) 重载歧义。
     */
    private ChatClient chatClientReturning(String... callContents) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(callContents[0],
                Arrays.copyOfRange(callContents, 1, callContents.length));
        return chatClient;
    }

    /** 规划模型整体不可用：prompt() 直接抛错，用于验证 reason() 的 fallback 降级路径。 */
    private ChatClient chatClientThrowing(RuntimeException cause) {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(cause);
        return chatClient;
    }

    /** 在 call().content() 链基础上再挂 stream().content()，用于 callModelStream 路径。 */
    private ChatClient chatClientWithModelStream(String plannerContent, Flux<String> streamContent) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(plannerContent);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(streamContent);
        return chatClient;
    }
}
