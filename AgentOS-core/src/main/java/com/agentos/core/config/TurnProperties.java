package com.agentos.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局轮次时间配置契约；Duration 可由 150s、1s 等 Boot 配置值绑定。
 *
 * <p>JpaTurnStore 使用 {@code max(leaseTtl, 剩余轮次预算 + leaseGrace)} 计算租约，
 * TurnExecutionBudget 使用 cleanup 作为 hard stop 收尾窗口。
 *
 * @param leaseTtl 规划中的租约基础存活时间
 * @param leaseGrace 规划中的租约宽限窗口，可为零
 * @param cleanup 规划中的超时清理余量，必须为正数
 */
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
