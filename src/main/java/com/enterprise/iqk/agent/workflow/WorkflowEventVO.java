package com.enterprise.iqk.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * File: WorkflowEventVO.java
 * <p>
 * 工作流事件溯源的对外展示 VO，由 {@link AgentWorkflowEngine} 从
 * {@link AgentEventRecord} 转换（payload JSON 反序列化为 Map）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowEventVO {
    /** 全局唯一事件 ID。 */
    private String eventId;
    /** 所属任务 ID。 */
    private String taskId;
    /** 关联步骤 ID，任务级事件为空。 */
    private String stepId;
    /** 事件类型：STATE_CHANGED / STEP_STARTED / STEP_COMPLETED / TASK_COMPLETED 等。 */
    private String eventType;
    /** 事件详情（payload JSON 反序列化为 Map）。 */
    private Map<String, Object> payload;
    /** 事件落库时间。 */
    private LocalDateTime createdAt;
}
