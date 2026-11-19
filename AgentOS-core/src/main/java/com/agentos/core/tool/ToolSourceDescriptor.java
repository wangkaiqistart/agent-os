package com.agentos.core.tool;

/** 来源安全状态，不携带地址、配置、进程或客户端。 */
public record ToolSourceDescriptor(String source, State state, Reason reason) {
    public ToolSourceDescriptor {
        ToolDescriptor.requireSource(source);
        java.util.Objects.requireNonNull(state);
    }

    public enum State {
        CONFIGURED,
        CONNECTING,
        AVAILABLE,
        UNAVAILABLE,
        UNCONFIGURED,
        CLOSING,
        CLOSED
    }

    public enum Reason {
        CONNECT_FAILED,
        TIMEOUT,
        INVALID_DEFINITION,
        DENIED,
        CLOSED,
        NOT_CONFIGURED
    }
}
