package com.agentos.provider.chat;

import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatModel;

/** 启动时建立、运行期间不可变的具名 Provider 连接。 */
public record ProviderConnection(
        String name,
        ConnectionType type,
        Duration timeout,
        Duration retryMinRemaining,
        Duration retryMaxWait,
        boolean available,
        String envVarName,
        OpenAiChatModel chatModel) {}
