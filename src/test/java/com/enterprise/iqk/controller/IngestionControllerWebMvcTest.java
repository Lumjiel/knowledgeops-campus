package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = IngestionController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class IngestionControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestionService ingestionService;
    @MockBean
    private ChatHistoryRepository chatHistoryRepository;
    @MockBean
    private org.springframework.beans.factory.ObjectProvider<Tracer> tracerProvider;
    @MockBean
    private IngestionProperties ingestionProperties;

    @Test
    void shouldAcceptUploadJob() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", "application/pdf", "data".getBytes());
        when(ingestionProperties.getQueueBackend()).thenReturn("redis_stream");
        when(ingestionService.submitPdf(any(), any(), any(), any(), any())).thenReturn(IngestionJob.builder()
                .jobId("job-1")
                .tenantId("public")
                .chatId("chat-1")
                .sourceName("sample.pdf")
                .status(IngestionJobStatus.PENDING)
                .attemptCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .build());

        mockMvc.perform(multipart("/ingestion/upload/chat-1").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.job.jobId").value("job-1"))
                .andExpect(jsonPath("$.job.queueBackend").value("redis_stream"));
    }

    // ── GET /ingestion/jobs/{jobId}（api-recipes.md「Check the returned jobId」）──

    @Test
    void shouldReturnJobByIdForCurrentTenant() throws Exception {
        when(ingestionProperties.getQueueBackend()).thenReturn("redis_stream");
        when(ingestionService.getByJobId("public", "job-1")).thenReturn(IngestionJob.builder()
                .jobId("job-1")
                .tenantId("public")
                .chatId("chat-1")
                .sourceName("sample.pdf")
                .status(IngestionJobStatus.SUCCEEDED)
                .attemptCount(1)
                .maxRetries(3)
                .traceId("trace-1")
                .createdAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/ingestion/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.chatId").value("chat-1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.queueBackend").value("redis_stream"));

        // 租户来自 MDC（RequestContextFilter 在切片中被排除，缺省归一为 public）
        verify(ingestionService).getByJobId("public", "job-1");
    }

    @Test
    void shouldReturn404WhenJobNotFoundInTenantScope() throws Exception {
        when(ingestionService.getByJobId(eq("public"), eq("missing"))).thenReturn(null);

        mockMvc.perform(get("/ingestion/jobs/missing"))
                .andExpect(status().isNotFound());

        verify(ingestionService).getByJobId("public", "missing");
    }

    // ── GET /ingestion/jobs?chatId=&limit=（api-recipes.md「List recent jobs」）──

    @Test
    void shouldListRecentJobsForChatWithDefaultLimit() throws Exception {
        when(ingestionProperties.getQueueBackend()).thenReturn("redis_stream");
        when(ingestionService.listByChatId("public", "demo-rag", 20)).thenReturn(List.of(
                job("job-2", IngestionJobStatus.SUCCEEDED),
                job("job-1", IngestionJobStatus.PENDING)));

        mockMvc.perform(get("/ingestion/jobs").param("chatId", "demo-rag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].jobId").value("job-2"))
                .andExpect(jsonPath("$[1].jobId").value("job-1"))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].queueBackend").value("redis_stream"));

        // limit 未传 → 默认 20
        verify(ingestionService).listByChatId("public", "demo-rag", 20);
    }

    @Test
    void shouldClampListLimitAbove100ToHardCap() throws Exception {
        when(ingestionService.listByChatId("public", "demo-rag", 100)).thenReturn(List.of());

        mockMvc.perform(get("/ingestion/jobs").param("chatId", "demo-rag").param("limit", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(ingestionService).listByChatId("public", "demo-rag", 100);
    }

    @Test
    void shouldResolveTenantFromRequestContextMdcForJobQueries() throws Exception {
        // RequestContextFilter 在切片中被排除，按它在生产的行为直接种 MDC
        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "acme");
        try {
            when(ingestionProperties.getQueueBackend()).thenReturn("redis_stream");
            when(ingestionService.listByChatId("acme", "demo-rag", 5)).thenReturn(List.of(
                    job("job-3", IngestionJobStatus.FAILED)));

            mockMvc.perform(get("/ingestion/jobs").param("chatId", "demo-rag").param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].jobId").value("job-3"))
                    .andExpect(jsonPath("$[0].status").value("FAILED"));
        } finally {
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        }
    }

    private IngestionJob job(String jobId, IngestionJobStatus status) {
        return IngestionJob.builder()
                .jobId(jobId)
                .tenantId("public")
                .chatId("demo-rag")
                .sourceName("heat-safety-policy.pdf")
                .status(status)
                .attemptCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
