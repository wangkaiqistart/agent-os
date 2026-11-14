package com.agentos.provider.chat;

import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * 启动时建立、运行期间不可变的具名 Provider 连接。
 *
 * <p>这是连接层对象，不是一次请求的 Profile：它保存“连接到哪里、是否可用、底层模型对象是什么”，
 * 不保存某个 Agent 的具体生成参数。
 */
public record ProviderConnection(
        String name,
        ConnectionType type,
        Duration timeout,
        Duration retryMinRemaining,
        Duration retryMaxWait,
        boolean available,
        String envVarName,
        OpenAiChatModel chatModel) {}
