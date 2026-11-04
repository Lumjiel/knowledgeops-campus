package com.enterprise.iqk.security;

import com.enterprise.iqk.domain.AuditLog;
import com.enterprise.iqk.mapper.AuditLogMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * File: AuditLogFilter.java
 * <p>
 * 审计日志过滤器：对每个非 actuator 请求在 finally 中落一条 {@code audit_log}
 * 记录（主体、方法、路径、状态码、耗时、chatId/jobId、脱敏后的查询串）。
 * 落库失败仅告警，绝不影响业务响应（审计是旁路数据）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogFilter extends OncePerRequestFilter {
    private final AuditLogMapper auditLogMapper;

    /** 放行请求并在结束后（无论成败）写审计记录；异常吞掉只告警。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long started = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                long duration = System.currentTimeMillis() - started;
                String uri = request.getRequestURI();
                if (!uri.startsWith("/actuator")) {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    String principal = authentication == null ? "anonymous" : authentication.getName();
                    String tenantId = resolveTenant(request);
                    AuditLog logRecord = AuditLog.builder()
                            .requestId(MDC.get("request_id"))
                            .traceId(MDC.get("trace_id"))
                            .tenantId(tenantId)
                            .userIdentity(principal)
                            .method(request.getMethod())
                            .path(uri)
                            .statusCode(response.getStatus())
                            .durationMs(duration)
                            .chatId(extractChatId(request))
                            .jobId(request.getParameter("jobId"))
                            .extraPayload(buildSafePayload(request))
                            .createdAt(LocalDateTime.now())
                            .build();
                    auditLogMapper.insert(logRecord);
                }
            } catch (Exception e) {
                log.warn("Failed to persist audit log", e);
            }
        }
    }

    /** 租户解析：认证过滤器写入的 attribute 优先，回退 X-Tenant-Id 头。 */
    private String resolveTenant(HttpServletRequest request) {
        Object tenantFromAttr = request.getAttribute(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        if (tenantFromAttr != null && StringUtils.hasText(String.valueOf(tenantFromAttr))) {
            return TenantContext.normalize(String.valueOf(tenantFromAttr));
        }
        return TenantContext.normalize(request.getHeader(TenantContext.TENANT_HEADER));
    }

    /** 取 chatId：query 参数优先，否则从 {@code /upload/{chatId}} 形态的 URI 提取。 */
    private String extractChatId(HttpServletRequest request) {
        String chatId = request.getParameter("chatId");
        if (StringUtils.hasText(chatId)) {
            return chatId;
        }
        String uri = request.getRequestURI();
        String[] tokens = uri.split("/");
        for (int i = 0; i < tokens.length; i++) {
            if ("upload".equals(tokens[i]) && i + 1 < tokens.length) {
                return tokens[i + 1];
            }
        }
        return "";
    }

    /** 查询串脱敏（api-key/token/contact 值替换为 ***）并截断至 1000 字符。 */
    private String buildSafePayload(HttpServletRequest request) {
        String query = request.getQueryString();
        if (!StringUtils.hasText(query)) {
            return "";
        }
        String safe = query
                .replaceAll("(?i)(api[-_]?key=)[^&]+", "$1***")
                .replaceAll("(?i)(token=)[^&]+", "$1***")
                .replaceAll("(?i)(contact(_info)?=)[^&]+", "$1***");
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }
}
