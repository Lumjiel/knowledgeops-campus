package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.AuditProperties;
import com.enterprise.iqk.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * File: AuditRetentionScheduler.java
 * <p>
 * 审计日志保留期调度器（docs/architecture-enterprise.md「Audit log retention
 * scheduler」）：每日 03:20 删除早于保留期（{@code app.audit.retention-days}）
 * 的 {@code audit_log} 行，防止审计表无限增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionScheduler {
    private final AuditLogMapper auditLogMapper;
    private final AuditProperties auditProperties;

    /** 每日定时清理；保留天数下限 1，删除行数大于 0 时输出 INFO 摘要。 */
    @Scheduled(cron = "0 20 3 * * *")
    public void cleanup() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(Math.max(1, auditProperties.getRetentionDays()));
        int deleted = auditLogMapper.deleteBefore(deadline);
        if (deleted > 0) {
            log.info("Audit retention cleanup deleted {} rows before {}", deleted, deadline);
        }
    }
}
