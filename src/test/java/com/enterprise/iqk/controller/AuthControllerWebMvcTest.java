package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.security.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AuthController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyAuthService apiKeyAuthService;
    @MockBean
    private ApiKeyLifecycleService apiKeyLifecycleService;
    @MockBean
    private JwtService jwtService;
    @MockBean
    private RefreshTokenService refreshTokenService;
    @MockBean
    private PermissionService permissionService;
    @MockBean
    private SecurityProperties securityProperties;

    private static final String GUEST_PERMISSIONS = "chat:read,chat:write,session:read,session:write,feedback:write,rag:read";

    private AuthIdentity identity(String principal, String tenantId) {
        return AuthIdentity.builder()
                .principal(principal)
                .roles(List.of("ADMIN"))
                .permissions(List.of("chat:write"))
                .source("api_key")
                .tenantId(tenantId)
                .build();
    }

    private void stubTokenIssuance(String tenantId) {
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write"));
        when(jwtService.issueToken(any(), any(), any(), eq(tenantId))).thenReturn("jwt-token");
        when(refreshTokenService.issue(any(), any(), eq(tenantId)))
                .thenReturn(new RefreshTokenService.RefreshTokenIssueResult("refresh-token", tenantId, LocalDateTime.now().plusDays(7)));
        when(securityProperties.getJwtExpireMinutes()).thenReturn(120);
    }

    @Test
    void shouldIssueAccessAndRefreshTokens() throws Exception {
        when(apiKeyAuthService.authenticate("abc")).thenReturn(identity("tester", "tenant-a"));
        stubTokenIssuance("tenant-a");

        mockMvc.perform(post("/auth/token").header("X-API-Key", "abc").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.expiresInSeconds").value(7200))
                .andExpect(jsonPath("$.refreshWillExpireSoon").value(false));
    }

    @Test
    void shouldRejectInvalidApiKey() throws Exception {
        when(apiKeyAuthService.authenticate("bad")).thenReturn(null);

        mockMvc.perform(post("/auth/token").header("X-API-Key", "bad").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("invalid api key"))
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(jwtService, never()).issueToken(any(), any(), any(), any());
        verify(refreshTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void shouldRejectTenantMismatchForApiKey() throws Exception {
        when(apiKeyAuthService.authenticate("abc")).thenReturn(identity("tester", "tenant-a"));

        mockMvc.perform(post("/auth/token")
                        .header("X-API-Key", "abc")
                        .header(TenantContext.TENANT_HEADER, "other-tenant")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("tenant mismatch for api key"));

        verify(jwtService, never()).issueToken(any(), any(), any(), any());
        verify(jwtService, never()).issueToken(any(), any(), any(), any(), anyLong());
        verify(refreshTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void shouldFallBackToDefaultTenantWhenIdentityTenantBlank() throws Exception {
        // normalize(blank) -> DEFAULT_TENANT("public"), and a matching "public"
        // header must still be accepted.
        when(apiKeyAuthService.authenticate("abc")).thenReturn(identity("tester", "  "));
        stubTokenIssuance("public");

        mockMvc.perform(post("/auth/token")
                        .header("X-API-Key", "abc")
                        .header(TenantContext.TENANT_HEADER, "public")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.tenantId").value("public"));

        verify(jwtService).issueToken(eq("tester"), any(), any(), eq("public"));
    }

    @Test
    void shouldSetRefreshCookieWithSecurityAttributes() throws Exception {
        when(apiKeyAuthService.authenticate("abc")).thenReturn(identity("tester", "tenant-a"));
        stubTokenIssuance("tenant-a");

        mockMvc.perform(post("/auth/token").header("X-API-Key", "abc").contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().stringValues("Set-Cookie",
                        hasItem(containsString(AuthController.REFRESH_COOKIE + "=refresh-token")),
                        hasItem(containsString("Path=/auth")),
                        hasItem(containsString("HttpOnly")),
                        hasItem(containsString("SameSite=Strict")),
                        hasItem(containsString("Secure"))));
    }

    @Test
    void shouldPreferRefreshTokenHeaderOverCookie() throws Exception {
        when(refreshTokenService.consume("header-token")).thenReturn(identity("tester", "tenant-a"));
        stubTokenIssuance("tenant-a");

        mockMvc.perform(post("/auth/refresh")
                        .header("X-Refresh-Token", "header-token")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE, "cookie-token"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.token").value("jwt-token"));

        verify(refreshTokenService).consume("header-token");
        verify(refreshTokenService, never()).consume("cookie-token");
    }

    @Test
    void shouldFallBackToRefreshCookieWhenHeaderAbsent() throws Exception {
        when(refreshTokenService.consume("cookie-token")).thenReturn(identity("tester", "tenant-a"));
        stubTokenIssuance("tenant-a");

        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE, "cookie-token"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1));

        verify(refreshTokenService).consume("cookie-token");
    }

    @Test
    void shouldRejectInvalidRefreshToken() throws Exception {
        when(refreshTokenService.consume(any())).thenReturn(null);

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("invalid refresh token"))
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(jwtService, never()).issueToken(any(), any(), any(), any());
    }

    @Test
    void shouldFlagRefreshTokenExpiringSoon() throws Exception {
        when(refreshTokenService.consume("header-token")).thenReturn(identity("tester", "tenant-a"));
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write"));
        when(jwtService.issueToken(any(), any(), any(), eq("tenant-a"))).thenReturn("jwt-token");
        // Expires within REFRESH_EXPIRE_SOON_DAYS(2) -> refreshWillExpireSoon=true.
        when(refreshTokenService.issue(any(), any(), eq("tenant-a")))
                .thenReturn(new RefreshTokenService.RefreshTokenIssueResult("refresh-token", "tenant-a", LocalDateTime.now().plusDays(1)));
        when(securityProperties.getJwtExpireMinutes()).thenReturn(120);

        mockMvc.perform(post("/auth/refresh").header("X-Refresh-Token", "header-token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.refreshWillExpireSoon").value(true));
    }

    @Test
    void shouldIssueGuestTokenWithShortExpiry() throws Exception {
        when(jwtService.issueToken(startsWith("guest-"), eq(List.of("GUEST")),
                eq(List.of("chat:read", "chat:write", "session:read", "session:write", "feedback:write", "rag:read")),
                eq("public"), eq(120L))).thenReturn("guest-jwt");

        mockMvc.perform(post("/auth/guest").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.token").value("guest-jwt"))
                .andExpect(jsonPath("$.tenantId").value("public"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void shouldUseForwardedIpForGuestPrincipal() throws Exception {
        when(jwtService.issueToken(startsWith("guest-"), eq(List.of("GUEST")),
                eq(List.of("chat:read", "chat:write", "session:read", "session:write", "feedback:write", "rag:read")),
                eq("public"), eq(120L))).thenReturn("guest-jwt");

        // Two requests from the same X-Forwarded-For ip must resolve to the
        // same principal (stable hash of ip+date).
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/auth/guest").header("X-Forwarded-For", "10.0.0.1, 10.0.0.2").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(1));
        }
        verify(jwtService, times(2)).issueToken(startsWith("guest-"), eq(List.of("GUEST")),
                eq(List.of("chat:read", "chat:write", "session:read", "session:write", "feedback:write", "rag:read")),
                eq("public"), eq(120L));
        org.mockito.ArgumentCaptor<String> principal = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jwtService, times(2)).issueToken(principal.capture(), any(), any(), any(), anyLong());
        org.junit.jupiter.api.Assertions.assertEquals(principal.getAllValues().get(0), principal.getAllValues().get(1));
    }

    @Test
    void shouldRateLimitGuestTokensPerIp() throws Exception {
        when(jwtService.issueToken(any(), any(), any(), any(), anyLong())).thenReturn("guest-jwt");

        // First GUEST_LIMIT_PER_HOUR (30) requests from one ip succeed...
        for (int i = 0; i < 30; i++) {
            expectGuestOk(post("/auth/guest").header("X-Forwarded-For", "203.0.113.7").contentType(MediaType.APPLICATION_JSON));
        }
        // ...the 31st from the same ip is rejected, while another ip is unaffected.
        expectGuestLimited(post("/auth/guest").header("X-Forwarded-For", "203.0.113.7").contentType(MediaType.APPLICATION_JSON));
        expectGuestOk(post("/auth/guest").header("X-Forwarded-For", "203.0.113.8").contentType(MediaType.APPLICATION_JSON));
    }

    private void expectGuestOk(ResultActions request) throws Exception {
        request.andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.token").value("guest-jwt"));
    }

    private void expectGuestLimited(ResultActions request) throws Exception {
        request.andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("too many guest tokens from this ip, try later"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void shouldIssueApiKeyWithDefaultRole() throws Exception {
        when(apiKeyLifecycleService.issue("app-key", "USER", "public"))
                .thenReturn(new ApiKeyLifecycleService.ApiKeyIssueResult("raw-key", "app-key", "public", LocalDateTime.now().plusDays(30)));

        mockMvc.perform(post("/auth/api-keys").param("keyName", "app-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.keyName").value("app-key"))
                .andExpect(jsonPath("$.rawApiKey").value("raw-key"))
                .andExpect(jsonPath("$.tenantId").value("public"));

        verify(apiKeyLifecycleService).issue("app-key", "USER", "public");
    }

    @Test
    void shouldIssueApiKeyWithTenantFromRequestContext() throws Exception {
        // RequestContextFilter is excluded, so seed the MDC the same way it does.
        org.slf4j.MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "tenant-b");
        try {
            when(apiKeyLifecycleService.issue("app-key", "ADMIN", "tenant-b"))
                    .thenReturn(new ApiKeyLifecycleService.ApiKeyIssueResult("raw-key", "app-key", "tenant-b", LocalDateTime.now().plusDays(30)));

            mockMvc.perform(post("/auth/api-keys").param("keyName", "app-key").param("role", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(1))
                    .andExpect(jsonPath("$.tenantId").value("tenant-b"));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    void shouldRotateApiKey() throws Exception {
        when(apiKeyLifecycleService.rotate("legacy", "manual", "public"))
                .thenReturn(new ApiKeyLifecycleService.ApiKeyIssueResult("new-raw", "legacy-v2", "public", LocalDateTime.now().plusDays(30)));

        mockMvc.perform(post("/auth/api-keys/rotate")
                        .param("keyName", "legacy")
                        .param("reason", "manual")
                        .param("tenantId", "public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg").value("rotated"))
                .andExpect(jsonPath("$.rawApiKey").value("new-raw"))
                .andExpect(jsonPath("$.tenantId").value("public"));
    }

    @Test
    void shouldRevokeApiKeyWithDefaultReason() throws Exception {
        mockMvc.perform(post("/auth/api-keys/revoke").param("keyName", "legacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg").value("revoked"))
                .andExpect(jsonPath("$.keyName").value("legacy"))
                .andExpect(jsonPath("$.tenantId").value("public"))
                .andExpect(jsonPath("$.rawApiKey").doesNotExist());

        verify(apiKeyLifecycleService).revoke("legacy", "manual revoke", "public");
    }

    // —— 错误处理与边界校验强化 ——

    @Test
    void shouldRejectBlankApiKeyHeaderWithReason() throws Exception {
        // 缺失/空白 X-API-Key 不再经全局兜底处理器变成 500，而是 ok=0 并说明原因。
        mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("invalid api key: blank X-API-Key header"));

        verify(apiKeyAuthService, never()).authenticate(any());
    }

    @Test
    void shouldReturnOk0WhenAuthenticationBackendThrows() throws Exception {
        // 认证后端异常（如存储超时）降级为 ok=0，且不签发任何凭证。
        when(apiKeyAuthService.authenticate("abc")).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/auth/token").header("X-API-Key", "abc").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("invalid api key: authentication backend error, try later"));

        verify(jwtService, never()).issueToken(any(), any(), any(), any());
        verify(refreshTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void shouldReturnOk0WhenTokenIssuanceFailsMidway() throws Exception {
        // 部分失败：认证通过但刷新令牌签发失败 —— 不得设置 Cookie，也不得返回 ok=1。
        when(apiKeyAuthService.authenticate("abc")).thenReturn(identity("tester", "tenant-a"));
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write"));
        when(jwtService.issueToken(any(), any(), any(), eq("tenant-a"))).thenReturn("jwt-token");
        when(refreshTokenService.issue(any(), any(), eq("tenant-a"))).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/auth/token").header("X-API-Key", "abc").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("token issuance failed: credential backend error, no token was issued"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void shouldReturnOk0WhenRefreshValidationBackendThrows() throws Exception {
        when(refreshTokenService.consume(any())).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("refresh failed: refresh token validation backend error, try later"));

        verify(jwtService, never()).issueToken(any(), any(), any(), any());
    }

    @Test
    void shouldExplainPartialFailureWhenRefreshIssueFailsAfterConsume() throws Exception {
        // 部分失败：旧刷新令牌已被一次性消费而新凭证未签出，msg 必须讲清后果。
        when(refreshTokenService.consume("header-token")).thenReturn(identity("tester", "tenant-a"));
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write"));
        when(jwtService.issueToken(any(), any(), any(), eq("tenant-a"))).thenReturn("jwt-token");
        when(refreshTokenService.issue(any(), any(), eq("tenant-a"))).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/auth/refresh").header("X-Refresh-Token", "header-token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("refresh failed: token backend error; the consumed refresh token is no longer valid, re-authenticate with your api key"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void shouldReturnOk0WhenGuestSigningBackendThrows() throws Exception {
        when(jwtService.issueToken(any(), any(), any(), any(), anyLong())).thenThrow(new RuntimeException("sign error"));

        mockMvc.perform(post("/auth/guest").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("guest token issuance failed: token signing backend error, try later"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void shouldRejectBlankKeyNameWithReason() throws Exception {
        mockMvc.perform(post("/auth/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("keyName is required"));

        verify(apiKeyLifecycleService, never()).issue(any(), any(), any());
    }

    @Test
    void shouldRejectOverlongKeyNameWithReason() throws Exception {
        mockMvc.perform(post("/auth/api-keys").param("keyName", "k".repeat(129)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("keyName must not exceed 128 characters"));

        verify(apiKeyLifecycleService, never()).issue(any(), any(), any());
    }

    @Test
    void shouldRejectDuplicateKeyNameWithServiceReason() throws Exception {
        // 服务端业务性拒绝（IllegalArgumentException）降级为 ok=0 并保留原因。
        when(apiKeyLifecycleService.issue(eq("dup"), eq("USER"), any()))
                .thenThrow(new IllegalArgumentException("active api key already exists for keyName"));

        mockMvc.perform(post("/auth/api-keys").param("keyName", "dup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("issue api key 'dup' failed: active api key already exists for keyName"));
    }

    @Test
    void shouldReturnOk0WhenIssueBackendThrows() throws Exception {
        when(apiKeyLifecycleService.issue(eq("boom"), eq("USER"), any())).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/auth/api-keys").param("keyName", "boom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("issue api key 'boom' failed: credential storage backend error, try later"));
    }

    @Test
    void shouldRejectRotateMissingKeyWithServiceReason() throws Exception {
        when(apiKeyLifecycleService.rotate(eq("ghost"), eq("manual"), any()))
                .thenThrow(new IllegalArgumentException("active api key not found"));

        mockMvc.perform(post("/auth/api-keys/rotate").param("keyName", "ghost").param("reason", "manual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("rotate api key 'ghost' failed: active api key not found"));
    }

    @Test
    void shouldWarnPartialFailureWhenRotateBackendThrows() throws Exception {
        // 部分失败：rotate = 撤销旧键 + 签发新键，中途异常必须提示旧键可能已被撤销。
        when(apiKeyLifecycleService.rotate(eq("legacy"), eq("manual"), any())).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/auth/api-keys/rotate").param("keyName", "legacy").param("reason", "manual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("rotate api key 'legacy' failed: credential storage backend error;"
                        + " the old key may already be revoked, verify its status before retrying"));
    }

    @Test
    void shouldRejectRevokeMissingKeyWithServiceReason() throws Exception {
        doThrow(new IllegalArgumentException("active api key not found"))
                .when(apiKeyLifecycleService).revoke(eq("ghost"), eq("manual revoke"), any());

        mockMvc.perform(post("/auth/api-keys/revoke").param("keyName", "ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("revoke api key 'ghost' failed: active api key not found"));
    }
}
