package com.agentos.core.turn;

/**
 * 轮次失败的稳定错误码，写进 {@code run.failed} 事件，后续网页端与 AG-UI 的 {@code RUN_ERROR.code} 直接使用。
 *
 * <p>只追加、不重命名：前端和存储会按名字识别，改名等于破坏契约。错误码只说明失败类别，
 * 不携带内部异常信息。
 */
public enum TurnFailureCode {
    /** 模型输出达到上限被截断（结束原因 length）。 */
    OUTPUT_TRUNCATED,
    /** 回答被内容安全策略过滤（结束原因 content_filter）。 */
    CONTENT_FILTERED,
    /** 模型返回了空回答。 */
    EMPTY_RESPONSE,
    /** 超过整轮时限。 */
    TIMED_OUT,
    /** 达到 max_iterations。 */
    MAX_ITERATIONS,
    /** 工具调用次数超限（THROW 模式）。 */
    TOOL_LIMIT_EXCEEDED,
    /** 模型服务限流。 */
    PROVIDER_RATE_LIMITED,
    /** 模型服务端错误。 */
    PROVIDER_SERVER_ERROR,
    /** 连接模型服务失败或读取超时。 */
    PROVIDER_CONNECTION,
    /** 模型服务认证失败。 */
    PROVIDER_AUTH,
    /** 请求参数被模型服务拒绝。 */
    PROVIDER_INVALID_REQUEST,
    /** 模型服务返回的结构无法处理。 */
    PROVIDER_PROTOCOL,
    /** 会话租约丢失。 */
    LEASE_LOST,
    /** 输出端（事件消费方）失效。 */
    OUTPUT_FAILED,
    /** 进程关闭导致中断。 */
    SHUTDOWN,
    /** 工具审计写入未确认。 */
    AUDIT_UNCONFIRMED,
    /** 工具资源清理未确认。 */
    TOOL_CLEANUP_UNCONFIRMED,
    /** 轮次是否建立尚未确认。 */
    START_UNCONFIRMED,
    /** 其他内部错误。 */
    INTERNAL
}
