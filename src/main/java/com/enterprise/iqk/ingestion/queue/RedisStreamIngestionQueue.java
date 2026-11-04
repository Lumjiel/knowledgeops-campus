package com.enterprise.iqk.ingestion.queue;

import com.enterprise.iqk.config.properties.IngestionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File: RedisStreamIngestionQueue.java
 * <p>
 * Redis Stream 队列后端（默认，{@code app.ingestion.queue-backend=redis_stream} 时装配）：
 * 消费者组拉模式 + 手动 ack，支持 {@link #claimIdle} 认领其他消费者超时未 ack 的
 * 消息（防 worker 崩溃后消息滞留 pending）。死信为独立 Stream。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "queue-backend", havingValue = "redis_stream")
@RequiredArgsConstructor
public class RedisStreamIngestionQueue implements IngestionQueue {

    private final StringRedisTemplate redisTemplate;
    private final IngestionProperties ingestionProperties;

    @Override
    public void publishJob(String jobId, String traceId) {
        Map<String, String> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("publishedAt", String.valueOf(Instant.now().toEpochMilli()));
        redisTemplate.opsForStream().add(ingestionProperties.getRedis().getStreamKey(), body);
    }

    @Override
    public void publishDlq(String jobId, String traceId, String reason) {
        Map<String, String> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("reason", reason == null ? "" : reason);
        body.put("publishedAt", String.valueOf(Instant.now().toEpochMilli()));
        redisTemplate.opsForStream().add(ingestionProperties.getRedis().getDlqStreamKey(), body);
    }

    @Override
    public List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty()
                .count(Math.max(1, batchSize))
                .block(block == null ? Duration.ofSeconds(2) : block);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(
                        Consumer.from(ingestionProperties.getRedis().getConsumerGroup(), consumerName),
                        options,
                        StreamOffset.create(ingestionProperties.getRedis().getStreamKey(), ReadOffset.lastConsumed())
                );
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream().map(record -> IngestionQueueMessage.builder()
                .recordId(record.getId().getValue())
                .jobId(valueAsString(record.getValue().get("jobId")))
                .traceId(valueAsString(record.getValue().get("traceId")))
                .build()).toList();
    }

    @Override
    public void ack(String consumerName, String recordId) {
        if (!StringUtils.hasText(recordId)) {
            return;
        }
        redisTemplate.opsForStream().acknowledge(
                ingestionProperties.getRedis().getStreamKey(),
                ingestionProperties.getRedis().getConsumerGroup(),
                RecordId.of(recordId)
        );
    }

    /**
     * 认领闲置消息：扫描 pending 列表中「最后一次投递距今 ≥ minIdle」的消息，
     * XCLAIM 转移到当前消费者名下（minIdle 须为正，否则直接返回空）。
     * 用于 worker 重启/崩溃后恢复滞留消息，防止任务丢失。
     */
    @Override
    public List<IngestionQueueMessage> claimIdle(String consumerName, Duration minIdle, int maxCount) {
        if (!StringUtils.hasText(consumerName) || minIdle == null || minIdle.isZero() || minIdle.isNegative()) {
            return Collections.emptyList();
        }
        String streamKey = ingestionProperties.getRedis().getStreamKey();
        String group = ingestionProperties.getRedis().getConsumerGroup();
        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, group);
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return Collections.emptyList();
        }
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(streamKey, group, Range.unbounded(), Math.max(1, maxCount));
        if (pending == null || pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecordId> idleIds = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                idleIds.add(message.getId());
            }
        }
        if (idleIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                .claim(streamKey, group, consumerName, minIdle, idleIds.toArray(RecordId[]::new));
        if (claimed == null || claimed.isEmpty()) {
            return Collections.emptyList();
        }
        return claimed.stream().map(record -> IngestionQueueMessage.builder()
                .recordId(record.getId().getValue())
                .jobId(valueAsString(record.getValue().get("jobId")))
                .traceId(valueAsString(record.getValue().get("traceId")))
                .build()).toList();
    }

    /**
     * 确保消费者组存在：先向 Stream 写一条初始化消息确保 Stream 存在，
     * 再创建消费者组；BUSYGROUP（组已存在）之外异常仅 debug 记录，不阻断启动。
     */
    @Override
    public void ensureConsumerGroup() {
        String streamKey = ingestionProperties.getRedis().getStreamKey();
        String group = ingestionProperties.getRedis().getConsumerGroup();
        try {
            // ensure stream exists
            redisTemplate.opsForStream().add(streamKey, Map.of("init", "1"));
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), group);
            log.info("Created redis stream consumer group: {}", group);
        } catch (Exception e) {
            if (!e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group create skipped: {}", e.getMessage());
            }
        }
    }

    private String valueAsString(Object val) {
        return val == null ? "" : String.valueOf(val);
    }
}
