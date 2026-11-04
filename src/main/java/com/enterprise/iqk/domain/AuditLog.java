package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * File: AuditLog.java
 * <p>
 * {@code audit_log} 表记录：一次 HTTP 请求的审计快照（谁、对什么、结果、耗时），
 * 由 {@link AuditLogFilter} 写入、{@link AuditRetentionScheduler} 按保留期清理。
 */
@TableName("audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 请求 ID（X-Request-Id 或生成 UUID）。 */
    private String requestId;
    /** 链路追踪 ID。 */
    private String traceId;
    /** 租户 ID（归一化后）。 */
    private String tenantId;
    /** 请求主体（认证 principal，未认证为 anonymous）。 */
    private String userIdentity;
    /** HTTP 方法。 */
    private String method;
    /** 请求路径。 */
    private String path;
    /** 响应状态码。 */
    private Integer statusCode;
    /** 请求耗时（毫秒）。 */
    private Long durationMs;
    /** 关联会话 ID（可从路径/参数提取）。 */
    private String chatId;
    /** 关联摄取任务 ID（jobId 参数）。 */
    private String jobId;
    /** 脱敏后的查询串（截断 1000 字符）。 */
    private String extraPayload;
    /** 落库时间。 */
    private LocalDateTime createdAt;

    /** 兼容别名：principal 即 userIdentity。 */
    public String getPrincipal() {
        return userIdentity;
    }

    /** 兼容别名：status 即 statusCode。 */
    public Integer getStatus() {
        return statusCode;
    }
}
