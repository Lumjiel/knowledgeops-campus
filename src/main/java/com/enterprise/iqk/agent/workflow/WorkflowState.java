package com.enterprise.iqk.agent.workflow;

/**
 * File: WorkflowState.java
 * <p>
 * Agent 工作流状态机的全部合法状态。迁移规则由 {@link #canTransitionTo} 单点定义，
 * 与 docs/architecture-agent-workflow.md 中的状态图一一对应；全部迁移路径：
 * <pre>
 * CREATED → PLANNING
 * PLANNING → SEARCHING / RETRIEVING / WRITING / FAILED
 * SEARCHING → RETRIEVING / JUDGING / FAILED
 * RETRIEVING → JUDGING / REFLECTING / FAILED
 * JUDGING → REFLECTING / WRITING / FAILED
 * REFLECTING → WRITING / NEED_MORE_EVIDENCE / FAILED
 * NEED_MORE_EVIDENCE → SEARCHING / RETRIEVING / FAILED
 * WRITING → DONE / FAILED
 * </pre>
 */
public enum WorkflowState {
    /** 初始态：任务记录已落库，尚未开始规划。 */
    CREATED,
    /** 规划中：planner 正在拆解任务并选择首个动作（CREATED 的唯一合法去向）。 */
    PLANNING,
    /** 搜索中：执行外部搜索类动作（如 rag_search 前置阶段）。 */
    SEARCHING,
    /** 取证中：从数据源取回候选证据/文档。 */
    RETRIEVING,
    /** 评估中：判断已取证据是否足以回答。 */
    JUDGING,
    /** 反思中：复盘已有轨迹，决定继续取证还是进入写作。 */
    REFLECTING,
    /** 写作中：基于证据生成最终输出。 */
    WRITING,
    /** 终态：任务成功，finalOutput 为最终产出。 */
    DONE,
    /** 反思结论：证据不足，需回到 SEARCHING / RETRIEVING 补充取证。 */
    NEED_MORE_EVIDENCE,
    /** 终态：任务失败，errorMessage 记录在任务记录的 finalOutput 字段。 */
    FAILED;

    /**
     * @return 是否为终态（仅 {@code DONE} 与 {@code FAILED}）；终态不允许再迁移
     */
    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }

    /**
     * 校验从当前状态迁移到 {@code target} 是否符合状态机规则（见类注释的迁移表）。
     * 终态（DONE / FAILED）恒返回 {@code false}。
     *
     * @param target 目标状态
     * @return 迁移合法返回 {@code true}
     */
    public boolean canTransitionTo(WorkflowState target) {
        return switch (this) {
            case CREATED -> target == PLANNING;
            case PLANNING -> target == SEARCHING || target == RETRIEVING || target == WRITING || target == FAILED;
            case SEARCHING -> target == RETRIEVING || target == JUDGING || target == FAILED;
            case RETRIEVING -> target == JUDGING || target == REFLECTING || target == FAILED;
            case JUDGING -> target == REFLECTING || target == WRITING || target == FAILED;
            case REFLECTING -> target == WRITING || target == NEED_MORE_EVIDENCE || target == FAILED;
            case NEED_MORE_EVIDENCE -> target == SEARCHING || target == RETRIEVING || target == FAILED;
            case WRITING -> target == DONE || target == FAILED;
            case DONE, FAILED -> false;
        };
    }
}
