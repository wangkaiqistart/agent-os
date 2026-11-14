package com.agentos.core.provider;

/** Provider 调用方式。 */
public enum CallMode {
    /** 一次性返回完整响应，适合普通同步 API。 */
    CALL,
    /** 以增量分片返回响应，适合聊天窗口实时显示文字。 */
    STREAM
}
