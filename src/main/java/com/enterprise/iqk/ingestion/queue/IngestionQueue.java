package com.enterprise.iqk.ingestion.queue;

import java.time.Duration;
import java.util.List;

/**
 * File: IngestionQueue.java
 * <p>
 * 队列后端抽象（docs/architecture-enterprise.md「Queue backend abstraction」）：
 * 屏蔽 Redis Stream / RabbitMQ / db_polling（Noop）三种实现的差异。
 * 读写两套模型并存：readBatch/ack/claimIdle 仅 Redis Stream 拉模式使用；
 * RabbitMQ 走推送式 listener，publish 之外的拉式方法为空实现。
 */
public interface IngestionQueue {
    /** 投递一个摄取任务（消息体只带 jobId 与 traceId，任务数据以 DB 为准）。 */
    void publishJob(String jobId, String traceId);

    /** 投递终态失败任务到死信队列（DLQ），reason 为截断后的错误信息。 */
    void publishDlq(String jobId, String traceId, String reason);

    /** 拉模式：以消费者身份批量读取消息（Redis Stream 语义，block 为阻塞时长）。 */
    List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block);

    /** 拉模式：确认消息处理完成。 */
    void ack(String consumerName, String recordId);

    /** 初始化消费者组（Redis Stream 语义，RabbitMQ/Noop 为空实现）。 */
    void ensureConsumerGroup();

    /**
     * Reclaims messages that were delivered to another consumer but never acked
     * (idle for at least {@code minIdle}). Returns an empty list when the backend
     * does not support reclaiming.
     */
    default List<IngestionQueueMessage> claimIdle(String consumerName, Duration minIdle, int maxCount) {
        return List.of();
    }
}
