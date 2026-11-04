package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/**
 * File: ApiKeyOrJwtAuthFilter.java
 * <p>
 * 请求级认证过滤器：优先 Bearer JWT，失败/缺失时回退 {@code X-API-Key}。
 * 认证通过后将角色/权限转为 Spring Security authorities（ROLE_* 与 PERM_* 前缀），并把租户写入
 * request attribute + response header + MDC（下游过滤器与业务层的数据来源）。
 * 公开端点（健康检查、auth、docs 等）与 security.enabled=false 时直接放行。
 */
@Component
@RequiredArgsConstructor
public class ApiKeyOrJwtAuthFilter extends OncePerRequestFilter {
    private static final String API_KEY_HEADER = "X-API-Key";

    private final SecurityProperties securityProperties;
    private final ApiKeyAuthService apiKeyAuthService;
    private final JwtService jwtService;

    /**
     * 认证主流程：解析身份（JWT 优先，API Key 兜底）→ 租户归一化并扩散到
     * attribute/header/MDC → 组装 ROLE_* 与 PERM_* authorities 放入 SecurityContext
     * → 放行；finally 中清理 MDC 与 SecurityContext，避免线程池串号。
     * 身份缺失时直接写结构化 401 JSON。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!securityProperties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (isPublic(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        AuthIdentity identity = resolveIdentity(request);
        if (identity == null) {
            unauthorized(response, "missing or invalid credentials");
            return;
        }
        String tenantId = TenantContext.normalize(identity.getTenantId());
        identity.setTenantId(tenantId);
        List<SimpleGrantedAuthority> roleAuthorities = (identity.getRoles() == null ? List.<String>of() : identity.getRoles()).stream()
                .filter(StringUtils::hasText)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .toList();
        List<SimpleGrantedAuthority> permissionAuthorities = identity.getPermissions() == null
                ? List.of()
                : identity.getPermissions().stream()
                .filter(StringUtils::hasText)
                .map(perm -> "PERM_" + perm.replace(':', '_').toUpperCase())
                .map(SimpleGrantedAuthority::new)
                .toList();
        List<SimpleGrantedAuthority> authorities = Stream.concat(roleAuthorities.stream(), permissionAuthorities.stream())
                .toList();
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(identity.getPrincipal(), identity.getSource(), authorities);
        request.setAttribute(TenantContext.TENANT_REQUEST_ATTRIBUTE, tenantId);
        response.setHeader(TenantContext.TENANT_HEADER, tenantId);
        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, tenantId);
        try {
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 凭证解析：Bearer JWT 合法即用；JWT 过期/畸形不中断请求，降级尝试 API Key。
     *
     * @return 认证身份，两种凭证均不可用时 {@code null}（由调用方写 401）
     */
    private AuthIdentity resolveIdentity(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            String token = authorization.substring("Bearer ".length()).trim();
            try {
                AuthIdentity jwtIdentity = jwtService.parse(token);
                if (jwtIdentity != null) {
                    return jwtIdentity;
                }
            } catch (io.jsonwebtoken.JwtException | IllegalArgumentException ex) {
                // Expired or malformed JWT must not abort the request with a 500;
                // fall through and try the API key credential instead.
            }
        }
        return apiKeyAuthService.authenticate(request.getHeader(API_KEY_HEADER));
    }

    /** 免认证端点前缀：健康/文档/错误页与 auth 系列端点。 */
    private boolean isPublic(String uri) {
        return uri.startsWith("/actuator/health")
                || uri.startsWith("/actuator/info")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/auth/token")
                || uri.startsWith("/auth/refresh")
                || uri.startsWith("/auth/guest")
                || uri.startsWith("/error");
    }

    /** 写 401 结构化 JSON（{ok, msg}），与 SecurityConfiguration 的 403 风格一致。 */
    private void unauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"ok\":0,\"msg\":\"" + msg + "\"}");
    }
}
