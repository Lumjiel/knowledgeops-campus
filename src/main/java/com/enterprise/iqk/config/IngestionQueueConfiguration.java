package com.enterprise.iqk.config;

import com.enterprise.iqk.config.properties.IngestionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * File: IngestionQueueConfiguration.java
 * <p>
 * RabbitMQ 拓扑声明（{@code queue-backend=rabbitmq} 时装配）：主交换机/队列 + DLQ
 * 交换机/队列，主队列配置 x-dead-letter-exchange/routing-key，使被拒绝或过期的
 * 消息自动转入死信队列（对应 docs/architecture-enterprise.md 的 DLQ sink 设计）。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion", name = "queue-backend", havingValue = "rabbitmq")
public class IngestionQueueConfiguration {

    private final IngestionProperties ingestionProperties;

    /** 声明主/DLQ 交换机、持久化队列及绑定关系（幂等，已存在时复用）。 */
    @Bean
    public Declarables ingestionRabbitDeclarables() {
        IngestionProperties.Rabbit rabbit = ingestionProperties.getRabbit();
        DirectExchange mainExchange = new DirectExchange(rabbit.getExchange(), true, false);
        DirectExchange dlqExchange = new DirectExchange(rabbit.getDlqExchange(), true, false);
        Queue mainQueue = QueueBuilder.durable(rabbit.getQueue())
                .withArgument("x-dead-letter-exchange", rabbit.getDlqExchange())
                .withArgument("x-dead-letter-routing-key", rabbit.getDlqRoutingKey())
                .build();
        Queue dlqQueue = QueueBuilder.durable(rabbit.getDlqQueue()).build();

        return new Declarables(
                mainExchange,
                dlqExchange,
                mainQueue,
                dlqQueue,
                BindingBuilder.bind(mainQueue).to(mainExchange).with(rabbit.getRoutingKey()),
                BindingBuilder.bind(dlqQueue).to(dlqExchange).with(rabbit.getDlqRoutingKey())
        );
    }
}
