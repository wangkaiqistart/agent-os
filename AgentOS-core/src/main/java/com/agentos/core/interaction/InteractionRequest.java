package com.agentos.core.interaction;

import java.util.UUID;

/** 原轮次内的一次提问；身份及截止时间不作为模型参数。 */
public record InteractionRequest(
        UUID requestId,
        UUID turnId,
        String toolCallId,
        String question,
        long deadlineNanos,
        int maxAnswerChars) {
    public InteractionRequest(
            UUID requestId, UUID turnId, String toolCallId, String question, long deadlineNanos) {
        this(requestId, turnId, toolCallId, question, deadlineNanos, 4000);
    }

    public InteractionRequest {
        java.util.Objects.requireNonNull(requestId);
        if (toolCallId == null
                || toolCallId.isBlank()
                || question == null
                || question.isBlank()
                || question.length() > 4000
                || maxAnswerChars < 1
                || maxAnswerChars > 4000) {
            throw new IllegalArgumentException("INVALID_ARGUMENT");
        }
    }
}
