package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.ingestion.queue.IngestionQueue;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.security.FileSafetyScanner;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.HashUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * File: IngestionService.java
 * <p>
 * PDF 文档摄取服务：覆盖「上传 → 落库 → 队列分发 → 解析切分 → 向量入库」的完整链路。
 * <ul>
 *   <li>提交侧：{@code submitPdf} 做文件安全扫描、幂等键去重（客户端键 / 内容哈希自动键）、
 *       PDF 持久化与任务记录落库，并按队列后端决定是否立即投递；落库/投递失败时
 *       清理孤儿文件或将任务自愈为 RETRY，不留 PENDING 死任务；</li>
 *   <li>消费侧：{@code processQueuedJob} 以数据库乐观认领（{@code claimForRun}）防止多 worker
 *       重复消费，失败后按 attemptCount / maxRetries 判定 RETRY 或 FAILED，FAILED 时写入死信队列；</li>
 *   <li>解析侧：按页读取 PDF，经 {@link TokenTextSplitter} 切分，并为每个 chunk 注入
 *       tenant_id / chat_id / job_id / chunk_index 等检索与溯源元数据；</li>
 *   <li>可观测性：提交数、完成数、处理时长均按 status / tenant 打点上报 Micrometer。</li>
 * </ul>
 * 所有查询与写入均以租户为作用域（{@link TenantContext#normalize}），后台 worker 线程
 * 无 MDC 时必须显式传入 tenantId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final IngestionJobMapper ingestionJobMapper;
    private final VectorStore vectorStore;
    private final IngestionProperties ingestionProperties;
    private final VectorStoreProperties vectorStoreProperties;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;
    private final IngestionQueue ingestionQueue;
    private final FileSafetyScanner fileSafetyScanner;
    // Serializes SimpleVectorStore snapshot writes across the worker pool so that
    // concurrent jobs cannot interleave writes to the same snapshot file.
    private final Object snapshotLock = new Object();

    /**
     * 提交一份 PDF 摄取任务（同步受理、异步处理）。
     * <p>
     * 处理顺序：租户归一化 → 参数校验 → {@link FileSafetyScanner} 安全扫描 → 幂等键归一化
     * 与去重 → 文件落盘 → 任务记录入库（PENDING）→ 非 db_polling 后端时立即投递队列。
     * 幂等语义：优先按归一化幂等键查已有任务直接返回；并发下若插入撞唯一键，
     * 再查一次并以已存在任务为准，仅当确实查不到时才上抛异常。
     *
     * @param tenantId       租户 ID，内部经 {@link TenantContext#normalize} 归一化
     * @param chatId         会话 ID，必填
     * @param file           上传的 PDF 文件，必填且非空
     * @param idempotencyKey 客户端幂等键，可空；为空时按租户+会话+文件内容哈希自动生成
     * @param traceId        链路追踪 ID，透传至任务记录与队列消息
     * @return 已受理的任务记录（新建或幂等命中的既有任务）
     * @throws IllegalArgumentException chatId 为空或文件为空时抛出
     */
    public IngestionJob submitPdf(String tenantId, String chatId, MultipartFile file, String idempotencyKey, String traceId) {
        String normalizedTenantId = TenantContext.normalize(tenantId);
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        fileSafetyScanner.scan(file);

        String sourceName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "unknown.pdf";
        String normalizedKey = normalizeIdempotencyKey(normalizedTenantId, chatId, file, idempotencyKey);
        IngestionJob existing = ingestionJobMapper.findByIdempotencyKey(normalizedTenantId, normalizedKey);
        if (existing != null) {
            return existing;
        }

        String jobId = "job-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String filePath = persistFile(jobId, sourceName, file);
        IngestionJob job = IngestionJob.builder()
                .jobId(jobId)
                .tenantId(normalizedTenantId)
                .chatId(chatId)
                .sourceType("PDF")
                .sourceName(sourceName)
                .filePath(filePath)
                .idempotencyKey(normalizedKey)
                .status(IngestionJobStatus.PENDING)
                .traceId(traceId)
                .attemptCount(0)
                .maxRetries(ingestionProperties.getMaxRetries())
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            ingestionJobMapper.insert(job);
        } catch (DuplicateKeyException duplicateKeyException) {
            // 幂等键撞车：本任务注定被丢弃，先清掉刚落盘的孤儿文件再返回既有任务。
            deleteFileQuietly(filePath, jobId);
            IngestionJob concurrent = ingestionJobMapper.findByIdempotencyKey(normalizedTenantId, normalizedKey);
            if (concurrent != null) {
                return concurrent;
            }
            throw duplicateKeyException;
        } catch (RuntimeException ex) {
            // 插入失败：任务没有建立，刚写的 PDF 文件成为孤儿，清理后带着原因上抛。
            deleteFileQuietly(filePath, jobId);
            throw ex;
        }
        if (!"db_polling".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
            try {
                ingestionQueue.publishJob(job.getJobId(), traceId);
            } catch (RuntimeException ex) {
                // 任务行已落库但投不进队列（非 db_polling 后端不会被轮询扫到），
                // 置为 RETRY 交给重试扫描器稍后重新投递，而不是让任务永远停在 PENDING。
                log.warn("queue publish failed for job {}, scheduling retry: {}", job.getJobId(), describe(ex), ex);
                scheduleRetryAfterPublishFailure(job);
                throw new IllegalStateException("job " + job.getJobId()
                        + " accepted but queue publish failed, scheduled for retry: " + describe(ex), ex);
            }
        }
        Counter.builder("ingestion.jobs.submitted")
                .tag("source", "pdf")
                .tag("tenant", normalizedTenantId)
                .register(meterRegistry)
                .increment();
        return job;
    }

    /**
     * 队列投递失败后的自愈：把 PENDING 任务改写为 RETRY 并按 baseDelaySeconds
     * 设置下次重试时间，交给 {@link #enqueueReadyRetries} 重新投递。改写本身失败时
     * 仅告警（此时任务留在 PENDING，需要人工介入），不再向调用方叠加异常。
     */
    private void scheduleRetryAfterPublishFailure(IngestionJob job) {
        try {
            LocalDateTime retryAt = LocalDateTime.now().plusSeconds(Math.max(1, ingestionProperties.getBaseDelaySeconds()));
            ingestionJobMapper.updateTerminalState(
                    job.getJobId(),
                    IngestionJobStatus.RETRY,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    "queue publish failed; scheduled for retry",
                    retryAt
            );
        } catch (RuntimeException updateEx) {
            log.error("failed to reschedule job {} after queue publish failure", job.getJobId(), updateEx);
        }
    }

    /** Best-effort 删除刚落盘的孤儿文件；失败仅告警，不掩盖原始异常。 */
    private void deleteFileQuietly(String filePath, String jobId) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (Exception cleanupEx) {
            log.warn("failed to clean up orphan file {} for job {}", filePath, jobId, cleanupEx);
        }
    }

    /**
     * 按 jobId 查询单个摄取任务（租户隔离：SQL 按 tenantId 过滤）。
     *
     * @param tenantId 租户 ID
     * @param jobId    任务 ID
     * @return 任务记录；不存在或不属于该租户时返回 {@code null}
     */
    public IngestionJob getByJobId(String tenantId, String jobId) {
        return ingestionJobMapper.findByJobIdAndTenant(TenantContext.normalize(tenantId), jobId);
    }

    /**
     * 查询指定会话下最近的摄取任务列表。
     *
     * @param tenantId 租户 ID
     * @param chatId   会话 ID
     * @param limit    返回条数上限，小于 1 时按 1 处理、超过 100 时按 100 截断
     * @return 按时间倒序的任务记录
     */
    public List<IngestionJob> listByChatId(String tenantId, String chatId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return ingestionJobMapper.findLatestByChatId(TenantContext.normalize(tenantId), chatId, safeLimit);
    }

    /** 两层作用域取文档：先会话级，再全局 shared-corpus（引用溯源「展开原文」用）。 */
    public List<IngestionJob> listBySourceAcrossScopes(String tenantId, String chatId, String sourceName, int limit) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        // 先取足够多的候选再按来源名过滤（shared-corpus 里可能有多份文档）
        List<IngestionJob> scoped = ingestionJobMapper.findLatestByChatId(normalizedTenant, chatId, 50);
        List<IngestionJob> shared = ingestionJobMapper.findLatestByChatId(
                normalizedTenant, ragProperties.getSharedCorpusChatId(), 50);
        List<IngestionJob> merged = new ArrayList<>(scoped);
        merged.addAll(shared);
        String source = StringUtils.hasText(sourceName) ? sourceName : "";
        String sourceLower = source.toLowerCase(Locale.ROOT);
        return merged.stream()
                // 来源名可能是历史脏数据，null/空白记录不能在比较时 NPE，直接跳过
                .filter(job -> job != null && StringUtils.hasText(job.getSourceName()))
                .filter(job -> !StringUtils.hasText(source)
                        || job.getSourceName().equalsIgnoreCase(source)
                        || job.getSourceName().equalsIgnoreCase(source + ".pdf")
                        || job.getSourceName().toLowerCase(Locale.ROOT).startsWith(sourceLower))
                .limit(Math.min(Math.max(limit, 1), 100))
                .toList();
    }

    /**
     * 消费入口便捷重载：租户取自 MDC（{@link TenantContext#TENANT_REQUEST_ATTRIBUTE}），
     * 适用于经 HTTP 过滤器进入、MDC 中已带租户的调用路径。
     *
     * @param jobId   任务 ID
     * @param traceId 链路追踪 ID
     * @return 处理结果（是否认领成功、最新状态、错误信息）
     */
    public IngestionProcessResult processQueuedJob(String jobId, String traceId) {
        return processQueuedJob(jobId, null, traceId);
    }

    /**
     * 认领并处理一个待执行的摄取任务（worker 主流程）。
     * <p>
     * 流程：以 (jobId, tenantId) 做 SQL 乐观认领（置 RUNNING，防多 worker 重复消费）→
     * 解析入库 {@link #processPdfJob} → 成功写 SUCCEEDED 终态；失败时按
     * attemptCount 与 maxRetries 判定 RETRY（按 baseDelaySeconds × attempts 计算下次重试时间）
     * 或 FAILED（写入死信队列）。认领失败（claimed=0）表示任务已被其他 worker 抢占或状态不符，
     * 直接返回 picked=false。
     *
     * @param jobId    任务 ID，为空时直接返回 picked=false 的失败结果
     * @param tenantId 任务归属租户；为空时回退到 MDC。后台 worker 线程无 MDC，必须显式传入
     * @param traceId  链路追踪 ID
     * @return 处理结果：picked 表示本次是否实际认领并执行
     */
    public IngestionProcessResult processQueuedJob(String jobId, String tenantId, String traceId) {
        if (!StringUtils.hasText(jobId)) {
            return IngestionProcessResult.builder()
                    .picked(false)
                    .status(IngestionJobStatus.FAILED)
                    .jobId("")
                    .traceId(traceId)
                    .errorMessage("jobId missing")
                    .build();
        }
        // Tenant scoping is required for both call paths:
        //  - HTTP controllers reach here through the MDC-scoped TenantContext filter.
        //  - Background workers (Redis / RabbitMQ / db_polling) run on threads without
        //    an MDC, so they must pass the job's tenantId explicitly.
        // Falling back to MDC keeps existing call sites working while the SQL now
        // refuses to claim a job owned by another tenant.
        String claimTenantId = StringUtils.hasText(tenantId)
                ? TenantContext.normalize(tenantId)
                : TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        LocalDateTime now = LocalDateTime.now();
        int claimed = ingestionJobMapper.claimForRun(jobId, claimTenantId, IngestionJobStatus.RUNNING, now, now);
        if (claimed == 0) {
            return IngestionProcessResult.builder()
                    .picked(false)
                    .status(IngestionJobStatus.RUNNING)
                    .jobId(jobId)
                    .traceId(traceId)
                    .build();
        }

        IngestionJob running = ingestionJobMapper.findByJobId(jobId);
        if (running == null) {
            return IngestionProcessResult.builder()
                    .picked(false)
                    .status(IngestionJobStatus.FAILED)
                    .jobId(jobId)
                    .traceId(traceId)
                    .errorMessage("job not found after claim")
                    .build();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String tenantTag = TenantContext.normalize(running.getTenantId());
        try {
            processPdfJob(running);
            ingestionJobMapper.updateTerminalState(
                    running.getJobId(),
                    IngestionJobStatus.SUCCEEDED,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    null,
                    null
            );
            sample.stop(Timer.builder("ingestion.jobs.duration")
                    .tag("status", "succeeded")
                    .tag("tenant", tenantTag)
                    .register(meterRegistry));
            Counter.builder("ingestion.jobs.finished")
                    .tag("status", "succeeded")
                    .tag("tenant", tenantTag)
                    .register(meterRegistry)
                    .increment();
            return IngestionProcessResult.builder()
                    .picked(true)
                    .status(IngestionJobStatus.SUCCEEDED)
                    .jobId(running.getJobId())
                    .traceId(traceId)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to process ingestion job: {}", running.getJobId(), ex);
            // 错误信息带上异常类名，message 为空（如 NPE）时也能定位原因
            String error = truncateError(describe(ex));
            // 一次读库同时取尝试次数与重试上限；读不到时按保守默认值判定
            IngestionJob latest = ingestionJobMapper.findByJobId(running.getJobId());
            int attempts = latest != null && latest.getAttemptCount() != null ? latest.getAttemptCount() : 1;
            int maxRetries = latest != null && latest.getMaxRetries() != null
                    ? latest.getMaxRetries()
                    : ingestionProperties.getMaxRetries();
            IngestionJobStatus nextStatus = attempts < maxRetries ? IngestionJobStatus.RETRY : IngestionJobStatus.FAILED;
            try {
                LocalDateTime nextRetryAt = null;
                if (nextStatus == IngestionJobStatus.RETRY) {
                    int delaySeconds = ingestionProperties.getBaseDelaySeconds() * Math.max(1, attempts);
                    nextRetryAt = LocalDateTime.now().plusSeconds(delaySeconds);
                }
                ingestionJobMapper.updateTerminalState(
                        running.getJobId(),
                        nextStatus,
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        error,
                        nextRetryAt
                );
            } catch (Exception bookkeepingEx) {
                log.error("Failed to record {} state for ingestion job {} (original error: {})",
                        nextStatus, running.getJobId(), error, bookkeepingEx);
            }
            try {
                sample.stop(Timer.builder("ingestion.jobs.duration")
                        .tag("status", nextStatus.name().toLowerCase())
                        .tag("tenant", tenantTag)
                        .register(meterRegistry));
                Counter.builder("ingestion.jobs.finished")
                        .tag("status", nextStatus.name().toLowerCase())
                        .tag("tenant", tenantTag)
                        .register(meterRegistry)
                        .increment();
                if (nextStatus == IngestionJobStatus.FAILED) {
                    ingestionQueue.publishDlq(running.getJobId(), traceId, error);
                }
            } catch (Exception reportingEx) {
                log.warn("Failed to report metrics/dlq for ingestion job {}: {}",
                        running.getJobId(), describe(reportingEx), reportingEx);
            }
            return IngestionProcessResult.builder()
                    .picked(true)
                    .status(nextStatus)
                    .jobId(running.getJobId())
                    .traceId(traceId)
                    .errorMessage(error)
                    .build();
        }
    }

    /**
     * 扫描到期的 RETRY 任务并重新投递队列（供定时调度调用）。
     * <p>
     * db_polling 后端由轮询逻辑自行处理，此处直接返回 0；其余后端对每条到期任务
     * 先 CAS 式改写（{@code requeueRetry}，防并发重复投递），成功才发布队列消息。
     *
     * @param limit 本轮最多处理条数，小于 1 时按 1 处理
     * @return 实际重新投递成功的任务数
     */
    public int enqueueReadyRetries(int limit) {
        if ("db_polling".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
            return 0;
        }
        List<IngestionJob> ready = ingestionJobMapper.findReadyRetries(LocalDateTime.now(), Math.max(1, limit));
        int count = 0;
        for (IngestionJob job : ready) {
            if (job == null || !StringUtils.hasText(job.getJobId())) {
                continue;
            }
            try {
                int updated = ingestionJobMapper.requeueRetry(job.getJobId(), LocalDateTime.now());
                if (updated > 0) {
                    ingestionQueue.publishJob(job.getJobId(), job.getTraceId());
                    count++;
                }
            } catch (RuntimeException ex) {
                // 注意时序：requeueRetry 已把任务改回 PENDING 并清空 next_retry_at，
                // 此时投递失败若不回写，RETRY 扫描器将永远不再拾取该任务。
                log.warn("failed to re-enqueue retry job {}: {}", job.getJobId(), describe(ex), ex);
                restoreRetryAfterPublishFailure(job, ex);
            }
        }
        return count;
    }

    /**
     * 重投扫描中队列发布失败的自愈：把已被 {@code requeueRetry} 改回 PENDING 的任务
     * 重新置为 RETRY 并按 baseDelaySeconds 设置下次重试时间，等待下一轮扫描。
     * 回写本身失败时仅告警（任务留在 PENDING，需要人工介入），不向调用方叠加异常。
     */
    private void restoreRetryAfterPublishFailure(IngestionJob job, RuntimeException cause) {
        try {
            LocalDateTime retryAt = LocalDateTime.now().plusSeconds(Math.max(1, ingestionProperties.getBaseDelaySeconds()));
            ingestionJobMapper.updateTerminalState(
                    job.getJobId(),
                    IngestionJobStatus.RETRY,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    "queue publish failed after requeue: " + describe(cause),
                    retryAt
            );
        } catch (RuntimeException updateEx) {
            log.error("failed to restore RETRY state for job {}", job.getJobId(), updateEx);
        }
    }

    /**
     * 执行单个 PDF 任务的解析与向量入库：按页读取（每页一个 Document）→
     * {@link #splitDocuments} 切分并补元数据 → 写入向量库 → 必要时持久化
     * SimpleVectorStore 快照。文件缺失、无可提取文本、切分为空等边界问题
     * 以带具体原因的异常上抛，交由调用方走重试/失败流程。
     * <p>
     * 有意不做强制超时：解析与向量写入无法安全中断，超时后放任后台线程
     * 继续执行会在 FAILED/RETRY 判定之后追加写入，造成向量重复与快照竞态；
     * 外部嵌入调用的超时应由其 HTTP 客户端配置兜底。
     */
    private void processPdfJob(IngestionJob job) {
        if (job == null || !StringUtils.hasText(job.getFilePath())) {
            throw new IllegalStateException("job has no file path (jobId="
                    + (job == null ? "null" : job.getJobId()) + ")");
        }
        File source = new File(job.getFilePath());
        if (!source.exists() || !source.isFile()) {
            throw new IllegalStateException("PDF file missing: " + job.getFilePath());
        }
        List<Document> pages;
        try {
            PagePdfDocumentReader reader = new PagePdfDocumentReader(
                    new FileSystemResource(source),
                    PdfDocumentReaderConfig.builder()
                            .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                            .withPagesPerDocument(1)
                            .build()
            );
            pages = reader.read();
        } catch (RuntimeException ex) {
            // PDFBox 解析失败（损坏/加密/伪造文件）：包装出带文件路径的原因后上抛
            throw new IllegalStateException("failed to parse PDF " + job.getFilePath() + ": " + describe(ex), ex);
        }
        if (pages == null || pages.isEmpty()) {
            throw new IllegalStateException("PDF contains no extractable pages "
                    + "(corrupted file or scanned copy without text layer): " + job.getFilePath());
        }
        List<Document> chunks = splitDocuments(pages, job);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("PDF split into zero chunks "
                    + "(check rag.split.chunk-size / max-num-chunks config): job " + job.getJobId());
        }
        vectorStore.add(chunks);
        persistSimpleVectorStoreIfNeeded();
    }

    /**
     * 按配置（chunkSize / minChunkSize / maxNumChunks）将页面 Document 切分为 chunk，
     * 并以租户/会话/任务/来源/chunk 序号覆盖 chunk 元数据，供检索过滤与引用溯源使用。
     * 切分配置非法（非正值）时先给出可定位的异常，而不是让 splitter 抛出模糊错误。
     */
    private List<Document> splitDocuments(List<Document> pages, IngestionJob job) {
        int chunkSize = ragProperties.getSplit().getChunkSize();
        int maxNumChunks = ragProperties.getSplit().getMaxNumChunks();
        if (chunkSize <= 0 || maxNumChunks <= 0) {
            throw new IllegalStateException("invalid rag.split config: chunk-size=" + chunkSize
                    + ", max-num-chunks=" + maxNumChunks + " (both must be positive)");
        }
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(ragProperties.getSplit().getMinChunkSize())
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(maxNumChunks)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(pages);
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("tenant_id", TenantContext.normalize(job.getTenantId()));
            metadata.put("chat_id", job.getChatId());
            metadata.put("job_id", job.getJobId());
            metadata.put("file_name", job.getSourceName());
            metadata.put("source_type", job.getSourceType());
            metadata.put("chunk_index", i);
            chunk.getMetadata().clear();
            chunk.getMetadata().putAll(metadata);
        }
        return chunks;
    }

    /**
     * 仅当向量库为 SimpleVectorStore 且后端配置为 simple 时，将内存索引快照落盘。
     * 写入采用「临时文件 + 原子替换」（文件系统不支持 ATOMIC_MOVE 时退化为普通替换），
     * 并以 {@link #snapshotLock} 串行化，避免并发任务交叉写坏快照；失败仅告警不阻断主流程。
     */
    private void persistSimpleVectorStoreIfNeeded() {
        if (!(vectorStore instanceof SimpleVectorStore simpleVectorStore)) {
            return;
        }
        if (!"simple".equalsIgnoreCase(vectorStoreProperties.getBackend())) {
            return;
        }
        String storePath = vectorStoreProperties.getSimpleStorePath();
        if (!StringUtils.hasText(storePath)) {
            return;
        }
        try {
            Path path = Path.of(storePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            synchronized (snapshotLock) {
                // Write to a temp file and atomically replace the snapshot so a crash
                // or concurrent reader never observes a half-written file.
                simpleVectorStore.save(tmp.toFile());
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException atomicMoveNotSupported) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to persist SimpleVectorStore snapshot", e);
        }
    }

    /**
     * 将上传文件落盘到配置的存储目录，文件名为「{jobId}_{净化后原名}」
     * （非 [a-zA-Z0-9._-] 字符替换为下划线，防路径注入）。
     *
     * @return 落盘文件的绝对路径
     * @throws IllegalStateException 目录创建或文件写出失败时抛出
     */
    private String persistFile(String jobId, String sourceName, MultipartFile file) {
        String sanitized = sourceName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path root = Path.of(ingestionProperties.getStorageDir());
        Path target = root.resolve(jobId + "_" + sanitized);
        try {
            Files.createDirectories(root);
            file.transferTo(target);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded PDF to " + target
                    + " (storage dir: " + root + "): " + e.getMessage(), e);
        }
    }

    /**
     * 归一化幂等键：客户端提供了键则用 {@code "client:" + trim}；否则按
     * {@code "auto:" + sha256(tenant|chat|内容哈希)} 生成（读流失败时退化为
     * 文件名+大小的种子哈希，降级原因记录告警日志）。前缀区分人工键与自动键，
     * 避免碰撞。
     */
    private String normalizeIdempotencyKey(String tenantId, String chatId, MultipartFile file, String provided) {
        if (StringUtils.hasText(provided)) {
            return "client:" + provided.trim();
        }
        try {
            String contentHash = HashUtils.sha256Hex(file.getInputStream());
            return "auto:" + HashUtils.sha256Hex(tenantId + "|" + chatId + "|" + contentHash);
        } catch (IOException e) {
            log.warn("failed to hash uploaded content for idempotency key, "
                    + "falling back to name+size seed: {}", e.getMessage(), e);
            String seed = tenantId + "|" + chatId + "|" + file.getOriginalFilename() + "|" + file.getSize();
            return "auto:" + HashUtils.sha256Hex(seed);
        }
    }

    /** 截断错误信息至 1000 字符（防止超长异常撑爆错误字段），空/空白时返回占位文案。 */
    private String truncateError(String msg) {
        if (!StringUtils.hasText(msg)) {
            return "unknown error";
        }
        return msg.length() <= 1000 ? msg : msg.substring(0, 1000);
    }

    /** 人可读的异常描述：异常类名 + 消息；message 为空（如 NPE）时也不会退化成空串。 */
    private String describe(Throwable ex) {
        String message = ex == null ? null : ex.getMessage();
        String className = ex == null ? "unknown" : ex.getClass().getSimpleName();
        return StringUtils.hasText(message) ? className + ": " + message : className;
    }
}
