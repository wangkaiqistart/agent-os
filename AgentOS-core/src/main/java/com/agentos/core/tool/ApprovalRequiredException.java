package com.agentos.core.tool;

import java.util.Objects;

/**
 * 动作合法但未被预先授权，需要用户确认。
 *
 * <p>继承 {@link SandboxViolationException}：调用方不处理时等同拒绝，既有审计与反馈路径不变；
 * 授权网关捕获后向当前入口请求确认。
 */
public final class ApprovalRequiredException extends SandboxViolationException {
    private static final long serialVersionUID = 1L;

    private final transient ApprovalSubject subject;

    public ApprovalRequiredException(ApprovalSubject subject) {
        super("DENIED：该操作需要用户授权");
        this.subject = Objects.requireNonNull(subject);
    }

    public ApprovalSubject subject() {
        return subject;
    }
}
