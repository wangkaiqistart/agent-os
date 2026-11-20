package com.agentos.core.turn;

/**
 * 轮次生命周期状态。RUNNING 是存储中的中间态，其余值描述不同结束原因。
 * 正常轮次会返回 SUCCEEDED、FAILED、TIMED_OUT 或 MAX_ITERATIONS；其余值描述中断和资源限额。
 */
public enum TurnStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** 模型调用次数达到 Agent 的 max_iterations，未再发起总结请求。 */
    MAX_ITERATIONS,
    /** 回收失去租约等无法继续的残留 RUNNING 记录时使用。 */
    INTERRUPTED,
    /** 仅用于需要 THROW 模式时表示工具限额终止。 */
    LIMIT_EXCEEDED,
    /** 用户主动取消；与进程关闭、租约丢失导致的 INTERRUPTED 区分。 */
    CANCELLED
}
