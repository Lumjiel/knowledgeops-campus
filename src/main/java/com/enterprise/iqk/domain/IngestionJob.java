package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * File: IngestionJob.java
 * <p>
 * {@code ingestion_job} 表记录：一次异步文档摄取任务的完整状态
 * （幂等键、重试计数、下一次重试时间等），对应 docs/architecture-enterprise.md
 * 的「Data Ownership: ingestion_job (async job state)」与可靠性设计。
 */
@TableName("ingestion_job")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionJob {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 全局唯一任务 ID（形如 job-&lt;uuid&gt;，队列消息仅携带此 ID）。 */
    private String jobId;
    /** 租户 ID（归一化后），消费认领按此隔离。 */
    private String tenantId;
    /** 归属会话 ID。 */
    private String chatId;
    /** 来源类型，当前固定 "PDF"。 */
    private String sourceType;
    /** 原始文件名。 */
    private String sourceName;
    /** 服务端落盘文件绝对路径。 */
    private String filePath;
    /** 归一化幂等键（client: 前缀 / auto: 前缀），租户内唯一，用于幂等去重。 */
    private String idempotencyKey;
    /** 任务状态：PENDING / RUNNING / RETRY / SUCCEEDED / FAILED。 */
    private IngestionJobStatus status;
    /** 链路追踪 ID，透传至队列消息与日志。 */
    private String traceId;
    /** 已尝试次数（认领时 SQL 自增）。 */
    private Integer attemptCount;
    /** 最大重试上限（提交时取自 IngestionProperties）。 */
    private Integer maxRetries;
    /** 最近一次失败的错误信息（截断至 1000 字符）。 */
    private String errorMessage;
    /** RETRY 状态下的下次重试时间（baseDelaySeconds × attempts 计算）。 */
    private LocalDateTime nextRetryAt;
    /** 开始处理时间（认领时写入）。 */
    private LocalDateTime startedAt;
    /** 终态时间（SUCCEEDED / FAILED / RETRY 判定时写入）。 */
    private LocalDateTime finishedAt;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
