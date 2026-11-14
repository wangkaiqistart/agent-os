package com.agentos.core.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/** ProviderService 的一次显式请求，不包含循环控制。 */
public record ProviderRequest(
        Profile profile,
        List<Message> messages,
        List<ToolCallback> tools,
        Map<String, Object> toolContext,
        Instant deadline,
        CallCorrelation correlation) {

    /** 归一化可选集合并建立不可变快照。 */
    public ProviderRequest {
        profile = Objects.requireNonNull(profile, "profile");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolContext = toolContext == null ? Map.of() : Map.copyOf(toolContext);
        correlation = correlation == null ? CallCorrelation.none() : correlation;
    }

    /** 返回 Spring AI 工具上下文，保留统一的 core 契约类型。 */
    public ToolContext springToolContext() {
        return new ToolContext(toolContext);
    }
}
