package com.enterprise.iqk.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * File: HttpMetricsFilter.java
 * <p>
 * HTTP 时延指标过滤器（Operations Context 可观测性）：为每个请求记录
 * {@code http.requests.latency} 直方图，按 method / 归一化 path / status 打标签，
 * 模板路径归一化防止高基数标签爆炸。
 */
@Component
@RequiredArgsConstructor
public class HttpMetricsFilter extends OncePerRequestFilter {
    private final MeterRegistry meterRegistry;

    /** 计时环绕整个过滤链，finally 中停止计时并上报（无论成败）。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            filterChain.doFilter(request, response);
        } finally {
            sample.stop(Timer.builder("http.requests.latency")
                    .tag("method", request.getMethod())
                    .tag("path", normalizePath(request.getRequestURI()))
                    .tag("status", String.valueOf(response.getStatus()))
                    .register(meterRegistry));
        }
    }

    /** 路径模板化：actuator 与带路径参数的端点折叠为模板，防止标签基数失控。 */
    private String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        if (raw.startsWith("/actuator")) {
            return "/actuator";
        }
        if (raw.startsWith("/ai/pdf/file/")) {
            return "/ai/pdf/file/{chatId}";
        }
        if (raw.startsWith("/ingestion/jobs/")) {
            return "/ingestion/jobs/{jobId}";
        }
        return raw;
    }
}
