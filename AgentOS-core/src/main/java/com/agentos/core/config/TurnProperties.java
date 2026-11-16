package com.agentos.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 全局轮次时间配置契约；Duration 可由 150s、1s 等 Boot 配置值绑定。
 *
 * <p>JpaTurnStore 使用 {@code max(leaseTtl, 剩余轮次预算 + leaseGrace)} 计算租约，
 * TurnExecutionBudget 使用 cleanup 作为 hard stop 收尾窗口。
 *
 * @param leaseTtl 租约基础存活时间
 * @param leaseGrace 租约宽限窗口，可为零
 * @param cleanup 固定一秒的共享清理余量，禁止扩容超时验收窗口
 * @param storageTimeout 普通轮次存储操作上限，仍须取整轮剩余预算的较小值
 */
@ConfigurationProperties("agentos.turn")
public record TurnProperties(
        Duration leaseTtl, Duration leaseGrace, Duration cleanup, Duration storageTimeout) {
    /** 兼容已有显式构造；运行时由 Boot 绑定下方完整构造器。 */
    public TurnProperties(Duration leaseTtl, Duration leaseGrace, Duration cleanup) {
        this(leaseTtl, leaseGrace, cleanup, null);
    }

    @ConstructorBinding
    public TurnProperties {
        leaseTtl = leaseTtl == null ? Duration.ofSeconds(150) : leaseTtl;
        leaseGrace = leaseGrace == null ? Duration.ofSeconds(30) : leaseGrace;
        cleanup = cleanup == null ? Duration.ofSeconds(1) : cleanup;
        storageTimeout = storageTimeout == null ? Duration.ofSeconds(5) : storageTimeout;
        if (leaseTtl.isNegative()
                || leaseTtl.isZero()
                || leaseGrace.isNegative()
                || cleanup.isNegative()
                || cleanup.isZero()
                || storageTimeout.isZero()
                || storageTimeout.isNegative()
                || !cleanup.equals(Duration.ofSeconds(1))) {
            throw new IllegalArgumentException("轮次时间配置非法");
        }
    }
}
