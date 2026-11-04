package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File: RateLimitProperties.java
 * <p>
 * 限流配置（前缀 {@code app.rate-limit}）：令牌桶容量与填充速率。
 * 对应文档建议「为 chat 与 ingestion 设置独立限流」——当前为单档全局速率，
 * 分端点限流需在此扩展维度。
 */
@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    /** 是否启用限流过滤器。 */
    private boolean enabled = true;
    /** 桶容量（突发上限，令牌数）。 */
    private long capacity = 60;
    /** 每个填充周期的补充令牌数。 */
    private long refillTokens = 60;
    /** 填充周期（秒）。 */
    private long refillSeconds = 60;
}
