package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.RateLimitProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RateLimitFilter 的纯单元测试（architecture-enterprise.md §4：
 * 「Tenant + principal composite rate limiting (single-instance in-memory buckets)」）。
 *
 * <p>既有测试只覆盖最基础的限流触发；这里补齐文档声明的关键语义：
 * 复合键（tenant + principal / tenant + ip）的桶隔离、认证主体优先于 IP、
 * 反向代理场景 X-Forwarded-For 取最右非私有跳、/actuator 放行、
 * 429 响应体，以及空闲桶逐出不清掉仍在消耗的桶。
 */
class RateLimitFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── 既有测试（保持原样） ─────────────────────────────────────

    @Test
    void shouldBlockWhenTooManyRequests() throws ServletException, IOException {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setCapacity(1);
        props.setRefillTokens(1);
        props.setRefillSeconds(60);

        RateLimitFilter filter = new RateLimitFilter(props);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/ai/chat");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(req1, resp1, new MockFilterChain());
        assertEquals(200, resp1.getStatus());

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/ai/chat");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(req2, resp2, new MockFilterChain());
        assertEquals(429, resp2.getStatus());
    }

    // ── 复合键：租户隔离 ─────────────────────────────────────────

    @Test
    void anonymousBucketsAreScopedByTenantSoTenantsDoNotShareQuota() throws Exception {
        RateLimitFilter filter = filterWithCapacity(1);

        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
        // 同 IP 同租户第二次请求被限流，响应体是统一的 JSON 信封
        MockHttpServletResponse limited = send(filter, request("tenant-a", "203.0.113.7"));
        assertEquals(429, limited.getStatus());
        assertEquals("{\"ok\":0,\"msg\":\"rate limit exceeded\"}", limited.getContentAsString());
        assertEquals("application/json", limited.getContentType());
        // 同 IP 不同租户：键不同 → 独立桶，不受租户 a 配额影响
        assertEquals(200, send(filter, request("tenant-b", "203.0.113.7")));
    }

    // ── 复合键：认证主体优先于 IP，且在租户内隔离 ────────────────

    @Test
    void authenticatedPrincipalGetsOwnBucketAndOverridesClientIp() throws Exception {
        RateLimitFilter filter = filterWithCapacity(1);
        authenticate("user-1");

        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
        // 同一主体换 IP 仍是同一个 principal 桶 → 限流
        assertEquals(429, send(filter, request("tenant-a", "198.51.100.9")));
        // 同租户不同主体 → 独立桶
        authenticate("user-2");
        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
        // 不同租户的同名主体 → 键含租户段，桶不同
        authenticate("user-1");
        assertEquals(200, send(filter, request("tenant-z", "203.0.113.7")));
    }

    @Test
    void anonymousAndAuthenticatedCallersUseSeparateBuckets() throws Exception {
        RateLimitFilter filter = filterWithCapacity(1);
        authenticate("user-1");

        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
        // 清掉认证后再发：匿名调用者（无 Authentication）走 ip 键，不与已认证主体的配额互斥
        SecurityContextHolder.clearContext();
        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
    }

    // ── 绕过与开关 ───────────────────────────────────────────────

    @Test
    void actuatorEndpointsBypassRateLimitingForHealthAndMetrics() throws Exception {
        RateLimitFilter filter = filterWithCapacity(1);

        for (int i = 0; i < 3; i++) {
            assertEquals(200, send(filter, request("tenant-a", "203.0.113.7", "/actuator/health")));
        }
    }

    @Test
    void disabledFilterPassesEverythingThrough() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(false);
        props.setCapacity(1);
        RateLimitFilter filter = new RateLimitFilter(props);

        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
    }

    // ── 空闲桶逐出：部分消耗的桶不被清掉 ─────────────────────────

    @Test
    void evictOnlyDropsFullyRefilledBucketsSoConsumedStateSurvivesSweep() throws Exception {
        RateLimitFilter filter = filterWithCapacity(1);

        assertEquals(200, send(filter, request("tenant-a", "203.0.113.7")));
        // 桶已耗尽（tokens < capacity）→ 逐出只清"已回满"的桶，限流状态保留
        filter.evictIdleBuckets();
        assertEquals(429, send(filter, request("tenant-a", "203.0.113.7")));
    }

    // ── resolveClientIp：反代场景取最右非私有跳（包私有静态方法） ──

    @Test
    void clientIpFallsBackToDirectAddrWhenNoForwardedHeader() {
        MockHttpServletRequest request = request("tenant-a", "203.0.113.7");

        assertEquals("203.0.113.7", RateLimitFilter.resolveClientIp(request));
    }

    @Test
    void clientIpBehindPrivateProxyPrefersRightmostNonPrivateHop() {
        MockHttpServletRequest request = request("tenant-a", "10.0.0.5");
        request.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.9");

        // 右起第一个非私有地址生效：恶意 caller 注入最左侧假 IP 无法轮换自己的桶
        assertEquals("203.0.113.9", RateLimitFilter.resolveClientIp(request));
    }

    @Test
    void clientIpIgnoresForwardedHeaderWhenDirectPeerIsPublic() {
        MockHttpServletRequest request = request("tenant-a", "93.184.216.34");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        // 直连对端已是公网地址 → XFF 不可信，维持直连地址
        assertEquals("93.184.216.34", RateLimitFilter.resolveClientIp(request));
    }

    @Test
    void clientIpFallsBackToDirectWhenAllHopsArePrivate() {
        MockHttpServletRequest request = request("tenant-a", "10.0.0.5");
        request.addHeader("X-Forwarded-For", "10.0.0.9, 192.168.1.1");

        assertEquals("10.0.0.5", RateLimitFilter.resolveClientIp(request));
    }

    @Test
    void clientIpTreats172Dot32AsPublicAndLoopbackAsPrivate() {
        // RFC1918 边界：172.16-31 私有，172.32+ 公有；127.0.0.1 直连视作代理
        MockHttpServletRequest boundary = request("tenant-a", "10.0.0.5");
        boundary.addHeader("X-Forwarded-For", "172.32.0.1");
        assertEquals("172.32.0.1", RateLimitFilter.resolveClientIp(boundary));

        MockHttpServletRequest loopback = request("tenant-a", "127.0.0.1");
        loopback.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("203.0.113.9", RateLimitFilter.resolveClientIp(loopback));
    }

    // ── 构造辅助 ─────────────────────────────────────────────────

    /** 长回填窗口：测试窗口内不会因 token 回填产生误放行。 */
    private RateLimitFilter filterWithCapacity(long capacity) {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setCapacity(capacity);
        props.setRefillTokens(1);
        props.setRefillSeconds(3600);
        return new RateLimitFilter(props);
    }

    private MockHttpServletRequest request(String tenant, String ip) {
        return request(tenant, ip, "/ai/chat");
    }

    private MockHttpServletRequest request(String tenant, String ip, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader(TenantContext.TENANT_HEADER, tenant);
        request.setRemoteAddr(ip);
        return request;
    }

    private void authenticate(String principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a"));
    }

    private MockHttpServletResponse send(RateLimitFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
