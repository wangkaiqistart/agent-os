package com.agentos.core.turn;

/** 轮次终态。RUNNING 只用于存储状态。 */
public enum TurnStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    MAX_ITERATIONS,
    INTERRUPTED,
    LIMIT_EXCEEDED
}
