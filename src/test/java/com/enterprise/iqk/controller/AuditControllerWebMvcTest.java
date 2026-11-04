package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.AuditLog;
import com.enterprise.iqk.mapper.AuditLogMapper;
import com.enterprise.iqk.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuditController 的 WebMvc 切片测试（api-recipes.md 的 /audit/logs 菜谱）。
 * 覆盖 limit 默认值与 [1,200] 钳制、租户来源（MDC，与 RequestContextFilter
 * 种入的键一致）以及日志体序列化。端点带 @PreAuthorize(PERM_AUDIT_READ)，
 * 测试统一用 @WithMockUser 授予该权限以兼容两种方法安全装配方式。
 */
@WebMvcTest(value = AuditController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "PERM_AUDIT_READ")
class AuditControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogMapper auditLogMapper;

    private AuditLog auditLog(long id, String path, int statusCode) {
        return AuditLog.builder()
                .id(id)
                .requestId("req-" + id)
                .tenantId("public")
                .method("GET")
                .path(path)
                .statusCode(statusCode)
                .durationMs(12L)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    @Test
    void shouldReturnLatestAuditLogsWithDefaultLimit() throws Exception {
        when(auditLogMapper.latestByTenant("public", 50)).thenReturn(List.of(
                auditLog(2, "/ai/react/chat", 200),
                auditLog(1, "/ai/chat", 200)));

        mockMvc.perform(get("/audit/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].requestId").value("req-2"))
                .andExpect(jsonPath("$[0].path").value("/ai/react/chat"))
                .andExpect(jsonPath("$[1].path").value("/ai/chat"));

        // 不传 limit → 默认 50；MDC 无 tenant_id → 默认租户 public
        verify(auditLogMapper).latestByTenant("public", 50);
    }

    @Test
    void shouldClampLimitAbove200ToHardCap() throws Exception {
        when(auditLogMapper.latestByTenant("public", 200)).thenReturn(List.of());

        mockMvc.perform(get("/audit/logs").param("limit", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(auditLogMapper).latestByTenant("public", 200);
    }

    @Test
    void shouldClampLimitBelow1ToMinimum() throws Exception {
        when(auditLogMapper.latestByTenant("public", 1)).thenReturn(List.of(auditLog(1, "/audit/logs", 200)));

        mockMvc.perform(get("/audit/logs").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value("req-1"));

        verify(auditLogMapper).latestByTenant("public", 1);
    }

    @Test
    void shouldResolveTenantFromRequestContextMdc() throws Exception {
        // RequestContextFilter 在切片中被排除，按它在生产的行为直接种 MDC
        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "acme");
        try {
            when(auditLogMapper.latestByTenant("acme", 50)).thenReturn(List.of());

            mockMvc.perform(get("/audit/logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));

            verify(auditLogMapper).latestByTenant(eq("acme"), eq(50));
        } finally {
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        }
    }
}
