package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File: IngestionProperties.java
 * <p>
 * 摄取链路配置（前缀 {@code app.ingestion}）：队列后端选择、worker 参数、
 * 重试/退避、上传存储目录，以及 Redis Stream / RabbitMQ 各自的拓扑参数。
 */
@Data
@ConfigurationProperties(prefix = "app.ingestion")
public class IngestionProperties {
    /** 队列后端：redis_stream（默认）/ rabbitmq / db_polling。 */
    private String queueBackend = "redis_stream";
    /** 是否启用后台 worker 消费（关闭时仅受理任务，供联调/灰度）。 */
    private boolean workerEnabled = true;
    /** db_polling / 重投调度轮询间隔（毫秒）。 */
    private long pollIntervalMs = 2000;
    /** 单任务最大重试次数，超出后判 FAILED 并入 DLQ。 */
    private int maxRetries = 3;
    /** 重试基础退避秒数，实际延迟 = baseDelaySeconds × 已尝试次数。 */
    private int baseDelaySeconds = 10;
    /** 上传文件落盘目录。 */
    private String storageDir = "./data/uploads";
    /** worker 并发数（RabbitMQ listener concurrency / 拉模式线程数）。 */
    private int workerCount = 3;
    /** Redis Stream 后端参数。 */
    private Redis redis = new Redis();
    /** RabbitMQ 后端拓扑参数。 */
    private Rabbit rabbit = new Rabbit();

    /** Redis Stream 参数：主/DLQ Stream key、消费者组、批量与闲置认领阈值。 */
    @Data
    public static class Redis {
        /** 任务 Stream key。 */
        private String streamKey = "ingestion:jobs";
        /** 死信 Stream key。 */
        private String dlqStreamKey = "ingestion:dlq";
        /** 消费者组名。 */
        private String consumerGroup = "ingestion_group";
        /** 消费者名前缀（实际消费者为 prefix-序号）。 */
        private String consumerPrefix = "worker";
        /** 单次 readBatch 拉取条数。 */
        private int readBatchSize = 10;
        /** claimIdle 认定的闲置毫秒阈值。 */
        private long claimIdleMs = 30000;
    }

    /** RabbitMQ 参数：主交换机/路由键/队列与 DLQ 拓扑。 */
    @Data
    public static class Rabbit {
        /** 主交换机。 */
        private String exchange = "ingestion.exchange";
        /** 主路由键。 */
        private String routingKey = "ingestion.jobs";
        /** 主队列。 */
        private String queue = "ingestion.jobs.queue";
        /** 死信交换机。 */
        private String dlqExchange = "ingestion.dlx";
        /** 死信路由键。 */
        private String dlqRoutingKey = "ingestion.jobs.dlq";
        /** 死信队列。 */
        private String dlqQueue = "ingestion.jobs.dlq.queue";
    }
}
