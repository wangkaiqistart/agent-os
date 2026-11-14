package com.agentos.provider.chat;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;

/** US-1 支持的 OpenAI 兼容连接类型。 */
public enum ConnectionType {
    OPENAI("openai"),
    DEEPSEEK("deepseek");

    private final String value;

    ConnectionType(String value) {
        this.value = value;
    }

    public static ConnectionType parse(String raw) {
        for (ConnectionType type : values()) {
            if (type.value.equalsIgnoreCase(raw)) {
                return type;
            }
        }
        throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "暂不支持的 provider 类型：" + raw);
    }
}
