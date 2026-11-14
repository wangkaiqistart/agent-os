package com.agentos.core.provider;

import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Provider 一次调用的最终响应与全部尝试记录。
 *
 * <p>{@code response} 是 Spring AI 的模型响应；{@code calls} 是审计信息。把两者放在一起，
 * 让上层既能继续处理模型结果，也能看到这次调用是否重试过。
 *
 * @param response Spring AI 返回的完整模型响应
 * @param calls 本次逻辑调用产生的全部实际请求记录
 */
public record ProviderResult(ChatResponse response, List<LlmCallRecord> calls) {

    /** 创建不可变调用记录快照，避免调用方修改审计列表。 */
    public ProviderResult {
        response = Objects.requireNonNull(response, "response");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
    }
}
