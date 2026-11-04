package com.enterprise.iqk.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * File: WorkflowTaskVO.java
 * <p>
 * 工作流任务的对外展示 VO（{@link AgentWorkflowEngine#getTask} / {@code listTasks} 返回），
 * 在 {@link AgentTaskRecord} 基础上组装步骤与事件子列表，供
 * {@code GET /ai/workflow/tasks} 系列端点输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTaskVO {
    /** 全局唯一任务 ID。 */
    private String taskId;
    /** 租户 ID（归一化后）。 */
    private String tenantId;
    /** 工作流类型：REACT / DEEP_RESEARCH / CUSTOM。 */
    private String type;
    /** 当前状态（{@link WorkflowState} 枚举名）。 */
    private String status;
    /** 用户原始输入。 */
    private String userInput;
    /** 最终产出（失败时为错误信息）。 */
    private String finalOutput;
    /** 模型档位。 */
    private String modelProfile;
    /** 关联会话 ID。 */
    private String chatId;
    /** 关联会话内记忆 ID。 */
    private String sessionId;
    /** 任务创建时间。 */
    private LocalDateTime createdAt;
    /** 最近状态变更时间。 */
    private LocalDateTime updatedAt;
    /** 执行步骤列表（按 step_order 升序；列表查询时也填充，事件为空）。 */
    private List<WorkflowStepVO> steps;
    /** 事件列表（仅任务详情查询填充，列表查询为空以减少查询量）。 */
    private List<WorkflowEventVO> events;
}
