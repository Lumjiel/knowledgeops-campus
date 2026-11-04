package com.enterprise.iqk.controller;

import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowEventVO;
import com.enterprise.iqk.agent.workflow.WorkflowReactAgentService;
import com.enterprise.iqk.agent.workflow.WorkflowTaskVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WorkflowController 的 WebMvc 切片测试，固化 architecture-agent-workflow.md
 * 的「API 端点」表：同步 ReAct、SSE 流式、任务详情（含 404 分支）、事件流、
 * 租户任务列表（分页默认值）。切片排除安全过滤器的方式与
 * AuthControllerWebMvcTest / IngestionControllerWebMvcTest 一致。
 */
@WebMvcTest(value = WorkflowController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class WorkflowControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowReactAgentService workflowReactAgentService;
    @MockBean
    private AgentWorkflowEngine workflowEngine;

    // ── POST /ai/workflow/react/chat（同步 ReAct 工作流）─────────

    @Test
    void reactChatReturnsServiceResponse() throws Exception {
        when(workflowReactAgentService.chat(any())).thenReturn(ReactChatResponseVO.builder()
                .ok(1)
                .msg("ok")
                .chatId("react-demo")
                .answer("工作流答案")
                .fallback(false)
                .build());

        mockMvc.perform(post("/ai/workflow/react/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"调研主题\",\"chatId\":\"react-demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.chatId").value("react-demo"))
                .andExpect(jsonPath("$.answer").value("工作流答案"))
                .andExpect(jsonPath("$.fallback").value(false));
    }

    // ── POST /ai/workflow/react/chat/stream（SSE 流式）───────────

    @Test
    void reactChatStreamReturnsSseFramedWorkflowEvents() throws Exception {
        when(workflowReactAgentService.stream(any())).thenReturn(reactor.core.publisher.Flux.just(
                "event: trace\ndata: {\"action\":\"finish\"}\n\n",
                "event: done\ndata: {\"answer\":\"流式答案\"}\n\n"));

        MvcResult asyncResult = mockMvc.perform(post("/ai/workflow/react/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"调研主题\",\"chatId\":\"react-stream\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // 服务返回的字符串自带 SSE 框架，SseEmitter 再包一层 data 帧；
        // 这里只断言框架无关的标记，避免耦合转义细节
        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event: trace")))
                .andExpect(content().string(containsString("event: done")));
    }

    // ── GET /ai/workflow/tasks/{taskId}（任务详情）───────────────

    @Test
    void getTaskReturnsTaskDetailForCurrentTenant() throws Exception {
        when(workflowEngine.getTask("public", "task-1")).thenReturn(WorkflowTaskVO.builder()
                .taskId("task-1")
                .tenantId("public")
                .type("DEEP_RESEARCH")
                .status("PLANNING")
                .userInput("调研主题")
                .build());

        mockMvc.perform(get("/ai/workflow/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.type").value("DEEP_RESEARCH"))
                .andExpect(jsonPath("$.status").value("PLANNING"));

        verify(workflowEngine).getTask("public", "task-1");
    }

    @Test
    void getTaskReturns404BodyWhenTaskMissingOrForeignTenant() throws Exception {
        when(workflowEngine.getTask("public", "missing")).thenReturn(null);

        mockMvc.perform(get("/ai/workflow/tasks/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("task not found"));
    }

    // ── GET /ai/workflow/tasks/{taskId}/events（事件流）──────────

    @Test
    void getTaskEventsReturnsEventStreamInChronologicalPayloadShape() throws Exception {
        when(workflowEngine.getTaskEvents("public", "task-1")).thenReturn(List.of(
                WorkflowEventVO.builder()
                        .eventId("evt-1")
                        .taskId("task-1")
                        .eventType("STATE_CHANGED")
                        .payload(Map.of("from", "CREATED", "to", "PLANNING"))
                        .build(),
                WorkflowEventVO.builder()
                        .eventId("evt-2")
                        .taskId("task-1")
                        .stepId("step-1")
                        .eventType("STEP_STARTED")
                        .payload(Map.of("agentName", "planner"))
                        .build()));

        mockMvc.perform(get("/ai/workflow/tasks/task-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventType").value("STATE_CHANGED"))
                .andExpect(jsonPath("$[0].payload.to").value("PLANNING"))
                .andExpect(jsonPath("$[1].eventType").value("STEP_STARTED"))
                .andExpect(jsonPath("$[1].stepId").value("step-1"))
                .andExpect(jsonPath("$[1].payload.agentName").value("planner"));
    }

    // ── GET /ai/workflow/tasks（租户任务列表）────────────────────

    @Test
    void listTasksForwardsDefaultAndExplicitPaging() throws Exception {
        when(workflowEngine.listTasks(eq("public"), eq(1), eq(20))).thenReturn(List.of(
                WorkflowTaskVO.builder()
                        .taskId("task-9")
                        .tenantId("public")
                        .status("DONE")
                        .build()));

        mockMvc.perform(get("/ai/workflow/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].taskId").value("task-9"))
                .andExpect(jsonPath("$[0].status").value("DONE"));

        verify(workflowEngine).listTasks("public", 1, 20);

        when(workflowEngine.listTasks("public", 3, 5)).thenReturn(List.of());
        mockMvc.perform(get("/ai/workflow/tasks").param("page", "3").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(workflowEngine).listTasks("public", 3, 5);
    }

    @Test
    void taskQueriesResolveTenantFromRequestContextMdc() throws Exception {
        // RequestContextFilter 在切片中被排除，按它在生产的行为直接种 MDC
        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "acme");
        try {
            when(workflowEngine.getTask("acme", "task-acme")).thenReturn(WorkflowTaskVO.builder()
                    .taskId("task-acme")
                    .tenantId("acme")
                    .status("WRITING")
                    .build());
            when(workflowEngine.getTaskEvents("acme", "task-acme")).thenReturn(List.of());

            mockMvc.perform(get("/ai/workflow/tasks/task-acme"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value("acme"));
            mockMvc.perform(get("/ai/workflow/tasks/task-acme/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));

            verify(workflowEngine).getTask("acme", "task-acme");
            verify(workflowEngine).getTaskEvents("acme", "task-acme");
        } finally {
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        }
    }
}
