package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.AgentObservation;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.TenantCostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WorkflowReactAgentService 的纯单元测试。
 *
 * <p>协作者全部 mock：AgentWorkflowEngine（任务/步骤生命周期）、AgentHarnessService（工具执行）、
 * ChatClient（规划/总结/流式模型调用）、ModelRouter、TenantCostService。租户取自 MDC
 * （{@link TenantContext#TENANT_REQUEST_ATTRIBUTE}），无 MDC 时落 "public"。
 *
 * <p>两个"行为钉住"测试（预算拒绝降级、action_input 形状错误降级）固化的是当前行为而非理想行为：
 * reason() 的 catch(RuntimeException) 会把这三类失败静默降级为确定性 fallback，任务仍记 DONE。
 * 若未来收紧该语义（例如预算拒绝应上抛），请同步修改这两个测试。
 */
class WorkflowReactAgentServiceTest {

    private static final String FINISH_JSON =
            "{\"thought\":\"信息足够\",\"action\":\"finish\",\"answer\":\"北京校区共 3 所。\"}";
    private static final String RAG_SEARCH_JSON =
            "{\"thought\":\"需要检索\",\"action\":\"rag_search\",\"action_input\":{\"query\":\"校区\"}}";
    private static final String RAG_SEARCH_BAD_INPUT_JSON =
            "{\"thought\":\"需要检索\",\"action\":\"rag_search\",\"action_input\":\"not-an-object\"}";

    // ── chat() ────────────────────────────────────────────────────

    @Test
    void chatRejectsInvalidRequestsBeforeTouchingAnyDependency() {
        AgentWorkflowEngine engine = mock(AgentWorkflowEngine.class);
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = mock(ChatClient.class);
        WorkflowReactAgentService service = service(engine, harness, chatClient);

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

        verifyNoInteractions(engine, harness, chatClient);
    }

    @Test
    void chatReturnsPlannerFinishAnswerWithoutToolExecutionOrTransition() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        WorkflowReactAgentService service = service(engine, harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getOk()).isEqualTo(1);
        assertThat(response.getMsg()).isEqualTo("ok");
        assertThat(response.getChatId()).isEqualTo("chat-1");
        assertThat(response.getAnswer()).isEqualTo("北京校区共 3 所。");
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getRouteProfile()).isEqualTo("quality");
        assertThat(response.getRouteReason()).isEqualTo("profile_match");
        assertThat(response.getExperimentBucket()).isNull();
        assertThat(response.getTrace()).hasSize(1);
        assertThat(response.getTrace().get(0).getStep()).isEqualTo(1);
        assertThat(response.getTrace().get(0).getAction()).isEqualTo("finish");
        assertThat(response.getTrace().get(0).getObservation())
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("status", "completed");

        verify(engine).startTask(eq("public"), eq("REACT"), eq("请查询校区"), any(), eq("chat-1"), any());
        verify(engine).completeStep(eq("step-1"), eq("COMPLETED"), any(), any(), any(), eq("finish"), any(),
                eq(0L), eq(0L), anyLong(), any());
        verify(engine).completeTask("task-1", WorkflowState.DONE, "北京校区共 3 所。");
        verify(engine).recordTaskMetrics(eq("REACT"), eq("DONE"), anyLong());
        verify(engine, never()).transitionStatus(anyString(), any(), any());
        verifyNoInteractions(harness);
    }

    @Test
    void chatExecutesToolActionThenFinishesOnSecondPlannerCall() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("school_db", Map.of("schools", List.of("北京校区")), 5L));
        ChatClient chatClient = chatClientReturning(RAG_SEARCH_JSON, FINISH_JSON);
        WorkflowReactAgentService service = service(engine, harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).isEqualTo("北京校区共 3 所。");
        assertThat(response.getTrace()).hasSize(2);
        assertThat(response.getTrace().get(0).getAction()).isEqualTo("rag_search");
        assertThat(response.getTrace().get(1).getAction()).isEqualTo("finish");
        assertThat(response.getTrace().get(0).getObservation())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("source", "school_db")
                .containsEntry("status", "success");

        org.mockito.ArgumentCaptor<AgentAction> actionCaptor =
                org.mockito.ArgumentCaptor.forClass(AgentAction.class);
        verify(harness).execute(actionCaptor.capture());
        AgentAction action = actionCaptor.getValue();
        assertThat(action.action()).isEqualTo("rag_search");
        assertThat(action.actionInput()).containsEntry("query", "校区");
        assertThat(action.tenantId()).isEqualTo("public");
        assertThat(action.taskId()).isEqualTo("task-1");
        assertThat(action.stepId()).isEqualTo("step-1");
        assertThat(action.chatId()).isEqualTo("chat-1");

        verify(engine).transitionStatus("task-1", WorkflowState.SEARCHING, WorkflowState.RETRIEVING);
        verify(engine, times(2)).startStep(eq("task-1"), eq("planner"), anyInt(), any());
        verify(engine).completeTask("task-1", WorkflowState.DONE, "北京校区共 3 所。");
        verify(engine).recordTaskMetrics(eq("REACT"), eq("DONE"), anyLong());
    }

    @Test
    void chatFallsBackToDeterministicSchoolAnswerWhenPlannerModelFails() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientThrowing(new IllegalStateException("model unavailable"));
        WorkflowReactAgentService service = service(engine, harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).contains("已识别为校区查询请求");
        assertThat(response.getAnswer()).contains("引用来源:");
        assertThat(response.getAnswer()).contains("[1] source=fallback://school_query_flow, chunk=1");
        assertThat(response.getCitations()).containsExactly("source=fallback://school_query_flow, chunk=1");
        assertThat(response.getTrace()).hasSize(1);
        assertThat(response.getTrace().get(0).getAction()).isEqualTo("finish");
        assertThat(response.getTrace().get(0).getThought()).isEqualTo("Fallback school query.");

        // 规划模型失败被 reason() 吞掉 → 任务仍以 DONE 收口，不失败、不执行工具
        verify(engine).completeTask(eq("task-1"), eq(WorkflowState.DONE), anyString());
        verify(engine, never()).failTask(anyString(), anyString());
        verifyNoInteractions(harness);
        verify(engine).recordTaskMetrics(eq("REACT"), eq("DONE"), anyLong());
    }

    @Test
    void chatSummarizesViaModelAfterMaxStepsWithoutFinish() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("rag", Map.of("hits", List.of("片段")), 3L));
        // 6 次规划调用都不 finish，第 7 次 callModel 是 summarizeAnswer
        ChatClient chatClient = chatClientReturning(
                RAG_SEARCH_JSON, RAG_SEARCH_JSON, RAG_SEARCH_JSON, RAG_SEARCH_JSON,
                RAG_SEARCH_JSON, RAG_SEARCH_JSON, "最终总结答案");
        WorkflowReactAgentService service = service(engine, harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).isEqualTo("最终总结答案");
        assertThat(response.getTrace()).hasSize(6);
        assertThat(response.getTrace()).allSatisfy(step -> assertThat(step.getAction()).isEqualTo("rag_search"));

        verify(engine, times(6)).transitionStatus(eq("task-1"), any(), any());
        verify(engine, times(6)).startStep(eq("task-1"), eq("planner"), anyInt(), any());
        verify(harness, times(6)).execute(any(AgentAction.class));
        verify(engine).completeTask("task-1", WorkflowState.DONE, "最终总结答案");
        verify(engine).recordTaskMetrics(eq("REACT"), eq("DONE"), anyLong());
        verify(engine, never()).failTask(anyString(), anyString());
    }

    @Test
    void chatFailsTaskAndRethrowsWhenToolExecutionExplodes() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenThrow(new IllegalStateException("tool exploded"));
        ChatClient chatClient = chatClientReturning(RAG_SEARCH_JSON);
        WorkflowReactAgentService service = service(engine, harness, chatClient);

        assertThatThrownBy(() -> service.chat(request("请查询校区")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tool exploded");

        verify(engine).failTask("task-1", "tool exploded");
        verify(engine).recordTaskMetrics(eq("REACT"), eq("FAILED"), anyLong());
        verify(engine, never()).completeTask(anyString(), any(), anyString());
    }

    /**
     * 行为钉住：reason() 的 catch(RuntimeException) 会吞掉 assertBudget 的拒绝，
     * 把预算不足静默降级为确定性 fallback，任务照常 DONE——预算违规不会阻断请求，
     * 只会以 fallback 答案的形式表现出来。若未来改为上抛，请同步修改本测试。
     */
    @Test
    void chatDegradesToFallbackInsteadOfFailingWhenBudgetCheckRejects() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(10);
        // assertBudget 是 void 方法，必须用 doThrow 而非 when(...).thenThrow
        doThrow(new IllegalStateException("预算不足"))
                .when(cost).assertBudget(anyString(), anyString(), anyLong(), anyLong());
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        WorkflowReactAgentService service =
                service(engine, harness, chatClient, cost);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getAnswer()).contains("已识别为校区查询请求");
        verify(engine).completeTask(eq("task-1"), eq(WorkflowState.DONE), anyString());
        verify(engine, never()).failTask(anyString(), anyString());
        verifyNoInteractions(harness);
        verify(cost, never()).recordUsage(anyString(), anyString(), anyLong(), anyLong(), anyString());
    }

    /**
     * 行为钉住：parseDecision 只捕获 JsonProcessingException，而 action_input 形状不符时
     * Jackson convertValue 抛的是 IllegalArgumentException——该异常同样被 reason() 吞掉，
     * 降级为 fallback 决策。规划器的"格式错误"与"模型不可用"在行为上不可区分。
     */
    @Test
    void chatSwallowsActionInputShapeErrorsIntoFallbackDecision() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(RAG_SEARCH_BAD_INPUT_JSON);
        WorkflowReactAgentService service = service(engine, harness, chatClient);

        ReactChatResponseVO response = service.chat(request("请查询校区"));

        assertThat(response.getTrace()).hasSize(1);
        assertThat(response.getTrace().get(0).getAction()).isEqualTo("finish");
        assertThat(response.getAnswer()).contains("已识别为校区查询请求");
        verifyNoInteractions(harness);
        verify(engine, never()).failTask(anyString(), anyString());
    }

    @Test
    void chatPropagatesTenantFromMdcToEngineAndBudgetGuard() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(10);
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        WorkflowReactAgentService service = service(engine, harness, chatClient, cost);

        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "acme");
        try {
            ReactChatResponseVO response = service.chat(request("请查询校区"));
            assertThat(response.getOk()).isEqualTo(1);
        } finally {
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        }

        verify(engine).startTask(eq("acme"), eq("REACT"), eq("请查询校区"), any(), eq("chat-1"), any());
        verify(cost).assertBudget(eq("acme"), eq("premium"), anyLong(), anyLong());
    }

    // ── stream() ──────────────────────────────────────────────────

    @Test
    void streamEmitsTraceThenTokenThenDoneAndCompletesTask() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowReactAgentService service = service(engine, harness, chatClient,
                mock(TenantCostService.class), registry);

        List<String> events = service.stream(request("请查询校区"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).startsWith("event: trace\n").contains("\"action\":\"finish\"");
        assertThat(events.get(1)).isEqualTo("event: token\ndata: {\"token\":\"北京校区共 3 所。\"}\n\n");
        assertThat(events.get(2)).startsWith("event: done\n").contains("\"answer\":\"北京校区共 3 所。\"");

        verify(engine).startTask(eq("public"), eq("REACT_STREAM"), eq("请查询校区"), any(), eq("chat-1"), any());
        verify(engine).completeTask("task-1", WorkflowState.DONE, "北京校区共 3 所。");
        assertThat(registry.get("react.stream.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("react.stream.first_token.latency").tag("outcome", "success").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("react.stream.total.latency").tag("outcome", "success").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void streamStreamsFinalAnswerFromModelWhenPlannerNeverFinishes() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("rag", Map.of("hits", List.of("片段")), 3L));
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(7);
        ChatClient chatClient = chatClientWithModelStream(RAG_SEARCH_JSON, Flux.just("流", "式"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowReactAgentService service = service(engine, harness, chatClient, cost, registry);

        List<String> events = service.stream(request("请查询校区"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // 6 条 trace + 2 个 token + 1 个 done
        assertThat(events).hasSize(9);
        assertThat(events.get(0)).startsWith("event: trace\n");
        assertThat(events.subList(6, 8)).containsExactly(
                "event: token\ndata: {\"token\":\"流\"}\n\n",
                "event: token\ndata: {\"token\":\"式\"}\n\n");
        assertThat(events.get(8)).startsWith("event: done\n").contains("\"answer\":\"流式\"");

        verify(harness, times(6)).execute(any(AgentAction.class));
        verify(engine).completeTask("task-1", WorkflowState.DONE, "流式");
        // callModelStream 的 doFinally 在流终止后记一次用量
        verify(cost, times(1)).recordUsage(eq("public"), eq("premium"), anyLong(), anyLong(), eq("react_final"));
        assertThat(registry.get("react.stream.requests").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void streamMarksTaskFailedAndEmitsErrorEventWhenModelStreamFailsMidFlight() {
        AgentWorkflowEngine engine = engineReturningTask("task-1");
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("rag", Map.of("hits", List.of("片段")), 3L));
        ChatClient chatClient = chatClientWithModelStream(
                RAG_SEARCH_JSON, Flux.error(new IllegalStateException("upstream down")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowReactAgentService service = service(engine, harness, chatClient,
                mock(TenantCostService.class), registry);

        List<String> events = service.stream(request("请查询校区"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // 6 条 trace 已发出，随后转为单个 error 事件（不会出现 done）
        assertThat(events).hasSize(7);
        assertThat(events.subList(0, 6)).allSatisfy(event -> assertThat(event).startsWith("event: trace\n"));
        assertThat(events.get(6)).isEqualTo("event: error\ndata: {\"message\":\"upstream down\"}\n\n");

        verify(engine).failTask("task-1", "upstream down");
        verify(engine).recordTaskMetrics(eq("REACT_STREAM"), eq("FAILED"), anyLong());
        verify(engine, never()).completeTask(anyString(), any(), anyString());
        assertThat(registry.get("react.stream.requests").tag("outcome", "error").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("react.stream.first_token.latency").timer()).isNull();
    }

    @Test
    void streamReportsValidationErrorAsErrorEventWithoutCreatingOrFailingTask() {
        AgentWorkflowEngine engine = mock(AgentWorkflowEngine.class);
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = chatClientReturning(FINISH_JSON);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowReactAgentService service = service(engine, harness, chatClient,
                mock(TenantCostService.class), registry);

        ReactChatRequestVO invalid = new ReactChatRequestVO();
        invalid.setChatId("chat-1"); // prompt 缺失

        List<String> events = service.stream(invalid)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).containsExactly("event: error\ndata: {\"message\":\"prompt is required\"}\n\n");
        verify(engine, never()).startTask(any(), any(), any(), any(), any(), any());
        verify(engine, never()).failTask(anyString(), anyString());
        assertThat(registry.get("react.stream.requests").tag("outcome", "error").counter().count())
                .isEqualTo(1.0);
    }

    // ── 构造辅助 ──────────────────────────────────────────────────

    private WorkflowReactAgentService service(AgentWorkflowEngine engine, AgentHarnessService harness,
                                              ChatClient chatClient) {
        return service(engine, harness, chatClient,
                mock(TenantCostService.class), new SimpleMeterRegistry());
    }

    private WorkflowReactAgentService service(AgentWorkflowEngine engine, AgentHarnessService harness,
                                              ChatClient chatClient, TenantCostService cost) {
        return service(engine, harness, chatClient, cost, new SimpleMeterRegistry());
    }

    private WorkflowReactAgentService service(AgentWorkflowEngine engine, AgentHarnessService harness,
                                              ChatClient chatClient, TenantCostService cost,
                                              MeterRegistry registry) {
        return new WorkflowReactAgentService(engine, harness, chatClient,
                modelRouterRouting(), cost, new ObjectMapper(), registry);
    }

    private ModelRouter modelRouterRouting() {
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(any(), any(), any(), any())).thenReturn(
                new ModelRouter.ModelRouteDecision("quality", "model-a", "premium", false, "profile_match", "", "", null));
        return modelRouter;
    }

    private AgentWorkflowEngine engineReturningTask(String taskId) {
        AgentWorkflowEngine engine = mock(AgentWorkflowEngine.class);
        when(engine.startTask(any(), any(), any(), any(), any(), any()))
                .thenReturn(AgentTaskRecord.builder().taskId(taskId).build());
        when(engine.startStep(any(), any(), anyInt(), any()))
                .thenReturn(AgentStepRecord.builder().stepId("step-1").taskId(taskId).build());
        return engine;
    }

    private ReactChatRequestVO request(String prompt) {
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt(prompt);
        request.setChatId("chat-1");
        request.setModelProfile("quality");
        return request;
    }

    /** 规划/总结走 call().content()，按传入顺序依次返回。 */
    private ChatClient chatClientReturning(String... callContents) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
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
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(plannerContent);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(streamContent);
        return chatClient;
    }
}
