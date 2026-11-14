package com.agentos.core.provider.embedding;

import java.util.Objects;

/**
 * Embedding 服务状态。
 *
 * <p>状态和原因放在同一个不可变对象中，健康检查或管理接口可以同时告诉调用方“当前状态”和“为什么”。
 *
 * @param state 当前状态枚举
 * @param reason 面向运维或调用方的原因说明
 */
public record EmbeddingStatus(State state, String reason) {

    public EmbeddingStatus {
        // 状态是必填的；原因允许为空，统一转换为空字符串方便展示。
        state = Objects.requireNonNull(state, "state");
        reason = reason == null ? "" : reason;
    }

    public enum State {
        /** 配置明确关闭。 */
        DISABLED,
        /** 正在做启动检查或连接检查。 */
        CHECKING,
        /** 配置正确且服务可用。 */
        READY,
        /** 配置存在，但远端暂时不可用。 */
        UNAVAILABLE,
        /** 配置缺失或格式错误。 */
        MISCONFIGURED
    }

    public static EmbeddingStatus disabled(String reason) {
        // 语义检索被配置关闭时使用，而不是把关闭误报成故障。
        return new EmbeddingStatus(State.DISABLED, reason);
    }

    public static EmbeddingStatus checking() {
        // 启动检查尚未完成时使用。
        return new EmbeddingStatus(State.CHECKING, "");
    }

    public static EmbeddingStatus ready() {
        // 配置有效且远程服务检查成功时使用。
        return new EmbeddingStatus(State.READY, "");
    }

    public static EmbeddingStatus unavailable(String reason) {
        // 配置基本正确，但远程服务暂时不可连接时使用。
        return new EmbeddingStatus(State.UNAVAILABLE, reason);
    }

    public static EmbeddingStatus misconfigured(String reason) {
        // 缺少 URL、密钥或维度不匹配等配置问题使用。
        return new EmbeddingStatus(State.MISCONFIGURED, reason);
    }
}
