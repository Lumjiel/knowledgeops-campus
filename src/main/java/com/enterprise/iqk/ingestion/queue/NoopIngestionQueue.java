package com.enterprise.iqk.ingestion.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * File: NoopIngestionQueue.java
 * <p>
 * db_polling 后端的空队列实现：不做任何投递/消费，任务由轮询调度直接
 * 扫描 {@code ingestion_job} 表驱动（见 IngestionService 的 db_polling 分支）。
 * 仅在 {@code app.ingestion.queue-backend=db_polling} 时装配。
 */
@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "queue-backend", havingValue = "db_polling")
public class NoopIngestionQueue implements IngestionQueue {
    @Override
    public void publishJob(String jobId, String traceId) {
        // noop
    }

    @Override
    public void publishDlq(String jobId, String traceId, String reason) {
        // noop
    }

    @Override
    public List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block) {
        return List.of();
    }

    @Override
    public void ack(String consumerName, String recordId) {
        // noop
    }

    @Override
    public void ensureConsumerGroup() {
        // noop
    }
}
