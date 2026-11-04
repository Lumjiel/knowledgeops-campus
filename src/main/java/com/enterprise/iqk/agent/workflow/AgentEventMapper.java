package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * File: AgentEventMapper.java
 * <p>
 * {@code agent_event} 表的 MyBatis-Plus Mapper：事件溯源只追加不更新，
 * 仅提供按任务（可叠加事件类型过滤）的按时间升序查询。
 */
@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEventRecord> {

    /** 查询任务的事件流，按落库时间升序（还原事件顺序）。 */
    @Select("SELECT * FROM agent_event WHERE task_id = #{taskId} ORDER BY created_at ASC")
    List<AgentEventRecord> findByTaskId(@Param("taskId") String taskId);

    /** 查询任务下指定类型的事件，按落库时间升序。 */
    @Select("SELECT * FROM agent_event WHERE task_id = #{taskId} AND event_type = #{eventType} ORDER BY created_at ASC")
    List<AgentEventRecord> findByTaskIdAndType(@Param("taskId") String taskId,
                                               @Param("eventType") String eventType);
}
