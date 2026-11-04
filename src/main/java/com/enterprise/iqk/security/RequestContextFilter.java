package com.enterprise.iqk.security;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * File: RequestContextFilter.java
 * <p>
 * 请求上下文过滤器（认证过滤器之后执行）：为每个请求确定 request_id（客户端
 * {@code X-Request-Id} 优先，否则生成 UUID）、租户与 trace_id 并写入 MDC /
 * response header，供日志与审计（{@link AuditLogFilter}）关联使用。
 * 租户以认证身份写入的 request attribute 为准，X-Tenant-Id 头仅作未认证回退，
 * 防止伪造头跨租户读写。
 */
@Component("aiRequestContextFilter")
@RequiredArgsConstructor
public class RequestContextFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private final ObjectProvider<Tracer> tracerProvider;

    /**
     * 上下文注入主流程：request_id / 租户（归一化）/ chat_id / trace_id 依次
     * 写入 MDC 并回写响应头，finally 中全部清理。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        // The tenant resolved from the authenticated identity (request attribute set by
        // ApiKeyOrJwtAuthFilter) is authoritative. The X-Tenant-Id header is only honored
        // when no authenticated identity provided a tenant, otherwise a caller could read
        // and write another tenant's data by sending a forged header.
        String tenantId;
        Object tenantFromAttr = request.getAttribute(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        if (tenantFromAttr != null && StringUtils.hasText(String.valueOf(tenantFromAttr))) {
            tenantId = String.valueOf(tenantFromAttr);
        } else {
            tenantId = request.getHeader(TenantContext.TENANT_HEADER);
        }
        String chatId = request.getParameter("chatId");
        String traceId = resolveTraceId();

        MDC.put("request_id", requestId);
        if (StringUtils.hasText(tenantId)) {
            String normalizedTenant = TenantContext.normalize(tenantId);
            MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, normalizedTenant);
            response.setHeader(TenantContext.TENANT_HEADER, normalizedTenant);
        }
        if (StringUtils.hasText(chatId)) {
            MDC.put("chat_id", chatId);
        }
        if (StringUtils.hasText(traceId)) {
            MDC.put("trace_id", traceId);
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("request_id");
            MDC.remove("chat_id");
            MDC.remove("trace_id");
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        }
    }

    /** 取当前 span 的 traceId；未接入 tracer / 无活跃 span 时返回空串。 */
    private String resolveTraceId() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return "";
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return "";
        }
        return span.context().traceId();
    }
}
