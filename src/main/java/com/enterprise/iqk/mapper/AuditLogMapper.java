package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * File: AuditLogMapper.java
 * <p>
 * {@code audit_log} 表的 MyBatis-Plus Mapper：按保留期删除与最新记录查询。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
    /** 删除早于 deadline 的审计行（保留期调度器调用）。 */
    @Update("DELETE FROM audit_log WHERE created_at < #{deadline}")
    int deleteBefore(@Param("deadline") LocalDateTime deadline);

    /** 全局最新审计记录（无租户过滤，仅运维视角）。 */
    @Select("SELECT * FROM audit_log ORDER BY created_at DESC LIMIT #{limit}")
    List<AuditLog> latest(@Param("limit") int limit);

    /** 租户隔离的最新审计记录（对外 {@code /audit/logs} 端点使用）。 */
    @Select("""
            SELECT * FROM audit_log
            WHERE tenant_id = #{tenantId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<AuditLog> latestByTenant(@Param("tenantId") String tenantId, @Param("limit") int limit);
}
