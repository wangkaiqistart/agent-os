package com.agentos.core.error;

import java.util.Objects;

/**
 * 带错误码的业务异常，各模块统一抛它。
 *
 * <p>{@link #getMessage()} 会原样返回给 API 调用方，不能包含密钥、webhook token 或内部堆栈信息。
 */
public class AgentOSException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public AgentOSException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public AgentOSException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
