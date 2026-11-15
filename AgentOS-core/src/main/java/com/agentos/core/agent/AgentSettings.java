package com.agentos.core.agent;

import java.time.Duration;

/** Agent 运行时设置。 */
public record AgentSettings(
        int maxIterations, int maxHistoryTurns, int maxHistoryTokens, Duration turnTimeout) {

    public AgentSettings {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("max_iterations 必须大于等于 1");
        }
        if (maxHistoryTurns < 0 || maxHistoryTokens < 0) {
            throw new IllegalArgumentException("历史上限不能为负数");
        }
        if (turnTimeout == null || turnTimeout.isZero() || turnTimeout.isNegative()) {
            throw new IllegalArgumentException("turn_timeout 必须为正数");
        }
    }

    public static AgentSettings defaults() {
        return new AgentSettings(10, 20, 32_000, Duration.ofSeconds(300));
    }

    /** 正 token 上限预留 10% 余量；0 表示不加载历史。 */
    public int effectiveHistoryTokens() {
        return maxHistoryTokens == 0 ? 0 : (int) Math.floor(maxHistoryTokens * 0.9d);
    }
}
