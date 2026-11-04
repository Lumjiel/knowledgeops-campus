package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * File: AgentWorkflowEngine.java
 * <p>
 * 深度研究类工作流（多 Agent 协作）的持久化引擎，为工作流执行器提供任务/步骤/事件
 * 三层记录的统一读写门面：
 * <ul>
 *   <li>任务生命周期：{@link #startTask} 建任务并进入 PLANNING，{@link #completeTask}/
 *       {@link #failTask} 写终态；</li>
 *   <li>步骤记录：{@link #startStep}/​{@link #completeStep} 落每个 Agent 步骤的输入输出、
 *       思考/行动轨迹与 token/耗时；</li>
 *   <li>状态机：{@link #transitionStatus} 按 {@link WorkflowState#canTransitionTo} 校验
 *       合法迁移，非法迁移与已终态仅告警不抛异常；</li>
 *   <li>事件溯源：所有生命周期动作经 {@link #emitEvent} 追加事件（失败仅记日志，不阻断主流程）；</li>
 *   <li>指标：步骤/任务级 latency + count 按 agent/type、status 打点上报 Micrometer。</li>
 * </ul>
 * 查询接口（{@link #getTask} 等）均以 {@link TenantContext#normalize} 归一化后的租户隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWorkflowEngine {

    private final AgentTaskMapper taskMapper;
    private final AgentStepMapper stepMapper;
    private final AgentEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // ── Task lifecycle ───────────────────────────────────────────

    /**
     * 创建工作流任务并立即迁入 PLANNING 状态（CREATED → PLANNING 是唯一合法首迁移）。
     * modelProfile 为空时回退 "balanced"。
     *
     * @param tenantId     租户 ID（内部归一化）
     * @param type         工作流类型标识，必填
     * @param userInput    用户原始输入，必填
     * @param modelProfile 模型档位，空则取 "balanced"
     * @param chatId       关联会话 ID
     * @param sessionId    关联会话内记忆 ID
     * @return 已落库的任务记录（状态字段为 CREATED；当前实际状态为 PLANNING，见 {@link #currentState}）
     * @throws IllegalArgumentException type 或 userInput 为空时抛出（此时任务尚未落库，无残留）
     */
    public AgentTaskRecord startTask(String tenantId, String type, String userInput,
                                     String modelProfile, String chatId, String sessionId) {
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("workflow type is required");
        }
        if (!StringUtils.hasText(userInput)) {
            throw new IllegalArgumentException("user input is required");
        }
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        AgentTaskRecord task = AgentTaskRecord.builder()
                .taskId(taskId)
                .tenantId(TenantContext.normalize(tenantId))
                .type(type)
                .status(WorkflowState.CREATED.name())
                .userInput(userInput)
                .modelProfile(StringUtils.hasText(modelProfile) ? modelProfile : "balanced")
                .chatId(chatId)
                .sessionId(sessionId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        taskMapper.insert(task);
        transitionStatus(taskId, WorkflowState.CREATED, WorkflowState.PLANNING);
        return task;
    }

    /**
     * 开始一个 Agent 步骤：落库 RUNNING 状态的步骤记录（输入序列化为 inputJson），
     * 并发出 STEP_STARTED 事件。
     *
     * @param taskId    所属任务 ID
     * @param agentName 执行该步骤的 Agent 名称
     * @param stepOrder 步骤序号（任务内顺序）
     * @param input     步骤输入，序列化为 JSON 存储
     * @return 已落库的步骤记录
     */
    public AgentStepRecord startStep(String taskId, String agentName, int stepOrder,
                                      Map<String, Object> input) {
        String stepId = "step-" + UUID.randomUUID().toString().replace("-", "");
        AgentStepRecord step = AgentStepRecord.builder()
                .stepId(stepId)
                .taskId(taskId)
                .agentName(agentName)
                .status("RUNNING")
                .stepOrder(stepOrder)
                .inputJson(toJson(input))
                .startedAt(LocalDateTime.now())
                .build();
        stepMapper.insert(step);
        // Map.of 不接受 null 值：agentName 为空时用占位符，避免事件行 NPE 炸掉已落库的步骤
        emitEvent(taskId, stepId, "STEP_STARTED",
                Map.of("agentName", orUnknown(agentName), "stepOrder", stepOrder));
        return step;
    }

    /**
     * 结束一个步骤：先经 SQL 完成 outputJson/observationJson 与 token/耗时/错误等终态字段落库；
     * 若携带思考轨迹（thought），再单查该步骤回填 thought/action/actionInputJson
     * （ReAct 轨迹三件套，仅在有内容时写）。最后发出 STEP_COMPLETED 事件。
     * 步骤不存在时 taskId 以 "unknown" 记入事件，不抛异常。
     *
     * @param stepId      步骤 ID
     * @param status      终态（如 SUCCEEDED / FAILED）
     * @param output      结构化输出，序列化为 JSON
     * @param observation 观察结果对象，序列化为 JSON
     * @param thought     模型思考文本，可空；非空时才回填 ReAct 轨迹字段
     * @param action      ReAct 行动名，与 thought 配套
     * @param actionInput ReAct 行动参数，与 thought 配套
     * @param inputTokens 输入 token 数
     * @param outputTokens 输出 token 数
     * @param latencyMs   步骤耗时（毫秒）
     * @param errorMessage 失败原因，可空
     */
    public void completeStep(String stepId, String status, Map<String, Object> output,
                              Object observation, String thought, String action,
                              Map<String, Object> actionInput,
                              long inputTokens, long outputTokens, long latencyMs,
                              String errorMessage) {
        // 状态为空既会写脏库又会让事件 payload 的 Map.of 抛 NPE，统一兜底为 UNKNOWN
        String safeStatus = StringUtils.hasText(status) ? status : "UNKNOWN";
        stepMapper.completeStep(stepId, safeStatus,
                toJson(output), toJson(observation),
                inputTokens, outputTokens, latencyMs, errorMessage);

        AgentStepRecord step = stepMapper.findByStepId(stepId);
        if (StringUtils.hasText(thought) && step != null) {
            step.setThought(thought);
            step.setAction(action);
            step.setActionInputJson(toJson(actionInput));
            stepMapper.updateById(step);
        }
        String taskId = step == null ? "unknown" : step.getTaskId();
        emitEvent(taskId, stepId, "STEP_COMPLETED",
                Map.of("status", safeStatus, "latencyMs", latencyMs));
    }

    /**
     * 任务成功收尾：写终态（DONE 等由调用方指定的 {@code finalStatus}）与最终产出文本，
     * 并发出 TASK_COMPLETED 事件。finalStatus 为空时在落库前即抛出，避免 NPE 无从定位。
     *
     * @throws IllegalArgumentException finalStatus 为 null 时抛出
     */
    public void completeTask(String taskId, WorkflowState finalStatus, String finalOutput) {
        if (finalStatus == null) {
            throw new IllegalArgumentException("final status is required");
        }
        taskMapper.completeTask(taskId, finalStatus.name(), finalOutput);
        emitEvent(taskId, null, "TASK_COMPLETED",
                Map.of("status", finalStatus.name()));
    }

    /**
     * 任务失败收尾：终态写 FAILED（errorMessage 存入 finalOutput 字段），并发出 TASK_FAILED 事件。
     * errorMessage 为空（如 NPE 的 getMessage()）时落库与事件均用 "unknown error" 占位——
     * 本方法常在调用方 catch 块中使用，任何异常都会替换掉正在处理的原始错误，绝不向上抛。
     */
    public void failTask(String taskId, String errorMessage) {
        String safeError = StringUtils.hasText(errorMessage) ? errorMessage : "unknown error";
        try {
            taskMapper.completeTask(taskId, WorkflowState.FAILED.name(), safeError);
            emitEvent(taskId, null, "TASK_FAILED", Map.of("error", safeError));
        } catch (RuntimeException e) {
            // 失败收尾自身再失败（如任务行已不存在）：记录原因，不替换调用方正在处理的原始异常
            log.error("failed to persist FAILED terminal state for task {}: {}", taskId, e.getMessage(), e);
        }
    }

    // ── State management ─────────────────────────────────────────

    /**
     * 按状态机迁移任务状态：先以 {@link WorkflowState#canTransitionTo} 校验合法性，
     * 再经 SQL 更新（已终态时影响行数为 0）。两种失败（非法迁移 / 已终态）均只告警
     * 不抛异常、不发事件，主流程不受阻。
     *
     * @param taskId 任务 ID
     * @param from   期望的当前状态
     * @param to     目标状态
     */
    public void transitionStatus(String taskId, WorkflowState from, WorkflowState to) {
        // from/to 为 null 是调用方编程错误：按既定「仅告警不抛异常」语义留痕后返回，
        // 不让 NPE（无任何上下文信息）打断主流程
        if (from == null || to == null) {
            log.warn("Invalid state transition: from={}, to={} (null state) for task {}", from, to, taskId);
            return;
        }
        if (!from.canTransitionTo(to)) {
            log.warn("Invalid state transition: {} -> {} for task {}", from, to, taskId);
            return;
        }
        int updated = taskMapper.updateStatus(taskId, to.name());
        if (updated == 0) {
            log.warn("State transition had no effect: task {} already in terminal state", taskId);
            return;
        }
        emitEvent(taskId, null, "STATE_CHANGED",
                Map.of("from", from.name(), "to", to.name()));
    }

    /**
     * 读取任务当前状态。
     *
     * @return 任务不存在时返回 {@code null}；库中状态串不在枚举范围内时按 FAILED 处理并告警留痕
     */
    public WorkflowState currentState(String taskId) {
        AgentTaskRecord task = taskMapper.findByTaskId(taskId);
        if (task == null) {
            return null;
        }
        try {
            return WorkflowState.valueOf(task.getStatus());
        } catch (IllegalArgumentException e) {
            // 库里的脏状态串映射不了：按 FAILED 处理，但原始值要留痕便于排查
            log.warn("unknown task status '{}' for task {}, treating as FAILED", task.getStatus(), taskId);
            return WorkflowState.FAILED;
        }
    }

    // ── Event sourcing ───────────────────────────────────────────

    /**
     * 追加一条事件溯源记录（事件 ID 形如 evt-<uuid>，payload 序列化为 JSON）。
     * 事件写入失败仅记 error 日志、不向调用方抛出——事件是旁路审计数据，
     * 不允许拖垮任务/步骤主流程。
     */
    public void emitEvent(String taskId, String stepId, String eventType, Map<String, Object> payload) {
        try {
            AgentEventRecord event = AgentEventRecord.builder()
                    .eventId("evt-" + UUID.randomUUID().toString().replace("-", ""))
                    .taskId(taskId)
                    .stepId(stepId)
                    .eventType(eventType)
                    .payloadJson(toJson(payload))
                    .createdAt(LocalDateTime.now())
                    .build();
            eventMapper.insert(event);
        } catch (Exception e) {
            log.error("Failed to persist agent event: taskId={}, type={}", taskId, eventType, e);
        }
    }

    // ── Metrics ──────────────────────────────────────────────────

    /**
     * 上报步骤级指标：直方图 timer {@code agent.workflow.step.latency} 与计数器
     * {@code agent.workflow.step.count}，按 agent / status 打标签。
     * agent/status 为空时以 "unknown" 占位（Micrometer 标签不接受 null），
     * 负数 latency 按 0 记录。
     */
    public void recordStepMetrics(String agentName, String status, long latencyMs) {
        String safeAgent = orUnknown(agentName);
        String safeStatus = orUnknown(status);
        long safeLatency = Math.max(0, latencyMs);
        Timer.builder("agent.workflow.step.latency")
                .description("Step execution latency")
                .tag("agent", safeAgent)
                .tag("status", safeStatus)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(safeLatency, TimeUnit.MILLISECONDS);

        Counter.builder("agent.workflow.step.count")
                .description("Step execution count")
                .tag("agent", safeAgent)
                .tag("status", safeStatus)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 上报任务级指标：直方图 timer {@code agent.workflow.task.latency} 与计数器
     * {@code agent.workflow.task.count}，按 type / status 打标签。null 防御同 {@link #recordStepMetrics}。
     */
    public void recordTaskMetrics(String type, String status, long latencyMs) {
        String safeType = orUnknown(type);
        String safeStatus = orUnknown(status);
        long safeLatency = Math.max(0, latencyMs);
        Timer.builder("agent.workflow.task.latency")
                .description("Task execution latency")
                .tag("type", safeType)
                .tag("status", safeStatus)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(safeLatency, TimeUnit.MILLISECONDS);

        Counter.builder("agent.workflow.task.count")
                .description("Task execution count")
                .tag("type", safeType)
                .tag("status", safeStatus)
                .register(meterRegistry)
                .increment();
    }

    // ── Query ────────────────────────────────────────────────────

    /**
     * 查询任务详情（含全部步骤与事件）。任务按租户隔离校验，不存在时返回 {@code null}。
     */
    public WorkflowTaskVO getTask(String tenantId, String taskId) {
        AgentTaskRecord task = taskMapper.findByTenantAndTaskId(TenantContext.normalize(tenantId), taskId);
        if (task == null) {
            return null;
        }
        List<AgentStepRecord> steps = stepMapper.findByTaskId(taskId);
        List<AgentEventRecord> events = eventMapper.findByTaskId(taskId);
        return toTaskVO(task, steps, events);
    }

    /**
     * 分页查询租户下任务列表（列表项含步骤但不含事件，减少查询量）。
     * page 小于 1 时按 1 处理；pageSize 夹紧到 [1, 100]（请求参数直通 SQL，
     * 不设上限会一次拖回全表，负数则产生非法 OFFSET）。
     */
    public List<WorkflowTaskVO> listTasks(String tenantId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        long offset = (long) (safePage - 1) * safePageSize;
        List<AgentTaskRecord> records = taskMapper.findByTenant(TenantContext.normalize(tenantId), offset, safePageSize);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream()
                .map(t -> toTaskVO(t, stepMapper.findByTaskId(t.getTaskId()),
                        Collections.emptyList()))
                .toList();
    }

    /**
     * 查询任务的事件流（按事件落库顺序）。任务不存在或不属于该租户时返回空列表（防越权探测）。
     */
    public List<WorkflowEventVO> getTaskEvents(String tenantId, String taskId) {
        if (taskMapper.findByTenantAndTaskId(TenantContext.normalize(tenantId), taskId) == null) {
            return Collections.emptyList();
        }
        return eventMapper.findByTaskId(taskId).stream()
                .map(this::toEventVO)
                .toList();
    }

    // ── Conversion helpers ───────────────────────────────────────

    /** 任务记录 → 详情 VO（组装步骤与事件子列表；子列表为 null 时按空处理）。 */
    private WorkflowTaskVO toTaskVO(AgentTaskRecord t, List<AgentStepRecord> steps,
                                     List<AgentEventRecord> events) {
        return WorkflowTaskVO.builder()
                .taskId(t.getTaskId())
                .tenantId(t.getTenantId())
                .type(t.getType())
                .status(t.getStatus())
                .userInput(t.getUserInput())
                .finalOutput(t.getFinalOutput())
                .modelProfile(t.getModelProfile())
                .chatId(t.getChatId())
                .sessionId(t.getSessionId())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .steps((steps == null ? List.<AgentStepRecord>of() : steps).stream().map(this::toStepVO).toList())
                .events((events == null ? List.<AgentEventRecord>of() : events).stream().map(this::toEventVO).toList())
                .build();
    }

    /** 步骤记录 → VO：JSON 字段（actionInput/observation）反序列化为 Map，token/耗时空值归零。 */
    private WorkflowStepVO toStepVO(AgentStepRecord s) {
        return WorkflowStepVO.builder()
                .stepId(s.getStepId())
                .taskId(s.getTaskId())
                .agentName(s.getAgentName())
                .status(s.getStatus())
                .stepOrder(s.getStepOrder())
                .thought(s.getThought())
                .action(s.getAction())
                .actionInput(parseJsonMap(s.getActionInputJson()))
                .observation(s.getObservationJson() != null ? parseJsonMap(s.getObservationJson()) : null)
                .modelProfile(s.getModelProfile())
                .inputTokens(s.getInputTokens() != null ? s.getInputTokens() : 0)
                .outputTokens(s.getOutputTokens() != null ? s.getOutputTokens() : 0)
                .latencyMs(s.getLatencyMs() != null ? s.getLatencyMs() : 0)
                .errorMessage(s.getErrorMessage())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .build();
    }

    /** 事件记录 → VO（payload JSON 反序列化为 Map）。 */
    private WorkflowEventVO toEventVO(AgentEventRecord e) {
        return WorkflowEventVO.builder()
                .eventId(e.getEventId())
                .taskId(e.getTaskId())
                .stepId(e.getStepId())
                .eventType(e.getEventType())
                .payload(parseJsonMap(e.getPayloadJson()))
                .createdAt(e.getCreatedAt())
                .build();
    }

    /** JSON 串 → Map；空串返回空 Map，解析失败记告警留痕后返回空 Map（展示数据，容错优先）。 */
    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("failed to parse stored json (first 120 chars: {}): {}",
                    json.substring(0, Math.min(120, json.length())), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 对象 → JSON 串；null 返回 null，序列化失败记告警留痕后返回 "{}"（落库字段非空约束兜底）。 */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("failed to serialize value of type {}, storing empty json object instead: {}",
                    value.getClass().getSimpleName(), e.getMessage());
            return "{}";
        }
    }

    /** 指标标签 / 事件 payload 的 null 占位（Micrometer 标签与 Map.of 均不接受 null）。 */
    private String orUnknown(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
    }
}
