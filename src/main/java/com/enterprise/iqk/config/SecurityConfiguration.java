package com.enterprise.iqk.config;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter;
import com.enterprise.iqk.security.AuditLogFilter;
import com.enterprise.iqk.security.HttpMetricsFilter;
import com.enterprise.iqk.security.RateLimitFilter;
import com.enterprise.iqk.security.RequestContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;

/**
 * File: SecurityConfiguration.java
 * <p>
 * 安全主配置：无状态会话 + CSRF 关闭 + 企业安全响应头；按路径声明
 * 路由级权限（PERM_* / ROLE_* authorities，即文档「RBAC + route-level permission
 * checks」）；装配过滤器链（认证 → 上下文 → 指标 → 限流 → 审计）；
 * 403 返回带 needUpgrade 的结构化 JSON。security.enabled=false 时全部放行。
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final SecurityProperties securityProperties;
    private final RequestContextFilter requestContextFilter;
    private final ApiKeyOrJwtAuthFilter apiKeyOrJwtAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final AuditLogFilter auditLogFilter;
    private final HttpMetricsFilter httpMetricsFilter;

    /**
     * 安全过滤器链：请求匹配规则见方法体（公开端点 → 各业务端点权限 → 其余
     * 全部需认证）；过滤器顺序为 ApiKeyOrJwtAuthFilter（认证）→
     * RequestContextFilter（上下文）→ HttpMetricsFilter（指标）→
     * RateLimitFilter（限流，认证后，可用 principal 复合键）→
     * AuditLogFilter（审计，最后记录含限流结果的真实状态码）。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // Enterprise security headers
        http.headers(headers -> headers
                .contentTypeOptions(cto -> {})
                .frameOptions(fo -> fo.deny())
                .permissionsPolicy(permissions -> permissions.policy("camera=(), microphone=(), geolocation=()"))
        );

        if (!securityProperties.isEnabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .requestMatchers("/actuator/prometheus").hasAnyAuthority("PERM_METRICS_READ", "ROLE_ADMIN", "ROLE_OPS")
                    .requestMatchers(HttpMethod.GET, "/audit/logs").hasAnyAuthority("PERM_AUDIT_READ", "ROLE_ADMIN", "ROLE_OPS")
                    .requestMatchers(HttpMethod.POST, "/auth/token", "/auth/refresh", "/auth/guest").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/api-keys/**").hasAnyAuthority("PERM_AUTH_KEY_MANAGE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ai/chat", "/ai/chat/stream", "/ai/service").hasAnyAuthority("PERM_CHAT_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ai/react/chat", "/ai/react/chat/stream").hasAnyAuthority("PERM_CHAT_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ai/harness/**").hasAnyAuthority("PERM_AGENT_TRUSTED", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ai/harness/**").hasAnyAuthority("PERM_AGENT_TRUSTED", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ai/evaluation/**").hasAnyAuthority("PERM_EVAL_READ", "PERM_EVAL_WRITE", "ROLE_ADMIN", "ROLE_OPS")
                    .requestMatchers(HttpMethod.POST, "/ai/evaluation/**").hasAnyAuthority("PERM_EVAL_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ai/sessions/**").hasAnyAuthority("PERM_SESSION_READ", "PERM_CHAT_READ", "PERM_CHAT_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/ai/sessions/**").hasAnyAuthority("PERM_SESSION_WRITE", "PERM_CHAT_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ai/sessions/**").hasAnyAuthority("PERM_SESSION_WRITE", "PERM_CHAT_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ai/feedback").hasAnyAuthority("PERM_FEEDBACK_WRITE", "PERM_CHAT_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ai/pdf/file/**").hasAnyAuthority("PERM_RAG_READ", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ai/pdf/chat").hasAnyAuthority("PERM_RAG_READ", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ai/pdf/upload/**", "/ingestion/upload/**").hasAnyAuthority("PERM_INGESTION_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.GET, "/cost/summary").hasAnyAuthority("PERM_COST_READ", "ROLE_ADMIN", "ROLE_OPS")
                    .requestMatchers(HttpMethod.POST, "/cost/budget").hasAnyAuthority("PERM_COST_WRITE", "ROLE_ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ingestion/jobs/**", "/ingestion/jobs").hasAnyAuthority("PERM_INGESTION_READ", "PERM_INGESTION_WRITE", "ROLE_ADMIN", "ROLE_OPS")
                    .requestMatchers(HttpMethod.POST, "/ingestion/jobs/process").hasRole("ADMIN")
                    .requestMatchers("/ingestion/**", "/ai/pdf/**").hasAnyAuthority("PERM_INGESTION_WRITE", "ROLE_ADMIN")
                    .anyRequest().authenticated()
            );
        }

        // 无权限（含游客越权）时返回结构化 JSON，而非容器默认的空 403：
        // needUpgrade 字段供前端识别「功能受限」并给出登录/升级引导卡片。
        http.exceptionHandling(eh -> eh.accessDeniedHandler(
                (request, response, ex) -> writeProblemJson(response,
                        HttpServletResponse.SC_FORBIDDEN, "access denied: permission required")));

        http.addFilterBefore(apiKeyOrJwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(requestContextFilter, ApiKeyOrJwtAuthFilter.class);
        http.addFilterAfter(httpMetricsFilter, RequestContextFilter.class);
        http.addFilterAfter(rateLimitFilter, ApiKeyOrJwtAuthFilter.class);
        http.addFilterAfter(auditLogFilter, RateLimitFilter.class);
        return http.build();
    }

    /** 统一的鉴权失败响应体：{ok, code, msg, needUpgrade}，与 ApiKeyOrJwtAuthFilter#unauthorized 的 401 风格一致。 */
    private static void writeProblemJson(HttpServletResponse response, int status, String msg)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"ok\":0,\"code\":" + status + ",\"msg\":\"" + msg + "\",\"needUpgrade\":true}");
    }
}
