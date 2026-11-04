package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * File: AgentStepMapper.java
 * <p>
 * {@code agent_step} 表的 MyBatis-Plus Mapper：步骤查询（按任务/步骤 ID）
 * 与步骤终态字段落库（{@link #completeStep}）。
 */
@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStepRecord> {

    /** 查询任务下全部步骤，按 step_order 升序。 */
    @Select("SELECT * FROM agent_step WHERE task_id = #{taskId} ORDER BY step_order ASC")
    List<AgentStepRecord> findByTaskId(@Param("taskId") String taskId);

    /** 按步骤 ID 查询单条步骤（{@code completeStep} 回填 ReAct 轨迹前复用）。 */
    @Select("SELECT * FROM agent_step WHERE step_id = #{stepId}")
    AgentStepRecord findByStepId(@Param("stepId") String stepId);

    /**
     * 步骤收尾：写入终态 status、output/observation JSON、token/耗时/错误信息，
     * 并以 NOW() 写 ended_at。ReAct 轨迹三件套（thought/action/action_input）
     * 不在此 SQL 中，由引擎单查后经通用 updateById 回填。
     *
     * @return 影响行数
     */
    @Update("UPDATE agent_step SET status = #{status}, output_json = #{outputJson}, " +
            "observation_json = #{observationJson}, input_tokens = #{inputTokens}, " +
            "output_tokens = #{outputTokens}, " +
            "latency_ms = #{latencyMs}, error_message = #{errorMessage}, ended_at = NOW() " +
            "WHERE step_id = #{stepId}")
    int completeStep(@Param("stepId") String stepId,
                     @Param("status") String status,
                     @Param("outputJson") String outputJson,
                     @Param("observationJson") String observationJson,
                     @Param("inputTokens") Long inputTokens,
                     @Param("outputTokens") Long outputTokens,
                     @Param("latencyMs") Long latencyMs,
                     @Param("errorMessage") String errorMessage);
}
