package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.ingestion.queue.IngestionQueue;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.security.FileSafetyScanner;
import com.enterprise.iqk.util.HashUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IngestionService 的纯单元测试。真实协作者：RagProperties（sharedCorpusChatId
 * 默认 shared-corpus）、SimpleMeterRegistry（断言提交/完成计数与耗时 Timer）。
 *
 * <p>一个有意的取舍：processQueuedJob 成功路径需要 PagePdfDocumentReader 真实
 * 解析 PDF（PDFBox），单元测试手造 PDF 过于脆弱，故失败/重试/DLQ 编排全部用
 * 「文件缺失」这一确定性异常驱动（processPdfJob 首行校验）；PDF 解析本身归
 * spring-ai/PDFBox 的集成测试。claim 为 0（被其他 worker 抢走）时不查 job。
 */
class IngestionServiceTest {

    private static final String TENANT_REQUEST_ATTRIBUTE = "tenant_id";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private IngestionService buildService(IngestionJobMapper mapper,
                                          org.springframework.ai.vectorstore.VectorStore vectorStore,
                                          IngestionProperties ingestionProperties,
                                          VectorStoreProperties vectorStoreProperties,
                                          IngestionQueue queue,
                                          FileSafetyScanner scanner) {
        return buildService(mapper, vectorStore, ingestionProperties, vectorStoreProperties,
                new RagProperties(), new SimpleMeterRegistry(), queue, scanner);
    }

    private IngestionService buildService(IngestionJobMapper mapper,
                                          org.springframework.ai.vectorstore.VectorStore vectorStore,
                                          IngestionProperties ingestionProperties,
                                          VectorStoreProperties vectorStoreProperties,
                                          RagProperties ragProperties,
                                          MeterRegistry meterRegistry,
                                          IngestionQueue queue,
                                          FileSafetyScanner scanner) {
        return new IngestionService(
                mapper,
                vectorStore,
                ingestionProperties,
                vectorStoreProperties,
                ragProperties,
                meterRegistry,
                queue,
                scanner
        );
    }

    // ── submitPdf()：参数校验、幂等键与并发竞争 ──────────────────

