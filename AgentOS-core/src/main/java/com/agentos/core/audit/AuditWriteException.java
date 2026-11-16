package com.agentos.core.audit;

/**
 * 审计无法可靠写入的信号，不能被当成“模型没调用”而直接重发模型请求。
 * 存储和工具边界仅传固定文案，不携带可能含 SQL 或凭证的原始 cause。
 */
public class AuditWriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean executionMayHaveOccurred;

    public AuditWriteException(String message) {
        this(message, true);
    }

    public AuditWriteException(String message, boolean executionMayHaveOccurred) {
        super(message);
        this.executionMayHaveOccurred = executionMayHaveOccurred;
    }

    public boolean executionMayHaveOccurred() {
        return executionMayHaveOccurred;
    }

    public AuditWriteException(String message, Throwable cause) {
        super(message, cause);
        executionMayHaveOccurred = true;
    }
}
