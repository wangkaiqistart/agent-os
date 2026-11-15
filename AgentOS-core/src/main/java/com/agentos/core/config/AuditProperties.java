package com.agentos.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
