package com.agentos.core.tool;

/** 工具明确报告的瞬时故障；只有确认外部动作未开始，治理层才允许幂等重试。 */
public final class TransientToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean executionStarted;

    public TransientToolException(boolean executionStarted) {
        super("工具发生瞬时故障");
        this.executionStarted = executionStarted;
    }

    public boolean executionStarted() {
        return executionStarted;
    }
}
