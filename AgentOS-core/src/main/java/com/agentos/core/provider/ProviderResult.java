package com.agentos.core.provider;

import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatResponse;

/** Provider 一次调用的最终响应与全部尝试记录。 */
public record ProviderResult(ChatResponse response, List<LlmCallRecord> calls) {

    /** 创建不可变调用记录快照。 */
    public ProviderResult {
        response = Objects.requireNonNull(response, "response");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
    }
}
