package com.agentos.core.error;

/**
 * 对外错误码，见技术方案 7.4。
 *
 * <p>core 不依赖 HTTP：错误码到 HTTP 状态码的映射只在 {@code AgentOS-web} 的 {@code GlobalExceptionHandler} 里做，
 * CLI、定时任务等入口拿到的是同一个错误码。新增错误码时，web 层的 {@code switch} 会编译失败，提醒补映射。
 */
public enum ErrorCode {
    /** 参数错误（400）。 */
    INVALID_ARGUMENT,
    /** 资源不存在（404）。 */
    NOT_FOUND,
    /** 会话正有轮次在执行（409）。 */
    SESSION_BUSY,
    /** Provider 故障（503）。 */
    PROVIDER_UNAVAILABLE,
    /** 内部错误（500）。 */
    INTERNAL_ERROR
}
