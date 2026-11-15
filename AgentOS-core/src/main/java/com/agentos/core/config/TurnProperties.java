package com.agentos.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agentos.turn")
public record TurnProperties(Duration leaseTtl, Duration leaseGrace, Duration cleanup) {
    public TurnProperties {
        leaseTtl = leaseTtl == null ? Duration.ofSeconds(150) : leaseTtl;
        leaseGrace = leaseGrace == null ? Duration.ofSeconds(30) : leaseGrace;
        cleanup = cleanup == null ? Duration.ofSeconds(1) : cleanup;
        if (leaseTtl.isNegative()
                || leaseTtl.isZero()
                || leaseGrace.isNegative()
                || cleanup.isNegative()
                || cleanup.isZero()) {
            throw new IllegalArgumentException("轮次时间配置非法");
        }
    }
}
