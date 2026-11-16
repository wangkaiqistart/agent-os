package com.agentos.core.audit;

/**
 * 审计无法可靠写入的信号，不能被当成“模型没调用”而直接重发模型请求。
 * 当前 JPA 记录器用它包装提交前能捕获的写入错误；保留 cause 供服务端排查。
 */
public class AuditWriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AuditWriteException(String message) {
        super(message);
    }

    public AuditWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
