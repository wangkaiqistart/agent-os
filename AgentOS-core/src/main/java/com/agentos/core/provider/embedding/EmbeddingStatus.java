package com.agentos.core.provider.embedding;

import java.util.Objects;

/** Embedding 服务状态。 */
public record EmbeddingStatus(State state, String reason) {

    public EmbeddingStatus {
        state = Objects.requireNonNull(state, "state");
        reason = reason == null ? "" : reason;
    }

    public enum State {
        DISABLED,
        CHECKING,
        READY,
        UNAVAILABLE,
        MISCONFIGURED
    }

    public static EmbeddingStatus disabled(String reason) {
        return new EmbeddingStatus(State.DISABLED, reason);
    }

    public static EmbeddingStatus checking() {
        return new EmbeddingStatus(State.CHECKING, "");
    }

    public static EmbeddingStatus ready() {
        return new EmbeddingStatus(State.READY, "");
    }

    public static EmbeddingStatus unavailable(String reason) {
        return new EmbeddingStatus(State.UNAVAILABLE, reason);
    }

    public static EmbeddingStatus misconfigured(String reason) {
        return new EmbeddingStatus(State.MISCONFIGURED, reason);
    }
}
