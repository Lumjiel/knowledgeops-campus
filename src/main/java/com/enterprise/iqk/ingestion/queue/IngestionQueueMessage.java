package com.enterprise.iqk.ingestion.queue;

import lombok.Builder;
import lombok.Data;

/**
 * File: IngestionQueueMessage.java
 * <p>
 * 队列消息的统一载体：任务 ID + 链路 ID + 后端记录 ID（Redis Stream 的
 * recordId 用于 ack；RabbitMQ/Noop 场景下无记录 ID 概念）。
 */
@Data
@Builder
public class IngestionQueueMessage {
    /** 后端记录 ID（Redis Stream RecordId），ack 时回传；其他后端为空。 */
    private String recordId;
    /** 摄取任务 ID。 */
    private String jobId;
    /** 链路追踪 ID。 */
    private String traceId;
}
