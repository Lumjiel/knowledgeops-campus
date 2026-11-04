package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.TenantCostService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.iqk.agent.harness.AgentObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Workflow-backed ReAct agent service (Reason + Act loop).
 *
 * <p>Implements the blocking chat flow and the SSE streaming flow on top of
 * {@link AgentWorkflowEngine}, which persists task/step records and status
 * transitions for observability. Each turn runs at most {@value #MAX_STEPS}
 * planner steps: the LLM planner returns a JSON decision, the chosen action is
 * executed through {@link AgentHarnessService}, and its observation is folded
 * into a rolling context that feeds the next planning step.</p>
 *
 * <p>Failure policy is degrade-not-fail: planner or parse errors fall back to
 * a deterministic keyword-based decision ({@link #fallbackDecision}), and a
 * missing final answer falls back to a canned summary. Tenant budget is
 * enforced per model call via {@link TenantCostService}; task metrics are
 * recorded per outcome via Micrometer.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowReactAgentService {

    /** Hard upper bound of ReAct steps per request; step 5+ maps to WRITING. */
    private static final int MAX_STEPS = 6;

    /**
     * Wall-clock budget for one ReAct turn's plan/act loop. When exceeded the
     * loop stops planning and degrades to summarization instead of failing,
     * so a slow tool/model cannot stretch a turn indefinitely.
     */
    private static final long LOOP_DEADLINE_MS = 50_000L;

    /**
     * Idle timeout applied to the streaming final-answer model call: a stream
     * that stays silent (between signals) for longer is failed with a
     * {@code TimeoutException} and surfaces as an SSE {@code error} event.
     */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Upper bound of the rolling context folded into planning/final prompts;
     * once exceeded the oldest observations are truncated so prompt size
     * stays bounded regardless of observation payload size.
     */
    private static final int MAX_ROLLING_CONTEXT_CHARS = 12_000;

    /** Canned answer used when final summarization is unavailable or blank. */
    private static final String CANNED_FALLBACK_ANSWER = "当前未能生成最终答案，请稍后重试。";

    private final AgentWorkflowEngine workflowEngine;
    private final AgentHarnessService agentHarnessService;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Runs the blocking ReAct loop for one user turn.
     *
     * <p>Starts a {@code REACT} workflow task, then iterates up to
     * {@value #MAX_STEPS} plan/act steps. When the planner decides
     * {@code finish} with a non-blank answer that answer is used directly,
     * otherwise a summarization model call produces the final answer; the same
     * summarization runs when the step budget is exhausted. Every step is
     * mirrored into the workflow engine (step records, status transitions,
     * task metrics).</p>
     *
     * @param request prompt, chatId and optional model profile; must pass
     *                request validation (prompt and chatId required)
     * @return final answer with trace steps, deduplicated citations/evidence
     *         collected from observations, and model-route/experiment metadata
     * @throws IllegalArgumentException if prompt or chatId is blank
     * @throws IllegalStateException    if the workflow engine or model router
     *                                  return a null/blank record (task, step
     *                                  or route decision) for this turn
     * @throws RuntimeException         re-thrown after the task is marked
     *                                  {@code FAILED} in the workflow engine
     */
    public ReactChatResponseVO chat(ReactChatRequestVO request) {
        validateRequest(request);
        String tenantId = currentTenantId();
        long startedNs = System.nanoTime();

        AgentTaskRecord task = workflowEngine.startTask(
                tenantId, "REACT", request.getPrompt(),
                request.getModelProfile(), request.getChatId(), null);
        if (task == null || !StringUtils.hasText(task.getTaskId())) {
            throw new IllegalStateException("workflow engine returned no task record for REACT turn"
                    + " (chatId=" + request.getChatId() + ", tenantId=" + tenantId + ")");
        }

        ModelRouter.ModelRouteDecision routeDecision = resolveRouteDecision(
                request.getModelProfile(), "react", request.getChatId(), tenantId);
        if (routeDecision == null) {
            throw new IllegalStateException("model router returned no route decision for REACT turn"
                    + " (chatId=" + request.getChatId() + ", taskId=" + task.getTaskId() + ")");
        }

        List<ReactTraceStepVO> trace = new ArrayList<>();
        String rollingContext = "";

        try {
            for (int stepNum = 1; stepNum <= MAX_STEPS; stepNum++) {
                if (stepNum > 1 && elapsedMs(startedNs) > LOOP_DEADLINE_MS) {
                    log.warn("ReAct loop deadline exceeded (taskId={}, chatId={}, completedSteps={}, elapsed={}ms);"
                                    + " degrading to summarization instead of planning further",
                            task.getTaskId(), request.getChatId(), stepNum - 1, elapsedMs(startedNs));
                    break;
                }
                AgentStepRecord stepRecord = workflowEngine.startStep(
                        task.getTaskId(), "planner", stepNum,
                        Map.of("prompt", request.getPrompt(), "rollingContext", rollingContext));
                if (stepRecord == null || !StringUtils.hasText(stepRecord.getStepId())) {
                    throw new IllegalStateException("workflow engine returned no step record for step " + stepNum
                            + " (taskId=" + task.getTaskId() + ")");
                }

                long stepStartNs = System.nanoTime();
                ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId);

                if ("finish".equals(decision.action())) {
                    String answer = StringUtils.hasText(decision.answer())
                            ? decision.answer()
                            : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
                    Map<String, Object> obs = new LinkedHashMap<>();
                    obs.put("status", "completed");
                    obs.put("citations", decision.citations() != null ? decision.citations() : List.of());
                    obs.put("evidence", decision.evidence() != null ? decision.evidence() : List.of());

                    trace.add(buildTraceStep(stepNum, decision, obs));
                    workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                            Map.of("answer", answer), obs,
                            decision.thought(), "finish", decision.actionInput(),
                            0, 0, elapsedMs(stepStartNs), null);
                    workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
                    workflowEngine.recordTaskMetrics("REACT", "DONE", elapsedMs(startedNs));
                    return success(request.getChatId(), answer, trace, routeDecision, task.getTaskId());
                }

                workflowEngine.transitionStatus(task.getTaskId(),
                        mapToWorkflowState(stepNum), mapToWorkflowState(stepNum + 1));

                Object observation = executeAction(request, decision.action(), decision.actionInput(), tenantId,
                        task.getTaskId(), stepRecord.getStepId());
                trace.add(buildTraceStep(stepNum, decision, observation));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                        null, observation,
                        decision.thought(), decision.action(), decision.actionInput(),
                        0, 0, elapsedMs(stepStartNs), null);

                rollingContext = appendContext(rollingContext, decision.action(), observation);
            }

            String answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
            workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
            workflowEngine.recordTaskMetrics("REACT", "DONE", elapsedMs(startedNs));
            return success(request.getChatId(), answer, trace, routeDecision, task.getTaskId());

        } catch (RuntimeException e) {
            // e.getMessage() can be null (e.g. NPE); persist the exception type
            // so the stored failure reason is never blank.
            String reason = StringUtils.hasText(e.getMessage())
                    ? e.getMessage()
                    : e.getClass().getSimpleName() + " (no message)";
            try {
                workflowEngine.failTask(task.getTaskId(), reason);
                workflowEngine.recordTaskMetrics("REACT", "FAILED", elapsedMs(startedNs));
            } catch (RuntimeException cleanupEx) {
                // Failure bookkeeping must never mask the original cause.
                log.error("Failed to mark REACT task {} as FAILED after: {}",
                        task.getTaskId(), reason, cleanupEx);
                e.addSuppressed(cleanupEx);
            }
            throw e;
        }
    }

    /**
     * Runs the streaming ReAct flow and emits an SSE event sequence:
     * one {@code trace} event per executed step, then {@code token} events of
     * the final answer, then a {@code done} event carrying the full
     * {@link ReactChatResponseVO}, or an {@code error} event on failure.
     *
     * <p>All workflow mutations are deferred until subscription. The final
     * answer streams directly from the model unless the planner produced a
     * non-blank {@code finish} answer, which is emitted as a single token.
     * On any error the task is marked {@code FAILED} (so it cannot stay
     * orphaned in a non-terminal state) and the error is surfaced as an SSE
     * event instead of a reactor error signal. Latency/request metrics are
     * recorded in {@code doFinally} regardless of outcome.</p>
     *
     * @param request prompt, chatId and optional model profile; validation
     *                happens on subscription
     * @return SSE-encoded {@code Flux<String>} as described above
     */
    public Flux<String> stream(ReactChatRequestVO request) {
        long startedNs = System.nanoTime();
        AtomicReference<Long> firstTokenMs = new AtomicReference<>(null);
        AtomicReference<String> outcomeRef = new AtomicReference<>("error");
        AtomicReference<String> taskIdRef = new AtomicReference<>("");

        return Flux.defer(() -> {
            validateRequest(request);
            String tenantId = currentTenantId();

            AgentTaskRecord task = workflowEngine.startTask(
                    tenantId, "REACT_STREAM", request.getPrompt(),
                    request.getModelProfile(), request.getChatId(), null);
            if (task == null || !StringUtils.hasText(task.getTaskId())) {
                throw new IllegalStateException("workflow engine returned no task record for REACT_STREAM turn"
                        + " (chatId=" + request.getChatId() + ", tenantId=" + tenantId + ")");
            }
            taskIdRef.set(task.getTaskId());

            ModelRouter.ModelRouteDecision routeDecision = resolveRouteDecision(
                    request.getModelProfile(), "react", request.getChatId(), tenantId);
            if (routeDecision == null) {
                throw new IllegalStateException("model router returned no route decision for REACT_STREAM turn"
                        + " (chatId=" + request.getChatId() + ", taskId=" + task.getTaskId() + ")");
            }

            List<ReactTraceStepVO> trace = new ArrayList<>();
            String rollingContext = "";
            String directAnswer = "";

            for (int stepNum = 1; stepNum <= MAX_STEPS; stepNum++) {
                if (stepNum > 1 && elapsedMs(startedNs) > LOOP_DEADLINE_MS) {
                    log.warn("ReAct stream loop deadline exceeded (taskId={}, chatId={}, completedSteps={}, elapsed={}ms);"
                                    + " skipping to final answer",
                            task.getTaskId(), request.getChatId(), stepNum - 1, elapsedMs(startedNs));
                    break;
                }
                AgentStepRecord stepRecord = workflowEngine.startStep(
                        task.getTaskId(), "planner", stepNum,
                        Map.of("prompt", request.getPrompt()));
                if (stepRecord == null || !StringUtils.hasText(stepRecord.getStepId())) {
                    throw new IllegalStateException("workflow engine returned no step record for step " + stepNum
                            + " (taskId=" + task.getTaskId() + ")");
                }

                long stepStartNs = System.nanoTime();
                ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId);

                if ("finish".equals(decision.action())) {
                    Map<String, Object> obs = new LinkedHashMap<>();
                    obs.put("status", "completed");
                    trace.add(buildTraceStep(stepNum, decision, obs));
                    directAnswer = emptyIfBlank(decision.answer());
                    workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                            Map.of("answer", directAnswer), obs,
                            decision.thought(), "finish", decision.actionInput(),
                            0, 0, elapsedMs(stepStartNs), null);
                    break;
                }

                Object observation = executeAction(request, decision.action(), decision.actionInput(), tenantId,
                        task.getTaskId(), stepRecord.getStepId());
                trace.add(buildTraceStep(stepNum, decision, observation));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                        null, observation,
                        decision.thought(), decision.action(), decision.actionInput(),
                        0, 0, elapsedMs(stepStartNs), null);
                rollingContext = appendContext(rollingContext, decision.action(), observation);
            }

            Flux<String> traceFlux = Flux.fromIterable(trace)
                    .map(step -> formatSse("trace", toJson(step)));

            StringBuilder answerBuilder = new StringBuilder();
            Flux<String> answerFlux = StringUtils.hasText(directAnswer)
                    ? Flux.just(directAnswer)
                    : callModelStream(
                    "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                    buildFinalPrompt(request, trace, rollingContext),
                    routeDecision, tenantId, "react_final")
                    // A silent model stream must not hang the SSE connection:
                    // inter-signal timeout fails it into the error-event path,
                    // where the task is marked FAILED.
                    .timeout(STREAM_IDLE_TIMEOUT);

            Flux<String> tokenFlux = answerFlux
                    .map(token -> {
                        if (firstTokenMs.get() == null) {
                            firstTokenMs.set(elapsedMs(startedNs));
                        }
                        answerBuilder.append(token);
                        return formatSse("token", toJson(Map.of("token", token)));
                    });

            String finalTaskId = task.getTaskId();
            return Flux.concat(traceFlux, tokenFlux)
                    .concatWith(Flux.defer(() -> {
                        if (firstTokenMs.get() == null) {
                            firstTokenMs.set(elapsedMs(startedNs));
                        }
                        // Partial failure guard: an empty model stream would
                        // otherwise complete the task DONE with a blank answer.
                        String answer = StringUtils.hasText(answerBuilder.toString())
                                ? answerBuilder.toString()
                                : CANNED_FALLBACK_ANSWER;
                        ReactChatResponseVO response = success(
                                request.getChatId(), answer, trace, routeDecision, finalTaskId);
                        workflowEngine.completeTask(finalTaskId, WorkflowState.DONE, answer);
                        outcomeRef.set("success");
                        return Flux.just(formatSse("done", toJson(response)));
                    }));
        })
                .onErrorResume(ex -> {
                    // Keep the exception type in the message when getMessage()
                    // is blank (e.g. NPE) so the SSE error names the cause.
                    String message = StringUtils.hasText(ex.getMessage())
                            ? ex.getMessage() : ex.getClass().getSimpleName() + ": stream failed";
                    // Mark the task FAILED so a failed stream does not leave the
                    // workflow record orphaned in a non-terminal state. Cleanup
                    // failures are logged and never suppress the SSE error event.
                    String failedTaskId = taskIdRef.get();
                    try {
                        if (StringUtils.hasText(failedTaskId)) {
                            workflowEngine.failTask(failedTaskId, message);
                            workflowEngine.recordTaskMetrics("REACT_STREAM", "FAILED", elapsedMs(startedNs));
                        }
                    } catch (RuntimeException cleanupEx) {
                        log.error("Failed to mark REACT_STREAM task {} as FAILED after: {}",
                                failedTaskId, message, cleanupEx);
                    }
                    return Flux.just(formatSse("error", toJson(Map.of("message", message))));
                })
                .doFinally(signal -> recordStreamMetrics(startedNs, firstTokenMs.get(), outcomeRef.get()));
    }

    // ── Reason / Action / Summarize (same logic, now with engine) ──

    /**
     * One planning step: asks the model for a strict-JSON ReAct decision
     * (thought / action / action_input / answer) constrained to the five
     * allowed actions. Falls back to the deterministic keyword decision when
     * the model call or JSON parsing fails, so the loop can always make
     * progress.
     */
    private ReasonDecision reason(ReactChatRequestVO request,
                                  String rollingContext,
                                  List<ReactTraceStepVO> trace,
                                  ModelRouter.ModelRouteDecision routeDecision,
                                  String tenantId) {
        String planningPrompt = "You are a ReAct planner for an education assistant.%nYou must choose exactly one action for the next step.%n%nAllowed actions:%n- query_school%n- query_course%n- add_course_reservation%n- rag_search%n- finish%n%nReturn JSON only:%n{%n  \"thought\": \"short reasoning\",%n  \"action\": \"one action from list\",%n  \"action_input\": {\"key\":\"value\"},%n  \"answer\": \"only provide when action is finish\"%n}%n%nUser question:%n%s%n%nRolling context:%n%s%n%nExisting trace:%n%s%n".formatted(request.getPrompt(), emptyIfBlank(rollingContext), toJson(trace));

        try {
            String raw = callModel("You are strict JSON ReAct planner. Return valid JSON only.",
                    planningPrompt, routeDecision, tenantId, "react_planner");
            return parseDecision(raw);
        } catch (RuntimeException ex) {
            // Degrade-not-fail: record why the planner was bypassed so the
            // fallback decision is traceable in logs.
            log.warn("ReAct planner call failed (chatId={}, endpoint=react_planner, stepTraceSize={});"
                            + " falling back to keyword decision",
                    request.getChatId(), trace == null ? 0 : trace.size(), ex);
            return fallbackDecision(request.getPrompt());
        }
    }

    /**
     * Dispatches the planner's chosen action to {@link AgentHarnessService},
     * which enforces tool policy/tenant isolation, and returns the execution
     * result as a map used both as trace observation and rolling context.
     */
    private Map<String, Object> executeAction(ReactChatRequestVO request,
                                              String action,
                                              Map<String, Object> actionInput,
                                              String tenantId,
                                              String taskId,
                                              String stepId) {
        AgentObservation observation = agentHarnessService.execute(new AgentAction(
                action,
                actionInput,
                request.getPrompt(),
                tenantId,
                request.getChatId(),
                request.getModelProfile(),
                taskId,
                stepId
        ));
        if (observation == null) {
            throw new IllegalStateException("tool harness returned no observation for action '" + action
                    + "' (taskId=" + taskId + ", stepId=" + stepId + ")");
        }
        Map<String, Object> result = observation.toMap();
        return result != null ? result : Collections.emptyMap();
    }

    /**
     * Produces the final answer via a model call over the accumulated trace
     * and rolling context; degrades to a canned Chinese message if the call
     * fails or returns blank, keeping the workflow response usable.
     */
    private String summarizeAnswer(ReactChatRequestVO request, List<ReactTraceStepVO> trace,
                                    String rollingContext, ModelRouter.ModelRouteDecision routeDecision,
                                    String tenantId) {
        String finalPrompt = buildFinalPrompt(request, trace, rollingContext);
        try {
            String answer = callModel("你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                    finalPrompt, routeDecision, tenantId, "react_final");
            if (StringUtils.hasText(answer)) {
                return answer;
            }
            log.warn("Final summarization returned blank content (chatId={}, endpoint=react_final);"
                    + " using canned fallback answer", request.getChatId());
        } catch (RuntimeException ex) {
            // The deterministic fallback below keeps the workflow response usable,
            // but the failure reason must stay visible in logs.
            log.warn("Final summarization model call failed (chatId={}, endpoint=react_final);"
                    + " using canned fallback answer", request.getChatId(), ex);
        }
        return CANNED_FALLBACK_ANSWER;
    }

    private String buildFinalPrompt(ReactChatRequestVO request,
                                     List<ReactTraceStepVO> trace, String rollingContext) {
        return "用户问题:%n%s%n%nReAct轨迹:%n%s%n%n观察上下文:%n%s%n%n请输出最终中文答案，要求简洁、可执行、结构清晰。%n".formatted(request.getPrompt(), toJson(trace), emptyIfBlank(rollingContext));
    }

    // ── Helpers (delegated from original ReactAgentService) ──────

    private ReactTraceStepVO buildTraceStep(int step, ReasonDecision d, Object obs) {
        return ReactTraceStepVO.builder()
                .step(step).thought(d.thought()).action(d.action())
                .actionInput(d.actionInput()).observation(obs).build();
    }

    private ReactChatResponseVO success(String chatId, String answer,
                                         List<ReactTraceStepVO> trace,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String taskId) {
        List<String> citations = extractTraceStrings(trace, "citations");
        List<String> evidence = extractTraceStrings(trace, "evidence");
        return ReactChatResponseVO.builder()
                .ok(1).msg("ok").chatId(chatId)
                .answer(attachCitationFooter(answer, citations))
                .citations(citations).evidence(evidence)
                .routeProfile(routeDecision == null ? "" : routeDecision.profile())
                .routeReason(routeDecision == null ? "" : routeDecision.reason())
                .routeCostTier(routeDecision == null ? "" : routeDecision.costTier())
                .experimentKey(routeDecision == null ? "" : routeDecision.experimentKey())
                .experimentVariant(routeDecision == null ? "" : routeDecision.experimentVariant())
                .experimentBucket(routeDecision == null ? null : routeDecision.experimentBucket())
                .trace(trace)
                .build();
    }

    /**
     * Resolves model profile / cost tier / experiment assignment for this
     * turn through the {@link ModelRouter}; the result is reused for all
     * model calls within the same request.
     */
    private ModelRouter.ModelRouteDecision resolveRouteDecision(String profile, String endpoint,
                                                                 String subjectKey, String tenantId) {
        return modelRouter.resolve(profile, endpoint, tenantId, subjectKey);
    }

    /**
     * Blocking model call with tenant budget enforcement: estimates input
     * tokens and asserts budget before the call, records actual usage
     * afterwards (tagged with {@code endpointTag} for cost attribution).
     */
    private String callModel(String system, String user, ModelRouter.ModelRouteDecision decision,
                              String tenantId, String endpointTag) {
        if (decision == null) {
            throw new IllegalStateException("model route decision is missing for endpoint '" + endpointTag
                    + "' (tenantId=" + tenantId + "); cannot resolve model to call");
        }
        long inputTokens = tenantCostService.estimateTokens(system)
                + tenantCostService.estimateTokens(user);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);
        String output = chatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .system(system).user(user).call().content();
        long outputTokens = tenantCostService.estimateTokens(output);
        tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, outputTokens, endpointTag);
        // Normalize a null model response to "" so callers never see null.
        return StringUtils.hasText(output) ? output : "";
    }

    /**
     * Streaming variant of {@link #callModel}: same pre-call budget assert,
     * with output usage recorded exactly once in {@code doFinally} after the
     * token stream terminates (collected via a side StringBuilder).
     */
    private Flux<String> callModelStream(String system, String user,
                                          ModelRouter.ModelRouteDecision decision,
                                          String tenantId, String endpointTag) {
        if (decision == null) {
            throw new IllegalStateException("model route decision is missing for endpoint '" + endpointTag
                    + "' (tenantId=" + tenantId + "); cannot resolve model to stream from");
        }
        long inputTokens = tenantCostService.estimateTokens(system)
                + tenantCostService.estimateTokens(user);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);
        StringBuilder collector = new StringBuilder();
        AtomicBoolean recorded = new AtomicBoolean(false);
        return chatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .system(system).user(user).stream().content()
                .doOnNext(collector::append)
                .doFinally(sig -> {
                    if (!recorded.compareAndSet(false, true)) return;
                    long out = tenantCostService.estimateTokens(collector.toString());
                    tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, out, endpointTag);
                });
    }

    /**
     * Maps ReAct step numbers to workflow status transitions
     * (SEARCHING → RETRIEVING → JUDGING → REFLECTING → WRITING) so the
     * persisted task status mirrors loop progress; steps beyond 4 stay in
     * WRITING.
     */
    private WorkflowState mapToWorkflowState(int step) {
        return switch (step) {
            case 1 -> WorkflowState.SEARCHING;
            case 2 -> WorkflowState.RETRIEVING;
            case 3 -> WorkflowState.JUDGING;
            case 4 -> WorkflowState.REFLECTING;
            default -> WorkflowState.WRITING;
        };
    }

    // ── Delegated helpers (same as original ReactAgentService) ───

    /**
     * Parses the planner's raw output into a {@link ReasonDecision}. Extracts
     * the outermost JSON object, normalizes and allow-lists the action
     * (unknown actions are coerced to {@code finish}), and degrades to a
     * {@code finish} decision carrying the raw text as answer when the JSON
     * is missing or invalid.
     */
    private ReasonDecision parseDecision(String raw) {
        String json = extractJson(raw);
        if (!StringUtils.hasText(json)) {
            return new ReasonDecision("Fallback to finish.", "finish",
                    Collections.emptyMap(), emptyIfBlank(raw), List.of(), List.of());
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String action = normalizeAction(node.path("action").asText("finish"));
            Map<String, Object> input = objectMapper.convertValue(
                    node.path("action_input"), new TypeReference<Map<String, Object>>() {});
            if (input == null) {
                input = Collections.emptyMap();
            }
            if (!List.of("query_school", "query_course", "add_course_reservation", "rag_search", "finish")
                    .contains(action)) {
                action = "finish";
            }
            return new ReasonDecision(node.path("thought").asText(""),
                    action, input, node.path("answer").asText(""), List.of(), List.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new ReasonDecision("Parse failed.", "finish",
                    Collections.emptyMap(), emptyIfBlank(raw), List.of(), List.of());
        }
    }

    /**
     * Deterministic keyword-based decision used when the planner model is
     * unavailable: routes campus/reservation/knowledge-base prompts to canned
     * answers (or {@code rag_search}), and otherwise advises retrying. Keeps
     * the ReAct contract intact without any model call.
     */
    private ReasonDecision fallbackDecision(String prompt) {
        String safe = emptyIfBlank(prompt).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(safe)) {
            return new ReasonDecision("Empty prompt.", "finish", Collections.emptyMap(),
                    "当前请求内容为空，请补充问题后重试。",
                    List.of("source=fallback://input_validation, chunk=1"),
                    List.of("规则兜底：空问题时引导用户补充输入。"));
        }
        if (containsAny(safe, "校区", "campus")) {
            return new ReasonDecision("Fallback school query.", "finish", Collections.emptyMap(),
                    "已识别为校区查询请求：可以返回校区列表，并按城市或课程类型做进一步筛选。",
                    List.of("source=fallback://school_query_flow, chunk=1"),
                    List.of("校区查询流程：先列出校区，再按城市/课程类型筛选。"));
        }
        if (containsAny(safe, "课程预约", "预约字段", "预约需要", "联系方式", "姓名")) {
            return new ReasonDecision("Fallback reservation.", "finish", Collections.emptyMap(),
                    "课程预约建议至少包含：课程、姓名、联系方式、校区。",
                    List.of("source=fallback://course_reservation_schema, chunk=1"),
                    List.of("预约字段模板。"));
        }
        if (containsAny(safe, "知识库", "引用", "来源", "pdf", "文档", "source")) {
            return new ReasonDecision("Fallback rag.", "rag_search",
                    Map.of("query", prompt), "", List.of(), List.of());
        }
        return new ReasonDecision("Generic fallback.", "finish", Collections.emptyMap(),
                "当前规划器暂不可用，建议稍后重试或细化问题关键词。",
                List.of("source=fallback://planner_unavailable, chunk=1"),
                List.of("系统兜底。"));
    }

    /**
     * Extracts the outermost {@code {...}} span from raw model output so that
     * JSON wrapped in prose or markdown fences can still be parsed; returns
     * {@code ""} when no well-formed span exists.
     */
    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start < 0 || end <= start) ? "" : raw.substring(start, end + 1);
    }

    /**
     * Collects the string list stored under {@code key} (e.g. {@code citations}
     * or {@code evidence}) in each step's observation map into a
     * de-duplicated, insertion-ordered list for the final response.
     */
    private List<String> extractTraceStrings(List<ReactTraceStepVO> trace, String key) {
        if (trace == null || trace.isEmpty()) return List.of();
        Set<String> values = new LinkedHashSet<>();
        for (ReactTraceStepVO step : trace) {
            if (!(step.getObservation() instanceof Map<?, ?> obs)) continue;
            Object raw = obs.get(key);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    String s = emptyIfBlank(String.valueOf(item));
                    if (StringUtils.hasText(s)) values.add(s);
                }
            }
        }
        return List.copyOf(values);
    }

    /**
     * Appends a numbered {@code 引用来源:} footer to the answer unless there
     * are no citations or the answer already cites its sources.
     */
    private String attachCitationFooter(String answer, List<String> citations) {
        if (citations == null || citations.isEmpty()) return emptyIfBlank(answer);
        if (emptyIfBlank(answer).contains("引用来源")) return answer;
        StringBuilder sb = new StringBuilder(emptyIfBlank(answer).trim());
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("引用来源:\n");
        for (int i = 0; i < citations.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(citations.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatSse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Keep the SSE/event payload well-formed but name the failure
            // reason instead of an opaque placeholder.
            log.warn("JSON serialization failed for {} (SSE/event payload degrades to placeholder)",
                    value == null ? "null" : value.getClass().getSimpleName(), e);
            return "{\"message\":\"serialization_failed\",\"reason\":\""
                    + e.getClass().getSimpleName() + "\"}";
        }
    }

    private String appendContext(String origin, String action, Object observation) {
        StringBuilder sb = new StringBuilder(emptyIfBlank(origin));
        if (sb.length() > 0) sb.append("\n");
        sb.append("action=").append(action).append(", observation=").append(toJson(observation));
        if (sb.length() > MAX_ROLLING_CONTEXT_CHARS) {
            // Keep the most recent observations and drop the oldest tail so
            // prompt size stays bounded across long step loops.
            sb.delete(0, sb.length() - MAX_ROLLING_CONTEXT_CHARS);
            sb.insert(0, "...(earlier observations truncated)...\n");
        }
        return sb.toString();
    }

    /**
     * Records per-outcome stream metrics: total latency and (when a token was
     * observed) first-token latency as histogram timers, plus a request
     * counter — all tagged with the final outcome ({@code success}/
     * {@code error}).
     */
    private void recordStreamMetrics(long startedNs, Long firstTokenMs, String outcome) {
        long total = elapsedMs(startedNs);
        Timer.builder("react.stream.total.latency").tag("outcome", outcome)
                .publishPercentileHistogram().register(meterRegistry)
                .record(total, TimeUnit.MILLISECONDS);
        if (firstTokenMs != null) {
            Timer.builder("react.stream.first_token.latency").tag("outcome", outcome)
                    .publishPercentileHistogram().register(meterRegistry)
                    .record(firstTokenMs, TimeUnit.MILLISECONDS);
        }
        Counter.builder("react.stream.requests").tag("outcome", outcome)
                .register(meterRegistry).increment();
    }

    // ── Trivial delegates ──────────────────────────────────────────

    private String normalizeAction(String a) {
        return (!StringUtils.hasText(a)) ? "finish" : a.trim().toLowerCase(Locale.ROOT);
    }
    private long elapsedMs(long startedNs) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs); }
    private String emptyIfBlank(String v) { return StringUtils.hasText(v) ? v : ""; }
    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null) return false;
        for (String kw : keywords) {
            if (StringUtils.hasText(kw) && text.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
    private String currentTenantId() { return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE)); }
    private void validateRequest(ReactChatRequestVO r) {
        if (r == null || !StringUtils.hasText(r.getPrompt())) throw new IllegalArgumentException("prompt is required");
        if (!StringUtils.hasText(r.getChatId())) throw new IllegalArgumentException("chatId is required");
    }

    private record ReasonDecision(String thought, String action,
                                   Map<String, Object> actionInput, String answer,
                                   List<String> citations, List<String> evidence) {}
}
