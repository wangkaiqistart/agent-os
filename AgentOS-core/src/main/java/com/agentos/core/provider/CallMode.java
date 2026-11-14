package com.agentos.core.provider;

/** Provider 调用方式。 */
public enum CallMode {
    /** 一次性返回完整响应。 */
    CALL,
    /** 以增量分片返回响应。 */
    STREAM
}
