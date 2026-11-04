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
 * File: AgentTaskRecord.java
 * <p>
 * {@code agent_task} 表记录：一次 Agent 工作流任务的完整生命周期持久化
 * （任务 ID、租户、类型 REACT/DEEP_RESEARCH、状态、原始输入与最终产出）。
 * 由 {@link AgentWorkflowEngine} 独占读写。
 */
@TableName("agent_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskRecord {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 全局唯一任务 ID（形如 task-&lt;uuid&gt;，由 {@link AgentWorkflowEngine#startTask} 生成）。 */
    private String taskId;
    /** 租户 ID（经 {@code TenantContext.normalize} 归一化，查询按此隔离）。 */
    private String tenantId;
    /** 工作流类型：REACT / DEEP_RESEARCH / CUSTOM。 */
    private String type;
    /** 当前状态，取值为 {@link WorkflowState} 枚举名。 */
    private String status;
    /** 用户原始输入。 */
    private String userInput;
    /** 最终产出（成功时为报告/回答；失败时存 errorMessage）。 */
    private String finalOutput;
    /** 模型档位，空值时引擎回退 "balanced"。 */
    private String modelProfile;
    /** 关联会话 ID。 */
    private String chatId;
    /** 关联会话内记忆 ID。 */
    private String sessionId;
    /** 扩展元数据 JSON，预留字段。 */
    private String metadataJson;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近一次状态变更时间（由 SQL 更新时以 NOW() 刷新）。 */
    private LocalDateTime updatedAt;
}
