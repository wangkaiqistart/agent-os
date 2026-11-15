package com.agentos.core.audit;

public class AuditWriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AuditWriteException(String message) {
        super(message);
    }

    public AuditWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
