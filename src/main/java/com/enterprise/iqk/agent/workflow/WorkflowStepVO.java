package com.enterprise.iqk.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * File: WorkflowStepVO.java
 * <p>
 * 工作流步骤的对外展示 VO，由 {@link AgentWorkflowEngine} 从
 * {@link AgentStepRecord} 转换（JSON 字段反序列化为 Map、指标空值归零）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStepVO {
    /** 全局唯一步骤 ID。 */
    private String stepId;
    /** 所属任务 ID。 */
    private String taskId;
    /** 执行 Agent 名称（planner, retriever, writer...）。 */
    private String agentName;
    /** 步骤状态：RUNNING / COMPLETED / FAILED 等。 */
    private String status;
    /** 任务内步骤序号，从 1 起。 */
    private int stepOrder;
    /** ReAct 思考文本，可空。 */
    private String thought;
    /** ReAct 行动名，可空。 */
    private String action;
    /** ReAct 行动参数（JSON 反序列化为 Map，解析失败为空 Map）。 */
    private Map<String, Object> actionInput;
    /** 观察结果（动作执行结果，结构不限）。 */
    private Object observation;
    /** 本步骤使用的模型档位。 */
    private String modelProfile;
    /** 输入 token 数（空值归零）。 */
    private long inputTokens;
    /** 输出 token 数（空值归零）。 */
    private long outputTokens;
    /** 步骤耗时毫秒（空值归零）。 */
    private long latencyMs;
    /** 失败原因，成功时为空。 */
    private String errorMessage;
    /** 步骤开始时间。 */
    private LocalDateTime startedAt;
    /** 步骤结束时间，运行中为空。 */
    private LocalDateTime endedAt;
}
