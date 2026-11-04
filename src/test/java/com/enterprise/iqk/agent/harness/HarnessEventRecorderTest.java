package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HarnessEventRecorderTest {

    @Test
    @SuppressWarnings("unchecked")
    void recordsSanitizedActionInputAndObservationSummary() {
        AgentWorkflowEngine workflowEngine = mock(AgentWorkflowEngine.class);
        HarnessEventRecorder recorder = new HarnessEventRecorder(
                workflowEngine,
                new ActionSchemaRegistry(),
                new HarnessPayloadSanitizer()
        );
        AgentAction action = new AgentAction("add_course_reservation",
                Map.of("course", "Java", "studentName", "A", "contactInfo", "13800000000", "school", "S"),
                "prompt", "tenant", "chat", "balanced", "task-1", "step-1");
        AgentObservation observation = AgentObservation.success("builtin",
                Map.of("status", "created", "reservationId", "r-1"), 9);

        recorder.started(action, "builtin");
        recorder.completed(action, observation);

        ArgumentCaptor<Map<String, Object>> started = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> completed = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngine).emitEvent(eq("task-1"), eq("step-1"), eq("ACTION_STARTED"), started.capture());
        verify(workflowEngine).emitEvent(eq("task-1"), eq("step-1"), eq("ACTION_COMPLETED"), completed.capture());

        assertThat((Map<String, Object>) started.getValue().get("actionInput"))
                .containsEntry("contactInfo", "[REDACTED]");
        assertThat((Map<String, Object>) completed.getValue().get("observation"))
                .containsEntry("source", "builtin")
                .containsEntry("latencyMs", 9L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsActionFailedEventForErrorObservation() {
        AgentWorkflowEngine workflowEngine = mock(AgentWorkflowEngine.class);
        HarnessEventRecorder recorder = new HarnessEventRecorder(
                workflowEngine,
                new ActionSchemaRegistry(),
                new HarnessPayloadSanitizer()
        );
        AgentAction action = new AgentAction("rag_search",
                Map.of("query", "校区"), "prompt", "tenant", "chat", "balanced", "task-1", "step-1");
        AgentObservation failure = AgentObservation.error("builtin", "action failed: boom", 4);

        recorder.started(action, "builtin");
        recorder.completed(action, failure);

        ArgumentCaptor<Map<String, Object>> completed = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngine).emitEvent(eq("task-1"), eq("step-1"), eq("ACTION_FAILED"), completed.capture());
        assertThat((Map<String, Object>) completed.getValue().get("observation"))
                .containsEntry("source", "builtin")
                .containsEntry("status", "error")
                .containsEntry("errorMessage", "action failed: boom");
    }

    @Test
    void skipsEventsWithoutWorkflowTaskAndStepContext() {
        AgentWorkflowEngine workflowEngine = mock(AgentWorkflowEngine.class);
        HarnessEventRecorder recorder = new HarnessEventRecorder(
                workflowEngine,
                new ActionSchemaRegistry(),
                new HarnessPayloadSanitizer()
        );
        // 普通 ReAct 对话没有 taskId/stepId → 不写 agent_event
        AgentAction action = new AgentAction("rag_search",
                Map.of("query", "校区"), "prompt", "tenant", "chat", "balanced", "", "");

        recorder.started(action, "builtin");
        recorder.completed(action, AgentObservation.error("builtin", "boom", 1));

        org.mockito.Mockito.verifyNoInteractions(workflowEngine);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startedEventFallsBackToUnknownRiskLevelForUnregisteredAction() {
        AgentWorkflowEngine workflowEngine = mock(AgentWorkflowEngine.class);
        HarnessEventRecorder recorder = new HarnessEventRecorder(
                workflowEngine,
                new ActionSchemaRegistry(),
                new HarnessPayloadSanitizer()
        );
        AgentAction action = new AgentAction("unknown_tool",
                Map.of("input", "x"), "prompt", "tenant", "chat", "balanced", "task-1", "step-1");

        recorder.started(action, "fake");

        ArgumentCaptor<Map<String, Object>> started = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngine).emitEvent(eq("task-1"), eq("step-1"), eq("ACTION_STARTED"), started.capture());
        assertThat(started.getValue())
                .containsEntry("action", "unknown_tool")
                .containsEntry("source", "fake")
                .containsEntry("riskLevel", "unknown");
    }
}
