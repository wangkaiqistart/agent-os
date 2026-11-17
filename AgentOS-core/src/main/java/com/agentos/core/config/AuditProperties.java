package com.agentos.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计写入重试配置；两类落库记录器共用 RetryingAuditWriter，每次尝试单独开短事务。
 * 这些配置只控制数据库审计，不控制模型或工具的外部调用次数。
 *
 * @param maxRetries 重试次数上限，取值 0～2，不含第一次尝试
 * @param backoffFirst 第一次重试前的退避时间
 * @param backoffSecond 第二次重试前的退避时间
 * @param attemptTimeout 单次审计尝试的时间上限，默认 2 秒，配置范围 1ms～5s，仍受整轮剩余预算约束
 */
@ConfigurationProperties("agentos.audit")
public record AuditProperties(
        int maxRetries, Duration backoffFirst, Duration backoffSecond, Duration attemptTimeout) {
    public AuditProperties {
        if (maxRetries < 0 || maxRetries > 2) {
            throw new IllegalArgumentException("audit.max-retries 必须在 0 到 2 之间");
        }
        backoffFirst = backoffFirst == null ? Duration.ofMillis(200) : backoffFirst;
        backoffSecond = backoffSecond == null ? Duration.ofMillis(500) : backoffSecond;
        attemptTimeout = attemptTimeout == null ? Duration.ofSeconds(2) : attemptTimeout;
        if (backoffFirst.isNegative()
                || backoffFirst.isZero()
                || backoffSecond.isNegative()
                || backoffSecond.isZero()) {
            throw new IllegalArgumentException("audit.backoff 必须为正数");
        }
        if (attemptTimeout.isNegative()
                || attemptTimeout.compareTo(Duration.ofMillis(1)) < 0
                || attemptTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("audit.attempt-timeout 必须在1ms到5s之间");
        }
    }
}
