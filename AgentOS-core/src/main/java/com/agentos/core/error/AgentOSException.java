package com.agentos.core.error;

import java.util.Objects;

/**
 * 带错误码的业务异常，各模块统一抛它。
 *
 * <p>{@link #getMessage()} 会原样返回给 API 调用方，不能包含密钥、webhook token 或内部堆栈信息。
 *
 * <p>普通的 {@link RuntimeException} 只能表达“发生了异常”，而这个类额外携带
 * {@link ErrorCode}，因此 Web、CLI、定时任务都能根据同一个业务错误码做处理。
 */
public class AgentOSException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public AgentOSException(ErrorCode errorCode, String message) {
        // super(message) 把可读的错误说明交给 RuntimeException 保存。
        super(message);
        // 错误码不能为空，否则异常到达统一处理器时无法映射状态。
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public AgentOSException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /** 返回机器可判断的业务错误码，而不是通过解析中文 message 判断错误类型。 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
