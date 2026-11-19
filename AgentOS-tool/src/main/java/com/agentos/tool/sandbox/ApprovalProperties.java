package com.agentos.tool.sandbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具授权等待配置。
 *
 * @param timeout 单次等待用户决定的上限；等待期间整轮计时暂停，但会话租约仍被占用，所以必须有上限
 */
@ConfigurationProperties("agentos.tools.approval")
public record ApprovalProperties(Duration timeout) {
    public ApprovalProperties {
        timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        if (timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("授权等待时间须在 0 到 1 小时之间");
        }
    }
}
