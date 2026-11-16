package com.agentos.core.turn;

/**
 * 轮次生命周期状态。RUNNING 是存储中的中间态，其余值描述不同结束原因。
 * 当前最小链路主要返回 SUCCEEDED、FAILED、TIMED_OUT；保留的上限状态不表示相应功能已接入。
 */
public enum TurnStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** 预留：耗尽模型迭代次数。 */
    MAX_ITERATIONS,
    /** 回收失去租约等无法继续的残留 RUNNING 记录时使用。 */
    INTERRUPTED,
    /** 预留：触发工具调用等资源限额。 */
    LIMIT_EXCEEDED
}
