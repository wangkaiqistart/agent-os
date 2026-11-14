package com.agentos.core.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * ProviderService 的一次显式请求，不包含循环控制。
 *
 * <p>“不包含循环控制”很重要：ReAct 要不要继续由上层循环决定，ProviderService 只负责把这一轮消息发给模型。
 *
 * @param profile 本次调用的模型和生成参数
 * @param messages 按顺序发送给模型的 Spring AI 消息，不能为空
 * @param tools 本次允许模型请求的工具列表
 * @param toolContext 显式传给工具的业务上下文
 * @param deadline 本次调用的最终截止时间
 * @param correlation 用于日志和审计关联的会话、轮次、迭代信息
 */
public record ProviderRequest(
        Profile profile,
        List<Message> messages,
        List<ToolCallback> tools,
        Map<String, Object> toolContext,
        Instant deadline,
        CallCorrelation correlation) {

    /**
     * 归一化可选集合并建立不可变快照。
     *
     * <p>调用方可以不提供工具、工具上下文或关联信息；这里统一转换成空集合或默认值，
     * 让后续实现不需要到处判空。
     */
    public ProviderRequest {
        // Profile 和消息是一次调用的必要信息，缺失时立刻报错。
        profile = Objects.requireNonNull(profile, "profile");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolContext = toolContext == null ? Map.of() : Map.copyOf(toolContext);
        correlation = correlation == null ? CallCorrelation.none() : correlation;
    }

    /**
     * 返回 Spring AI 工具上下文，保留统一的 core 契约类型。
     *
     * <p>core 里的请求使用普通 Map，真正调用 Spring AI 工具时才转换成 {@link ToolContext}。
     */
    public ToolContext springToolContext() {
        return new ToolContext(toolContext);
    }
}
