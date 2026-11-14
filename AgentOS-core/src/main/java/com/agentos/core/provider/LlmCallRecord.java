package com.agentos.core.provider;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次实际 Provider 请求的记录。
 *
 * <p>空值与 0 不混用。记录不得包含 key、Prompt、工具参数、图片数据、网址或原始响应体。
 */
public record LlmCallRecord(
        UUID sessionId,
        UUID turnId,
        Integer iteration,
        String provider,
        String requestedModel,
        String responseModel,
        String responseId,
        CallMode mode,
        int attempt,
        String finishReason,
        Integer toolCallCount,
        int imageCount,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Long requestsRemaining,
        Long tokensRemaining,
        Boolean outputCapHonored,
        boolean success,
        FailureKind failureKind,
        Integer httpStatus,
        String errorSummary,
        Instant startedAt,
        long durationMs) {

    /** 校验本次尝试的固定约束。 */
    public LlmCallRecord {
        if (attempt != 1 && attempt != 2) {
            throw new IllegalArgumentException("attempt 必须为 1 或 2");
        }
        if (imageCount < 0 || durationMs < 0) {
            throw new IllegalArgumentException("图片数量和耗时不能为负数");
        }
    }
}
