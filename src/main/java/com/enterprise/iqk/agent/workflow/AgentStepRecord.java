package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * File: AgentStepRecord.java
 * <p>
 * {@code agent_step} 表记录：工作流内一个 Agent 执行步骤的持久化，
 * 包含 ReAct 范式的思考/行动/观察轨迹与 token、耗时指标。
 * 由 {@link AgentWorkflowEngine#startStep}/​{@link AgentWorkflowEngine#completeStep} 写入。
 */
@TableName("agent_step")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepRecord {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 全局唯一步骤 ID（形如 step-&lt;uuid&gt;）。 */
    private String stepId;
    /** 所属任务 ID（对应 {@code agent_task.task_id}）。 */
    private String taskId;
    /** 执行该步骤的 Agent 名称（planner, retriever, writer...）。 */
    private String agentName;
    /** 步骤状态：RUNNING（创建时）→ SUCCEEDED / FAILED 等终态。 */
    private String status;
    /** 任务内步骤序号，从 1 起，查询时按此升序。 */
    private Integer stepOrder;
    /** 步骤输入 JSON（{@code startStep} 时序列化落库）。 */
    private String inputJson;
    /** 步骤结构化输出 JSON（{@code completeStep} 时落库）。 */
    private String outputJson;
    /** ReAct 思考文本（携带 thought 时由 {@code completeStep} 回填）。 */
    private String thought;
    /** ReAct 行动名，与 thought 配套。 */
    private String action;
    /** ReAct 行动参数 JSON，与 thought 配套。 */
    private String actionInputJson;
    /** 观察结果 JSON（动作执行结果）。 */
    private String observationJson;
    /** 本步骤使用的模型档位。 */
    private String modelProfile;
    /** 输入 token 数。 */
    private Long inputTokens;
    /** 输出 token 数。 */
    private Long outputTokens;
    /** 步骤耗时（毫秒）。 */
    private Long latencyMs;
    /** 失败原因，成功时为空。 */
    private String errorMessage;
    /** 步骤开始时间。 */
    private LocalDateTime startedAt;
    /** 步骤结束时间（由 SQL 更新时以 NOW() 写入）。 */
    private LocalDateTime endedAt;
}