    @Test
    void shouldRejectSubmitWithoutChatIdOrFile() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), mock(IngestionQueue.class), scanner);

        assertThatThrownBy(() -> service.submitPdf("public", "  ", pdf("a.pdf", "pdf"), null, "trace-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chatId is required");
        assertThatThrownBy(() -> service.submitPdf("public", "chat-1", null, null, "trace-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file is required");
        assertThatThrownBy(() -> service.submitPdf("public", "chat-1", pdf("a.pdf", ""), null, "trace-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file is required");
        // 安全校验在读幂等键之前，非法请求根本不落库
        verifyNoInteractions(mapper, scanner);
    }

    @Test
    void shouldPropagateScannerRejectionBeforeAnyPersistence() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        MockMultipartFile file = pdf("evil.pdf", "pdf");
        doThrow(new SecurityException("危险文件类型")).when(scanner).scan(file);

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), queue, scanner);

        assertThatThrownBy(() -> service.submitPdf("public", "chat-1", file, null, "trace-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("危险文件类型");
        verify(mapper, never()).findByIdempotencyKey(anyString(), anyString());
        verify(mapper, never()).insert(any(IngestionJob.class));
        verify(queue, never()).publishJob(anyString(), anyString());
    }

    @Test
    void shouldReuseExistingJobByIdempotencyKey() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        org.springframework.ai.vectorstore.VectorStore vectorStore = mock(org.springframework.ai.vectorstore.VectorStore.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);

        IngestionProperties ingestionProperties = new IngestionProperties();
        VectorStoreProperties vectorStoreProperties = new VectorStoreProperties();
        IngestionJob existing = IngestionJob.builder()
                .jobId("job-x")
                .tenantId("public")
                .chatId("chat-1")
                .status(com.enterprise.iqk.domain.enums.IngestionJobStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        when(mapper.findByIdempotencyKey("public", "client:k1")).thenReturn(existing);

        IngestionService service = buildService(mapper, vectorStore, ingestionProperties, vectorStoreProperties, queue, scanner);

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "pdf".getBytes());
        IngestionJob job = service.submitPdf("public", "chat-1", file, "k1", "trace-1");
        assertEquals("job-x", job.getJobId());
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.<IngestionJob>any());
    }

    @Test
    void shouldCreateNewJob() throws Exception {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        org.springframework.ai.vectorstore.VectorStore vectorStore = mock(org.springframework.ai.vectorstore.VectorStore.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        when(mapper.findByIdempotencyKey(anyString(), anyString())).thenReturn(null);

        IngestionProperties ingestionProperties = new IngestionProperties();
        Path temp = Files.createTempDirectory("ingestion-test");
        ingestionProperties.setStorageDir(temp.toString());
        VectorStoreProperties vectorStoreProperties = new VectorStoreProperties();

        IngestionService service = buildService(mapper, vectorStore, ingestionProperties, vectorStoreProperties, queue, scanner);
        MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", "application/pdf", "dummy".getBytes());
        IngestionJob job = service.submitPdf("public", "chat-2", file, null, "trace-x");
        assertNotNull(job.getJobId());
        assertEquals("public", job.getTenantId());
        assertEquals("chat-2", job.getChatId());
        verify(mapper).insert(org.mockito.ArgumentMatchers.<IngestionJob>any());
        verify(queue).publishJob(any(), any());
    }

    @Test
    void shouldComputeAutoIdempotencyKeyFromContentHashAndPersistFile() throws Exception {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        when(mapper.findByIdempotencyKey(anyString(), anyString())).thenReturn(null);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        IngestionProperties ingestionProperties = new IngestionProperties();
        Path temp = Files.createTempDirectory("ingestion-test");
        ingestionProperties.setStorageDir(temp.toString());

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                ingestionProperties, new VectorStoreProperties(), new RagProperties(), registry, queue, scanner);
        MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", "application/pdf", "dummy".getBytes());

        IngestionJob job = service.submitPdf("public", "chat-2", file, null, "trace-x");

        // 自动幂等键 = auto:sha256(tenant|chatId|sha256(文件内容))
        String contentHash = HashUtils.sha256Hex(
                new ByteArrayInputStream("dummy".getBytes(StandardCharsets.UTF_8)));
        String expectedKey = "auto:" + HashUtils.sha256Hex("public|chat-2|" + contentHash);
        verify(mapper).findByIdempotencyKey("public", expectedKey);

        ArgumentCaptor<IngestionJob> jobCaptor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(mapper).insert(jobCaptor.capture());
        IngestionJob inserted = jobCaptor.getValue();
        assertThat(inserted.getIdempotencyKey()).isEqualTo(expectedKey);
        assertThat(inserted.getStatus()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(inserted.getMaxRetries()).isEqualTo(3);
        assertThat(inserted.getSourceType()).isEqualTo("PDF");
        assertThat(inserted.getSourceName()).isEqualTo("sample.pdf");
        assertThat(inserted.getTraceId()).isEqualTo("trace-x");
        assertThat(inserted.getAttemptCount()).isZero();
        // 文件已按 jobId_原始名 落盘，内容一致
        Path stored = Path.of(inserted.getFilePath());
        assertThat(stored.getFileName().toString()).startsWith(inserted.getJobId()).endsWith("sample.pdf");
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).isEqualTo("dummy".getBytes(StandardCharsets.UTF_8));

        assertThat(registry.counter("ingestion.jobs.submitted", "source", "pdf", "tenant", "public").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldFallBackToUnknownNameWhenOriginalFilenameBlank() throws Exception {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        when(mapper.findByIdempotencyKey(anyString(), anyString())).thenReturn(null);
        IngestionProperties ingestionProperties = new IngestionProperties();
        ingestionProperties.setStorageDir(Files.createTempDirectory("ingestion-test").toString());

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                ingestionProperties, new VectorStoreProperties(), mock(IngestionQueue.class), mock(FileSafetyScanner.class));
        MockMultipartFile file = new MockMultipartFile("file", "", "application/pdf", "pdf".getBytes());

        IngestionJob job = service.submitPdf("public", "chat-1", file, null, "trace-1");

        assertThat(job.getSourceName()).isEqualTo("unknown.pdf");
    }

    @Test
    void shouldReturnConcurrentJobWhenInsertRacesOnDuplicateKey() throws Exception {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        IngestionProperties ingestionProperties = new IngestionProperties();
        ingestionProperties.setStorageDir(Files.createTempDirectory("ingestion-test").toString());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        IngestionJob concurrent = IngestionJob.builder().jobId("job-race").tenantId("public").build();
        // 第一次探测无记录 → 插入撞唯一键 → 重查命中并发任务
        when(mapper.findByIdempotencyKey(eq("public"), anyString())).thenReturn(null, concurrent);
        when(mapper.insert(any(IngestionJob.class))).thenThrow(new DuplicateKeyException("uk_ingestion_idem"));

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                ingestionProperties, new VectorStoreProperties(), new RagProperties(), registry, queue, scanner);
        IngestionJob job = service.submitPdf("public", "chat-1", pdf("a.pdf", "pdf"), null, "trace-1");

        assertThat(job.getJobId()).isEqualTo("job-race");
        // 插入在入队之前失败，任务不得重复发布
        verify(queue, never()).publishJob(anyString(), anyString());
        assertThat(registry.find("ingestion.jobs.submitted").counter()).isNull();
    }

    @Test
    void shouldSkipQueuePublishWhenBackendIsDbPolling() throws Exception {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        when(mapper.findByIdempotencyKey(anyString(), anyString())).thenReturn(null);
        IngestionProperties ingestionProperties = new IngestionProperties();
        ingestionProperties.setQueueBackend("db_polling");
        ingestionProperties.setStorageDir(Files.createTempDirectory("ingestion-test").toString());

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                ingestionProperties, new VectorStoreProperties(), queue, mock(FileSafetyScanner.class));

        service.submitPdf("public", "chat-1", pdf("a.pdf", "pdf"), "k1", "trace-1");

        verify(mapper).insert(any(IngestionJob.class));
        verify(queue, never()).publishJob(anyString(), anyString());
    }

    // ── 查询接口：租户归一化与 limit 钳制 ────────────────────────

    @Test
    void shouldNormalizeTenantForLookups() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionJob job = IngestionJob.builder().jobId("job-1").build();
        when(mapper.findByJobIdAndTenant("public", "job-1")).thenReturn(job);
        when(mapper.findLatestByChatId(eq("public"), eq("chat-1"), anyInt())).thenReturn(java.util.List.of(job));

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), mock(IngestionQueue.class), mock(FileSafetyScanner.class));

        assertThat(service.getByJobId(null, "job-1")).isSameAs(job);
        assertThat(service.listByChatId(" public ", "chat-1", 0)).hasSize(1);
        // limit 钳制到 ≥1
        verify(mapper).findLatestByChatId("public", "chat-1", 1);
    }

    @Test
    void shouldMergeChatAndSharedCorpusScopesWithSourceFilter() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionJob scoped = IngestionJob.builder().jobId("j1").sourceName("报告.pdf").chatId("chat-1").build();
        IngestionJob other = IngestionJob.builder().jobId("j2").sourceName("其他.pdf").chatId("chat-1").build();
        IngestionJob shared = IngestionJob.builder().jobId("j3").sourceName("报告 v2.pdf").chatId("shared-corpus").build();
        when(mapper.findLatestByChatId("public", "chat-1", 50)).thenReturn(java.util.List.of(scoped, other));
        when(mapper.findLatestByChatId("public", "shared-corpus", 50)).thenReturn(java.util.List.of(shared));

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), mock(IngestionQueue.class), mock(FileSafetyScanner.class));

        // “报告” 命中 报告.pdf（等值）与 报告 v2.pdf（前缀），大小写不敏感
        java.util.List<IngestionJob> filtered = service.listBySourceAcrossScopes(null, "chat-1", "报告", 10);
        assertThat(filtered).extracting(IngestionJob::getJobId).containsExactly("j1", "j3");

        // 来源名为空 → 不过滤；limit 钳制到 ≥1
        java.util.List<IngestionJob> all = service.listBySourceAcrossScopes(null, "chat-1", " ", 0);
        assertThat(all).extracting(IngestionJob::getJobId).containsExactly("j1");
        verify(mapper).findLatestByChatId("public", "chat-1", 50);
        verify(mapper).findLatestByChatId("public", "shared-corpus", 50);
    }

    // ── processQueuedJob()：认领、重试与 DLQ 编排 ────────────────

    @Test
    void shouldReturnFailedResultWhenJobIdBlank() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), mock(IngestionQueue.class), mock(FileSafetyScanner.class));

        IngestionProcessResult result = service.processQueuedJob(" ", null, "trace-1");

        assertFalse(result.isPicked());
        assertEquals(IngestionJobStatus.FAILED, result.getStatus());
        assertEquals("jobId missing", result.getErrorMessage());
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldNotPickMissingQueuedJob() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        org.springframework.ai.vectorstore.VectorStore vectorStore = mock(org.springframework.ai.vectorstore.VectorStore.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        when(mapper.claimForRun(any(), any(), any(), any(), any())).thenReturn(0);

        IngestionService service = buildService(
                mapper, vectorStore, new IngestionProperties(), new VectorStoreProperties(), queue, scanner
        );
        IngestionProcessResult result = service.processQueuedJob("job-1", "trace-a");
        assertFalse(result.isPicked());
        assertEquals(IngestionJobStatus.RUNNING, result.getStatus());
    }

    @Test
    void shouldClaimUnderExplicitTenantThenMdcFallback() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        when(mapper.claimForRun(anyString(), anyString(), any(), any(), any())).thenReturn(0);

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), mock(IngestionQueue.class), mock(FileSafetyScanner.class));

        // 显式 tenantId 优先（后台 worker 无 MDC 的调用路径），并做归一化
        service.processQueuedJob("job-1", " t1 ", "trace-1");
        verify(mapper).claimForRun(eq("job-1"), eq("t1"), eq(IngestionJobStatus.RUNNING), any(), any());

        // 未传 tenantId 时回落 MDC；MDC 也为空则回落 public
        service.processQueuedJob("job-2", null, "trace-2");
        verify(mapper).claimForRun(eq("job-2"), eq("public"), eq(IngestionJobStatus.RUNNING), any(), any());
        MDC.put(TENANT_REQUEST_ATTRIBUTE, "t-mdc");
        service.processQueuedJob("job-3", null, "trace-3");
        verify(mapper).claimForRun(eq("job-3"), eq("t-mdc"), eq(IngestionJobStatus.RUNNING), any(), any());
    }

    @Test
    void shouldReportFailedWhenJobMissingAfterClaim() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        when(mapper.claimForRun(anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.findByJobId("job-1")).thenReturn(null);
        IngestionQueue queue = mock(IngestionQueue.class);

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), queue, mock(FileSafetyScanner.class));

        IngestionProcessResult result = service.processQueuedJob("job-1", "t1", "trace-1");

        assertFalse(result.isPicked());
        assertEquals(IngestionJobStatus.FAILED, result.getStatus());
        assertEquals("job not found after claim", result.getErrorMessage());
        verify(mapper, never()).updateTerminalState(anyString(), any(), any(), any(), any(), any());
        verify(queue, never()).publishDlq(anyString(), anyString(), anyString());
    }

    @Test
    void shouldScheduleRetryWhenAttemptsRemain() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionQueue queue = mock(IngestionQueue.class);
        when(mapper.claimForRun(anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.findByJobId("job-1")).thenReturn(missingFileJob(1, 3));

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), new RagProperties(), registry, queue, mock(FileSafetyScanner.class));

        IngestionProcessResult result = service.processQueuedJob("job-1", "t1", "trace-1");

        // attempt 1 < maxRetries 3 → RETRY，退避 = baseDelay(10) * attempt
        assertEquals(IngestionJobStatus.RETRY, result.getStatus());
        assertTrue(result.isPicked());

        ArgumentCaptor<IngestionJobStatus> statusCaptor = ArgumentCaptor.forClass(IngestionJobStatus.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> retryAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).updateTerminalState(eq("job-1"), statusCaptor.capture(), any(), any(),
                errorCaptor.capture(), retryAtCaptor.capture());
        assertEquals(IngestionJobStatus.RETRY, statusCaptor.getValue());
        assertThat(errorCaptor.getValue()).contains("PDF file missing");
        assertThat(retryAtCaptor.getValue())
                .isAfter(LocalDateTime.now().plusSeconds(8))
                .isBefore(LocalDateTime.now().plusSeconds(15));

        verify(queue, never()).publishDlq(anyString(), anyString(), anyString());
        assertThat(registry.counter("ingestion.jobs.finished", "status", "retry", "tenant", "t1").count())
                .isEqualTo(1.0);
        assertThat(registry.get("ingestion.jobs.duration").tags("status", "retry", "tenant", "t1").timer().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldPublishDlqWhenRetriesExhausted() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionQueue queue = mock(IngestionQueue.class);
        when(mapper.claimForRun(anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.findByJobId("job-1")).thenReturn(missingFileJob(3, 3));

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), new RagProperties(), registry, queue, mock(FileSafetyScanner.class));

        IngestionProcessResult result = service.processQueuedJob("job-1", "t1", "trace-1");

        // attempt 3 >= maxRetries 3 → FAILED 并进 DLQ
        assertEquals(IngestionJobStatus.FAILED, result.getStatus());
        ArgumentCaptor<String> dlqErrorCaptor = ArgumentCaptor.forClass(String.class);
        verify(queue).publishDlq(eq("job-1"), eq("trace-1"), dlqErrorCaptor.capture());
        assertThat(dlqErrorCaptor.getValue()).contains("PDF file missing");
        assertThat(result.getErrorMessage()).contains("PDF file missing");

        ArgumentCaptor<IngestionJobStatus> statusCaptor = ArgumentCaptor.forClass(IngestionJobStatus.class);
        ArgumentCaptor<LocalDateTime> retryAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).updateTerminalState(eq("job-1"), statusCaptor.capture(), any(), any(), any(), retryAtCaptor.capture());
        assertEquals(IngestionJobStatus.FAILED, statusCaptor.getValue());
        assertThat(retryAtCaptor.getValue()).isNull();

        assertThat(registry.counter("ingestion.jobs.finished", "status", "failed", "tenant", "t1").count())
                .isEqualTo(1.0);
        assertThat(registry.get("ingestion.jobs.duration").tags("status", "failed", "tenant", "t1").timer().count())
                .isEqualTo(1.0);
        verify(mapper, times(2)).findByJobId("job-1"); // 认领后一次、失败后重读最新 attempt 一次
    }

    // ── enqueueReadyRetries()：到期重试的重新入队 ────────────────

    @Test
    void shouldSkipRetryScanWhenBackendIsDbPolling() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionProperties ingestionProperties = new IngestionProperties();
        ingestionProperties.setQueueBackend("db_polling");

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                ingestionProperties, new VectorStoreProperties(), mock(IngestionQueue.class), mock(FileSafetyScanner.class));

        assertThat(service.enqueueReadyRetries(5)).isZero();
        verify(mapper, never()).findReadyRetries(any(), anyInt());
    }

    @Test
    void shouldPublishOnlySuccessfullyRequeuedRetries() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        IngestionJob ready1 = IngestionJob.builder().jobId("job-r1").traceId("trace-1").build();
        IngestionJob ready2 = IngestionJob.builder().jobId("job-r2").traceId("trace-2").build();
        when(mapper.findReadyRetries(any(), eq(1))).thenReturn(java.util.List.of(ready1, ready2));
        when(mapper.requeueRetry(eq("job-r1"), any())).thenReturn(1);
        when(mapper.requeueRetry(eq("job-r2"), any())).thenReturn(0);

        IngestionService service = buildService(mapper, mock(org.springframework.ai.vectorstore.VectorStore.class),
                new IngestionProperties(), new VectorStoreProperties(), queue, mock(FileSafetyScanner.class));

        // limit 钳制到 ≥1；仅 requeue 成功的任务才发布，返回成功数
        int count = service.enqueueReadyRetries(0);

        assertEquals(1, count);
        verify(queue).publishJob("job-r1", "trace-1");
        verify(queue, never()).publishJob(eq("job-r2"), anyString());
    }

    // ── 测试脚手架 ──────────────────────────────────────────────

    private MockMultipartFile pdf(String name, String content) {
        return new MockMultipartFile("file", name, "application/pdf", content.getBytes(StandardCharsets.UTF_8));
    }

    /** filePath 指向不存在的文件 → processPdfJob 首行抛 IllegalStateException，驱动失败编排。 */
    private IngestionJob missingFileJob(int attemptCount, int maxRetries) {
        return IngestionJob.builder()
                .jobId("job-1")
                .tenantId("t1")
                .chatId("chat-1")
                .sourceType("PDF")
                .sourceName("missing.pdf")
                .filePath("/nonexistent/missing-" + attemptCount + ".pdf")
                .status(IngestionJobStatus.RUNNING)
                .attemptCount(attemptCount)
                .maxRetries(maxRetries)
                .build();
    }
}
