package com.agentos.core.interaction;

import com.agentos.core.tool.ApprovalSubject;
import java.util.Objects;
import java.util.UUID;

/**
 * 原轮次内的一次授权请求；身份与截止时间不作为模型参数。
 *
 * @param deadlineNanos 等待用户决定的最晚时刻（单调时钟），等待期间整轮计时暂停
 */
public record ApprovalRequest(
        UUID requestId,
        UUID sessionId,
        UUID turnId,
        String toolCallId,
        ApprovalSubject subject,
        long deadlineNanos) {
    public ApprovalRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(subject);
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("INVALID_ARGUMENT");
        }
    }
}
