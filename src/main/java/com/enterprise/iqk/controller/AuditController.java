package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.AuditLog;
import com.enterprise.iqk.mapper.AuditLogMapper;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * File: AuditController.java
 * <p>
 * 审计日志查询端点（{@code /audit/logs}）：按当前租户隔离返回最新审计记录；
 * 双层权限控制——路由级（SecurityConfiguration 的 PERM_AUDIT_READ/ROLE）+
 * 方法级 {@code @PreAuthorize}。
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditLogMapper auditLogMapper;

    /** 查询当前租户最新审计记录，limit 被夹取到 [1, 200]。 */
    @GetMapping("/logs")
    @PreAuthorize("hasAnyAuthority('PERM_AUDIT_READ','ROLE_ADMIN')")
    public List<AuditLog> latest(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return auditLogMapper.latestByTenant(TenantContext.currentTenantId(), boundedLimit);
    }
}
