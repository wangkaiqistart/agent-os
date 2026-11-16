package com.agentos.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计写入重试的配置边界，当前完成绑定和校验，尚未接入统一重试执行器。
 *
 * <p>不能因为 maxRetries 有值就认为 JpaLlmCallRecorder 已经自动重试；当前存储实现仍会
 * 把写入失败转换成 AuditWriteException，再交给上层处理。
 *
 * @param maxRetries 重试次数上限，取值 0～2，不含第一次尝试
 * @param backoffFirst 第一次重试前的退避时间
 * @param backoffSecond 第二次重试前的退避时间
 * @param attemptTimeout 单次审计尝试的时间上限，配置范围 1～250ms
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
        attemptTimeout = attemptTimeout == null ? Duration.ofMillis(250) : attemptTimeout;
        if (backoffFirst.isNegative()
                || backoffFirst.isZero()
                || backoffSecond.isNegative()
                || backoffSecond.isZero()) {
            throw new IllegalArgumentException("audit.backoff 必须为正数");
        }
        if (attemptTimeout.isNegative()
                || attemptTimeout.isZero()
                || attemptTimeout.compareTo(Duration.ofMillis(250)) > 0) {
            throw new IllegalArgumentException("audit.attempt-timeout 必须在1到250ms之间");
        }
    }
}
