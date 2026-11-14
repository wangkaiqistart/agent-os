package com.agentos.core.provider;

/**
 * Provider 调用失败分类；只有前三类允许由 ProviderService 追加一次重试。
 *
 * <p>分类的目的不是美化日志，而是决定“能不能安全重试”：限流、临时服务错误、连接抖动通常可以重试；
 * 身份认证、参数错误和协议解析错误即使重发也不会变好。
 */
public enum FailureKind {
    /** 429 或等价的限流失败。 */
    RATE_LIMITED,
    /** 可重试的 500、502、503、504。 */
    SERVER_ERROR,
    /** 临时连接或读取失败。 */
    CONNECTION,
    /** 401、403 或凭证错误。 */
    AUTH,
    /** 请求参数或媒体类型错误。 */
    INVALID_REQUEST,
    /** Provider 返回的协议结构无法解析。 */
    PROTOCOL,
    /** 调用线程被中断或调用方取消。 */
    CANCELLED
}
