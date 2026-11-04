package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * File: AgentTaskMapper.java
 * <p>
 * {@code agent_task} 表的 MyBatis-Plus Mapper。除通用 CRUD 外提供：
 * 按租户隔离的任务查询（{@link #findByTenantAndTaskId} / {@link #findByTenant}）、
 * 状态机迁移 SQL（{@link #updateStatus}）与终态收尾 SQL（{@link #completeTask}）。
 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTaskRecord> {

    /** 按任务 ID 查询（不带租户条件，仅限引擎内部使用）。 */
    @Select("SELECT * FROM agent_task WHERE task_id = #{taskId}")
    AgentTaskRecord findByTaskId(@Param("taskId") String taskId);

    /** 按租户 + 任务 ID 查询，用于对外查询的租户隔离校验。 */
    @Select("SELECT * FROM agent_task WHERE tenant_id = #{tenantId} AND task_id = #{taskId}")
    AgentTaskRecord findByTenantAndTaskId(@Param("tenantId") String tenantId,
                                          @Param("taskId") String taskId);

    /** 分页查询租户下任务列表，按创建时间倒序。 */
    @Select("SELECT * FROM agent_task WHERE tenant_id = #{tenantId} ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentTaskRecord> findByTenant(@Param("tenantId") String tenantId,
                                       @Param("offset") long offset,
                                       @Param("limit") int limit);

    /** 统计租户下任务总数。 */
    @Select("SELECT COUNT(*) FROM agent_task WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") String tenantId);

    /** 分页查询租户下指定类型的任务列表，按创建时间倒序。 */
    @Select("SELECT * FROM agent_task WHERE tenant_id = #{tenantId} AND type = #{type} ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentTaskRecord> findByTenantAndType(@Param("tenantId") String tenantId,
                                              @Param("type") String type,
                                              @Param("offset") long offset,
                                              @Param("limit") int limit);

    /**
     * 状态机迁移：直接按 task_id 更新 status 并刷新 updated_at。
     * 任务已处于终态时（SQL 侧由终态行不再被匹配的机制约束）由调用方校验影响行数。
     *
     * @return 影响行数，0 表示任务不存在或无法迁移
     */
    @Update("UPDATE agent_task SET status = #{status}, updated_at = NOW() WHERE task_id = #{taskId}")
    int updateStatus(@Param("taskId") String taskId, @Param("status") String status);

    /**
     * 终态收尾：写入终态 status 与 finalOutput（失败时该字段存 errorMessage），
     * 并刷新 updated_at。
     *
     * @return 影响行数
     */
    @Update("UPDATE agent_task SET status = #{status}, final_output = #{finalOutput}, updated_at = NOW() WHERE task_id = #{taskId}")
    int completeTask(@Param("taskId") String taskId,
                     @Param("status") String status,
                     @Param("finalOutput") String finalOutput);
}
