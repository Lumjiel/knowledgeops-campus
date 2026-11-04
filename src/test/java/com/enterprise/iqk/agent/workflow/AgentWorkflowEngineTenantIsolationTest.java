package com.enterprise.iqk.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentWorkflowEngineTenantIsolationTest {

    private AgentTaskMapper taskMapper;
    private AgentStepMapper stepMapper;
    private AgentEventMapper eventMapper;
    private SimpleMeterRegistry meterRegistry;
    private AgentWorkflowEngine engine;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AgentTaskMapper.class);
        stepMapper = mock(AgentStepMapper.class);
        eventMapper = mock(AgentEventMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        engine = new AgentWorkflowEngine(
                taskMapper,
                stepMapper,
                eventMapper,
                new ObjectMapper(),
                meterRegistry
        );
    }

    @Test
    void returnsTaskOnlyWhenItBelongsToCurrentTenant() {
        AgentTaskRecord task = AgentTaskRecord.builder()
                .taskId("task-1")
                .tenantId("tenant-a")
                .status(WorkflowState.DONE.name())
                .build();
        when(taskMapper.findByTenantAndTaskId("tenant-a", "task-1")).thenReturn(task);
        when(stepMapper.findByTaskId("task-1")).thenReturn(Collections.emptyList());
        when(eventMapper.findByTaskId("task-1")).thenReturn(Collections.emptyList());

        WorkflowTaskVO result = engine.getTask("tenant-a", "task-1");

        assertThat(result).isNotNull();
        assertThat(result.getTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void hidesTaskAndEventsFromAnotherTenant() {
        when(taskMapper.findByTenantAndTaskId("tenant-b", "task-1")).thenReturn(null);

        WorkflowTaskVO task = engine.getTask("tenant-b", "task-1");
        List<WorkflowEventVO> events = engine.getTaskEvents("tenant-b", "task-1");

        assertThat(task).isNull();
        assertThat(events).isEmpty();
        verify(stepMapper, never()).findByTaskId("task-1");
        verify(eventMapper, never()).findByTaskId("task-1");
    }

    @Test
    void persistsTaskAndStepLifecycleWithEventsAndMetrics() {
        AgentTaskRecord task = engine.startTask(
                "tenant-a", "REACT", "question", "", "chat-1", "session-1");
        AgentStepRecord step = engine.startStep(task.getTaskId(), "planner", 1, Map.of("prompt", "question"));
        when(stepMapper.findByStepId(step.getStepId())).thenReturn(step);

        engine.completeStep(
                step.getStepId(), "DONE", Map.of("answer", "ok"), Map.of("found", true),
                "reason", "search", Map.of("query", "question"),
                10, 20, 35, null);
        engine.completeTask(task.getTaskId(), WorkflowState.DONE, "final answer");
        engine.recordStepMetrics("planner", "DONE", 35);
        engine.recordTaskMetrics("REACT", "DONE", 50);

        assertThat(task.getTenantId()).isEqualTo("tenant-a");
        assertThat(task.getModelProfile()).isEqualTo("balanced");
        assertThat(step.getThought()).isEqualTo("reason");
        assertThat(step.getAction()).isEqualTo("search");
        verify(taskMapper).insert(task);
        verify(taskMapper).updateStatus(task.getTaskId(), WorkflowState.PLANNING.name());
        verify(stepMapper).insert(step);
        verify(stepMapper).updateById(step);
        verify(taskMapper).completeTask(task.getTaskId(), WorkflowState.DONE.name(), "final answer");
        ArgumentCaptor<AgentEventRecord> eventCaptor = ArgumentCaptor.forClass(AgentEventRecord.class);
        verify(eventMapper, org.mockito.Mockito.atLeastOnce()).insert(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).isNotEmpty();
    }

    @Test
    void convertsScopedTaskDetailsAndHandlesInvalidState() {
        LocalDateTime now = LocalDateTime.now();
        AgentTaskRecord task = AgentTaskRecord.builder()
                .taskId("task-2")
                .tenantId("tenant-a")
                .type("RESEARCH")
                .status(WorkflowState.WRITING.name())
                .userInput("topic")
                .modelProfile("quality")
                .createdAt(now)
                .updatedAt(now)
                .build();
        AgentStepRecord step = AgentStepRecord.builder()
                .stepId("step-2")
                .taskId("task-2")
                .agentName("writer")
                .status("DONE")
                .stepOrder(2)
                .actionInputJson("{\"topic\":\"java\"}")
                .observationJson("{\"sources\":2}")
                .build();
        AgentEventRecord event = AgentEventRecord.builder()
                .eventId("event-2")
                .taskId("task-2")
                .eventType("STATE_CHANGED")
                .payloadJson("{\"to\":\"WRITING\"}")
                .createdAt(now)
                .build();
        when(taskMapper.findByTenantAndTaskId("tenant-a", "task-2")).thenReturn(task);
        when(stepMapper.findByTaskId("task-2")).thenReturn(List.of(step));
        when(eventMapper.findByTaskId("task-2")).thenReturn(List.of(event));
        when(taskMapper.findByTenant("tenant-a", 0, 20)).thenReturn(List.of(task));
        when(taskMapper.findByTaskId("task-2")).thenReturn(task);

        WorkflowTaskVO detail = engine.getTask("tenant-a", "task-2");
        List<WorkflowTaskVO> listed = engine.listTasks("tenant-a", 1, 20);
        List<WorkflowEventVO> events = engine.getTaskEvents("tenant-a", "task-2");
        WorkflowState state = engine.currentState("task-2");

        assertThat(detail.getSteps()).singleElement()
                .satisfies(item -> assertThat(item.getActionInput()).containsEntry("topic", "java"));
        assertThat(detail.getEvents()).singleElement()
                .satisfies(item -> assertThat(item.getPayload()).containsEntry("to", "WRITING"));
        assertThat(listed).hasSize(1);
        assertThat(events).hasSize(1);
        assertThat(state).isEqualTo(WorkflowState.WRITING);

        task.setStatus("NOT_A_STATE");
        assertThat(engine.currentState("task-2")).isEqualTo(WorkflowState.FAILED);
        when(taskMapper.findByTaskId("missing")).thenReturn(null);
        assertThat(engine.currentState("missing")).isNull();

    }

    @Test
    void rejectsInvalidStateTransitionWithoutPersisting() {
        engine.transitionStatus("task-illegal", WorkflowState.CREATED, WorkflowState.DONE);

        verify(taskMapper, never()).updateStatus("task-illegal", WorkflowState.DONE.name());
        verifyNoInteractions(eventMapper);
    }

    @Test
    void skipsEventWhenTransitionAffectsZeroRows() {
        when(taskMapper.updateStatus("task-stale", WorkflowState.PLANNING.name())).thenReturn(0);

        engine.transitionStatus("task-stale", WorkflowState.CREATED, WorkflowState.PLANNING);

        verify(taskMapper).updateStatus("task-stale", WorkflowState.PLANNING.name());
        verifyNoInteractions(eventMapper);
    }

    // ── startTask / startStep ────────────────────────────────────

    @Test
    void startTaskKeepsExplicitProfileAndNormalizesBlankTenant() {
        AgentTaskRecord task = engine.startTask(
                "  ", "RESEARCH", "topic", "quality", "chat-9", "session-9");

        assertThat(task.getTaskId()).startsWith("task-");
        assertThat(task.getTenantId()).isEqualTo("public");
        assertThat(task.getStatus()).isEqualTo(WorkflowState.CREATED.name());
        assertThat(task.getModelProfile()).isEqualTo("quality");
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
        verify(taskMapper).insert(task);
        // startTask 立即推进 CREATED -> PLANNING
        verify(taskMapper).updateStatus(task.getTaskId(), WorkflowState.PLANNING.name());
    }

    @Test
    void startStepSerializesInputAndEmitsStepStartedEvent() {
        AgentTaskRecord task = engine.startTask("tenant-a", "REACT", "q", "", "chat-1", "s-1");

        AgentStepRecord step = engine.startStep(
                task.getTaskId(), "planner", 3, Map.of("prompt", "q"));

        assertThat(step.getStepId()).startsWith("step-");
        assertThat(step.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(step.getStatus()).isEqualTo("RUNNING");
        assertThat(step.getStepOrder()).isEqualTo(3);
        assertThat(step.getInputJson()).isEqualTo("{\"prompt\":\"q\"}");
        assertThat(step.getStartedAt()).isNotNull();

        ArgumentCaptor<AgentEventRecord> eventCaptor = ArgumentCaptor.forClass(AgentEventRecord.class);
        verify(eventMapper, atLeastOnce()).insert(eventCaptor.capture());
        AgentEventRecord stepStarted = eventCaptor.getAllValues().stream()
                .filter(e -> "STEP_STARTED".equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(stepStarted.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(stepStarted.getStepId()).isEqualTo(step.getStepId());
        assertThat(stepStarted.getPayloadJson())
                .contains("\"agentName\":\"planner\"")
                .contains("\"stepOrder\":3");
    }

    @Test
    void startStepWritesFallbackJsonWhenInputIsNotSerializable() {
        // 自引用 Map 使 Jackson 抛 JsonMappingException（JsonProcessingException 子类），
        // 引擎应降级为 "{}" 而不是让任务启动失败。
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        AgentStepRecord step = engine.startStep("task-x", "planner", 1, cyclic);

        assertThat(step.getInputJson()).isEqualTo("{}");
        verify(stepMapper).insert(step);
    }

    // ── completeStep ─────────────────────────────────────────────

    @Test
    void completeStepPersistsOutputAndSkipsProfileUpdateWhenThoughtBlank() {
        AgentStepRecord step = engine.startStep("task-1", "writer", 1, Map.of("k", "v"));
        when(stepMapper.findByStepId(step.getStepId())).thenReturn(step);

        engine.completeStep(step.getStepId(), "DONE", Map.of("answer", "ok"), Map.of("found", true),
                null, null, null, 10L, 20L, 35L, null);

        verify(stepMapper).completeStep(eq(step.getStepId()), eq("DONE"),
                eq("{\"answer\":\"ok\"}"), eq("{\"found\":true}"),
                eq(10L), eq(20L), eq(35L), isNull());
        // thought 为空 → 跳过 thought/action 档案更新
        verify(stepMapper, never()).updateById(any());
        ArgumentCaptor<AgentEventRecord> eventCaptor = ArgumentCaptor.forClass(AgentEventRecord.class);
        verify(eventMapper, atLeastOnce()).insert(eventCaptor.capture());
        AgentEventRecord completed = eventCaptor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(completed.getTaskId()).isEqualTo("task-1");
        assertThat(completed.getPayloadJson())
                .contains("\"status\":\"DONE\"")
                .contains("\"latencyMs\":35");
    }

    @Test
    void completeStepUpdatesProfileWhenThoughtPresent() {
        AgentStepRecord step = engine.startStep("task-1", "planner", 1, Map.of());
        when(stepMapper.findByStepId(step.getStepId())).thenReturn(step);

        engine.completeStep(step.getStepId(), "DONE", null, null,
                "reason it", "search", Map.of("query", "q"), 0L, 0L, 5L, null);

        assertThat(step.getThought()).isEqualTo("reason it");
        assertThat(step.getAction()).isEqualTo("search");
        assertThat(step.getActionInputJson()).isEqualTo("{\"query\":\"q\"}");
        verify(stepMapper).updateById(step);
    }

    @Test
    void completeStepForUnknownStepFallsBackToUnknownTaskId() {
        when(stepMapper.findByStepId("ghost")).thenReturn(null);

        engine.completeStep("ghost", "FAILED", null, null,
                "thought", null, null, 0L, 0L, 1L, "boom");

        verify(stepMapper).completeStep(eq("ghost"), eq("FAILED"), isNull(), isNull(),
                eq(0L), eq(0L), eq(1L), eq("boom"));
        verify(stepMapper, never()).updateById(any());
        ArgumentCaptor<AgentEventRecord> eventCaptor = ArgumentCaptor.forClass(AgentEventRecord.class);
        verify(eventMapper, atLeastOnce()).insert(eventCaptor.capture());
        AgentEventRecord completed = eventCaptor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(completed.getTaskId()).isEqualTo("unknown");
    }

    // ── transitionStatus 事件载荷 ─────────────────────────────────

    @Test
    void successfulTransitionEmitsStateChangedEventWithFromAndTo() {
        when(taskMapper.updateStatus("task-1", WorkflowState.SEARCHING.name())).thenReturn(1);

        engine.transitionStatus("task-1", WorkflowState.PLANNING, WorkflowState.SEARCHING);

        ArgumentCaptor<AgentEventRecord> eventCaptor = ArgumentCaptor.forClass(AgentEventRecord.class);
        verify(eventMapper).insert(eventCaptor.capture());
        AgentEventRecord event = eventCaptor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-1");
        assertThat(event.getStepId()).isNull();
        assertThat(event.getEventType()).isEqualTo("STATE_CHANGED");
        assertThat(event.getPayloadJson())
                .contains("\"from\":\"PLANNING\"")
                .contains("\"to\":\"SEARCHING\"");
    }

    // ── emitEvent 容错 ───────────────────────────────────────────

    @Test
    void emitEventSwallowsPersistenceFailureInsteadOfFailingTaskFlow() {
        doThrow(new RuntimeException("db down")).when(eventMapper).insert(any(AgentEventRecord.class));

        // 事件落库失败只记日志，不向上抛——任务收口不应被可观测性故障阻断
        assertThatCode(() -> engine.completeTask("task-1", WorkflowState.DONE, "final"))
                .doesNotThrowAnyException();

        verify(taskMapper).completeTask("task-1", WorkflowState.DONE.name(), "final");
    }

    // ── failTask：终态 FAILED 处理 ───────────────────────────────

    @Test
    void failTaskPersistsFailedStatusAndEmitsTaskFailedEventWithError() {
        engine.failTask("task-1", "tool exploded");

        // finalOutput 落的是错误信息；状态固定为 FAILED 终态
        verify(taskMapper).completeTask("task-1", WorkflowState.FAILED.name(), "tool exploded");

        ArgumentCaptor<AgentEventRecord> eventCaptor = ArgumentCaptor.forClass(AgentEventRecord.class);
        verify(eventMapper).insert(eventCaptor.capture());
        AgentEventRecord event = eventCaptor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-1");
        assertThat(event.getStepId()).isNull();
        assertThat(event.getEventType()).isEqualTo("TASK_FAILED");
        assertThat(event.getPayloadJson()).contains("\"error\":\"tool exploded\"");
    }

    // ── Metrics ──────────────────────────────────────────────────

    @Test
    void recordStepMetricsRegistersTaggedTimerAndCounter() {
        engine.recordStepMetrics("planner", "DONE", 35L);
        engine.recordStepMetrics("planner", "FAILED", 10L);

        assertThat(meterRegistry.get("agent.workflow.step.latency")
                .tag("agent", "planner").tag("status", "DONE").timer().count())
                .isEqualTo(1L);
        assertThat(meterRegistry.get("agent.workflow.step.latency")
                .tag("agent", "planner").tag("status", "FAILED").timer().count())
                .isEqualTo(1L);
        assertThat(meterRegistry.get("agent.workflow.step.count")
                .tag("agent", "planner").tag("status", "DONE").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("agent.workflow.step.count")
                .tag("agent", "planner").tag("status", "FAILED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void recordTaskMetricsRegistersTaggedTimerAndCounter() {
        engine.recordTaskMetrics("REACT", "DONE", 120L);
        engine.recordTaskMetrics("REACT", "DONE", 80L);

        assertThat(meterRegistry.get("agent.workflow.task.latency")
                .tag("type", "REACT").tag("status", "DONE").timer().count())
                .isEqualTo(2L);
        assertThat(meterRegistry.get("agent.workflow.task.count")
                .tag("type", "REACT").tag("status", "DONE").counter().count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.find("agent.workflow.task.latency")
                .tag("status", "FAILED").timer())
                .isNull();
    }

    // ── listTasks 分页与 VO 转换边界 ──────────────────────────────

    @Test
    void listTasksClampsPageBelowOneAndComputesOffset() {
        when(taskMapper.findByTenant("tenant-a", 0L, 20)).thenReturn(Collections.emptyList());

        assertThat(engine.listTasks("tenant-a", 0, 20)).isEmpty();
        verify(taskMapper).findByTenant("tenant-a", 0L, 20);

        engine.listTasks("tenant-a", 3, 20);
        verify(taskMapper).findByTenant("tenant-a", 40L, 20);
    }

    @Test
    void taskStepVoFallsBackToEmptyMapForInvalidOrMissingJson() {
        AgentTaskRecord task = AgentTaskRecord.builder()
                .taskId("task-3")
                .tenantId("tenant-a")
                .status(WorkflowState.PLANNING.name())
                .build();
        AgentStepRecord step = AgentStepRecord.builder()
                .stepId("step-3")
                .taskId("task-3")
                .agentName("planner")
                .status("DONE")
                .stepOrder(1)
                .actionInputJson("not-json")
                .observationJson(null)
                .build();
        when(taskMapper.findByTenantAndTaskId("tenant-a", "task-3")).thenReturn(task);
        when(stepMapper.findByTaskId("task-3")).thenReturn(List.of(step));
        when(eventMapper.findByTaskId("task-3")).thenReturn(Collections.emptyList());

        WorkflowTaskVO detail = engine.getTask("tenant-a", "task-3");

        assertThat(detail.getSteps()).singleElement().satisfies(vo -> {
            assertThat(vo.getActionInput()).isEmpty();
            assertThat(vo.getObservation()).isNull();
            assertThat(vo.getInputTokens()).isZero();
            assertThat(vo.getOutputTokens()).isZero();
            assertThat(vo.getLatencyMs()).isZero();
        });
    }
}
