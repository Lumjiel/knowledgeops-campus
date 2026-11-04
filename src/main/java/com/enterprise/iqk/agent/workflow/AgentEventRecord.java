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
 * File: AgentEventRecord.java
 * <p>
 * {@code agent_event} 表记录：工作流事件溯源的追加式审计数据。
 * 所有状态变更/步骤开始完成/任务收尾均作为事件异步追加，
 * 写入失败仅记日志、不阻断主流程（见 {@link AgentWorkflowEngine#emitEvent}）。
 */
@TableName("agent_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEventRecord {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 全局唯一事件 ID（形如 evt-&lt;uuid&gt;）。 */
    private String eventId;
    /** 所属任务 ID。 */
    private String taskId;
    /** 关联步骤 ID，任务级事件（如 STATE_CHANGED / TASK_COMPLETED）为空。 */
    private String stepId;
    /** 事件类型：STATE_CHANGED / STEP_STARTED / STEP_COMPLETED / TASK_COMPLETED / TASK_FAILED。 */
    private String eventType;
    /** 事件详情 JSON（如状态迁移的 from/to、步骤耗时等）。 */
    private String payloadJson;
    /** 事件落库时间（查询时按此升序还原事件流）。 */
    private LocalDateTime createdAt;
}
