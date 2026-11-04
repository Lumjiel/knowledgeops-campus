package com.enterprise.iqk.ingestion.queue;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.ingestion.IngestionProcessResult;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * File: RabbitMqIngestionListener.java
 * <p>
 * RabbitMQ 摄取任务消费者（{@code queue-backend=rabbitmq} 时装配）：
 * 监听主队列（并发度/开关由配置决定），反序列化 payload 后委托
 * {@link IngestionService#processQueuedJob}。worker 线程无 MDC，
 * 租户从任务记录本身读取后显式传入，保证认领 SQL 的租户隔离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion", name = "queue-backend", havingValue = "rabbitmq")
public class RabbitMqIngestionListener {

    private final IngestionService ingestionService;
    private final IngestionProperties ingestionProperties;
    private final IngestionJobMapper ingestionJobMapper;

    /**
     * 队列消费入口：payload 缺 jobId 时告警丢弃；否则从 DB 读取任务归属租户
     * （消息本身不携带租户，防伪造）后执行认领-处理-重试/死信全流程。
     * 处理异常不向外抛——失败已在 processQueuedJob 内落为 RETRY/FAILED，
     * 抛出只会触发 RabbitMQ 无谓的重新投递。
     */
    @RabbitListener(
            queues = "${app.ingestion.rabbit.queue}",
            concurrency = "${app.ingestion.worker-count:3}",
            autoStartup = "${app.ingestion.worker-enabled:true}"
    )
    public void consume(Map<String, Object> payload) {
        String jobId = payload == null ? "" : asString(payload.get("jobId"));
        String traceId = payload == null ? "" : asString(payload.get("traceId"));
        if (!StringUtils.hasText(jobId)) {
            log.warn("Skip rabbit ingestion message without jobId: {}", payload);
            return;
        }
        // Read tenant from the job itself; threads here have no MDC and the SQL
        // now requires the owning tenant to claim the row.
        IngestionJob job = ingestionJobMapper.findByJobId(jobId);
        String ownerTenant = job == null ? null : job.getTenantId();
        IngestionProcessResult result = ingestionService.processQueuedJob(jobId, ownerTenant, traceId);
        if (result.getStatus() != null) {
            log.debug("Rabbit ingestion processed. backend={}, jobId={}, status={}",
                    ingestionProperties.getQueueBackend(), jobId, result.getStatus());
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
