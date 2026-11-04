package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.vo.IngestionJobVO;
import com.enterprise.iqk.domain.vo.IngestionSubmitVO;
import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.ingestion.IngestionProcessResult;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * File: IngestionController.java
 * <p>
 * 文档摄取对外 REST 端点（前缀 {@code /ingestion}）：PDF 上传受理（幂等键经
 * {@code X-Idempotency-Key} 请求头传入）、任务查询、以及 ADMIN 手动触发消费/重投。
 * 所有查询与处理均按当前租户（{@code TenantContext}）隔离。
 */
@RestController
@RequestMapping("/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ObjectProvider<Tracer> tracerProvider;
    private final IngestionProperties ingestionProperties;

    /**
     * 受理一份 PDF 上传：同步落库返回任务记录，实际解析/向量化异步执行。
     * 路由级权限 PERM_INGESTION_WRITE（见 SecurityConfiguration）。
     *
     * @param chatId        会话 ID（路径参数），任务归属会话
     * @param file          上传的 PDF 文件
     * @param idempotencyKey 可选幂等键；缺省时服务端按租户+会话+内容哈希自动生成
     * @return 受理结果（含任务 VO，状态 PENDING 或幂等命中的既有任务）
     */
    @PostMapping("/upload/{chatId}")
    public IngestionSubmitVO uploadPdf(@PathVariable String chatId,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        String traceId = currentTraceId();
        IngestionJob job = ingestionService.submitPdf(currentTenantId(), chatId, file, idempotencyKey, traceId);
        chatHistoryRepository.save("pdf", chatId);
        return IngestionSubmitVO.builder()
                .ok(1)
                .msg("accepted")
                .job(toVO(job))
                .build();
    }

    /** 查询单个摄取任务（租户隔离）；不存在时 404。 */
    @GetMapping("/jobs/{jobId}")
    public IngestionJobVO getJob(@PathVariable String jobId) {
        IngestionJob job = ingestionService.getByJobId(currentTenantId(), jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return toVO(job);
    }

    /** 查询会话下最近的摄取任务列表，limit 被夹取到 [1, 100]。 */
    @GetMapping("/jobs")
    public List<IngestionJobVO> getJobsByChatId(@RequestParam("chatId") String chatId,
                                                @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ingestionService.listByChatId(currentTenantId(), chatId, Math.max(1, Math.min(limit, 100)))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * ADMIN 手动处理入口（{@code @PreAuthorize hasRole('ADMIN')}）：
     * 不带 jobId 时扫描并重投到期的 RETRY 任务；带 jobId 时立即消费该任务
     * （租户隔离校验，任务不存在时 404）。
     *
     * @return 处理结果：重投数量、processed（已执行）或 empty（被其他 worker 抢占）
     */
    @PostMapping("/jobs/process")
    @PreAuthorize("hasRole('ADMIN')")
    public IngestionSubmitVO processOne(@RequestParam(value = "jobId", required = false) String jobId) {
        if (!StringUtils.hasText(jobId)) {
            int enqueued = ingestionService.enqueueReadyRetries(20);
            return IngestionSubmitVO.builder()
                    .ok(1)
                    .msg("requeue=" + enqueued)
                    .job(null)
                    .build();
        }
        IngestionJob scopedJob = ingestionService.getByJobId(currentTenantId(), jobId);
        if (scopedJob == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        IngestionProcessResult processed = ingestionService.processQueuedJob(jobId, currentTraceId());
        boolean picked = processed.isPicked();
        return IngestionSubmitVO.builder()
                .ok(1)
                .msg(picked ? "processed" : "empty")
                .job(null)
                .build();
    }

    /** 任务记录 → 对外 VO（附当前队列后端标识，便于前端提示处理时延）。 */
    private IngestionJobVO toVO(IngestionJob job) {
        return IngestionJobVO.builder()
                .jobId(job.getJobId())
                .chatId(job.getChatId())
                .sourceName(job.getSourceName())
                .status(job.getStatus())
                .attemptCount(job.getAttemptCount())
                .maxRetries(job.getMaxRetries())
                .errorMessage(job.getErrorMessage())
                .traceId(job.getTraceId())
                .queueBackend(ingestionProperties.getQueueBackend())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }

    /** 从 Micrometer Tracer 当前 span 取 traceId；无 tracer / 无 span 时返回空串。 */
    private String currentTraceId() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return "";
        }
        var span = tracer.currentSpan();
        if (span == null) {
            return "";
        }
        String traceId = span.context().traceId();
        return StringUtils.hasText(traceId) ? traceId : "";
    }

    /** 当前租户 ID：取 MDC 中的租户并归一化（缺省 "public"）。 */
    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }
}
