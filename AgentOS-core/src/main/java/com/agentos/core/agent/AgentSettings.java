package com.agentos.core.agent;

import java.time.Duration;

/**
 * 单个 Agent 的运行限制，从 AGENT.md 的 settings 段解析。
 *
 * <p>当前主链路使用 turnTimeout 限制整轮时间；maxIterations 控制循环上限，历史字段控制
 * 下一轮提示词恢复的整轮和 token 边界。
 *
 * @param maxIterations 允许的模型迭代上限，至少为 1
 * @param maxHistoryTurns 历史轮次上限，0 表示不保留历史轮次
 * @param maxHistoryTokens 历史 token 预算，0 表示不加载历史
 * @param turnTimeout 从本轮启动开始计算的总预算，不是每次外呼重新计算
 */
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

    /** 未提供 settings 时的统一默认值，和 AgentLoader 的字段缺省规则一致。 */
    public static AgentSettings defaults() {
        return new AgentSettings(10, 20, 32_000, Duration.ofSeconds(300));
    }

    /** 正 token 上限预留 10% 余量；0 表示不加载历史。 */
    public int effectiveHistoryTokens() {
        return maxHistoryTokens == 0 ? 0 : (int) Math.floor(maxHistoryTokens * 0.9d);
    }
}
